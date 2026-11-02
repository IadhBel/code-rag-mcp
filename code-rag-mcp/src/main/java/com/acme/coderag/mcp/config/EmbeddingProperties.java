package com.acme.coderag.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding configuration for the query side.
 *
 * <p>The model and dimension must match what built the index, and
 * {@link SchemaGuard} refuses to start the server if they do not: a mismatched
 * model produces plausible-looking similarity scores over incomparable vectors,
 * which is the single worst failure mode this system has -- wrong answers with no
 * error.
 */
@ConfigurationProperties(prefix = "coderag.embedding")
public record EmbeddingProperties(Provider provider, int dimension, Vertex vertex,
                                  boolean allowDeterministicIndex) {

    public enum Provider {
        VERTEX,
        DETERMINISTIC
    }

    /**
     * @param taskType the <em>query</em> task type, e.g. {@code RETRIEVAL_QUERY}, or
     *                 {@code CODE_RETRIEVAL_QUERY} where the model supports it -- it
     *                 is built for exactly this workload. Deliberately different from
     *                 the indexer's {@code RETRIEVAL_DOCUMENT}: the asymmetry is what
     *                 the model expects, and matching them degrades ranking.
     */
    public record Vertex(String project, String location, String model,
                         String taskType, int maxRetries) {
    }
}
