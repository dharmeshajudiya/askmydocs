package com.askmydocs.dto;

import com.askmydocs.entity.Document;

import java.util.UUID;

public record DocumentStatusResponse(
    UUID id,
    String status,
    Integer totalChunks,
    String errorLog
) {
    public static DocumentStatusResponse from(Document doc) {
        return new DocumentStatusResponse(
            doc.getId(),
            doc.getStatus(),
            doc.getTotalChunks(),
            doc.getErrorLog()
        );
    }
}
