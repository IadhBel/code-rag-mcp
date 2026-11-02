package com.acme.coderag.core.embed;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decorator that enforces the two invariants the rest of the system relies on:
 * every vector is unit length, and every vector has the configured dimension.
 *
 * <p>Unit length is not cosmetic. Google requires manual normalisation when
 * {@code gemini-embedding-001} is truncated below its native 3072 dimensions,
 * which we do at 768. And once vectors are unit length, cosine distance and inner
 * product rank identically, so changing the pgvector operator later is a one-line
 * change instead of a re-index.
 *
 * <p>The dimension check is here rather than at start-up because the failure it
 * catches is silent: a provider that quietly returns a different width produces
 * vectors that still insert, still rank, and are still wrong. Better to fail on the
 * first call.
 *
 * <p>Batching, retries and token accounting are deliberately <em>not</em> here --
 * the delegate does them. {@code VertexAiEmbeddingModel} splits a call into
 * requests that respect the provider's segments-per-call and tokens-per-call
 * limits, which is why callers can hand {@link #embedAll(List)} the whole flush
 * batch and forget about it.
 */
public final class NormalizedEmbeddingModel implements IdentifiedEmbeddingModel {

    private final EmbeddingModel delegate;
    private final ModelId modelId;

    public NormalizedEmbeddingModel(EmbeddingModel delegate, ModelId modelId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
    }

    @Override
    public ModelId modelId() {
        return modelId;
    }

    @Override
    public int dimension() {
        return modelId.dimension();
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        if (segments.isEmpty()) {
            return Response.from(List.of());
        }
        Response<List<Embedding>> response = delegate.embedAll(segments);
        List<Embedding> content = response.content();
        if (content.size() != segments.size()) {
            throw new IllegalStateException("embedding count mismatch: asked "
                    + segments.size() + ", got " + content.size());
        }
        List<Embedding> normalized = new ArrayList<>(content.size());
        for (Embedding embedding : content) {
            normalized.add(checked(Embedding.from(Vectors.l2Normalize(embedding.vector()))));
        }
        return Response.from(normalized, response.tokenUsage(), response.finishReason());
    }

    private Embedding checked(Embedding embedding) {
        if (embedding.dimension() != modelId.dimension()) {
            throw new IllegalStateException("expected dimension " + modelId.dimension()
                    + " but " + modelId.modelName() + " returned " + embedding.dimension()
                    + "; the index and the model are out of sync");
        }
        return embedding;
    }
}
