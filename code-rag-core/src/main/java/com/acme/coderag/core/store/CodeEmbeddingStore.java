package com.acme.coderag.core.store;

import com.acme.coderag.core.embed.ModelId;
import com.acme.coderag.core.embed.Vectors;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The corpus as a LangChain4j {@link EmbeddingStore}, <strong>read-only</strong>.
 *
 * <p>Implementing the framework interface over our own schema, rather than adopting
 * {@code PgVectorEmbeddingStore}, is the deliberate trade in this design. The
 * standard store's table is {@code (embedding_id, text, embedding, metadata jsonb)},
 * which would cost all four of the things this system is built on: chunk text stored
 * a second time alongside {@code code_file.content}; narrowing pushed into JSONB
 * instead of indexed columns; no {@code tsvector}, so no lexical half and no hybrid
 * search; and one row per (chunk, vector) pair, so no content-addressed
 * deduplication -- the same generated DTO in three repositories would be embedded
 * and stored three times.
 *
 * <p>What the interface buys in exchange is everything downstream: this store plugs
 * into {@code EmbeddingStoreContentRetriever}, into a {@code RetrievalAugmentor},
 * and into any future LangChain4j application in the company, with no adapter.
 *
 * <p><strong>Writes belong to the indexer.</strong> {@code add} and {@code addAll}
 * throw. They cannot be honoured: the interface carries an embedding and a text
 * segment, while a row here needs the file's blob SHA, its full content and the
 * commit it came from -- the very data that makes {@code get_file_outline} and
 * {@code read_lines} possible. Accepting a partial write would corrupt the corpus
 * quietly, so it is refused loudly.
 *
 * <h2>Why two queries</h2>
 * Ranking runs against {@code code_embedding} alone so the HNSW index is actually
 * usable, and the winners are expanded to chunks afterwards. Joining
 * {@code code_chunk} into the ordering would force a scan and sort over the whole
 * corpus -- the trap that comes with content-addressed vectors, where one vector is
 * referenced by many chunks.
 */
public final class CodeEmbeddingStore implements EmbeddingStore<TextSegment> {

    /** Lines of code carried in a match. Every one lands in an agent's context. */
    public static final int DEFAULT_SNIPPET_LINES = 18;

    private static final int DEFAULT_MAX_RESULTS = 10;

    /**
     * Extra candidates fetched when a filter is present. pgvector applies the filter
     * inside the index scan, so a narrow filter over a wide corpus can exhaust the
     * candidate list before finding {@code maxResults} survivors.
     */
    private static final int FILTERED_RECALL_FACTOR = 3;

    private final JdbcTemplate jdbc;
    private final ModelId modelId;
    private final int snippetLines;

