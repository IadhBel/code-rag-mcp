package com.acme.coderag.indexer;

import com.acme.coderag.indexer.index.IndexRunStats;
import com.acme.coderag.indexer.index.IndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Runs the job and turns its outcome into a process exit code.
 *
 * <p>The exit code is the only signal the pipeline sees, so a failure must never
 * be swallowed: a job that logs an error and exits zero produces a green pipeline
 * over a stale index, which is worse than a red one.
 */
@Component
public class IndexerJobRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(IndexerJobRunner.class);

    private final IndexService indexService;
    private int exitCode;

    public IndexerJobRunner(IndexService indexService) {
        this.indexService = indexService;
    }

    @Override
    public void run(ApplicationArguments args) {
        long startedAt = System.nanoTime();
        try {
            IndexRunStats stats = indexService.run();
            log.info("Indexing succeeded in {} ms -- {}",
                    (System.nanoTime() - startedAt) / 1_000_000, stats);
        } catch (RuntimeException e) {
            log.error("Indexing failed after {} ms",
                    (System.nanoTime() - startedAt) / 1_000_000, e);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
