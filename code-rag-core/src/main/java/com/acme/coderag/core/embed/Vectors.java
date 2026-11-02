package com.acme.coderag.core.embed;

/**
 * Vector normalisation and the pgvector wire format.
 *
 * <p>We L2-normalise every vector before storing it, for two reasons. Google
 * requires manual normalisation when {@code gemini-embedding-001} is truncated
 * below its native 3072 dimensions, which we do (768). And once vectors are unit
 * length, cosine distance and inner product rank identically, so switching the
 * pgvector operator later is a one-line change rather than a re-index.
 */
public final class Vectors {

    private Vectors() {
    }

    /**
     * Returns a unit-length copy. A zero vector is returned unchanged rather
     * than producing NaNs -- it can legitimately occur for empty input.
     */
    public static float[] l2Normalize(float[] vector) {
        double sumOfSquares = 0.0;
        for (float v : vector) {
            sumOfSquares += (double) v * v;
        }
        if (sumOfSquares == 0.0) {
            return vector.clone();
        }
        double norm = Math.sqrt(sumOfSquares);
        float[] out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = (float) (vector[i] / norm);
        }
        return out;
    }

    /**
     * pgvector's text input format: {@code [0.1,-0.2,0.3]}.
     *
     * <p>Passed as a JDBC string and cast with {@code ::vector} in SQL, which
     * avoids adding a driver-level type mapping for one column.
     */
    public static String toPgLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 12 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        return sb.append(']').toString();
    }
}
