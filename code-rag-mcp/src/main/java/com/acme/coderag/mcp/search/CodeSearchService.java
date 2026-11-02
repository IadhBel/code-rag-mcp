package com.acme.coderag.mcp.search;

import com.acme.coderag.core.store.CodeChunks;
import com.acme.coderag.mcp.config.SearchProperties;
import com.acme.coderag.mcp.store.CodeReadRepository;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Hybrid search: dense retrieval fused with lexical retrieval.
 *
 * <p>Neither half is sufficient on source code. Embeddings are good at intent
 * ("where do we retry failed payments") and poor at exact identifiers; full-text is
 * the reverse. Running both and fusing costs one extra millisecond-scale query and
 * removes the most common failure mode of naive code RAG, which is failing to find a
 * method the user named precisely.
 *
 * <p>Both halves are LangChain4j {@link ContentRetriever}s and the fusion is
 * LangChain4j's {@link ContentAggregator}, which ranks by reciprocal rank fusion.
 * Ranks rather than scores is the correct choice regardless of who implements it:
 * cosine distance and {@code ts_rank_cd} are on unrelated scales, and normalising
 * them would mean inventing a calibration nobody can justify.
 *
 * <p>The retrievers are built per request rather than injected. They are cheap value
 * objects, and constructing them here is what lets the caller's narrowing be an
 * ordinary {@link Filter} argument instead of a thread-local read by a
 * {@code dynamicFilter} callback.
 */
@Service
public class CodeSearchService {

    private static final Logger log = LoggerFactory.getLogger(CodeSearchService.class);

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final CodeReadRepository repository;
    private final ContentAggregator aggregator;
    private final SearchProperties properties;

    public CodeSearchService(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                             CodeReadRepository repository, ContentAggregator aggregator,
                             SearchProperties properties) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.repository = repository;
        this.aggregator = aggregator;
        this.properties = properties;
    }

    public List<SearchHit> search(String queryText, SearchFilters filters, Integer requestedLimit) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("query must not be empty");
        }
        int limit = properties.clampLimit(requestedLimit);
        int candidates = properties.candidatesPerStrategy();
        Filter filter = filters.toFilter();
        Query query = Query.from(queryText);

        var denseBuilder = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(candidates);
        // Only set when there is something to narrow: "no filter" is the absence of a
        // filter, not a null one, and builders are entitled to reject null.
        if (filter != null) {
            denseBuilder.filter(filter);
        }
        ContentRetriever dense = denseBuilder.build();
        ContentRetriever lexical = new LexicalContentRetriever(
                repository, filter, candidates, properties.snippetLines());

        List<Content> denseHits = comparable(dense.retrieve(query));
        List<Content> lexicalHits = comparable(lexical.retrieve(query));

        Map<Query, Collection<List<Content>>> byQuery =
                Map.of(query, List.of(denseHits, lexicalHits));
        List<Content> fused = aggregator.aggregate(byQuery);

        List<SearchHit> hits = new ArrayList<>(Math.min(limit, fused.size()));
        for (Content content : fused) {
            if (hits.size() == limit) {
                break;
            }
            hits.add(toHit(content, hits.size() + 1));
        }
        log.debug("query='{}' dense={} lexical={} fused={} returned={}",
                queryText, denseHits.size(), lexicalHits.size(), fused.size(), hits.size());
        return hits;
    }

    /**
     * Rebuilds each result from its text segment alone, discarding retriever-supplied
     * content metadata.
     *
     * <p>This is what makes fusion work. The dense retriever attaches its own
     * annotations (an embedding id, a similarity score) to every result; the lexical
     * one cannot produce the same values. Left in place, the same chunk found by both
     * halves is two unequal results, fusion never merges them, and hybrid search
     * quietly degrades into two half-length lists stapled together. Comparing on the
     * segment -- the chunk's actual identity, built by one shared row mapper -- is the
     * behaviour we want, and it is the reason the per-half scores are not reported:
     * see {@link SearchHit#rank()}.
     */
    private static List<Content> comparable(List<Content> contents) {
        return contents.stream().map(content -> Content.from(content.textSegment())).toList();
    }

    private static SearchHit toHit(Content content, int rank) {
        TextSegment segment = content.textSegment();
        Metadata metadata = segment.metadata();
        return new SearchHit(
                metadata.getString(CodeChunks.REPO),
                metadata.getString(CodeChunks.PATH),
                CodeChunks.locationOf(segment),
                metadata.getString(CodeChunks.SYMBOL),
                metadata.getString(CodeChunks.SIGNATURE),
                metadata.getString(CodeChunks.KIND),
                metadata.getString(CodeChunks.LANGUAGE),
                metadata.getInteger(CodeChunks.START_LINE),
                metadata.getInteger(CodeChunks.END_LINE),
                metadata.getString(CodeChunks.COMMIT_SHA),
                rank,
                segment.text());
    }
}
