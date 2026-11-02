package com.acme.coderag.core.embed;

import dev.langchain4j.model.embedding.EmbeddingModel;

/**
 * A LangChain4j {@link EmbeddingModel} that also knows the identity stored next to
 * its vectors.
 *
 * <p>This is the only embedding abstraction in the codebase. Everything else --
 * the indexer, the MCP server, the {@link com.acme.coderag.core.store.CodeEmbeddingStore},
 * and any future LangChain4j application -- speaks {@code EmbeddingModel}, so
 * retrievers, ingestors and stores can be wired straight onto our beans with no
 * adapter in between.
 *
 * <p>The single addition over the LangChain4j interface is {@link #modelId()}.
 * It is needed because {@code code_embedding} is keyed by
 * {@code (chunk_sha, model_id)}: vectors from two different models, or two
 * different dimensions, are not comparable, and querying across them returns
 * confident nonsense with no error anywhere. The identity therefore has to travel
 * with the model rather than be configured separately on each side.
 */
public interface IdentifiedEmbeddingModel extends EmbeddingModel {

    /** Identity written to {@code code_embedding.model_id}. See {@link ModelId}. */
    ModelId modelId();
}
