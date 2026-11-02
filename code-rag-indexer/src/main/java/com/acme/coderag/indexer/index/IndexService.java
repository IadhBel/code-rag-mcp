package com.acme.coderag.indexer.index;

import com.acme.coderag.core.chunk.ChunkerRouter;
import com.acme.coderag.core.embed.IdentifiedEmbeddingModel;
import com.acme.coderag.core.lang.Languages;
import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;
import com.acme.coderag.indexer.config.JobProperties;
import com.acme.coderag.indexer.git.GitClient;
import com.acme.coderag.indexer.git.TreeEntry;
import com.acme.coderag.indexer.store.IndexWriteRepository;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Indexes one commit of one repository.
 *
 * <p>The whole point is to do as little as possible. Three filters run in order,
 * each cheaper than the work it avoids:
 *
 * <ol>
 *   <li><strong>Path and size</strong> -- build output, vendored trees and
 *       binaries never enter the corpus.</li>
 *   <li><strong>Blob SHA</strong> -- a file whose git object id is unchanged is
 *       not even read from disk. On a typical commit this eliminates almost
 *       everything.</li>
 *   <li><strong>Chunk hash</strong> -- of the chunks that remain, only those whose
 *       text has never been embedded for this model are sent to Vertex AI. Editing
 *       one method in a large class costs one embedding call.</li>
 * </ol>
 */
@Service
public class IndexService {

    private static final Logger log = LoggerFactory.getLogger(IndexService.class);

    /**
     * Chunks buffered before a flush. Bounds memory on a first full index while
     * still giving the embedding API batches large enough to be worth a round trip.
     */
    private static final int FLUSH_THRESHOLD_CHUNKS = 400;

    private final GitClient git;
    private final ChunkerRouter chunker;
    private final IdentifiedEmbeddingModel embeddings;
    private final IndexWriteRepository repository;
    private final JobProperties job;

    public IndexService(GitClient git, ChunkerRouter chunker, IdentifiedEmbeddingModel embeddings,
                        IndexWriteRepository repository, JobProperties job) {
        this.git = git;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.repository = repository;
        this.job = job;
    }

