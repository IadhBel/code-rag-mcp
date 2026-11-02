package com.acme.coderag.core.chunk;

import java.util.Arrays;

/**
 * Offset-to-line mapping, built once per file.
 *
 * <p>Every chunk carries a 1-based inclusive line range, because that is what
 * makes retrieval results actionable: {@code OrderService.java:120-148} can be
 * opened directly, and {@code read_lines} can serve an exact slice without the
 * caller guessing.
 */
public final class LineIndex {

    /** Offset at which each line starts; {@code lineStarts[0]} is always 0. */
    private final int[] lineStarts;
    private final int length;

    private LineIndex(int[] lineStarts, int length) {
        this.lineStarts = lineStarts;
        this.length = length;
    }

    public static LineIndex of(String source) {
        int[] starts = new int[Math.max(8, source.length() / 32 + 2)];
        int count = 0;
        starts[count++] = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n' && i + 1 <= source.length()) {
                if (count == starts.length) {
                    starts = Arrays.copyOf(starts, starts.length * 2);
                }
                starts[count++] = i + 1;
            }
        }
        return new LineIndex(Arrays.copyOf(starts, count), source.length());
    }

    /** Total number of lines, counting a trailing empty line only if the file ends without a newline. */
    public int lineCount() {
        return lineStarts.length;
    }

    /** 1-based line containing {@code offset}. */
    public int lineOf(int offset) {
        int clamped = Math.max(0, Math.min(offset, length));
        int found = Arrays.binarySearch(lineStarts, clamped);
        return found >= 0 ? found + 1 : -found - 1;
    }

    /** Offset where the given 1-based line starts. */
    public int startOf(int line) {
        return lineStarts[clampLine(line) - 1];
    }

    /** Offset just past the last character of the given 1-based line, excluding its newline. */
    public int endOf(int line) {
        int l = clampLine(line);
        return l == lineStarts.length ? length : Math.max(lineStarts[l - 1], lineStarts[l] - 1);
    }

    /** Text of lines {@code from..to}, both 1-based and inclusive. */
    public String slice(String source, int from, int to) {
        int f = clampLine(from);
        int t = clampLine(to);
        if (t < f) {
            return "";
        }
        return source.substring(startOf(f), endOf(t));
    }

    private int clampLine(int line) {
        return Math.max(1, Math.min(line, lineStarts.length));
    }
}
