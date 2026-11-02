package com.acme.coderag.mcp.store;

import com.acme.coderag.core.store.CodeChunks;
import com.acme.coderag.core.store.MetadataFilterSql;
import com.acme.coderag.mcp.search.FileOutline;
import com.acme.coderag.mcp.search.FileSlice;
import com.acme.coderag.mcp.search.RepositoryInfo;
import com.acme.coderag.mcp.search.SymbolHit;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The reads that are not vector search.
 *
 * <p>Dense retrieval lives in {@link com.acme.coderag.core.store.CodeEmbeddingStore}
 * because it is a LangChain4j {@code EmbeddingStore} and belongs to the shared
 * module. What is left here is the lexical half plus the three projections that make
 * this service cheap to talk to: a file's outline, an exact line range, and symbol
 * lookup. None of them has a framework equivalent -- they are SQL projections whose
 * whole value is returning less than a file.
 *
 * <p>Connects with a role that has {@code SELECT} and nothing else, so a bug here
 * cannot damage the index.
 */
@Repository
public class CodeReadRepository {

    private final JdbcTemplate jdbc;

    public CodeReadRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Lexical retrieval ───────────────────────────────────────────────────

    /**
     * Full-text ranking over symbols, signatures and code, in rank order.
     *
     * <p>Returns {@link TextSegment}s built by {@link CodeChunks#SEGMENT}, the same
     * mapper the vector store uses, because rank fusion identifies a result by its
     * segment. Narrowing goes through {@link MetadataFilterSql} for the same reason:
     * one translator, so the two halves cannot disagree about what a filter means.
     */
    public List<TextSegment> lexicalSearch(String text, Filter filter, int limit,
                                           int snippetLines) {
        MetadataFilterSql.Predicate predicate = MetadataFilterSql.translate(filter, "c");

        List<Object> params = new ArrayList<>();
        params.add(Math.max(1, snippetLines));

        StringBuilder sql = new StringBuilder(CodeChunks.SELECT_SEGMENT)
                .append("WHERE c.lexical @@ plainto_tsquery('simple', ?)\n");
        params.add(text);
        if (predicate != null) {
            sql.append("  AND ").append(predicate.sql()).append('\n');
            params.addAll(predicate.params());
        }
        sql.append("ORDER BY ts_rank_cd(c.lexical, plainto_tsquery('simple', ?)) DESC\nLIMIT ?");
        params.add(text);
        params.add(limit);

        return jdbc.query(sql.toString(), CodeChunks.SEGMENT, params.toArray());
    }

    // ── Outline, slices, symbols, repositories ──────────────────────────────

    public Optional<FileOutline> outlineOf(String repo, String path) {
        List<Object[]> meta = jdbc.query("""
                SELECT language, line_count, commit_sha
                FROM code_file WHERE repo_slug = ? AND path = ?
                """, (rs, n) -> new Object[]{
                rs.getString("language"), rs.getInt("line_count"), rs.getString("commit_sha")
        }, repo, path);

        if (meta.isEmpty()) {
            return Optional.empty();
        }
        List<FileOutline.Member> members = jdbc.query("""
                SELECT kind, symbol, signature, start_line, end_line
                FROM code_chunk
                WHERE repo_slug = ? AND path = ?
                ORDER BY start_line, chunk_index
                """, (rs, n) -> new FileOutline.Member(
                rs.getString("kind"),
                rs.getString("symbol"),
                rs.getString("signature"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                path + ":" + rs.getInt("start_line") + "-" + rs.getInt("end_line")),
                repo, path);

        Object[] row = meta.getFirst();
        return Optional.of(new FileOutline(repo, path, (String) row[0], (Integer) row[1],
                (String) row[2], members));
    }

    public Optional<FileSlice> readLines(String repo, String path, int fromLine, int toLine,
                                         boolean truncated) {
        List<FileSlice> slices = jdbc.query("""
                SELECT line_count,
                       array_to_string((string_to_array(content, chr(10)))[? : ?], chr(10)) AS body
                FROM code_file WHERE repo_slug = ? AND path = ?
                """, (rs, n) -> new FileSlice(repo, path, fromLine, toLine,
                rs.getInt("line_count"), truncated, rs.getString("body")),
                fromLine, toLine, repo, path);
        return slices.isEmpty() ? Optional.empty() : Optional.of(slices.getFirst());
    }

    public List<SymbolHit> findSymbol(String query, String repo, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("""
                SELECT repo_slug, path, kind, symbol, signature, start_line, end_line
                FROM code_chunk
                WHERE symbol IS NOT NULL AND symbol ILIKE ?
                """);
        params.add("%" + query + "%");
        if (repo != null) {
            sql.append("  AND repo_slug = ?\n");
            params.add(repo);
        }
        // Closest name first, then the shortest -- an exact match should outrank a
        // longer symbol that merely contains the query.
        sql.append("ORDER BY similarity(symbol, ?) DESC, length(symbol) ASC\nLIMIT ?");
        params.add(query);
        params.add(limit);

        return jdbc.query(sql.toString(), (rs, n) -> new SymbolHit(
                rs.getString("repo_slug"),
                rs.getString("path"),
                rs.getString("path") + ":" + rs.getInt("start_line") + "-" + rs.getInt("end_line"),
                rs.getString("symbol"),
                rs.getString("signature"),
                rs.getString("kind"),
                rs.getInt("start_line"),
                rs.getInt("end_line")), params.toArray());
    }

    public List<RepositoryInfo> listRepositories() {
        return jdbc.query("""
                SELECT slug, last_branch, last_commit_sha, last_indexed_at, file_count, chunk_count
                FROM code_repository
                ORDER BY slug
                """, (rs, n) -> new RepositoryInfo(
                rs.getString("slug"),
                rs.getString("last_branch"),
                rs.getString("last_commit_sha"),
                rs.getTimestamp("last_indexed_at") == null
                        ? null : rs.getTimestamp("last_indexed_at").toInstant().toString(),
                rs.getLong("file_count"),
                rs.getLong("chunk_count")));
    }

    /** Distinct model ids present in the index. Used by the start-up guard. */
    public List<String> indexedModelIds() {
        return jdbc.queryForList("SELECT DISTINCT model_id FROM code_embedding", String.class);
    }
}
