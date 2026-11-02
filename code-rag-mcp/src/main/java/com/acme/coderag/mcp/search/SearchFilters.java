package com.acme.coderag.mcp.search;

import com.acme.coderag.core.store.CodeChunks;
import dev.langchain4j.store.embedding.filter.Filter;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * The narrowing an MCP caller asked for, in the shape the tools receive it.
 *
 * <p>Converted to a LangChain4j {@link Filter} by {@link #toFilter()} before it
 * reaches either half of the search, so both halves narrow through the same
 * translator and cannot drift. Filtering happens before ranking rather than after:
 * a query scoped to one repository should not spend its recall budget on candidates
 * from the other nine.
 *
 * @param repo       repository slug, or {@code null} for all
 * @param language   language id from {@link com.acme.coderag.core.lang.Languages}
 * @param pathPrefix repository-relative path prefix, e.g. {@code src/main/java/com/acme/billing}
 */
public record SearchFilters(String repo, String language, String pathPrefix) {

    /** Blank strings become {@code null}: an omitted tool argument arrives either way. */
    public static SearchFilters of(String repo, String language, String pathPrefix) {
        return new SearchFilters(blankToNull(repo), blankToNull(language), blankToNull(pathPrefix));
    }

    /** @return {@code null} when nothing was requested, which means "no narrowing" */
    public Filter toFilter() {
        Filter filter = null;
        if (repo != null) {
            filter = and(filter, metadataKey(CodeChunks.REPO).isEqualTo(repo));
        }
        if (language != null) {
            filter = and(filter, metadataKey(CodeChunks.LANGUAGE).isEqualTo(language));
        }
        if (pathPrefix != null) {
            // PATH_PREFIX is our own pseudo-key: the filter algebra has no prefix
            // operator. See CodeChunks#PATH_PREFIX.
            filter = and(filter, metadataKey(CodeChunks.PATH_PREFIX).isEqualTo(pathPrefix));
        }
        return filter;
    }

    private static Filter and(Filter left, Filter right) {
        return left == null ? right : Filter.and(left, right);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
