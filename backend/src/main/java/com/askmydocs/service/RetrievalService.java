package com.askmydocs.service;

import com.askmydocs.dto.SourceChunk;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.proto.QueryResponse;
import io.pinecone.proto.ScoredVector;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private static final int TOP_K = 20;
    private static final int RERANK_TOP_N = 5;

    private final OpenAiEmbeddingModel embeddingModel;
    private final AnthropicChatModel chatModel;
    private final Pinecone pineconeClient;
    private final RestClient restClient;

    @Value("${app.pinecone.index-host}")
    private String pineconeIndexHost;

    @Value("${app.cohere.api-key}")
    private String cohereApiKey;

    public List<SourceChunk> retrieve(String question, String docId, String userId) {
        String logPrefix = "docId=" + docId + " userId=" + userId;

        // 1. Query rewrite for short questions
        String rewritten = rewriteIfShort(question, logPrefix);

        // 2. Embed query
        float[] queryVector = embeddingModel.embed(rewritten);
        List<Float> vector = new ArrayList<>();
        for (float v : queryVector) vector.add(v);

        // 3. Vector search scoped to document
        String namespace = "user_" + userId;
        Index index = pineconeClient.getIndexConnection(pineconeIndexHost);

        var filterStruct = com.google.protobuf.Struct.newBuilder()
            .putFields("doc_id", com.google.protobuf.Value.newBuilder()
                .setStructValue(com.google.protobuf.Struct.newBuilder()
                    .putFields("$eq", com.google.protobuf.Value.newBuilder()
                        .setStringValue(docId).build())
                    .build())
                .build())
            .build();

        QueryResponse response = index.queryByVector(TOP_K, vector, namespace, filterStruct, true, true);
        List<ScoredVector> matches = response.getMatchesList();
        log.info("{} vector_search_complete results={}", logPrefix, matches.size());

        if (matches.isEmpty()) return List.of();

        // 4. Rerank with Cohere (fallback to top-5 by raw score)
        return rerank(matches, rewritten, logPrefix);
    }

    private String rewriteIfShort(String question, String logPrefix) {
        long wordCount = question.trim().chars().filter(c -> c == ' ').count() + 1;
        if (wordCount > 20) return question;

        try {
            var prompt = new Prompt(new UserMessage(
                "Rewrite this question to be specific and self-contained for document search: " + question
            ));
            String rewritten = chatModel.call(prompt).getResult().getOutput().getContent();
            log.info("{} query_rewritten original='{}' rewritten='{}'", logPrefix, question, rewritten);
            return rewritten;
        } catch (Exception e) {
            log.warn("{} query_rewrite_failed falling_back error={}", logPrefix, e.getMessage());
            return question;
        }
    }

    @SuppressWarnings("unchecked")
    private List<SourceChunk> rerank(List<ScoredVector> matches, String query, String logPrefix) {
        List<String> texts = matches.stream()
            .map(m -> m.getMetadata().getFieldsOrDefault(
                "text", com.google.protobuf.Value.newBuilder().setStringValue("").build()
            ).getStringValue())
            .toList();

        try {
            var requestBody = Map.of(
                "model", "rerank-3",
                "query", query,
                "documents", texts,
                "top_n", RERANK_TOP_N
            );

            var cohereResponse = restClient.post()
                .uri("https://api.cohere.com/v1/rerank")
                .header("Authorization", "Bearer " + cohereApiKey)
                .header("Content-Type", "application/json")
                .body(requestBody)
                .retrieve()
                .body(Map.class);

            List<Map<String, Object>> results = (List<Map<String, Object>>) cohereResponse.get("results");
            List<SourceChunk> chunks = new ArrayList<>();
            for (var result : results) {
                int idx = ((Number) result.get("index")).intValue();
                double score = ((Number) result.get("relevance_score")).doubleValue();
                ScoredVector match = matches.get(idx);
                var meta = match.getMetadata().getFieldsMap();
                int page = (int) meta.getOrDefault("page_number",
                    com.google.protobuf.Value.newBuilder().setNumberValue(1).build()
                ).getNumberValue();
                chunks.add(new SourceChunk(texts.get(idx), page, score));
            }
            log.info("{} rerank_complete top_chunks={}", logPrefix, chunks.size());
            return chunks;

        } catch (Exception e) {
            log.warn("{} cohere_rerank_failed falling_back error={}", logPrefix, e.getMessage());
            // Fallback: top-5 by cosine score
            return matches.stream()
                .sorted(Comparator.comparingDouble(ScoredVector::getScore).reversed())
                .limit(RERANK_TOP_N)
                .map(m -> {
                    var meta = m.getMetadata().getFieldsMap();
                    String text = meta.getOrDefault("text",
                        com.google.protobuf.Value.newBuilder().setStringValue("").build()
                    ).getStringValue();
                    int page = (int) meta.getOrDefault("page_number",
                        com.google.protobuf.Value.newBuilder().setNumberValue(1).build()
                    ).getNumberValue();
                    return new SourceChunk(text, page, m.getScore());
                })
                .toList();
        }
    }
}
