package com.acme.coderag.core.util;

/**
 * Cheap token estimation.
 *
 * <p>Deliberately a heuristic rather than a real tokeniser. Its only job is to
 * keep a chunk under the embedding model's hard input limit
 * ({@code gemini-embedding-001} truncates above 2 048 tokens), and for that a
 * conservative over-estimate is safer and far cheaper than loading a tokeniser
 * into a batch job that processes tens of thousands of files.
 *
 * <p>Four characters per token is the usual rule of thumb for prose. Source code
 * tokenises slightly denser because of punctuation, so this tends to
 * over-estimate -- which is the direction we want.
 */
public final class Tokens {

    private static final int CHARS_PER_TOKEN = 4;

    private Tokens() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.max(1, (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN);
    }
}
