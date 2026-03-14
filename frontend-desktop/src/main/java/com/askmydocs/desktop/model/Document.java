package com.askmydocs.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Document(
    String id,
    String filename,
    String status,
    @JsonProperty("total_chunks") Integer totalChunks,
    @JsonProperty("created_at")   String createdAt
) {
    public boolean isReady()      { return "ready".equals(status); }
    public boolean isProcessing() { return "processing".equals(status) || "queued".equals(status); }
    public boolean isError()      { return "error".equals(status); }
}
