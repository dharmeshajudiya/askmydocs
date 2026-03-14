package com.askmydocs.dto;

import com.askmydocs.entity.QueryHistory;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record QueryHistoryResponse(
    UUID id,
    UUID documentId,
    String question,
    String answer,
    List<SourceChunk> sourceChunks,
    Integer latencyMs,
    OffsetDateTime createdAt
) {
    @SuppressWarnings("unchecked")
    public static QueryHistoryResponse from(QueryHistory qh) {
        List<SourceChunk> chunks = qh.getSourceChunks().stream()
            .map(m -> new SourceChunk(
                (String) m.get("chunkText"),
                ((Number) m.get("page")).intValue(),
                ((Number) m.get("score")).doubleValue()
            ))
            .toList();

        return new QueryHistoryResponse(
            qh.getId(),
            qh.getDocumentId(),
            qh.getQuestion(),
            qh.getAnswer(),
            chunks,
            qh.getLatencyMs(),
            qh.getCreatedAt()
        );
    }
}
