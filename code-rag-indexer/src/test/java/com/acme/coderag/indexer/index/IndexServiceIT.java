package com.acme.coderag.indexer.index;

import com.acme.coderag.core.chunk.ChunkerRouter;
import com.acme.coderag.core.embed.EmbeddingModels;
import com.acme.coderag.core.embed.IdentifiedEmbeddingModel;
import com.acme.coderag.indexer.config.JobProperties;
import com.acme.coderag.indexer.git.GitClient;
import com.acme.coderag.indexer.store.IndexWriteRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof that re-indexing is nearly free.
 *
 * <p>This is the test that matters most in the repository. The design claims that
 * editing one method in a class costs one embedding call; here that claim is
 * measured against a real PostgreSQL with pgvector and a real git repository, rather
 * than asserted in a comment.
 *
 * <p>Uses {@link EmbeddingModels#deterministic(int)} so it runs offline with no
 * Google Cloud project and no billable calls. Vector <em>quality</em> is not under
 * test -- the deduplication accounting is.
 */
@Testcontainers
class IndexServiceIT {

    private static final int DIMENSION = 384;

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("coderag")
            .withUsername("coderag")
            .withPassword("coderag");

    static DataSource dataSource;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
        jdbc = new JdbcTemplate(source);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .placeholders(Map.of("vector_dimension", String.valueOf(DIMENSION)))
                .load()
                .migrate();
    }

    @Test
    @DisplayName("editing one method body re-embeds one chunk and reuses the rest")
    void reindexingReusesUnchangedChunks(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("origin");
        Files.createDirectories(repo);
        initGitRepo(repo);

        Files.writeString(repo.resolve("OrderService.java"), """
                package com.acme;

                public class OrderService {
                    private static final int MAX_RETRIES = 3;

                    public Order findById(Long id) {
                        return repository.findById(id).orElseThrow();
                    }

                    public void cancel(Long id) {
                        repository.cancel(id);
                    }

                    public void refund(Long id) {
                        repository.refund(id);
                    }
                }
                """);
        Files.writeString(repo.resolve("application.yml"), """
                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/orders
                """);
        Files.writeString(repo.resolve("README.md"), "# Orders\n\nHandles orders.\n");

        String firstCommit = commitAll(repo, "initial");

        IndexRunStats first = indexAt(repo, firstCommit);

        assertThat(first.filesIndexed()).isEqualTo(3);
        assertThat(first.chunksReused()).isZero();
        assertThat(first.chunksEmbedded()).isEqualTo(first.chunksWritten());
        int embeddedAfterFirst = countEmbeddings();
        int chunksAfterFirst = countChunks("test/orders");
        assertThat(embeddedAfterFirst).isEqualTo(first.chunksEmbedded());

        // --- Change exactly one method body -------------------------------
        Files.writeString(repo.resolve("OrderService.java"),
                Files.readString(repo.resolve("OrderService.java"))
                        .replace("repository.cancel(id);", "repository.cancelWithAudit(id);"));
        String secondCommit = commitAll(repo, "audit cancellations");

        IndexRunStats second = indexAt(repo, secondCommit);

        // The yaml and markdown files were not read at all: their blob SHA is
        // unchanged, which is the first-level filter doing its job.
        assertThat(second.filesSkipped()).isEqualTo(2);
        assertThat(second.filesIndexed()).isEqualTo(1);

        // Only the edited method's text changed. The type skeleton holds signatures
        // rather than bodies, so it hashes identically and is reused too.
        assertThat(second.chunksEmbedded())
                .as("only the edited method should need a new vector")
                .isEqualTo(1);
        assertThat(second.chunksReused())
                .as("everything else in the file should be reused")
                .isGreaterThan(second.chunksEmbedded());

        assertThat(countEmbeddings())
                .as("exactly one vector added")
                .isEqualTo(embeddedAfterFirst + 1);

        // A body edit changes no boundaries, so the chunk inventory is identical.
        assertThat(countChunks("test/orders"))
                .as("chunk count is unchanged by a body-only edit")
                .isEqualTo(chunksAfterFirst);

        // Run history records the saving so it can be reported, not just claimed.
        assertThat(jdbc.queryForObject("""
                SELECT chunks_reused FROM index_run
                WHERE repo_slug = ? ORDER BY started_at DESC LIMIT 1
                """, Integer.class, "test/orders"))
                .isEqualTo(second.chunksReused());
    }

    @Test
    @DisplayName("a deleted file and its chunks are pruned")
    void prunesDeletedFiles(@TempDir Path tempDir) throws Exception {
        Path repo = tempDir.resolve("origin2");
        Files.createDirectories(repo);
        initGitRepo(repo);

        Files.writeString(repo.resolve("Keep.java"), """
                public class Keep {
                    public void keep() {
                        System.out.println("keep");
                    }
                }
                """);
        Files.writeString(repo.resolve("Drop.java"), """
                public class Drop {
                    public void drop() {
                        System.out.println("drop");
                    }
                }
                """);
        String first = commitAll(repo, "two files");
        indexAt(repo, first, "test/prune");
        assertThat(pathCount("test/prune")).isEqualTo(2);

        Files.delete(repo.resolve("Drop.java"));
        String second = commitAll(repo, "remove one");
        IndexRunStats stats = indexAt(repo, second, "test/prune");

        assertThat(stats.filesDeleted()).isEqualTo(1);
        assertThat(pathCount("test/prune")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM code_chunk WHERE repo_slug = ? AND path = ?",
                Integer.class, "test/prune", "Drop.java"))
                .as("chunks follow the file via ON DELETE CASCADE")
                .isZero();
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private IndexRunStats indexAt(Path repo, String commitSha) {
        return indexAt(repo, commitSha, "test/orders");
    }

    private IndexRunStats indexAt(Path repo, String commitSha, String slug) {
        IdentifiedEmbeddingModel embeddings = EmbeddingModels.deterministic(DIMENSION);
        JobProperties job = new JobProperties(
                slug,
                repo.toUri().toString(),
                "main",
                commitSha,
                "",
                "",
                true,
                512 * 1024,
                Duration.ofMinutes(2));

        IndexService service = new IndexService(
                new GitClient(Duration.ofMinutes(2)),
                ChunkerRouter.withDefaults(),
                embeddings,
                new IndexWriteRepository(jdbc),
                job);
        return service.run();
    }

    private int countEmbeddings() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM code_embedding", Integer.class);
        return count == null ? 0 : count;
    }

    private int pathCount(String slug) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM code_file WHERE repo_slug = ?", Integer.class, slug);
        return count == null ? 0 : count;
    }

    private int countChunks(String slug) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM code_chunk WHERE repo_slug = ?", Integer.class, slug);
        return count == null ? 0 : count;
    }

    private static void initGitRepo(Path dir) throws IOException, InterruptedException {
        git(dir, "init", "-q", "-b", "main");
        git(dir, "config", "user.email", "ci@example.com");
        git(dir, "config", "user.name", "CI");
        // Allow shallow fetching from this local repository over file://.
        git(dir, "config", "uploadpack.allowFilter", "true");
        git(dir, "config", "uploadpack.allowAnySHA1InWant", "true");
    }

    private static String commitAll(Path dir, String message)
            throws IOException, InterruptedException {
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", message);
        return git(dir, "rev-parse", "HEAD").strip();
    }

    private static String git(Path dir, String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
        }
        return output;
    }
}
