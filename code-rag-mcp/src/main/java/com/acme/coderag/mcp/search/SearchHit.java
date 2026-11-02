package com.acme.coderag.mcp.search;

/**
 * One search result, sized to be read by an agent rather than a human browser.
 *
 * <p>{@code location} is pre-formatted as {@code path:start-end} because that is
 * the form an agent can act on directly -- and because formatting it here means it
 * is not spent as tokens on the caller reassembling it.
 *
 * @param rank    1 is the best match. A rank rather than a score: results come from
 *                reciprocal rank fusion over two retrievers whose own scores are on
 *                unrelated scales, so any single number printed here would be a
 *                fusion artefact rather than a similarity anyone could interpret.
 * @param snippet the first few lines of the chunk, bounded by
 *                {@code coderag.search.snippet-lines}. Use {@code read_lines} to
 *                see more; do not assume this is the whole chunk.
 */
public record SearchHit(
        String repo,
        String path,
        String location,
        String symbol,
        String signature,
        String kind,
        String language,
        int startLine,
        int endLine,
        String commitSha,
        int rank,
        String snippet) {
}
