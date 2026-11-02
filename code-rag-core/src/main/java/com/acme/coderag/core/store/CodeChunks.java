package com.acme.coderag.core.store;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.jdbc.core.RowMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The bridge between our schema and LangChain4j's {@link TextSegment}.
 *
 * <p>Everything that turns a {@code code_chunk} row into a segment goes through
 * here, for one reason that is easy to overlook: <strong>rank fusion identifies a
 * result by its segment</strong>. If the dense path and the lexical path build
 * their segments even slightly differently -- a different snippet budget, a
 * metadata key present on one side only -- the same chunk found by both looks like
 * two different results, fusion never merges them, and hybrid search silently
 * degrades into two half-length lists stapled together. One projection and one row
 * mapper, used by both sides, is what prevents that.
 *
 * <p>Segment text is the <em>bounded snippet</em>, not the whole chunk, and it is
 * cut inside Postgres. A fifteen-line preview transfers fifteen lines. This is also
 * why {@code code_chunk} has no {@code content} column: chunk text is a slice of
 * {@code code_file.content} and storing it twice would double the largest table in
 * the database.
 */
public final class CodeChunks {

    // Metadata keys. Also the vocabulary accepted by MetadataFilterSql, so renaming
    // one here changes the filter API of the store.
    public static final String REPO = "repo";
    public static final String PATH = "path";
    public static final String CHUNK_INDEX = "chunk_index";
    public static final String CHUNK_SHA = "chunk_sha";
    public static final String KIND = "kind";
    public static final String SYMBOL = "symbol";
    public static final String SIGNATURE = "signature";
    public static final String LANGUAGE = "language";
    public static final String START_LINE = "start_line";
    public static final String END_LINE = "end_line";
    public static final String COMMIT_SHA = "commit_sha";

    /**
     * Pseudo-key: {@code isEqualTo(PATH_PREFIX, "src/main/java")} becomes
     * {@code path LIKE 'src/main/java%'}.
     *
     * <p>LangChain4j's filter algebra has no prefix operator, and a path prefix is
     * the one narrowing a caller actually asks for on a code corpus. Modelling it as
     * a metadata key keeps the store usable through the standard
     * {@code EmbeddingStoreContentRetriever} instead of forcing a bespoke retriever.
     * It never appears in a segment's metadata -- it only exists in filters.
     */
    public static final String PATH_PREFIX = "path_prefix";

    /**
     * Shared projection. Takes exactly one leading parameter: the snippet line
     * budget. Callers append their own {@code WHERE} clause.
     */
    public static final String SELECT_SEGMENT = """
            SELECT c.repo_slug, c.path, c.chunk_index, c.chunk_sha, c.kind, c.symbol,
                   c.signature, c.language, c.start_line, c.end_line, c.commit_sha,
                   array_to_string(
                       (string_to_array(f.content, chr(10)))[
                           c.start_line : LEAST(c.end_line, c.start_line + ? - 1)],
                       chr(10)) AS snippet
            FROM code_chunk c
            JOIN code_file f ON f.repo_slug = c.repo_slug AND f.path = c.path
            """;

    /** Maps a row of {@link #SELECT_SEGMENT}. */
    public static final RowMapper<TextSegment> SEGMENT = (rs, rowNum) -> {
        Map<String, Object> metadata = new LinkedHashMap<>();
        put(metadata, REPO, rs.getString("repo_slug"));
        put(metadata, PATH, rs.getString("path"));
        put(metadata, CHUNK_INDEX, rs.getInt("chunk_index"));
        put(metadata, CHUNK_SHA, rs.getString("chunk_sha"));
        put(metadata, KIND, rs.getString("kind"));
        put(metadata, SYMBOL, rs.getString("symbol"));
        put(metadata, SIGNATURE, rs.getString("signature"));
        put(metadata, LANGUAGE, rs.getString("language"));
        put(metadata, START_LINE, rs.getInt("start_line"));
        put(metadata, END_LINE, rs.getInt("end_line"));
        put(metadata, COMMIT_SHA, rs.getString("commit_sha"));
        String snippet = rs.getString("snippet");
        return TextSegment.from(snippet == null ? "" : snippet, Metadata.from(metadata));
    };

    private CodeChunks() {
    }

    /**
     * Opaque, stable identity of a chunk, used as the store's {@code embeddingId}.
     *
     * <p>Not the vector's identity: one vector is shared by every chunk with the
     * same hash, which is the point of content-addressed embeddings. This names the
     * chunk that referenced it.
     */
    public static String idOf(TextSegment segment) {
        Metadata metadata = segment.metadata();
        return metadata.getString(REPO) + "#" + metadata.getString(PATH)
                + "#" + metadata.getInteger(CHUNK_INDEX);
    }

    /** {@code path:start-end} -- the form an agent can act on without reassembling it. */
    public static String locationOf(TextSegment segment) {
        Metadata metadata = segment.metadata();
        return metadata.getString(PATH) + ":" + metadata.getInteger(START_LINE)
                + "-" + metadata.getInteger(END_LINE);
    }

    /** Metadata rejects null values, and symbol and signature are legitimately null. */
    private static void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }
}
