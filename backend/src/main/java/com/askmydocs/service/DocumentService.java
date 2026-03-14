package com.askmydocs.service;

import com.askmydocs.dto.DocumentResponse;
import com.askmydocs.dto.DocumentStatusResponse;
import com.askmydocs.entity.Document;
import com.askmydocs.repository.DocumentRepository;
import com.askmydocs.util.S3Util;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final S3Util s3Util;
    private final IngestionService ingestionService;
    private final Pinecone pineconeClient;

    @Value("${app.pinecone.index-host}")
    private String pineconeIndexHost;

    private static final List<String> ALLOWED_TYPES = List.of(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain"
    );

    @Transactional
    public DocumentResponse upload(MultipartFile file, UUID userId) throws IOException {
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Only PDF, DOCX, and TXT are supported");
        }

        UUID docId = UUID.randomUUID();
        String key = s3Util.documentKey(docId.toString(), file.getOriginalFilename());
        String s3Url = s3Util.upload(file.getBytes(), key, contentType);
        log.info("docId={} userId={} s3_upload_complete", docId, userId);

        Document document = Document.builder()
            .id(docId)
            .userId(userId)
            .filename(file.getOriginalFilename())
            .s3Url(s3Url)
            .status("queued")
            .build();
        documentRepository.save(document);

        // Enqueue async ingestion
        ingestionService.ingest(docId);
        log.info("docId={} userId={} ingestion_task_queued", docId, userId);

        return DocumentResponse.from(document);
    }

    public List<DocumentResponse> listForUser(UUID userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(DocumentResponse::from).toList();
    }

    public DocumentStatusResponse getStatus(UUID docId, UUID userId) {
        var doc = documentRepository.findByIdAndUserId(docId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        return DocumentStatusResponse.from(doc);
    }

    @Transactional
    public void delete(UUID docId, UUID userId) {
        var doc = documentRepository.findByIdAndUserId(docId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));

        // Delete Pinecone vectors
        try {
            String namespace = "user_" + userId;
            Index index = pineconeClient.getIndexConnection(pineconeIndexHost);
            index.deleteByFilter(
                com.google.protobuf.Struct.newBuilder()
                    .putFields("doc_id", com.google.protobuf.Value.newBuilder()
                        .setStringValue(docId.toString()).build())
                    .build(),
                namespace
            );
            log.info("docId={} userId={} pinecone_vectors_deleted", docId, userId);
        } catch (Exception e) {
            log.warn("docId={} pinecone_delete_failed error={}", docId, e.getMessage());
        }

        // Delete S3 file
        s3Util.delete(doc.getS3Url());
        log.info("docId={} userId={} s3_file_deleted", docId, userId);

        documentRepository.delete(doc);
        log.info("docId={} userId={} document_deleted", docId, userId);
    }
}
