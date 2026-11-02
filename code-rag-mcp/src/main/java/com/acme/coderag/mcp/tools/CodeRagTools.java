package com.acme.coderag.mcp.tools;

import com.acme.coderag.mcp.config.SearchProperties;
import com.acme.coderag.mcp.search.CodeSearchService;
import com.acme.coderag.mcp.search.FileOutline;
import com.acme.coderag.mcp.search.FileSlice;
import com.acme.coderag.mcp.search.RepositoryInfo;
import com.acme.coderag.mcp.search.SearchFilters;
import com.acme.coderag.mcp.search.SearchHit;
import com.acme.coderag.mcp.search.SymbolHit;
import com.acme.coderag.mcp.store.CodeReadRepository;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The MCP surface.
 *
 * <p>Every tool returns pointers and bounded excerpts, never a file dump. The
 * intended sequence is: {@code search_code} or {@code find_symbol} to locate, then
 * {@code get_file_outline} to understand a file's shape for a couple of hundred
 * tokens, then {@code read_lines} on the two or three ranges that actually matter.
 * Following that path costs roughly an order of magnitude fewer tokens than reading
 * the files, which is the entire reason this service exists.
 *
 * <p>Tool descriptions are written for the agent that will read them, including their
 * approximate cost, because a tool an agent misuses is worse than one it ignores.
 */
@Service
public class CodeRagTools {

    private final CodeSearchService search;
    private final CodeReadRepository repository;
    private final SearchProperties properties;

    public CodeRagTools(CodeSearchService search, CodeReadRepository repository,
                        SearchProperties properties) {
        this.search = search;
        this.repository = repository;
        this.properties = properties;
    }

    @McpTool(name = "list_repositories", description = """
            List the indexed repositories with the commit each was indexed at.
            Call this first when you do not know the exact repository slug, and to
            check whether the index is current: if commitSha is not the revision you
            are working on, results describe an older state of the code.
            Cost: about 100 tokens.""")
    public List<RepositoryInfo> listRepositories() {
        return repository.listRepositories();
    }

    @McpTool(name = "search_code", description = """
            Semantic + keyword search over indexed source code. Use this instead of
            reading files when you need to find where something is implemented.
            Combines vector similarity (good for intent, e.g. "where do we retry
            failed payments") with full-text matching (good for exact identifiers),
            so both phrasings work.
            Returns chunks best-first: location as path:start-end, symbol, signature
            and a short snippet. Each hit carries a rank (1 is best), not a similarity
            score -- the two searches are combined by rank, so there is no comparable
            number to report. The snippet is truncated: use read_lines to see a full
            method.
            Cost: roughly 150 tokens per hit.""")
    public List<SearchHit> searchCode(
            @McpToolParam(description = "What you are looking for. Natural language or an "
                    + "identifier; both are handled.", required = true)
            String query,
            @McpToolParam(description = "Restrict to one repository slug, as returned by "
                    + "list_repositories. Omit to search all.", required = false)
            String repo,
            @McpToolParam(description = "Restrict to one language, e.g. java, typescript, sql.",
                    required = false)
            String language,
            @McpToolParam(description = "Restrict to a path prefix, e.g. "
                    + "src/main/java/com/acme/billing", required = false)
            String pathPrefix,
            @McpToolParam(description = "Maximum hits. Defaults to 8; keep it small and "
                    + "search again with a narrower query rather than asking for many.",
                    required = false)
            Integer limit) {

        requireKnownRepo(repo);
        return search.search(query, SearchFilters.of(repo, language, pathPrefix), limit);
    }

    @McpTool(name = "get_file_outline", description = """
            Structural map of one indexed file: every type and method with its
            signature and line range, without any bodies.
            This is the cheapest way to understand a file. Prefer it over reading the
            file whenever you need to know what is in it: a 600-line class costs about
            200 tokens here against several thousand read whole. Then read_lines only
            the ranges you need.
            Cost: about 20 tokens per declaration.""")
    public FileOutline getFileOutline(
            @McpToolParam(description = "Repository slug from list_repositories.", required = true)
            String repo,
            @McpToolParam(description = "Repository-relative path, e.g. "
                    + "src/main/java/com/acme/OrderService.java", required = true)
            String path) {

        requireKnownRepo(repo);
        return repository.outlineOf(repo, path).orElseThrow(() -> new IllegalArgumentException(
                "'" + path + "' is not indexed in '" + repo + "'. Paths are repository-relative "
                + "and case-sensitive; use search_code to find the exact path."));
    }

    @McpTool(name = "read_lines", description = """
            Exact line range from an indexed file, sliced server-side.
            Use after search_code or get_file_outline has told you which lines matter.
            Requesting a wide range defeats the purpose: ranges are capped, and the
            response says whether it was truncated.
            Cost: proportional to the range you ask for.""")
    public FileSlice readLines(
            @McpToolParam(description = "Repository slug.", required = true)
            String repo,
            @McpToolParam(description = "Repository-relative path.", required = true)
            String path,
            @McpToolParam(description = "First line, 1-based and inclusive.", required = true)
            int fromLine,
            @McpToolParam(description = "Last line, 1-based and inclusive.", required = true)
            int toLine) {

        requireKnownRepo(repo);
        if (fromLine < 1) {
            throw new IllegalArgumentException("fromLine is 1-based; got " + fromLine);
        }
        if (toLine < fromLine) {
            throw new IllegalArgumentException(
                    "toLine (" + toLine + ") must not be before fromLine (" + fromLine + ")");
        }
        int requested = toLine - fromLine + 1;
        boolean truncated = requested > properties.maxReadLines();
        int effectiveTo = truncated ? fromLine + properties.maxReadLines() - 1 : toLine;

        return repository.readLines(repo, path, fromLine, effectiveTo, truncated)
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + path + "' is not indexed in '" + repo + "'"));
    }

    @McpTool(name = "find_symbol", description = """
            Locate declarations by name, with fuzzy matching. Use when you already
            know roughly what something is called and only need to know where it is --
            it is far cheaper than search_code because it returns no code.
            Matches partial and misspelled names, and searches qualified forms such as
            OrderService#findById.
            Cost: about 50 tokens per hit.""")
    public List<SymbolHit> findSymbol(
            @McpToolParam(description = "Symbol or fragment, e.g. findByAccountId, "
                    + "OrderService, or OrderService#cancel", required = true)
            String symbol,
            @McpToolParam(description = "Restrict to one repository slug.", required = false)
            String repo,
            @McpToolParam(description = "Maximum hits. Defaults to 8.", required = false)
            Integer limit) {

        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol must not be empty");
        }
        requireKnownRepo(repo);
        return repository.findSymbol(symbol.trim(),
                repo == null || repo.isBlank() ? null : repo,
                properties.clampLimit(limit));
    }

    /**
     * Turns a mistyped slug into a useful message instead of an empty result set.
     *
     * <p>An agent that receives zero hits will usually rephrase the query and try
     * again, burning turns on a typo. Naming the available repositories ends that loop
     * immediately.
     */
    private void requireKnownRepo(String repo) {
        if (repo == null || repo.isBlank()) {
            return;
        }
        List<String> known = repository.listRepositories().stream()
                .map(RepositoryInfo::slug)
                .toList();
        if (!known.contains(repo)) {
            throw new IllegalArgumentException(
                    "unknown repository '" + repo + "'. Indexed repositories: " + known);
        }
    }
}
