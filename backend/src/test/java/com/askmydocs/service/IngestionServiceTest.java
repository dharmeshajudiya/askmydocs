package com.askmydocs.service;

import com.askmydocs.entity.Document;
import com.askmydocs.repository.DocumentRepository;
import com.askmydocs.util.S3Util;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IngestionServiceTest {

    @Mock DocumentRepository documentRepository;
    @Mock OpenAiEmbeddingModel embeddingModel;
    @Mock S3Util s3Util;
    @Mock Pinecone pineconeClient;
    @Mock Index pineconeIndex;

    @InjectMocks IngestionService ingestionService;

    private UUID docId;
    private Document document;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(ingestionService, "pineconeIndexHost", "https://test.pinecone.io");
        docId = UUID.randomUUID();
        document = Document.builder()
            .id(docId)
            .userId(UUID.randomUUID())
            .filename("test.txt")
            .s3Url("s3://bucket/test.txt")
            .status("queued")
            .build();
    }

    @Test
    void statusSetToProcessingBeforeExtraction() {
        // Capture all saves to document to verify ordering
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(s3Util.download(anyString())).thenReturn("hello world content for testing".getBytes());
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(pineconeClient.getIndexConnection(anyString())).thenReturn(pineconeIndex);

        ArgumentCaptor<Document> saveCaptor = ArgumentCaptor.forClass(Document.class);

        ingestionService.ingest(docId);

        verify(documentRepository, atLeast(2)).save(saveCaptor.capture());
        String firstSavedStatus = saveCaptor.getAllValues().get(0).getStatus();
        assertThat(firstSavedStatus).isEqualTo("processing");
    }

    @Test
    void finalStatusIsReadyOnSuccess() {
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(s3Util.download(anyString())).thenReturn("test content".getBytes());
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(pineconeClient.getIndexConnection(anyString())).thenReturn(pineconeIndex);

        ingestionService.ingest(docId);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(captor.capture());
        Document lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSave.getStatus()).isEqualTo("ready");
        assertThat(lastSave.getTotalChunks()).isGreaterThan(0);
    }

    @Test
    void statusSetToErrorOnException() {
        when(documentRepository.findById(docId)).thenReturn(Optional.of(document));
        when(s3Util.download(anyString())).thenThrow(new RuntimeException("S3 unavailable"));

        ingestionService.ingest(docId);

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(documentRepository, atLeast(2)).save(captor.capture());
        Document lastSave = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(lastSave.getStatus()).isEqualTo("error");
        assertThat(lastSave.getErrorLog()).contains("S3 unavailable");
    }
}
