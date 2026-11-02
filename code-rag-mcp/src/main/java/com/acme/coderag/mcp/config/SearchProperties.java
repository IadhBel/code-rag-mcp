package com.acme.coderag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Retrieval and response-size tuning.
 *
 * <p>The size limits exist for one reason: every character a tool returns lands in
 * an agent's context window. A tool that helpfully returns "a bit more, just in
 * case" defeats the purpose of the whole service.
 *
 * <p>There is no fusion constant to tune here. Rank fusion is LangChain4j's
 * {@code DefaultContentAggregator}, which uses the reciprocal-rank-fusion constant
 * from the original paper.
 *
 * @param candidatesPerStrategy how many hits each of the dense and lexical searches
 *                              retrieves before fusion. Larger means better recall
 *                              and a slower query.
 * @param defaultLimit          hits returned when the caller does not say
 * @param maxLimit              hard ceiling on hits per call
 * @param snippetLines          lines of code shown per hit. Shared by both halves of
 *                              the search -- see {@link com.acme.coderag.core.store.CodeChunks}
 *                              for why they must agree.
 * @param maxReadLines          hard ceiling for {@code read_lines}
 */
@ConfigurationProperties(prefix = "coderag.search")
public record SearchProperties(
        int candidatesPerStrategy,
        int defaultLimit,
        int maxLimit,
        int snippetLines,
        int maxReadLines) {

    public int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return defaultLimit;
        }
        return Math.min(requested, maxLimit);
    }
}
