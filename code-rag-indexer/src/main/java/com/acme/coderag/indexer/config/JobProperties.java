package com.acme.coderag.indexer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * What to index on this execution.
 *
 * <p>Every field is normally supplied by the pipeline as an environment override
 * on {@code gcloud run jobs execute}, which is why they have no defaults worth
 * relying on: a mistyped variable must fail the job, not silently index the wrong
 * thing.
 *
 * @param repoSlug        stable identifier used in every table and in MCP tool
 *                        arguments, e.g. {@code acme/order-service}
 * @param cloneUrl        HTTPS clone URL
 * @param branch          branch being indexed, recorded for reporting
 * @param commitSha       exact commit to index. The job checks out this commit
 *                        rather than a branch tip, so a slow queue cannot cause it
 *                        to index a different revision than the one that triggered it.
 * @param gitUsername     e.g. {@code x-access-token}
 * @param gitToken        short-lived credential. Passed to git through
 *                        {@code GIT_ASKPASS} rather than embedded in the URL, so it
 *                        never appears in the process arguments.
 * @param pruneDeleted    remove files that no longer exist at this commit
 * @param maxFileBytes    files above this size are skipped
 * @param gitTimeout      per-git-command timeout
 */
@ConfigurationProperties(prefix = "coderag.job")
public record JobProperties(
        String repoSlug,
        String cloneUrl,
        String branch,
        String commitSha,
        String gitUsername,
        String gitToken,
        boolean pruneDeleted,
        int maxFileBytes,
        Duration gitTimeout) {

    public void validate() {
        require(repoSlug, "coderag.job.repo-slug");
        require(cloneUrl, "coderag.job.clone-url");
        require(commitSha, "coderag.job.commit-sha");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
    }
}
