package com.acme.coderag.indexer.config;

import com.acme.coderag.core.chunk.ChunkerRouter;
import com.acme.coderag.core.chunk.ChunkingOptions;
import com.acme.coderag.core.embed.EmbeddingModels;
import com.acme.coderag.core.embed.IdentifiedEmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Objects;

@Configuration(proxyBeanMethods = false)
public class IndexerConfig {

    private static final Logger log = LoggerFactory.getLogger(IndexerConfig.class);

    /**
     * The document-side embedding model.
     *
     * <p>Exposed as a LangChain4j {@code EmbeddingModel}, so batching, retries and
     * the provider's per-request limits are the library's problem: callers hand
     * {@code embedAll} a whole flush batch and it splits the requests itself.
     */
    @Bean
    IdentifiedEmbeddingModel embeddingModel(EmbeddingProperties properties) {
        return switch (properties.provider()) {
            case VERTEX -> {
                EmbeddingProperties.Vertex vertex = Objects.requireNonNull(properties.vertex(),
                        "coderag.embedding.vertex.* is required when provider is VERTEX");
                log.info("Embedding with Vertex AI model={} dimension={} project={} location={}",
                        vertex.model(), properties.dimension(), vertex.project(), vertex.location());
                yield EmbeddingModels.vertex(
                        vertex.project(),
                        vertex.location(),
                        vertex.model(),
                        properties.dimension(),
                        vertex.taskType(),
                        vertex.maxRetries());
            }
            case DETERMINISTIC -> {
                log.warn("Using DETERMINISTIC embeddings: vectors carry no semantic "
                        + "meaning and search results will be nonsense. "
                        + "This mode exists for tests and offline development only.");
                yield EmbeddingModels.deterministic(properties.dimension());
            }
        };
    }

    @Bean
    ChunkerRouter chunker() {
        return new ChunkerRouter(ChunkingOptions.DEFAULTS);
    }

    /**
     * Feeds the configured vector dimension into {@code V1__init.sql}.
     *
     * <p>Set programmatically rather than through {@code spring.flyway.placeholders.*}
     * because map keys in configuration binding are normalised in ways that are
     * easy to get subtly wrong, and a missing placeholder fails the migration with
     * an unhelpful message.
     */
    @Bean
    FlywayConfigurationCustomizer vectorDimensionPlaceholder(EmbeddingProperties properties) {
        return configuration -> configuration.placeholders(
                Map.of("vector_dimension", String.valueOf(properties.dimension())));
    }
}
