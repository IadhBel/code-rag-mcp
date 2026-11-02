package com.acme.coderag.indexer.store;

import com.acme.coderag.core.embed.Vectors;
import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;
import com.acme.coderag.core.util.Identifiers;
import com.acme.coderag.indexer.index.IndexRunStats;
import com.acme.coderag.indexer.index.IndexUnit;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All write access to the index. The MCP server has no equivalent: it is read-only. */
@Repository
public class IndexWriteRepository {

    private static final Logger log = LoggerFactory.getLogger(IndexWriteRepository.class);

    private final JdbcTemplate jdbc;

    public IndexWriteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Run bookkeeping ─────────────────────────────────────────────────────

    public void upsertRepository(String slug, String cloneUrl) {
        jdbc.update("""
                INSERT INTO code_repository (slug, clone_url)
                VALUES (?, ?)
                ON CONFLICT (slug) DO UPDATE SET clone_url = EXCLUDED.clone_url
                """, slug, cloneUrl);
    }

    public long startRun(String slug, String commitSha, String branch) {
        Long id = jdbc.queryForObject("""
                INSERT INTO index_run (repo_slug, commit_sha, branch, status)
                VALUES (?, ?, ?, 'RUNNING')
                RETURNING id
                """, Long.class, slug, commitSha, branch);
        return id == null ? -1L : id;
    }

    public void finishRun(long runId, String status, IndexRunStats stats, String error) {
        jdbc.update("""
                UPDATE index_run SET
                    status = ?, finished_at = now(),
                    files_seen = ?, files_skipped = ?, files_indexed = ?, files_deleted = ?,
                    chunks_written = ?, chunks_embedded = ?, chunks_reused = ?,
                    error_message = ?
                WHERE id = ?
                """,
                status,
                stats.filesSeen(), stats.filesSkipped(), stats.filesIndexed(), stats.filesDeleted(),
                stats.chunksWritten(), stats.chunksEmbedded(), stats.chunksReused(),
                truncate(error, 4000),
                runId);
    }

    public void refreshCounters(String slug, String branch, String commitSha) {
        jdbc.update("""
                UPDATE code_repository r SET
                    last_branch = ?, last_commit_sha = ?, last_indexed_at = now(),
                    file_count  = (SELECT count(*) FROM code_file  f WHERE f.repo_slug = r.slug),
                    chunk_count = (SELECT count(*) FROM code_chunk c WHERE c.repo_slug = r.slug)
                WHERE r.slug = ?
                """, branch, commitSha, slug);
    }

    // ── Deduplication level 1: which files changed ───────────────────────────

    /** Current {@code path -> blob_sha} for a repository. */
    public Map<String, String> blobShasOf(String slug) {
        Map<String, String> shas = new HashMap<>();
        jdbc.query("SELECT path, blob_sha FROM code_file WHERE repo_slug = ?",
                rs -> {
                    shas.put(rs.getString("path"), rs.getString("blob_sha"));
                }, slug);
        return shas;
    }

    // ── Deduplication levels 2 and 3: which chunks already have a vector ─────

    /**
     * Of the given chunk hashes, which already have a vector for this model.
     *
     * <p>This single query is what turns "re-index the repository" into a handful
     * of embedding calls: hashes present here are skipped entirely, whether they
     * are unchanged methods in an edited file or code that happens to be identical
     * to something in another repository.
     */
    public Set<String> alreadyEmbedded(Collection<String> shas, String modelId) {
        if (shas.isEmpty()) {
            return Set.of();
        }
        Set<String> found = new HashSet<>();
        String[] array = shas.toArray(String[]::new);
        jdbc.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    SELECT chunk_sha FROM code_embedding
                    WHERE model_id = ? AND chunk_sha = ANY (?)
                    """);
            ps.setString(1, modelId);
            ps.setArray(2, connection.createArrayOf("text", array));
            return ps;
        }, rs -> {
            found.add(rs.getString(1));
        });
        return found;
    }

    /**
     * Stores vectors. {@code ON CONFLICT DO NOTHING} makes this idempotent, which
     * matters because two jobs indexing two repositories can legitimately produce
     * the same hash at the same moment.
     *
     * @return how many rows were genuinely inserted
     */
    public int insertEmbeddings(Map<String, Embedding> vectorsBySha, String modelId) {
        if (vectorsBySha.isEmpty()) {
            return 0;
        }
        List<Map.Entry<String, Embedding>> rows = new ArrayList<>(vectorsBySha.entrySet());
        int[] affected = jdbc.batchUpdate("""
                INSERT INTO code_embedding (chunk_sha, model_id, embedding)
                VALUES (?, ?, ?::vector)
                ON CONFLICT (chunk_sha, model_id) DO NOTHING
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Map.Entry<String, Embedding> row = rows.get(i);
                ps.setString(1, row.getKey());
                ps.setString(2, modelId);
                ps.setString(3, Vectors.toPgLiteral(row.getValue().vector()));
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
        int inserted = 0;
        for (int count : affected) {
            inserted += Math.max(count, 0);
        }
        return inserted;
    }

