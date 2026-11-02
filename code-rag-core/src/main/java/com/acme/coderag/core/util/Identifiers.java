package com.acme.coderag.core.util;

/**
 * Identifier tokenisation for the lexical half of hybrid search.
 *
 * <p>Postgres' {@code simple} dictionary treats {@code findByAccountId} as one
 * token, so a search for "find account" would never match it even though a human
 * would consider that an obvious hit. Splitting camel case, snake case and dotted
 * paths into words fixes that, and it must be applied identically when indexing
 * and when querying -- hence living in the shared module rather than in either
 * application.
 */
public final class Identifiers {

    private Identifiers() {
    }

    /**
     * Splits identifiers into space-separated words.
     *
     * <p>{@code OrderService#findByAccountId} becomes
     * {@code Order Service find By Account Id}, and {@code MAX_RETRY_COUNT}
     * becomes {@code MAX RETRY COUNT}. Acronym runs are kept together up to the
     * last capital that starts a new word, so {@code parseHTTPResponse} yields
     * {@code parse HTTP Response}.
     */
    public static String splitWords(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '_' || c == '.' || c == '#' || c == '$' || c == '/' || c == '-') {
                out.append(' ');
                continue;
            }
            boolean startsNewWord = i > 0
                    && Character.isUpperCase(c)
                    && (Character.isLowerCase(text.charAt(i - 1))
                        || (i + 1 < text.length() && Character.isLowerCase(text.charAt(i + 1))
                            && Character.isUpperCase(text.charAt(i - 1))));
            if (startsNewWord) {
                out.append(' ');
            }
            out.append(c);
        }
        return out.toString().replaceAll("\\s+", " ").strip();
    }

    /**
     * Text fed to {@code to_tsvector} for a chunk: the symbol and signature, plus
     * their word-split forms, plus the code itself.
     */
    public static String lexicalInput(String symbol, String signature, String text) {
        String safeSymbol = symbol == null ? "" : symbol;
        String safeSignature = signature == null ? "" : signature;
        return String.join(" ",
                safeSymbol,
                splitWords(safeSymbol),
                safeSignature,
                splitWords(safeSignature),
                text == null ? "" : text);
    }
}
