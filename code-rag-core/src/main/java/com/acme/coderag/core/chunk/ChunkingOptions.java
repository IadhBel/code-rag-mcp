package com.acme.coderag.core.chunk;

/**
 * Chunking limits.
 *
 * <p>These values are part of the index contract: changing them changes chunk
 * boundaries, therefore chunk hashes, therefore invalidates every stored
 * embedding. Change them together with a full re-index, never in isolation.
 *
 * @param maxTokensPerChunk hard ceiling per chunk. Must stay below the embedding
 *                          model's input limit, with headroom for the estimator
 *                          being approximate.
 * @param overlapLines      lines repeated between consecutive windows when a
 *                          region has to be split. Overlap prevents an answer
 *                          from falling exactly on a boundary.
 * @param minChunkTokens    floor for <em>unstructured</em> regions: a scrap of
 *                          leftover text this small is dropped rather than
 *                          embedded. Declarations are never dropped, however
 *                          short -- a method the chunker omits has no row in
 *                          {@code code_chunk}, so it is missing from
 *                          {@code get_file_outline} and unfindable by
 *                          {@code find_symbol}.
 */
public record ChunkingOptions(int maxTokensPerChunk, int overlapLines, int minChunkTokens) {

    /**
     * Sized for {@code gemini-embedding-001} (2 048 token input limit): 1 400
     * leaves room for the estimator under-counting dense code.
     */
    public static final ChunkingOptions DEFAULTS = new ChunkingOptions(1_400, 8, 12);

    public ChunkingOptions {
        if (maxTokensPerChunk < 64) {
            throw new IllegalArgumentException("maxTokensPerChunk too small: " + maxTokensPerChunk);
        }
        if (overlapLines < 0) {
            throw new IllegalArgumentException("overlapLines must not be negative");
        }
        if (minChunkTokens < 0) {
            throw new IllegalArgumentException("minChunkTokens must not be negative");
        }
    }
}
