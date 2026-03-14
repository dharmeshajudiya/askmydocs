package com.askmydocs.controller;

import com.askmydocs.dto.DocumentResponse;
import com.askmydocs.dto.DocumentStatusResponse;
import com.askmydocs.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean DocumentService documentService;

    @Test
    @WithMockUser
    void uploadAcceptedForPdf() throws Exception {
        var docId = UUID.randomUUID();
        var now = OffsetDateTime.now();
        when(documentService.upload(any(), any())).thenReturn(
            new DocumentResponse(docId, "test.pdf", "queued", null, now, now)
        );

        mockMvc.perform(multipart("/documents")
                .file(new MockMultipartFile("file", "test.pdf", "application/pdf", "pdf content".getBytes()))
                .with(csrf()))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.id").value(docId.toString()))
            .andExpect(jsonPath("$.status").value("queued"));
    }

    @Test
    @WithMockUser
    void uploadRejectedForUnsupportedType() throws Exception {
        when(documentService.upload(any(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE));

        mockMvc.perform(multipart("/documents")
                .file(new MockMultipartFile("file", "image.png", "image/png", "png data".getBytes()))
                .with(csrf()))
            .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @WithMockUser
    void listReturnsDocuments() throws Exception {
        UUID docId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        when(documentService.listForUser(any())).thenReturn(
            List.of(new DocumentResponse(docId, "report.pdf", "ready", 42, now, now))
        );

        mockMvc.perform(get("/documents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].filename").value("report.pdf"))
            .andExpect(jsonPath("$[0].status").value("ready"));
    }

    @Test
    @WithMockUser
    void statusReturns404WhenNotFound() throws Exception {
        when(documentService.getStatus(any(), any()))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/documents/" + UUID.randomUUID() + "/status"))
            .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser
    void deleteReturnsNoContent() throws Exception {
        doNothing().when(documentService).delete(any(), any());

        mockMvc.perform(delete("/documents/" + UUID.randomUUID()).with(csrf()))
            .andExpect(status().isNoContent());
    }
}