    public IndexRunStats run() {
        job.validate();
        IndexRunStats stats = new IndexRunStats();
        Path workDir = null;
        long runId = -1;

        try {
            workDir = Files.createTempDirectory("coderag-index-");
            repository.upsertRepository(job.repoSlug(), job.cloneUrl());
            runId = repository.startRun(job.repoSlug(), job.commitSha(), job.branch());

            log.info("Indexing {} at {} (branch {}) with model {}",
                    job.repoSlug(), job.commitSha(), job.branch(), embeddings.modelId());

            String head = git.checkout(workDir, job.cloneUrl(), job.commitSha(),
                    job.branch(), job.gitUsername(), job.gitToken());
            List<TreeEntry> tree = git.listTree(workDir);
            stats.addFilesSeen(tree.size());

            Map<String, String> knownShas = repository.blobShasOf(job.repoSlug());
            Set<String> livePaths = new HashSet<>();
            List<IndexUnit> pending = new ArrayList<>();
            int pendingChunks = 0;

            for (TreeEntry entry : tree) {
                if (!Languages.isIndexable(entry.path())
                        || entry.sizeBytes() > job.maxFileBytes()) {
                    continue;
                }
                // Recorded before the skip test: an unchanged file is still part of
                // the corpus and must survive pruning.
                livePaths.add(entry.path());

                if (entry.blobSha().equals(knownShas.get(entry.path()))) {
                    stats.incrementFilesSkipped();
                    continue;
                }

                Optional<SourceFile> loaded = read(workDir, entry);
                if (loaded.isEmpty()) {
                    stats.incrementFilesSkipped();
                    continue;
                }
                SourceFile file = loaded.get();
                List<SourceChunk> chunks = chunker.chunk(file);

                pending.add(new IndexUnit(file, chunks));
                pendingChunks += chunks.size();
                stats.incrementFilesIndexed();

                if (pendingChunks >= FLUSH_THRESHOLD_CHUNKS) {
                    flush(pending, head, stats);
                    pending.clear();
                    pendingChunks = 0;
                }
            }
            flush(pending, head, stats);

            if (job.pruneDeleted()) {
                int deleted = repository.deleteFilesNotIn(job.repoSlug(), livePaths);
                stats.addFilesDeleted(deleted);
                if (deleted > 0) {
                    repository.deleteOrphanEmbeddings();
                }
            }

            repository.refreshCounters(job.repoSlug(), job.branch(), head);
            repository.finishRun(runId, "SUCCEEDED", stats, null);
            return stats;

        } catch (IOException e) {
            failRun(runId, stats, e);
            throw new IllegalStateException("indexing failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            failRun(runId, stats, e);
            throw e;
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Embeds what is genuinely new, then persists the batch.
     *
     * <p>The whole batch of new chunks goes to {@code embedAll} in one call.
     * LangChain4j splits it into requests that respect Vertex's segments-per-call
     * and tokens-per-call limits and retries the failures, so there is no batching
     * loop here to get wrong.
     *
     * <p>Embedding happens before the transaction opens. Calling Vertex AI while
     * holding a database transaction would pin a connection for as long as the
     * slowest request in the batch, and on a first full index that is the
     * difference between minutes and hours of connection pressure.
     *
     * <p>This is also the one place a LangChain4j abstraction is deliberately not
     * used. {@code EmbeddingStoreIngestor} would split, embed and store in a single
     * call, but it offers no seam between "embed" and "store", so it cannot skip a
     * chunk whose vector already exists -- and skipping is the entire point.
     * {@code EmbeddingModel} cannot express it either: its contract is one vector
     * returned per input, so a caching decorator would have to read every reused
     * vector back out of the database to satisfy it. Asking which hashes are already
     * present, and embedding only the rest, is three lines and reads plainly.
     */
    private void flush(List<IndexUnit> units, String commitSha, IndexRunStats stats) {
        if (units.isEmpty()) {
            return;
        }
        String modelId = embeddings.modelId().asString();

        // Deduplicate within the batch: the same boilerplate often appears in
        // several files of the same commit.
        Map<String, String> textBySha = new LinkedHashMap<>();
        int chunkCount = 0;
        for (IndexUnit unit : units) {
            for (SourceChunk chunk : unit.chunks()) {
                textBySha.putIfAbsent(chunk.sha(), chunk.text());
                chunkCount++;
            }
        }

        Set<String> present = repository.alreadyEmbedded(textBySha.keySet(), modelId);
        List<String> missing = textBySha.keySet().stream()
                .filter(sha -> !present.contains(sha))
                .toList();

        if (!missing.isEmpty()) {
            List<TextSegment> segments = missing.stream()
                    .map(sha -> TextSegment.from(textBySha.get(sha)))
                    .toList();
            List<Embedding> vectors = embeddings.embedAll(segments).content();

            Map<String, Embedding> rows = new LinkedHashMap<>(missing.size());
            for (int i = 0; i < missing.size(); i++) {
                rows.put(missing.get(i), vectors.get(i));
            }
            int inserted = repository.insertEmbeddings(rows, modelId);
            if (inserted < rows.size()) {
                // Another job embedded the same content concurrently. Harmless.
                log.debug("{} of {} vectors were already present on insert",
                        rows.size() - inserted, rows.size());
            }
        }

        // Counted as "paid for", not "rows inserted": a concurrent job winning the
        // race does not refund the API call we made.
        stats.addChunksEmbedded(missing.size());
        stats.addChunksReused(chunkCount - missing.size());
        stats.addChunksWritten(chunkCount);

        repository.writeUnits(job.repoSlug(), commitSha, units);

        log.info("Flushed {} file(s), {} chunk(s): {} embedded, {} reused",
                units.size(), chunkCount, missing.size(), chunkCount - missing.size());
    }

    // ── File loading ────────────────────────────────────────────────────────

    private Optional<SourceFile> read(Path workDir, TreeEntry entry) {
        Path resolved = workDir.resolve(entry.path()).normalize();
        if (!resolved.startsWith(workDir.normalize())) {
            // Cannot happen with paths from ls-tree, but a path that escapes the
            // work directory is worth refusing loudly rather than reading.
            log.warn("Refusing path outside the work tree: {}", entry.path());
            return Optional.empty();
        }
        try {
            String raw = Files.readString(resolved, StandardCharsets.UTF_8);
            if (raw.indexOf('\0') >= 0) {
                return Optional.empty();
            }
            String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
            return Optional.of(new SourceFile(
                    entry.path(),
                    Languages.of(entry.path()).id(),
                    entry.blobSha(),
                    normalized));
        } catch (MalformedInputException e) {
            log.debug("Skipping non-UTF-8 file {}", entry.path());
            return Optional.empty();
        } catch (IOException e) {
            log.warn("Could not read {}: {}", entry.path(), e.getMessage());
            return Optional.empty();
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void failRun(long runId, IndexRunStats stats, Exception e) {
        if (runId > 0) {
            try {
                repository.finishRun(runId, "FAILED", stats, e.toString());
            } catch (RuntimeException nested) {
                log.error("Could not record the failure on index_run {}", runId, nested);
            }
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.debug("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean the work directory {}: {}", root, e.getMessage());
        }
    }
}
