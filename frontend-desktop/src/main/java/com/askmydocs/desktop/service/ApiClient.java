package com.askmydocs.desktop.service;

import com.askmydocs.desktop.util.TokenStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Shared HTTP client and Jackson mapper for all API calls.
 * Base URL defaults to http://localhost:8080 (Java backend).
 * Set env variable API_BASE_URL to switch to Python backend (http://localhost:8000).
 */
public final class ApiClient {

    public static final String BASE_URL =
        System.getenv().getOrDefault("API_BASE_URL", "http://localhost:8080");

    public static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private ApiClient() {}

    public static HttpClient http() { return HTTP; }

    public static HttpRequest.Builder authorized() {
        var builder = HttpRequest.newBuilder()
            .header("Content-Type", "application/json");
        String token = TokenStore.get().accessToken();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    public static HttpRequest.Builder anonymous() {
        return HttpRequest.newBuilder()
            .header("Content-Type", "application/json");
    }

    public static URI uri(String path) {
        return URI.create(BASE_URL + path);
    }

    public static <T> T post(String path, Object body, Class<T> responseType) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var request = authorized()
            .uri(uri(path))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ApiException(response.statusCode(), response.body());
        }
        return MAPPER.readValue(response.body(), responseType);
    }

    public static <T> T postAnon(String path, Object body, Class<T> responseType) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        var request = anonymous()
            .uri(uri(path))
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ApiException(response.statusCode(), response.body());
        }
        return MAPPER.readValue(response.body(), responseType);
    }

    public static <T> T get(String path, Class<T> responseType) throws Exception {
        var request = authorized().uri(uri(path)).GET().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ApiException(response.statusCode(), response.body());
        }
        return MAPPER.readValue(response.body(), responseType);
    }

    public static void delete(String path) throws Exception {
        var request = authorized().uri(uri(path)).DELETE().build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new ApiException(response.statusCode(), response.body());
        }
    }

    public static class ApiException extends RuntimeException {
        private final int statusCode;

        public ApiException(int statusCode, String body) {
            super("HTTP " + statusCode + ": " + body);
            this.statusCode = statusCode;
        }

        public int getStatusCode() { return statusCode; }
    }
}
