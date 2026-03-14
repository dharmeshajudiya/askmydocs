package com.askmydocs.controller;

import com.askmydocs.dto.DocumentResponse;
import com.askmydocs.dto.DocumentStatusResponse;
import com.askmydocs.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public DocumentResponse upload(
        @RequestParam("file") MultipartFile file,
        @AuthenticationPrincipal UserDetails principal
    ) throws IOException {
        return documentService.upload(file, UUID.fromString(principal.getUsername()));
    }

    @GetMapping
    public List<DocumentResponse> list(@AuthenticationPrincipal UserDetails principal) {
        return documentService.listForUser(UUID.fromString(principal.getUsername()));
    }

    @GetMapping("/{id}/status")
    public DocumentStatusResponse status(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails principal
    ) {
        return documentService.getStatus(id, UUID.fromString(principal.getUsername()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        @PathVariable UUID id,
        @AuthenticationPrincipal UserDetails principal
    ) {
        documentService.delete(id, UUID.fromString(principal.getUsername()));
    }
}
