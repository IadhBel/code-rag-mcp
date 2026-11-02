package com.acme.coderag.mcp.search;

import java.util.List;

/**
 * Structural map of a file: declarations and line ranges, no bodies.
 *
 * <p>This is the cheapest useful thing the server offers. A 600-line class costs
 * roughly two hundred tokens as an outline against several thousand read whole, and
 * it is usually enough to decide which two methods actually need reading.
 */
public record FileOutline(
        String repo,
        String path,
        String language,
        int lineCount,
        String commitSha,
        List<Member> members) {

    /**
     * @param kind      {@code TYPE}, {@code METHOD} or {@code TEXT}
     * @param location  {@code path:start-end}, ready to pass to {@code read_lines}
     */
    public record Member(
            String kind,
            String symbol,
            String signature,
            int startLine,
            int endLine,
            String location) {
    }
}
