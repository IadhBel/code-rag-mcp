package com.acme.coderag.core.chunk;

import com.acme.coderag.core.lang.Languages;
import com.acme.coderag.core.model.ChunkKind;
import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;
import com.acme.coderag.core.util.Hashes;
import com.acme.coderag.core.util.Tokens;

import java.util.ArrayList;
import java.util.List;

/**
 * Line-window chunker for files with no brace structure worth exploiting:
 * SQL, YAML, Markdown, properties, Dockerfiles.
 *
 * <p>Windows are sized by token budget rather than a fixed line count, so a
 * dense SQL migration and a sparse YAML file both produce chunks the embedding
 * model can actually accept.
 */
public final class PlainTextChunker implements Chunker {

    private final ChunkingOptions options;

    public PlainTextChunker(ChunkingOptions options) {
        this.options = options;
    }

    @Override
    public List<SourceChunk> chunk(SourceFile file) {
        String src = file.content();
        if (src.isBlank()) {
            return List.of();
        }
        LineIndex lines = LineIndex.of(src);
        List<SourceChunk> out = window(src, lines, file, 1, lines.lineCount(), null);
        for (int i = 0; i < out.size(); i++) {
            out.set(i, out.get(i).withIndex(i));
        }
        return List.copyOf(out);
    }

    /**
     * Splits the inclusive line range into token-bounded windows.
     *
     * @param symbolPrefix when non-null, produced chunks are named
     *                     {@code prefix#partN}. Used by the structural chunker to
     *                     break up an oversized method while keeping its identity.
     */
    List<SourceChunk> window(String src, LineIndex lines, SourceFile file,
                            int fromLine, int toLine, String symbolPrefix) {
        List<SourceChunk> out = new ArrayList<>();
        int start = Math.max(1, fromLine);
        int last = Math.min(toLine, lines.lineCount());
        int part = 1;

        while (start <= last) {
            int end = start;
            int budget = 0;
            while (end <= last) {
                int cost = Tokens.estimate(lines.slice(src, end, end)) + 1;
                if (budget + cost > options.maxTokensPerChunk() && end > start) {
                    break;
                }
                budget += cost;
                end++;
            }
            end = Math.min(end - 1, last);

            String text = lines.slice(src, start, end);
            if (!text.isBlank()) {
                String symbol = symbolPrefix == null
                        ? Languages.fileNameOf(file.path()) + "#L" + start
                        : symbolPrefix + "#part" + part;
                out.add(new SourceChunk(
                        out.size(),
                        symbolPrefix == null ? ChunkKind.TEXT : ChunkKind.METHOD,
                        symbol,
                        firstMeaningfulLine(text),
                        file.language(),
                        start,
                        end,
                        text,
                        Hashes.sha256OfNormalized(text),
                        Tokens.estimate(text)));
                part++;
            }

            if (end >= last) {
                break;
            }
            // Step forward with overlap, but always make progress.
            int nextStart = end - options.overlapLines() + 1;
            start = Math.max(nextStart, start + 1);
        }
        return out;
    }

    private static String firstMeaningfulLine(String text) {
        for (String line : text.split("\n", -1)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) {
                return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
            }
        }
        return "";
    }
}
