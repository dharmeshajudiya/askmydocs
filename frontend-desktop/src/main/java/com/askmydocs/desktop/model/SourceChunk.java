package com.askmydocs.desktop.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SourceChunk(
    @JsonProperty("chunk_text") String chunkText,
    int page,
    double score
) {}
