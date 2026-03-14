package com.askmydocs.controller;

import com.askmydocs.dto.QueryHistoryResponse;
import com.askmydocs.dto.SourceChunk;
import com.askmydocs.entity.Document;
import com.askmydocs.repository.DocumentRepository;
import com.askmydocs.repository.QueryHistoryRepository;
import com.askmydocs.service.GenerationService;
import com.askmydocs.service.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QueryController.class)
class QueryControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean RetrievalService retrievalService;
    @MockBean GenerationService generationService;
    @MockBean DocumentRepository documentRepository;
    @MockBean QueryHistoryRepository queryHistoryRepository;

    @Test
    @WithMockUser
    void queryReturns404WhenDocumentNotReady() throws Exception {
        when(documentRepository.findByIdAndUserIdAndStatus(any(), any(), eq("ready")))
            .thenReturn(Optional.empty());

        mockMvc.perform(post("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"documentId": "%s", "question": "What is this?"}
                    """.formatted(UUID.randomUUID()))
                .with(csrf()))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void queryStreamsSSEForReadyDocument() throws Exception {
        UUID docId = UUID.randomUUID();
        var doc = Document.builder().id(docId).userId(UUID.randomUUID())
            .filename("test.pdf").s3Url("s3://test").status("ready").build();

        when(documentRepository.findByIdAndUserIdAndStatus(any(), any(), eq("ready")))
            .thenReturn(Optional.of(doc));
        when(retrievalService.retrieve(anyString(), anyString(), anyString()))
            .thenReturn(List.of(new SourceChunk("chunk text", 1, 0.95)));
        when(generationService.stream(anyString(), anyList()))
            .thenReturn(Flux.just("The ", "answer ", "is here."));
        when(queryHistoryRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        mockMvc.perform(post("/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"documentId": "%s", "question": "What is the answer?"}
                    """.formatted(docId))
                .with(csrf()))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", "text/event-stream"));
    }

    @Test
    @WithMockUser
    void historyReturnsEmptyList() throws Exception {
        when(queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        mockMvc.perform(get("/query/history"))
            .andExpect(status().isOk())
            .andExpect(content().json("[]"));
    }
}
