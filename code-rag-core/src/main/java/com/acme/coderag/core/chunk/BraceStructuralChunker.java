package com.acme.coderag.core.chunk;

import com.acme.coderag.core.model.ChunkKind;
import com.acme.coderag.core.model.SourceChunk;
import com.acme.coderag.core.model.SourceFile;
import com.acme.coderag.core.util.Hashes;
import com.acme.coderag.core.util.Tokens;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural chunker for brace-delimited languages.
 *
 * <p>Fixed-size windows are the wrong unit for source code: they cut methods in
 * half, so a match returns the tail of one method and the head of the next, and
 * the caller has to fetch more context to make sense of it -- which is exactly
 * the token spend this whole system exists to avoid. One method per chunk means
 * a hit is self-contained.
 *
 * <p>Three kinds of chunk come out of a file:
 * <ul>
 *   <li>{@link ChunkKind#METHOD} -- one per method or constructor, including its
 *       javadoc and annotations, since the header region starts immediately after
 *       the previous declaration ended;</li>
 *   <li>{@link ChunkKind#TYPE} -- a skeleton of each type: declaration, fields,
 *       constants, and the signature of every method with its body elided. This
 *       is what answers "what is this class responsible for" in one chunk;</li>
 *   <li>{@link ChunkKind#TEXT} -- whatever is left, typically the package and
 *       import header.</li>
 * </ul>
 *
 * <p><strong>Note on what is not done deliberately:</strong> the enclosing type
 * name is not prepended to the embedded text of a method chunk. Doing so is a
 * common trick to improve retrieval, but it would make two identical methods in
 * two different classes hash differently and destroy cross-repository
 * deduplication. Instead the symbol and signature are indexed into the sparse
 * {@code lexical} tsvector, so exact-name lookup works through the lexical half
 * of hybrid search while the vector stays purely about the code.
 */
public final class BraceStructuralChunker implements Chunker {

    private enum Role { TYPE, METHOD, OTHER }

    private static final Pattern TYPE_DECL = Pattern.compile(
            "\\b(?:@interface|class|interface|enum|record|trait|struct)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /**
     * Identifiers that can sit in front of a parenthesis without introducing a
     * method. Without this, {@code if (x) {} } is indexed as a method called
     * "if" in every file in the corpus.
     */
    private static final Set<String> NON_METHOD_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "do", "else",
            "try", "finally", "return", "new", "case", "default", "assert",
            "throw", "throws", "with", "function", "await", "yield");

    private static final String ELIDED = "// ...";

    private final ChunkingOptions options;
    private final PlainTextChunker windower;

    public BraceStructuralChunker(ChunkingOptions options) {
        this.options = options;
        this.windower = new PlainTextChunker(options);
    }

    @Override
    public List<SourceChunk> chunk(SourceFile file) {
        String src = file.content();
        if (src.isBlank()) {
            return List.of();
        }

        LineIndex lines = LineIndex.of(src);
        SourceScanner.Scan scan = SourceScanner.scan(src);
        List<SourceScanner.Block> blocks = scan.blocks();
        if (blocks.isEmpty()) {
            // Claims to be a brace language but has no blocks: an interface stub,
            // a package-info, a constants file. Window it as text.
            return windower.chunk(file);
        }

        int count = blocks.size();
        Role[] roles = new Role[count];
        String[] names = new String[count];
        int[] headerOffset = new int[count];

        for (int b = 0; b < count; b++) {
            SourceScanner.Block block = blocks.get(b);
            int lowerBound = block.parent() >= 0 ? blocks.get(block.parent()).openIndex() + 1 : 0;
            int from = headerStart(scan.structuralStops(), block.openIndex(), lowerBound);
            headerOffset[b] = skipWhitespace(src, from, block.openIndex());

            String header = stripComments(src.substring(headerOffset[b], block.openIndex()));
            Matcher typeMatch = TYPE_DECL.matcher(header);
            if (typeMatch.find()) {
                roles[b] = Role.TYPE;
                names[b] = typeMatch.group(1);
            } else {
                // Annotations must be removed before looking for the parameter
                // list, otherwise `@Transactional(readOnly = true) void find()`
                // is read as a method named "Transactional". The type check runs
                // first precisely because `@interface Foo` would not survive it.
                String method = methodName(stripAnnotations(header));
                roles[b] = method == null ? Role.OTHER : Role.METHOD;
                names[b] = method;
            }
        }

        boolean[] covered = new boolean[lines.lineCount() + 2];
        Map<Integer, String> signatureByFirstLine = new HashMap<>();
        List<SourceChunk> out = new ArrayList<>();

        // ── Pass 1: methods and constructors ────────────────────────────────
        // Only direct members of a type, or top-level functions as found in Go
        // and TypeScript. This single condition is what keeps blocks nested
        // inside method bodies -- if/for/lambda -- from becoming chunks.
        for (int b = 0; b < count; b++) {
            SourceScanner.Block block = blocks.get(b);
            if (roles[b] != Role.METHOD || !block.isClosed()) {
                continue;
            }
            int parent = block.parent();
            if (parent >= 0 && roles[parent] != Role.TYPE) {
                continue;
            }

            int startLine = lines.lineOf(headerOffset[b]);
            int endLine = lines.lineOf(block.closeIndex());
            String symbol = memberSymbol(blocks, roles, names, b);
            String signature = signatureOf(src, headerOffset[b], block.openIndex());

            addContiguous(out, src, lines, file, ChunkKind.METHOD, symbol, signature, startLine, endLine);
            signatureByFirstLine.put(startLine, signature);
            markCovered(covered, startLine, endLine);
        }

        // ── Pass 2: type skeletons, innermost first ─────────────────────────
        // Depth-descending order lets a nested type claim its own lines before
        // the enclosing type builds its skeleton, so the outer skeleton shows the
        // inner type as one elided line instead of duplicating its whole body.
        List<Integer> typeBlocks = new ArrayList<>();
        for (int b = 0; b < count; b++) {
            if (roles[b] == Role.TYPE && blocks.get(b).isClosed()) {
                typeBlocks.add(b);
            }
        }
        typeBlocks.sort(Comparator.comparingInt((Integer b) -> blocks.get(b).depth()).reversed());

        for (int b : typeBlocks) {
            SourceScanner.Block block = blocks.get(b);
            int startLine = lines.lineOf(headerOffset[b]);
            int endLine = lines.lineOf(block.closeIndex());
            String symbol = typeSymbol(blocks, roles, names, b);
            String signature = signatureOf(src, headerOffset[b], block.openIndex());
            String skeleton = buildSkeleton(src, lines, covered, signatureByFirstLine, startLine, endLine);

            if (!isEmpty(skeleton)) {
                out.add(newChunk(ChunkKind.TYPE, symbol, signature, file.language(),
                        startLine, endLine, truncateToBudget(skeleton)));
            }
            signatureByFirstLine.put(startLine, signature);
            markCovered(covered, startLine, endLine);
        }

        // ── Pass 3: leftovers (package declaration, imports, top-level code) ─
        int line = 1;
        while (line <= lines.lineCount()) {
            if (covered[line]) {
                line++;
                continue;
            }
            int runStart = line;
            while (line <= lines.lineCount() && !covered[line]) {
                line++;
            }
            int runEnd = line - 1;
            if (!isTrivial(lines.slice(src, runStart, runEnd))) {
                out.addAll(windower.window(src, lines, file, runStart, runEnd, null));
            }
        }

        out.sort(Comparator.comparingInt(SourceChunk::startLine)
                .thenComparingInt(SourceChunk::endLine));
        List<SourceChunk> numbered = new ArrayList<>(out.size());
        for (int i = 0; i < out.size(); i++) {
            numbered.add(out.get(i).withIndex(i));
        }
        return List.copyOf(numbered);
    }

    // ── Chunk construction ──────────────────────────────────────────────────

    /** Emits a contiguous line range, splitting into windows if it busts the budget. */
    private void addContiguous(List<SourceChunk> out, String src, LineIndex lines, SourceFile file,
                               ChunkKind kind, String symbol, String signature,
                               int startLine, int endLine) {
        String text = lines.slice(src, startLine, endLine);
        if (isEmpty(text)) {
            return;
        }
        if (Tokens.estimate(text) <= options.maxTokensPerChunk()) {
            out.add(newChunk(kind, symbol, signature, file.language(), startLine, endLine, text));
            return;
        }
        // A method longer than the model's input limit. Split it, but keep the
        // identity so every part still points at the same symbol.
        out.addAll(windower.window(src, lines, file, startLine, endLine, symbol));
    }

    private SourceChunk newChunk(ChunkKind kind, String symbol, String signature, String language,
                                 int startLine, int endLine, String text) {
        return new SourceChunk(0, kind, symbol, signature, language, startLine, endLine,
                text, Hashes.sha256OfNormalized(text), Tokens.estimate(text));
    }

    /**
     * Builds a type skeleton: every line not already claimed by a member, with
     * each elided member rendered as its signature followed by a marker.
     */
    private String buildSkeleton(String src, LineIndex lines, boolean[] covered,
                                 Map<Integer, String> signatureByFirstLine,
                                 int startLine, int endLine) {
        StringBuilder sb = new StringBuilder();
        int line = startLine;
        while (line <= endLine) {
            if (!covered[line]) {
                sb.append(lines.slice(src, line, line)).append('\n');
                line++;
                continue;
            }
            // Collapse a covered run into one readable placeholder.
            String signature = signatureByFirstLine.get(line);
            sb.append("    ")
              .append(signature == null ? ELIDED : signature + " { " + ELIDED + " }")
              .append('\n');
            while (line <= endLine && covered[line]) {
                line++;
            }
        }
        return sb.toString().stripTrailing();
    }

    private String truncateToBudget(String text) {
        int maxChars = options.maxTokensPerChunk() * 4;
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n" + ELIDED + " truncated";
    }

    // ── Header location and classification ──────────────────────────────────

    /**
     * A declaration header starts immediately after the previous statement or
     * block ended. That is also why javadoc and annotations end up inside the
     * chunk for free: they sit between the previous {@code ;} or {@code &#125;}
     * and this block's opening brace.
     */
    private static int headerStart(int[] stops, int openIndex, int lowerBound) {
        int found = Arrays.binarySearch(stops, openIndex);
        int previous = found >= 0 ? found - 1 : -found - 2;
        if (previous >= 0 && stops[previous] >= lowerBound) {
            return stops[previous] + 1;
        }
        return lowerBound;
    }

    private static int skipWhitespace(String src, int from, int limit) {
        int i = Math.max(0, from);
        while (i < limit && Character.isWhitespace(src.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Returns the method or constructor name, or {@code null} if this is not one. */
    private static String methodName(String header) {
        int paren = header.indexOf('(');
        if (paren < 0 || header.indexOf(')', paren) < 0) {
            return null;
        }
        int i = paren - 1;
        while (i >= 0 && Character.isWhitespace(header.charAt(i))) {
            i--;
        }
        if (i < 0 || !isIdentifierPart(header.charAt(i))) {
            // Anonymous parenthesis: a lambda `() -> {`, a cast, an expression.
            return null;
        }
        int end = i + 1;
        while (i >= 0 && isIdentifierPart(header.charAt(i))) {
            i--;
        }
        String name = header.substring(i + 1, end);
        if (name.isEmpty() || NON_METHOD_KEYWORDS.contains(name)) {
            return null;
        }
        // `new Runnable() {` is an anonymous class body, not a declaration.
        String before = header.substring(0, i + 1).stripTrailing();
        if (before.endsWith("new")) {
            int k = before.length() - 3;
            if (k == 0 || !isIdentifierPart(before.charAt(k - 1))) {
                return null;
            }
        }
        return name;
    }

    private static String signatureOf(String src, int from, int openIndex) {
        String raw = stripComments(src.substring(from, openIndex));
        String collapsed = raw.replaceAll("\\s+", " ").strip();
        return collapsed.length() > 200 ? collapsed.substring(0, 200) : collapsed;
    }

    /**
     * Removes annotations and their argument lists.
     *
     * <p>String literals inside the arguments are stepped over, so
     * {@code @Query("select x from y where z = (1)")} does not throw the
     * parenthesis balance off.
     */
    private static String stripAnnotations(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) != '@') {
                sb.append(text.charAt(i));
                i++;
                continue;
            }
            i++;
            while (i < text.length()
                    && (isIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) {
                i++;
            }
            int probe = i;
            while (probe < text.length() && Character.isWhitespace(text.charAt(probe))) {
                probe++;
            }
            if (probe < text.length() && text.charAt(probe) == '(') {
                i = skipBalancedParens(text, probe);
            }
            // Keep a separator so `@Ann public void x()` does not become `publicvoid`.
            sb.append(' ');
        }
        return sb.toString();
    }

    /** Returns the offset just past the parenthesis group starting at {@code open}. */
    private static int skipBalancedParens(String text, int open) {
        int depth = 0;
        int i = open;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                char quote = c;
                i++;
                while (i < text.length() && text.charAt(i) != quote) {
                    i += text.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
            i++;
        }
        return text.length();
    }

    private static String stripComments(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? text.length() : end + 2;
            } else if (c == '/' && next == '/') {
                int end = text.indexOf('\n', i + 2);
                i = end < 0 ? text.length() : end;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ── Symbols ─────────────────────────────────────────────────────────────

    private static String typeSymbol(List<SourceScanner.Block> blocks, Role[] roles,
                                     String[] names, int block) {
        Deque<String> parts = new ArrayDeque<>();
        for (int cursor = block; cursor >= 0; cursor = blocks.get(cursor).parent()) {
            if (roles[cursor] == Role.TYPE && names[cursor] != null) {
                parts.push(names[cursor]);
            }
        }
        return String.join(".", parts);
    }

    private static String memberSymbol(List<SourceScanner.Block> blocks, Role[] roles,
                                       String[] names, int block) {
        int parent = blocks.get(block).parent();
        String owner = parent >= 0 ? typeSymbol(blocks, roles, names, parent) : "";
        return owner.isEmpty() ? names[block] : owner + "#" + names[block];
    }

    // ── Small helpers ───────────────────────────────────────────────────────

    private static void markCovered(boolean[] covered, int fromLine, int toLine) {
        for (int l = Math.max(1, fromLine); l <= Math.min(toLine, covered.length - 1); l++) {
            covered[l] = true;
        }
    }

    /** True for whitespace and lone punctuation: nothing a reader could use. */
    private static boolean isEmpty(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        return text.replaceAll("[{}();\\s]", "").isEmpty();
    }

    /**
     * True for text not worth a vector of its own.
     *
     * <p>Applied to unstructured filler only, never to a declaration. A declaration
     * is not just a vector: {@code get_file_outline} and {@code find_symbol} project
     * symbols and line ranges straight out of {@code code_chunk}, so a method folded
     * into its enclosing type is invisible to the two cheapest tools in the server
     * and the agent has to fall back to reading the file -- the spend this whole
     * system exists to avoid. A one-line getter is exactly the kind of symbol worth
     * answering by pointer. Its embedding costs nothing beyond the first repository
     * to contain it either, because {@code code_embedding} is content-addressed.
     */
    private boolean isTrivial(String text) {
        return isEmpty(text) || Tokens.estimate(text) < options.minChunkTokens();
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
