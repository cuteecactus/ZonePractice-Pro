package dev.nandi0813.practice.botpvp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class WeightLoader {

    public final Map<String, float[]> vectors = new HashMap<>();
    public final Map<String, float[][]> matrices = new HashMap<>();

    public void load(String filePath) throws IOException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(filePath)) {
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);

            for (Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
                String key = entry.getKey();
                JsonArray array = entry.getValue().getAsJsonArray();

                if (array.size() == 0) {
                    continue;
                }

                if (array.get(0).isJsonArray()) {
                    int rows = array.size();
                    int cols = array.get(0).getAsJsonArray().size();
                    float[][] matrix = new float[rows][cols];

                    for (int i = 0; i < rows; i++) {
                        JsonArray row = array.get(i).getAsJsonArray();
                        for (int j = 0; j < cols; j++) {
                            matrix[i][j] = row.get(j).getAsFloat();
                        }
                    }
                    matrices.put(key, matrix);
                } else {
                    int size = array.size();
                    float[] vector = new float[size];

                    for (int i = 0; i < size; i++) {
                        vector[i] = array.get(i).getAsFloat();
                    }
                    vectors.put(key, vector);
                }
            }
        }
    }
}