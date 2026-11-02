package com.acme.coderag.core.embed;

import com.acme.coderag.core.util.Hashes;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;

import java.util.ArrayList;
import java.util.List;

/**
 * Hash-derived vectors for tests and offline development.
 *
 * <p><strong>This carries no semantic meaning whatsoever.</strong> Two texts about
 * the same subject get unrelated vectors. It exists so the schema, the
 * deduplication accounting, the SQL and the MCP wiring can be exercised end to end
 * without a Google Cloud project and without a single billable call -- nothing more.
 *
 * <p>Chosen over LangChain4j's in-process {@code AllMiniLmL6V2EmbeddingModel} on
 * purpose: that one pulls an ONNX runtime and a model file into the build, which is
 * a large dependency to carry for a test double, and its output is not reproducible
 * across versions. Deduplication tests need determinism, not quality.
 *
 * <p>An index built with this model is tagged {@code deterministic:sha256:N}, and
 * the MCP server refuses to serve it unless explicitly permitted, so it cannot
 * reach production by accident.
 */
public final class DeterministicEmbeddingModel implements EmbeddingModel {

    private final int dimension;

    public DeterministicEmbeddingModel(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive, got " + dimension);
        }
        this.dimension = dimension;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
        List<Embedding> out = new ArrayList<>(segments.size());
        for (TextSegment segment : segments) {
            out.add(Embedding.from(expand(segment.text())));
        }
        return Response.from(out);
    }

    /** Expands the digest deterministically until the vector is full. */
    private float[] expand(String text) {
        float[] vector = new float[dimension];
        int written = 0;
        int round = 0;
        while (written < vector.length) {
            String digest = Hashes.sha256Hex(round + ":" + Hashes.normalize(text));
            for (int i = 0; i + 1 < digest.length() && written < vector.length; i += 2) {
                int byteValue = Integer.parseInt(digest.substring(i, i + 2), 16);
                vector[written++] = (byteValue - 127.5f) / 127.5f;
            }
            round++;
        }
        return vector;
    }
}
