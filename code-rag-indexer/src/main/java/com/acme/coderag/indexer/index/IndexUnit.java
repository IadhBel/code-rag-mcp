package com.acme.coderag.indexer.index;

import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;

import java.util.List;

/**
 * One file, chunked and waiting to be written.
 *
 * <p>Held in memory only between chunking and the flush that persists it, so that
 * embedding calls can be batched across several files -- Vertex AI accepts up to
 * 250 segments per request, and embedding one file at a time would waste most of
 * that.
 */
public record IndexUnit(SourceFile file, List<SourceChunk> chunks) {
}
