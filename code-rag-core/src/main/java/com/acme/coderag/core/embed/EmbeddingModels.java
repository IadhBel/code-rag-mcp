package com.acme.coderag.core.embed;

import dev.langchain4j.model.vertexai.VertexAiEmbeddingModel;

/**
 * Builds the embedding model each application uses. One factory rather than one
 * adapter class per provider, because with LangChain4j supplying the client there
 * is nothing left to adapt.
 *
 * <p><strong>One task type per model instance.</strong> Retrieval quality depends
 * on telling the model which side of the comparison it is looking at: indexing uses
 * {@code RETRIEVAL_DOCUMENT}, searching uses {@code RETRIEVAL_QUERY} (or
 * {@code CODE_RETRIEVAL_QUERY} where the model supports it), and using the same
 * type for both measurably degrades ranking. Vertex sets the task type on the model
 * instance, so the indexer and the MCP server each build exactly one model with
 * their own side's type -- neither needs the other's.
 *
 * <p>Vertex AI is used rather than an external embedding API for a reason that
 * outweighs benchmark scores: source code is the company's intellectual property,
 * and with Vertex it never leaves the Google Cloud project that already hosts the
 * service.
 */
public final class EmbeddingModels {

    /** Provider tag of {@link #deterministic(int)}, checked by the MCP start-up guard. */
    public static final String DETERMINISTIC_PROVIDER = "deterministic";

    /**
     * Provider limits per request. Set explicitly rather than left to the library
     * default so a change on Google's side is a one-line edit here, and so
     * {@code embedAll} can be handed an entire flush batch: LangChain4j splits it
     * into conforming requests itself.
     */
    private static final int MAX_SEGMENTS_PER_BATCH = 250;
    private static final int MAX_TOKENS_PER_BATCH = 20_000;

    private EmbeddingModels() {
    }

    /**
     * @param taskType {@code RETRIEVAL_DOCUMENT} on the indexing side,
     *                 {@code RETRIEVAL_QUERY} or {@code CODE_RETRIEVAL_QUERY} on the
     *                 query side. Passed as a string and resolved through the enum so
     *                 a provider adding a task type needs no code change here.
     */
    public static IdentifiedEmbeddingModel vertex(String project, String location,
                                                  String modelName, int dimension,
                                                  String taskType, int maxRetries) {
        VertexAiEmbeddingModel model = VertexAiEmbeddingModel.builder()
                .project(project)
                .location(location)
                .endpoint(location + "-aiplatform.googleapis.com:443")
                .publisher("google")
                .modelName(modelName)
                .outputDimensionality(dimension)
                .taskType(VertexAiEmbeddingModel.TaskType.valueOf(taskType))
                .maxSegmentsPerBatch(MAX_SEGMENTS_PER_BATCH)
                .maxTokensPerBatch(MAX_TOKENS_PER_BATCH)
                .maxRetries(maxRetries)
                // Fail loudly on oversized input rather than silently embedding a
                // truncated chunk, which would be indistinguishable from a good one.
                .autoTruncate(false)
                .build();
        return new NormalizedEmbeddingModel(model, new ModelId("vertex", modelName, dimension));
    }

    /** Offline model for tests. See {@link DeterministicEmbeddingModel}. */
    public static IdentifiedEmbeddingModel deterministic(int dimension) {
        return new NormalizedEmbeddingModel(
                new DeterministicEmbeddingModel(dimension),
                new ModelId(DETERMINISTIC_PROVIDER, "sha256", dimension));
    }
}
