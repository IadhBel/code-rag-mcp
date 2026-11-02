package com.acme.coderag.core.chunk;

import com.acme.coderag.core.lang.Languages;
import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;

import java.util.List;

/**
 * Single entry point both applications use to chunk a file.
 *
 * <p>Kept as the only public path into the chunkers so the writer and the reader
 * cannot accidentally choose different strategies for the same file.
 */
public final class ChunkerRouter implements Chunker {

    private final Chunker structural;
    private final Chunker plain;

    public ChunkerRouter(ChunkingOptions options) {
        this.structural = new BraceStructuralChunker(options);
        this.plain = new PlainTextChunker(options);
    }

    public static ChunkerRouter withDefaults() {
        return new ChunkerRouter(ChunkingOptions.DEFAULTS);
    }

    @Override
    public List<SourceChunk> chunk(SourceFile file) {
        if (!Languages.isIndexable(file.path())) {
            return List.of();
        }
        return Languages.of(file.path()).braceStructured()
                ? structural.chunk(file)
                : plain.chunk(file);
    }
}
