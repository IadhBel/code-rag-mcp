package com.acme.coderag.indexer.index;

/**
 * Counters for one execution, persisted to {@code index_run}.
 *
 * <p>{@code chunksEmbedded} versus {@code chunksReused} is the number the whole
 * design exists to move: reused chunks are Vertex AI calls that did not happen.
 * Recording it means the saving can be shown rather than claimed.
 */
public final class IndexRunStats {

    private int filesSeen;
    private int filesSkipped;
    private int filesIndexed;
    private int filesDeleted;
    private int chunksWritten;
    private int chunksEmbedded;
    private int chunksReused;

    public void addFilesSeen(int n) {
        filesSeen += n;
    }

    public void incrementFilesSkipped() {
        filesSkipped++;
    }

    public void incrementFilesIndexed() {
        filesIndexed++;
    }

    public void addFilesDeleted(int n) {
        filesDeleted += n;
    }

    public void addChunksWritten(int n) {
        chunksWritten += n;
    }

    public void addChunksEmbedded(int n) {
        chunksEmbedded += n;
    }

    public void addChunksReused(int n) {
        chunksReused += n;
    }

    public int filesSeen() {
        return filesSeen;
    }

    public int filesSkipped() {
        return filesSkipped;
    }

    public int filesIndexed() {
        return filesIndexed;
    }

    public int filesDeleted() {
        return filesDeleted;
    }

    public int chunksWritten() {
        return chunksWritten;
    }

    public int chunksEmbedded() {
        return chunksEmbedded;
    }

    public int chunksReused() {
        return chunksReused;
    }

    /** Share of chunks that did not need an embedding call, as a percentage. */
    public int reuseRatePercent() {
        int total = chunksEmbedded + chunksReused;
        return total == 0 ? 0 : Math.round(chunksReused * 100f / total);
    }

    @Override
    public String toString() {
        return "files seen=%d skipped=%d indexed=%d deleted=%d | chunks written=%d embedded=%d reused=%d (%d%% reuse)"
                .formatted(filesSeen, filesSkipped, filesIndexed, filesDeleted,
                        chunksWritten, chunksEmbedded, chunksReused, reuseRatePercent());
    }
}
