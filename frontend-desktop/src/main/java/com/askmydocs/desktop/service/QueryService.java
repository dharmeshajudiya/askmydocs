package com.askmydocs.desktop.service;

import com.askmydocs.desktop.util.TokenStore;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class QueryService {

    /**
     * Streams the answer for a question via SSE.
     * Tokens are delivered on the JavaFX Application Thread via onToken.
     * onDone fires when [DONE] is received.
     * onError fires on any HTTP or I/O failure.
     *
     * @return a CompletableFuture that completes when the stream ends
     */
    public CompletableFuture<Void> streamQuery(
        String documentId,
        String question,
        Consumer<String> onToken,
        Runnable onDone,
        Consumer<Throwable> onError
    ) {
        try {
            String json = ApiClient.MAPPER.writeValueAsString(
                Map.of("document_id", documentId, "question", question)
            );

            var request = HttpRequest.newBuilder()
                .uri(URI.create(ApiClient.BASE_URL + "/query"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TokenStore.get().accessToken())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            // BodyHandlers.ofLines() reads an SSE stream line-by-line perfectly
            return ApiClient.http()
                .sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> {
                    if (response.statusCode() >= 400) {
                        Platform.runLater(() ->
                            onError.accept(new ApiClient.ApiException(response.statusCode(), "Query failed"))
                        );
                        return;
                    }
                    response.body().forEach(line -> {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                Platform.runLater(onDone);
                            } else {
                                Platform.runLater(() -> onToken.accept(data));
                            }
                        }
                    });
                })
                .exceptionally(ex -> {
                    Platform.runLater(() -> onError.accept(ex));
                    return null;
                });

        } catch (Exception e) {
            onError.accept(e);
            return CompletableFuture.completedFuture(null);
        }
    }
}