    // ── Writing files and chunks ────────────────────────────────────────────

    /**
     * Replaces the stored form of each file in one transaction.
     *
     * <p>Delete-then-insert rather than a merge: a file's chunk count and
     * boundaries change when it is edited, so trying to reconcile row by row would
     * be both slower and easy to get wrong. The unique key makes the delete an
     * index scan.
     *
     * <p>Embedding happens before this call, never inside it: holding a database
     * transaction open across a network call to Vertex AI would pin a connection
     * for the duration of the slowest request in the batch.
     */
    @Transactional
    public void writeUnits(String slug, String commitSha, List<IndexUnit> units) {
        for (IndexUnit unit : units) {
            upsertFile(slug, commitSha, unit.file());
            jdbc.update("DELETE FROM code_chunk WHERE repo_slug = ? AND path = ?",
                    slug, unit.file().path());
            insertChunks(slug, commitSha, unit);
        }
    }

    private void upsertFile(String slug, String commitSha, SourceFile file) {
        jdbc.update("""
                INSERT INTO code_file
                    (repo_slug, path, language, blob_sha, content, line_count, commit_sha, indexed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (repo_slug, path) DO UPDATE SET
                    language   = EXCLUDED.language,
                    blob_sha   = EXCLUDED.blob_sha,
                    content    = EXCLUDED.content,
                    line_count = EXCLUDED.line_count,
                    commit_sha = EXCLUDED.commit_sha,
                    indexed_at = now()
                """,
                slug, file.path(), file.language(), file.blobSha(),
                file.content(), file.lineCount(), commitSha);
    }

    private void insertChunks(String slug, String commitSha, IndexUnit unit) {
        List<SourceChunk> chunks = unit.chunks();
        if (chunks.isEmpty()) {
            return;
        }
        String path = unit.file().path();
        jdbc.batchUpdate("""
                INSERT INTO code_chunk
                    (repo_slug, path, chunk_index, chunk_sha, kind, symbol, signature,
                     language, start_line, end_line, token_estimate, commit_sha, lexical)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, to_tsvector('simple', ?))
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                SourceChunk chunk = chunks.get(i);
                ps.setString(1, slug);
                ps.setString(2, path);
                ps.setInt(3, chunk.index());
                ps.setString(4, chunk.sha());
                ps.setString(5, chunk.kind().name());
                ps.setString(6, chunk.symbol());
                ps.setString(7, chunk.signature());
                ps.setString(8, chunk.language());
                ps.setInt(9, chunk.startLine());
                ps.setInt(10, chunk.endLine());
                ps.setInt(11, chunk.tokenEstimate());
                ps.setString(12, commitSha);
                ps.setString(13, Identifiers.lexicalInput(
                        chunk.symbol(), chunk.signature(), chunk.text()));
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
    }

    // ── Pruning ─────────────────────────────────────────────────────────────

    /**
     * Removes files that no longer exist at the indexed commit. Chunks follow via
     * {@code ON DELETE CASCADE}.
     */
    public int deleteFilesNotIn(String slug, Set<String> keepPaths) {
        String[] keep = keepPaths.toArray(String[]::new);
        Integer deleted = jdbc.execute((java.sql.Connection connection) -> {
            try (PreparedStatement ps = connection.prepareStatement("""
                    DELETE FROM code_file
                    WHERE repo_slug = ? AND NOT (path = ANY (?))
                    """)) {
                ps.setString(1, slug);
                ps.setArray(2, connection.createArrayOf("text", keep));
                return ps.executeUpdate();
            }
        });
        return deleted == null ? 0 : deleted;
    }

    /**
     * Drops vectors no chunk points at any more.
     *
     * <p>A full anti-join, so it is only worth running after a prune actually
     * removed something. Vectors are the expensive rows, but they are also shared:
     * a hash orphaned in one repository may still be referenced from another,
     * which is exactly what the {@code NOT EXISTS} check verifies.
     */
    public int deleteOrphanEmbeddings() {
        int deleted = jdbc.update("""
                DELETE FROM code_embedding e
                WHERE NOT EXISTS (SELECT 1 FROM code_chunk c WHERE c.chunk_sha = e.chunk_sha)
                """);
        if (deleted > 0) {
            log.info("Garbage-collected {} orphaned embedding(s)", deleted);
        }
        return deleted;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
