package com.acme.coderag.indexer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Embedding configuration for the indexing side.
 *
 * @param provider  which model to build
 * @param dimension vector length. Also injected into the Flyway migration, so this
 *                  is the single place the index dimension is decided; changing it
 *                  invalidates every stored vector.
 * @param vertex    required when {@code provider} is {@code VERTEX}
 */
@ConfigurationProperties(prefix = "coderag.embedding")
public record EmbeddingProperties(Provider provider, int dimension, Vertex vertex) {

    public enum Provider {
        /** Google Cloud Vertex AI. The only production-supported option. */
        VERTEX,
        /**
         * Hash-derived vectors with no semantic meaning, for tests and offline
         * development. See
         * {@link com.acme.coderag.core.embed.DeterministicEmbeddingModel}.
         */
        DETERMINISTIC
    }

    /**
     * @param model    e.g. {@code gemini-embedding-001}
     * @param taskType the <em>document</em> task type, e.g. {@code RETRIEVAL_DOCUMENT}.
     *                 Only one is configured here because Vertex sets the task type
     *                 per model instance and this application never embeds a query;
     *                 the MCP server configures the query side independently.
     */
    public record Vertex(String project, String location, String model,
                         String taskType, int maxRetries) {
    }
}
