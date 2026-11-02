package com.acme.coderag.core.model;

import java.util.Objects;

/**
 * One retrievable unit of source code.
 *
 * <p>{@code sha} is the second- and third-level deduplication key. It is the
 * hash of the <em>normalised</em> chunk text, so it is stable across
 * reformatting that only touches trailing whitespace or line endings, and
 * identical across repositories. The indexer embeds a chunk only when its
 * {@code sha} is absent from {@code code_embedding}, which means:
 *
 * <ul>
 *   <li>editing one method in a 40-method class re-embeds 1 chunk, not 40;</li>
 *   <li>the same generated DTO copied into three repositories is embedded once.</li>
 * </ul>
 *
 * @param index          position within the file, ordered by {@code startLine}
 * @param kind           structural role
 * @param symbol         dotted/hashed identifier, e.g. {@code OrderService#findById}
 * @param signature      the declaration line, without body
 * @param startLine      1-based, inclusive
 * @param endLine        1-based, inclusive
 * @param text           the chunk text actually embedded
 * @param sha            SHA-256 of the normalised text, lowercase hex
 * @param tokenEstimate  rough token count, used to keep chunks under the
 *                       embedding model's input limit
 */
public record SourceChunk(
        int index,
        ChunkKind kind,
        String symbol,
        String signature,
        String language,
        int startLine,
        int endLine,
        String text,
        String sha,
        int tokenEstimate) {

    public SourceChunk {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(sha, "sha");
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException(
                    "invalid line range " + startLine + ".." + endLine + " for " + symbol);
        }
    }

    /** A copy with a new index, used when chunks are renumbered after sub-splitting. */
    public SourceChunk withIndex(int newIndex) {
        return new SourceChunk(newIndex, kind, symbol, signature, language,
                startLine, endLine, text, sha, tokenEstimate);
    }
}
