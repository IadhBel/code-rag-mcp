package com.acme.coderag.mcp.search;

/**
 * An exact range of lines from an indexed file.
 *
 * <p>Sliced in the database rather than by fetching the file and cutting it in
 * Java, so a fifteen-line request transfers fifteen lines.
 *
 * @param truncated true when the requested range was reduced to respect
 *                  {@code coderag.search.max-read-lines}. Stated explicitly so the
 *                  caller never silently believes it has the whole range.
 */
public record FileSlice(
        String repo,
        String path,
        int fromLine,
        int toLine,
        int fileLineCount,
        boolean truncated,
        String content) {
}
