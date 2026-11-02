package com.acme.coderag.core.embed;

/**
 * Identity of the embedding model that produced a vector.
 *
 * <p>Stored in {@code code_embedding.model_id} and part of that table's primary
 * key, because two models produce incomparable vectors for the same text. Two
 * consequences worth stating explicitly:
 *
 * <ul>
 *   <li>switching model or dimension requires a full re-index, then purging the
 *       old {@code model_id} rows -- vectors cannot be migrated;</li>
 *   <li>the MCP server refuses to start if its configured model id differs from
 *       the one present in the index, rather than silently returning nonsense
 *       similarity scores.</li>
 * </ul>
 *
 * @param provider  e.g. {@code vertex}, {@code deterministic}
 * @param modelName e.g. {@code gemini-embedding-001}
 * @param dimension vector length actually stored
 */
public record ModelId(String provider, String modelName, int dimension) {

    public ModelId {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName is required");
        }
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }
    }

    /** Canonical database representation, e.g. {@code vertex:gemini-embedding-001:768}. */
    public String asString() {
        return provider + ":" + modelName + ":" + dimension;
    }

    @Override
    public String toString() {
        return asString();
    }
}
