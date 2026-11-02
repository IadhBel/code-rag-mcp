package com.acme.coderag.mcp.search;

import com.acme.coderag.core.util.Identifiers;
import com.acme.coderag.mcp.store.CodeReadRepository;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.filter.Filter;

import java.util.List;

/**
 * The lexical half of hybrid search, as a LangChain4j {@link ContentRetriever}.
 *
 * <p>It exists because dense vectors are poor at exact identifiers. A query for
 * {@code findByAccountId} should return that method first, and only a full-text
 * index can promise that. Implementing the framework's retriever interface rather
 * than an internal one means both halves are the same type, so
 * {@code DefaultContentAggregator} fuses them with no glue -- and the same pair can
 * be dropped into a {@code RetrievalAugmentor} later without touching this class.
 *
 * <p>Cheap to construct and holds no state beyond its query parameters, so the
 * search service builds one per request with that request's filter. That is what
 * lets narrowing be a plain {@link Filter} instead of a thread-local.
 */
public final class LexicalContentRetriever implements ContentRetriever {

    private final CodeReadRepository repository;
    private final Filter filter;
    private final int maxResults;
    private final int snippetLines;

    public LexicalContentRetriever(CodeReadRepository repository, Filter filter,
                                   int maxResults, int snippetLines) {
        this.repository = repository;
        this.filter = filter;
        this.maxResults = maxResults;
        this.snippetLines = snippetLines;
    }

    @Override
    public List<Content> retrieve(Query query) {
        return repository.lexicalSearch(expand(query.text()), filter, maxResults, snippetLines)
                .stream()
                .map(Content::from)
                .toList();
    }

    /**
     * Enriches the query for the lexical index.
     *
     * <p>The {@code simple} dictionary treats {@code findByAccountId} as a single
     * token, so the word-split form is appended -- exactly the transformation applied
     * when the chunk was indexed. Both sides must agree, which is why the split lives
     * in the shared module.
     */
    private static String expand(String query) {
        String split = Identifiers.splitWords(query);
        return split.equalsIgnoreCase(query) ? query : query + " " + split;
    }
}
