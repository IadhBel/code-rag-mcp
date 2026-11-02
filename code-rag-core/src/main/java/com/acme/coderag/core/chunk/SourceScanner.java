package com.acme.coderag.core.chunk;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Single-pass lexical scan that finds brace blocks while ignoring braces that
 * appear inside strings, characters, text blocks, template literals and
 * comments.
 *
 * <p>A regex-based chunker looks simpler right up until a method contains
 * {@code "}"} in a string literal or a javadoc example, at which point every
 * subsequent chunk boundary in the file is wrong -- and nothing reports an error,
 * the index just quietly degrades. Hence a real state machine.
 *
 * <p>It is deliberately approximate on one point: JavaScript regex literals
 * ({@code /}...{@code /}) are not distinguished from division, so a regex
 * containing an unbalanced brace can still confuse the scan. That is rare, and
 * the consequence is a suboptimal chunk boundary rather than data loss.
 */
public final class SourceScanner {

    /**
     * A brace-delimited block.
     *
     * @param openIndex  offset of the opening brace
     * @param closeIndex offset of the matching closing brace, or -1 if unterminated
     * @param depth      nesting depth, 0 for top level
     * @param parent     index into the block list of the enclosing block, -1 at top level
     */
    public record Block(int openIndex, int closeIndex, int depth, int parent) {

        public boolean isClosed() {
            return closeIndex >= 0;
        }
    }

    /**
     * @param blocks          all blocks, ordered by {@code openIndex}
     * @param structuralStops sorted offsets of {@code ;} {@code &#123;} {@code &#125;}
     *                        found outside strings and comments. Used to locate
     *                        where a declaration header begins: it starts just
     *                        after the previous statement or block ended.
     */
    public record Scan(List<Block> blocks, int[] structuralStops) {
    }

    private enum State {
        CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR, TEXT_BLOCK, TEMPLATE
    }

    private SourceScanner() {
    }

    public static Scan scan(String src) {
        List<Block> blocks = new ArrayList<>();
        List<int[]> mutable = new ArrayList<>();
        Deque<Integer> openStack = new ArrayDeque<>();
        List<Integer> stops = new ArrayList<>();

        State state = State.CODE;
        int i = 0;
        int n = src.length();

        while (i < n) {
            char c = src.charAt(i);
            char next = i + 1 < n ? src.charAt(i + 1) : '\0';

            switch (state) {
                case CODE -> {
                    if (c == '/' && next == '/') {
                        state = State.LINE_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (c == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                        state = State.TEXT_BLOCK;
                        i += 3;
                        continue;
                    }
                    if (c == '"') {
                        state = State.STRING;
                        i++;
                        continue;
                    }
                    if (c == '\'') {
                        state = State.CHAR;
                        i++;
                        continue;
                    }
                    if (c == '`') {
                        state = State.TEMPLATE;
                        i++;
                        continue;
                    }
                    if (c == '{') {
                        int id = mutable.size();
                        int parent = openStack.isEmpty() ? -1 : openStack.peek();
                        mutable.add(new int[]{i, -1, openStack.size(), parent});
                        openStack.push(id);
                        stops.add(i);
                    } else if (c == '}') {
                        if (!openStack.isEmpty()) {
                            mutable.get(openStack.pop())[1] = i;
                        }
                        stops.add(i);
                    } else if (c == ';') {
                        stops.add(i);
                    }
                    i++;
                }
                case LINE_COMMENT -> {
                    if (c == '\n') {
                        state = State.CODE;
                    }
                    i++;
                }
                case BLOCK_COMMENT -> {
                    if (c == '*' && next == '/') {
                        state = State.CODE;
                        i += 2;
                    } else {
                        i++;
                    }
                }
                case STRING -> {
                    if (c == '\\') {
                        i += 2;
                    } else if (c == '"' || c == '\n') {
                        // A newline inside a single-quoted string means the source
                        // is malformed; recover rather than swallowing the rest.
                        state = State.CODE;
                        i++;
                    } else {
                        i++;
                    }
                }
                case CHAR -> {
                    if (c == '\\') {
                        i += 2;
                    } else if (c == '\'' || c == '\n') {
                        state = State.CODE;
                        i++;
                    } else {
                        i++;
                    }
                }
                case TEXT_BLOCK -> {
                    if (c == '\\') {
                        i += 2;
                    } else if (c == '"' && next == '"' && i + 2 < n && src.charAt(i + 2) == '"') {
                        state = State.CODE;
                        i += 3;
                    } else {
                        i++;
                    }
                }
                case TEMPLATE -> {
                    if (c == '\\') {
                        i += 2;
                    } else if (c == '`') {
                        state = State.CODE;
                        i++;
                    } else {
                        i++;
                    }
                }
            }
        }

        for (int[] b : mutable) {
            blocks.add(new Block(b[0], b[1], b[2], b[3]));
        }
        int[] stopArray = new int[stops.size()];
        for (int k = 0; k < stops.size(); k++) {
            stopArray[k] = stops.get(k);
        }
        return new Scan(List.copyOf(blocks), stopArray);
    }
}