    public CodeEmbeddingStore(JdbcTemplate jdbc, ModelId modelId, int snippetLines) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.snippetLines = Math.max(1, snippetLines);
    }

    public CodeEmbeddingStore(JdbcTemplate jdbc, ModelId modelId) {
        this(jdbc, modelId, DEFAULT_SNIPPET_LINES);
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        Integer requested = request.maxResults();
        int limit = requested == null || requested <= 0 ? DEFAULT_MAX_RESULTS : requested;
        Double minScore = request.minScore();

        MetadataFilterSql.Predicate predicate =
                MetadataFilterSql.translate(request.filter(), "c");
        int candidates = predicate == null ? limit : limit * FILTERED_RECALL_FACTOR;

        Map<String, Double> distanceBySha =
                nearest(request.queryEmbedding(), predicate, candidates);
        if (distanceBySha.isEmpty()) {
            return new EmbeddingSearchResult<>(List.of());
        }

        // Rank by hash, because that is what the vector index ordered.
        Map<String, Integer> rankBySha = new LinkedHashMap<>();
        for (String sha : distanceBySha.keySet()) {
            rankBySha.put(sha, rankBySha.size());
        }

        List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
        for (TextSegment segment : expand(rankBySha.keySet(), predicate)) {
            String sha = segment.metadata().getString(CodeChunks.CHUNK_SHA);
            Double distance = distanceBySha.get(sha);
            if (distance == null) {
                continue;
            }
            double score = relevance(distance);
            if (minScore != null && score < minScore) {
                continue;
            }
            // The embedding itself is not returned: it is 768 floats no caller of
            // this store reads, and fetching it would multiply the result payload.
            matches.add(new EmbeddingMatch<>(score, CodeChunks.idOf(segment), null, segment));
        }

        matches.sort(Comparator.comparingInt(
                match -> rankBySha.getOrDefault(
                        match.embedded().metadata().getString(CodeChunks.CHUNK_SHA),
                        Integer.MAX_VALUE)));
        return new EmbeddingSearchResult<>(
                matches.size() <= limit ? matches : matches.subList(0, limit));
    }

    /** Hash to cosine distance, in vector-index order. */
    private Map<String, Double> nearest(Embedding queryEmbedding,
                                        MetadataFilterSql.Predicate predicate, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT e.chunk_sha, e.embedding <=> CAST(? AS vector) AS distance
                FROM code_embedding e
                WHERE e.model_id = ?
                """);
        params.add(Vectors.toPgLiteral(queryEmbedding.vector()));
        params.add(modelId.asString());

        if (predicate != null) {
            sql.append("  AND EXISTS (SELECT 1 FROM code_chunk c")
                    .append(" WHERE c.chunk_sha = e.chunk_sha AND ")
                    .append(predicate.sql()).append(")\n");
            params.addAll(predicate.params());
        }
        // Ordering by the output alias resolves to the distance expression, so the
        // HNSW index is still used -- and the query vector is bound once, not twice.
        sql.append("ORDER BY distance\nLIMIT ?");
        params.add(limit);

        // A two-argument RowMapper rather than a RowCallbackHandler: a one-argument
        // lambda fits both RowCallbackHandler and ResultSetExtractor, and the overload
        // is ambiguous.
        List<Map.Entry<String, Double>> rows = jdbc.query(sql.toString(),
                (rs, rowNum) -> Map.entry(rs.getString("chunk_sha"), rs.getDouble("distance")),
                params.toArray());

        Map<String, Double> distances = new LinkedHashMap<>();
        for (Map.Entry<String, Double> row : rows) {
            distances.putIfAbsent(row.getKey(), row.getValue());
        }
        return distances;
    }

    /** Every chunk referencing one of the winning hashes, filter still applied. */
    private List<TextSegment> expand(Iterable<String> shas,
                                     MetadataFilterSql.Predicate predicate) {
        List<Object> params = new ArrayList<>();
        params.add(snippetLines);

        StringBuilder sql = new StringBuilder(CodeChunks.SELECT_SEGMENT)
                .append("WHERE c.chunk_sha IN (");
        int count = 0;
        for (String sha : shas) {
            sql.append(count++ == 0 ? "?" : ",?");
            params.add(sha);
        }
        sql.append(")");
        if (predicate != null) {
            sql.append(" AND ").append(predicate.sql());
            params.addAll(predicate.params());
        }
        return jdbc.query(sql.toString(), CodeChunks.SEGMENT, params.toArray());
    }

    /**
     * pgvector's {@code <=>} yields cosine distance in {@code [0, 2]}; LangChain4j
     * scores are relevance in {@code [0, 1]}, higher being better.
     */
    private static double relevance(double cosineDistance) {
        return (2.0 - cosineDistance) / 2.0;
    }

    // ── Write side: refused, see the class javadoc ───────────────────────────

    @Override
    public String add(Embedding embedding) {
        throw readOnly();
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw readOnly();
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        throw readOnly();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw readOnly();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        throw readOnly();
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        throw readOnly();
    }

    private static UnsupportedOperationException readOnly() {
        return new UnsupportedOperationException(
                "the code-rag corpus is written only by code-rag-indexer, which needs a "
                + "file's blob SHA, content and commit -- data this interface cannot "
                + "carry. Run the indexer job instead of writing through the store.");
    }
}
