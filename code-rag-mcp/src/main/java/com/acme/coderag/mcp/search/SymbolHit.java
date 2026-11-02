package com.acme.coderag.mcp.search;

/**
 * A declaration matched by name.
 *
 * <p>Deliberately carries no snippet: this tool answers "where is it" for a few
 * tens of tokens per hit. Follow up with {@code read_lines} on the ones that matter.
 */
public record SymbolHit(
        String repo,
        String path,
        String location,
        String symbol,
        String signature,
        String kind,
        int startLine,
        int endLine) {
}
