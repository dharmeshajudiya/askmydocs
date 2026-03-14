package com.askmydocs.controller;

import com.askmydocs.dto.QueryHistoryResponse;
import com.askmydocs.dto.QueryRequest;
import com.askmydocs.dto.SourceChunk;
import com.askmydocs.entity.Document;
import com.askmydocs.entity.QueryHistory;
import com.askmydocs.repository.DocumentRepository;
import com.askmydocs.repository.QueryHistoryRepository;
import com.askmydocs.service.GenerationService;
import com.askmydocs.service.RetrievalService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/query")
@RequiredArgsConstructor
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final RetrievalService retrievalService;
    private final GenerationService generationService;
    private final DocumentRepository documentRepository;
    private final QueryHistoryRepository queryHistoryRepository;

    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter query(
        @Valid @RequestBody QueryRequest request,
        @AuthenticationPrincipal UserDetails principal
    ) {
        UUID userId = UUID.fromString(principal.getUsername());

        // Validate document ownership and readiness
        Document document = documentRepository
            .findByIdAndUserIdAndStatus(request.documentId(), userId, "ready")
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Document not found or not ready"));

        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        long startMs = System.currentTimeMillis();

        sseExecutor.execute(() -> {
            StringBuilder fullAnswer = new StringBuilder();
            String logPrefix = "docId=" + request.documentId() + " userId=" + userId;
            log.info("{} query_started question='{}'", logPrefix, request.question());

            try {
                List<SourceChunk> chunks = retrievalService.retrieve(
                    request.question(),
                    request.documentId().toString(),
                    userId.toString()
                );

                generationService.stream(request.question(), chunks)
                    .doOnNext(token -> {
                        fullAnswer.append(token);
                        try {
                            emitter.send(SseEmitter.event().data(token));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            emitter.send(SseEmitter.event().data("[DONE]"));
                            emitter.complete();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }

                        int latencyMs = (int) (System.currentTimeMillis() - startMs);
                        persistHistory(userId, request, chunks, fullAnswer.toString(), latencyMs);
                        log.info("{} query_completed latency_ms={}", logPrefix, latencyMs);
                    })
                    .doOnError(emitter::completeWithError)
                    .subscribe();

            } catch (Exception e) {
                log.error("{} query_failed error={}", logPrefix, e.getMessage(), e);
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @GetMapping("/history")
    public List<QueryHistoryResponse> history(@AuthenticationPrincipal UserDetails principal) {
        UUID userId = UUID.fromString(principal.getUsername());
        return queryHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId)
            .stream().map(QueryHistoryResponse::from).toList();
    }

    private void persistHistory(
        UUID userId, QueryRequest request,
        List<SourceChunk> chunks, String answer, int latencyMs
    ) {
        List<Map<String, Object>> sourceChunkData = chunks.stream()
            .map(c -> Map.<String, Object>of(
                "chunkText", c.chunkText(),
                "page", c.page(),
                "score", c.score()
            ))
            .toList();

        var history = QueryHistory.builder()
            .userId(userId)
            .documentId(request.documentId())
            .question(request.question())
            .answer(answer)
            .sourceChunks(sourceChunkData)
            .latencyMs(latencyMs)
            .build();

        queryHistoryRepository.save(history);
    }
}
