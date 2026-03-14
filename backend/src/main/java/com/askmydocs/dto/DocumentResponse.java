package com.askmydocs.dto;

import com.askmydocs.entity.Document;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentResponse(
    UUID id,
    String filename,
    String status,
    Integer totalChunks,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static DocumentResponse from(Document doc) {
        return new DocumentResponse(
            doc.getId(),
            doc.getFilename(),
            doc.getStatus(),
            doc.getTotalChunks(),
            doc.getCreatedAt(),
            doc.getUpdatedAt()
        );
    }
}
