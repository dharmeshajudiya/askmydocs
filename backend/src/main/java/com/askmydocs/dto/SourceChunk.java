package com.askmydocs.dto;

public record SourceChunk(
    String chunkText,
    int page,
    double score
) {}
