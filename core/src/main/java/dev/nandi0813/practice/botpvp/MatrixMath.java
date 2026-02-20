package dev.nandi0813.practice.botpvp;

public class MatrixMath {

    public static float[] add(float[] a, float[] b) {
        float[] result = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = a[i] + b[i];
        }
        return result;
    }

    public static float[][] multiply(float[][] a, float[][] b) {
        int rowsA = a.length;
        int colsA = a[0].length;
        int colsB = b[0].length;
        float[][] result = new float[rowsA][colsB];

        for (int i = 0; i < rowsA; i++) {
            for (int k = 0; k < colsA; k++) {
                for (int j = 0; j < colsB; j++) {
                    result[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return result;
    }

    public static float[][] addBias(float[][] matrix, float[] bias) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[][] result = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                result[i][j] = matrix[i][j] + bias[j];
            }
        }
        return result;
    }

    public static float[][] gelu(float[][] matrix) {
        int rows = matrix.length;
        int cols = matrix[0].length;
        float[][] result = new float[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                float x = matrix[i][j];
                result[i][j] = (float) (0.5 * x * (1.0 + Math.tanh(Math.sqrt(2.0 / Math.PI) * (x + 0.044715 * Math.pow(x, 3)))));
            }
        }
        return result;
    }

    public static float[] softmax(float[] x) {
        float[] result = new float[x.length];
        float max = Float.NEGATIVE_INFINITY;
        for (float val : x) {
            if (val > max) max = val;
        }
        float sum = 0;
        for (int i = 0; i < x.length; i++) {
            result[i] = (float) Math.exp(x[i] - max);
            sum += result[i];
        }
        for (int i = 0; i < x.length; i++) {
            result[i] /= sum;
        }
        return result;
    }
}