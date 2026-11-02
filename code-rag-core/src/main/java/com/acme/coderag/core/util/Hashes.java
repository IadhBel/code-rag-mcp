package com.acme.coderag.core.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content hashing for deduplication.
 *
 * <p><strong>This class is a contract, not a utility.</strong> The indexer uses
 * it to decide whether a chunk needs embedding; the MCP server relies on the
 * resulting identity when joining chunks to vectors. If normalisation ever
 * changes, every existing hash becomes meaningless and the whole corpus must be
 * re-indexed. Treat any edit here as a breaking schema change.
 */
public final class Hashes {

    private Hashes() {
    }

    /**
     * Canonical form used before hashing.
     *
     * <p>Line endings are unified and trailing whitespace is dropped, so a
     * reformat that only touches those does not invalidate an embedding. Leading
     * indentation is deliberately preserved: in source code it carries structure,
     * and two blocks differing only in indentation are genuinely different
     * contexts.
     */
    public static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = unified.split("\n", -1);
        StringBuilder out = new StringBuilder(unified.length());
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            out.append(stripTrailing(lines[i]));
        }
        return out.toString().strip();
    }

    /** SHA-256 of {@link #normalize(String)}, lowercase hex, 64 characters. */
    public static String sha256OfNormalized(String text) {
        return sha256Hex(normalize(text));
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; this cannot happen.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return end == line.length() ? line : line.substring(0, end);
    }
}
