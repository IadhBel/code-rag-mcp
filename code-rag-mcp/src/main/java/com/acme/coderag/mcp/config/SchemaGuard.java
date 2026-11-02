package com.acme.coderag.mcp.config;

import com.acme.coderag.core.embed.EmbeddingModels;
import com.acme.coderag.core.embed.IdentifiedEmbeddingModel;
import com.acme.coderag.mcp.store.CodeReadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refuses to start when the index and this server disagree.
 *
 * <p>The failure this prevents is the nastiest one available: querying vectors built
 * by a different model or dimension returns confident, well-ranked, entirely wrong
 * results, and nothing anywhere reports an error. Better to not start.
 */
@Component
public class SchemaGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuard.class);

    private final CodeReadRepository repository;
    private final IdentifiedEmbeddingModel embeddings;
    private final EmbeddingProperties properties;

    public SchemaGuard(CodeReadRepository repository, IdentifiedEmbeddingModel embeddings,
                       EmbeddingProperties properties) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> indexed;
        try {
            indexed = repository.indexedModelIds();
        } catch (DataAccessException e) {
            throw new IllegalStateException(
                    "the code-rag schema is missing or unreadable. The indexer owns the "
                    + "migrations: run code-rag-indexer once against this database before "
                    + "starting the MCP server. Cause: " + e.getMessage(), e);
        }

        String configured = embeddings.modelId().asString();

        if (indexed.isEmpty()) {
            log.warn("The index is empty. Tools will return no results until "
                    + "code-rag-indexer has run. Expecting model {}.", configured);
            return;
        }

        if (!indexed.contains(configured)) {
            throw new IllegalStateException(
                    "embedding model mismatch: this server is configured for '" + configured
                    + "' but the index contains " + indexed
                    + ". Vectors from different models or dimensions are not comparable, "
                    + "so querying across them would return plausible nonsense. "
                    + "Either reconfigure this server to match, or re-index with the new model.");
        }

        boolean deterministic = indexed.stream()
                .anyMatch(id -> id.startsWith(EmbeddingModels.DETERMINISTIC_PROVIDER + ":"));
        if (deterministic && !properties.allowDeterministicIndex()) {
            throw new IllegalStateException(
                    "the index contains DETERMINISTIC vectors, which carry no semantic "
                    + "meaning. Set coderag.embedding.allow-deterministic-index=true to "
                    + "serve it anyway (tests only), or re-index with Vertex AI.");
        }

        log.info("Index validated: model {} present, {} model(s) stored", configured, indexed.size());
    }
}
