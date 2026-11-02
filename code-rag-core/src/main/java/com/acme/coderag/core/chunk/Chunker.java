package com.acme.coderag.core.chunk;

import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;

import java.util.List;

/**
 * Splits a file into retrievable units.
 *
 * <p>Implementations must be deterministic and side-effect free: the same input
 * must always yield byte-identical chunks, or deduplication breaks and the
 * corpus grows without bound.
 */
public interface Chunker {

    /**
     * @return chunks ordered by {@code startLine}, with {@code index} assigned
     *         sequentially from 0. Never {@code null}; empty for a file with no
     *         indexable content.
     */
    List<SourceChunk> chunk(SourceFile file);
}
