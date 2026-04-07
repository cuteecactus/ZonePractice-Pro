package dev.nandi0813.practice.manager.fight.match.bot.neural;

import com.google.gson.Gson;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class NeuralInferenceClient {
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final URI PREDICT_URI = URI.create("http://127.0.0.1:8000/predict");
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(45);

    private final Gson gson = new Gson();

    public CompletableFuture<BotPrediction> fetchPrediction(GameState state) {
        String body = gson.toJson(state);

        HttpRequest request = HttpRequest.newBuilder(PREDICT_URI)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return HTTP_CLIENT
                .sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(HttpResponse::body)
                .thenApply(json -> gson.fromJson(json, BotPrediction.class));
    }
}

