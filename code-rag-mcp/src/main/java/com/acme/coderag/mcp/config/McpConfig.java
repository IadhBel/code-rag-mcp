package com.acme.coderag.mcp.config;

import com.acme.coderag.core.embed.EmbeddingModels;
import com.acme.coderag.core.embed.IdentifiedEmbeddingModel;
import com.acme.coderag.core.store.CodeEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.aggregator.ContentAggregator;
import dev.langchain4j.rag.content.aggregator.DefaultContentAggregator;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * The retrieval stack, assembled from LangChain4j parts.
 *
 * <p>Three beans is the whole of it: a model, a store, and a fuser. Everything the
 * search service needs beyond that -- the retrievers -- is built per request, because
 * they carry that request's filter.
 */
@Configuration(proxyBeanMethods = false)
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    /**
     * The query-side embedding model.
     *
     * <p>Must be configured to the same model and dimension as the indexer's, with
     * the query task type rather than the document one. {@link SchemaGuard} verifies
     * the first part at start-up against what is actually stored, rather than
     * trusting the configuration to be right.
     */
    @Bean
    IdentifiedEmbeddingModel embeddingModel(EmbeddingProperties properties) {
        return switch (properties.provider()) {
            case VERTEX -> {
                EmbeddingProperties.Vertex vertex = Objects.requireNonNull(properties.vertex(),
                        "coderag.embedding.vertex.* is required when provider is VERTEX");
                log.info("Queries embedded with Vertex AI model={} dimension={} taskType={}",
                        vertex.model(), properties.dimension(), vertex.taskType());
                yield EmbeddingModels.vertex(
                        vertex.project(),
                        vertex.location(),
                        vertex.model(),
                        properties.dimension(),
                        vertex.taskType(),
                        vertex.maxRetries());
            }
            case DETERMINISTIC -> {
                log.warn("DETERMINISTIC embeddings: search results will be meaningless. "
                        + "Tests and offline development only.");
                yield EmbeddingModels.deterministic(properties.dimension());
            }
        };
    }

    /**
     * The corpus as a LangChain4j store, so dense retrieval is the framework's
     * {@code EmbeddingStoreContentRetriever} rather than hand-written SQL in a
     * service.
     *
     * <p>The snippet budget comes from the same property the lexical half uses. They
     * have to match: fusion identifies a result by its segment text.
     */
    @Bean
    EmbeddingStore<TextSegment> codeEmbeddingStore(JdbcTemplate jdbc,
                                                   IdentifiedEmbeddingModel embeddingModel,
                                                   SearchProperties properties) {
        return new CodeEmbeddingStore(jdbc, embeddingModel.modelId(), properties.snippetLines());
    }

    /**
     * Reciprocal rank fusion over the dense and lexical retrievers.
     *
     * <p>Stateless, hence a singleton. This is the whole of the fusion logic: the
     * framework's default aggregator implements RRF with the constant from the
     * original paper, so there is no scoring code and no tuning knob of ours to get
     * wrong.
     */
    @Bean
    ContentAggregator contentAggregator() {
        return new DefaultContentAggregator();
    }
}
