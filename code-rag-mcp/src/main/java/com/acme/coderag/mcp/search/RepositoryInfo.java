package com.acme.coderag.mcp.search;

/**
 * What is indexed, and how current it is.
 *
 * <p>{@code lastIndexedAt} and {@code commitSha} are the honest answer to "can I
 * trust this index": if the commit is not the one being worked on, retrieval
 * results describe the past.
 */
public record RepositoryInfo(
        String slug,
        String branch,
        String commitSha,
        String lastIndexedAt,
        long fileCount,
        long chunkCount) {
}
