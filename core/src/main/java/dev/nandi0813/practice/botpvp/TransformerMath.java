package dev.nandi0813.practice.botpvp;

public class TransformerMath {

    public static float[][] layerNorm(float[][] x, float[] weight, float[] bias, float eps) {
        int seqLen = x.length;
        int dim = x[0].length;
        float[][] out = new float[seqLen][dim];
        for (int i = 0; i < seqLen; i++) {
            float sum = 0;
            for (int j = 0; j < dim; j++) sum += x[i][j];
            float mean = sum / dim;
            float varSum = 0;
            for (int j = 0; j < dim; j++) varSum += (x[i][j] - mean) * (x[i][j] - mean);
            float var = varSum / dim;
            float std = (float) Math.sqrt(var + eps);
            for (int j = 0; j < dim; j++) {
                out[i][j] = ((x[i][j] - mean) / std) * weight[j] + bias[j];
            }
        }
        return out;
    }

    public static float[][] multiHeadAttention(float[][] q, float[][] k, float[][] v, int numHeads) {
        int seqLen = q.length;
        int dim = q[0].length;
        int headDim = dim / numHeads;
        float[][] out = new float[seqLen][dim];
        float scale = (float) (1.0 / Math.sqrt(headDim));

        for (int h = 0; h < numHeads; h++) {
            int headOffset = h * headDim;

            for (int i = 0; i < seqLen; i++) {
                float[] scores = new float[seqLen];
                float maxScore = Float.NEGATIVE_INFINITY;

                for (int j = 0; j <= i; j++) {
                    float dot = 0;
                    for (int d = 0; d < headDim; d++) {
                        dot += q[i][headOffset + d] * k[j][headOffset + d];
                    }
                    scores[j] = dot * scale;
                    if (scores[j] > maxScore) {
                        maxScore = scores[j];
                    }
                }

                float sumExp = 0;
                for (int j = 0; j <= i; j++) {
                    scores[j] = (float) Math.exp(scores[j] - maxScore);
                    sumExp += scores[j];
                }

                for (int j = 0; j <= i; j++) {
                    float prob = scores[j] / sumExp;
                    for (int d = 0; d < headDim; d++) {
                        out[i][headOffset + d] += prob * v[j][headOffset + d];
                    }
                }
            }
        }
        return out;
    }
}