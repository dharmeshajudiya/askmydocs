package com.askmydocs.service;

import com.askmydocs.dto.SourceChunk;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.proto.QueryResponse;
import io.pinecone.proto.ScoredVector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock OpenAiEmbeddingModel embeddingModel;
    @Mock AnthropicChatModel chatModel;
    @Mock Pinecone pineconeClient;
    @Mock RestClient restClient;
    @Mock Index pineconeIndex;

    @InjectMocks RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(retrievalService, "pineconeIndexHost", "https://test.pinecone.io");
        ReflectionTestUtils.setField(retrievalService, "cohereApiKey", "test-key");
    }

    @Test
    void queryRewriteSkippedForLongQuestions() {
        // 21 words — must not trigger rewrite
        String longQuestion = "word ".repeat(21).trim();

        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(pineconeClient.getIndexConnection(anyString())).thenReturn(pineconeIndex);
        when(pineconeIndex.queryByVector(anyInt(), anyList(), anyString(), any(), anyBoolean(), anyBoolean()))
            .thenReturn(QueryResponse.newBuilder().build());

        retrievalService.retrieve(longQuestion, "doc123", "user456");

        // chatModel should never be called for query rewrite
        verify(chatModel, never()).call(any());
    }

    @Test
    void returnsEmptyListWhenNoVectorMatches() {
        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(pineconeClient.getIndexConnection(anyString())).thenReturn(pineconeIndex);
        when(pineconeIndex.queryByVector(anyInt(), anyList(), anyString(), any(), anyBoolean(), anyBoolean()))
            .thenReturn(QueryResponse.newBuilder().build());

        List<SourceChunk> result = retrievalService.retrieve(
            "What is the answer?", "doc123", "user456"
        );

        assertThat(result).isEmpty();
    }

    @Test
    void fallsBackToTopFiveByScoreWhenCohereUnavailable() {
        // Build 20 mock matches with ascending scores
        var responseBuilder = QueryResponse.newBuilder();
        for (int i = 0; i < 20; i++) {
            var meta = com.google.protobuf.Struct.newBuilder()
                .putFields("text", com.google.protobuf.Value.newBuilder().setStringValue("chunk " + i).build())
                .putFields("page_number", com.google.protobuf.Value.newBuilder().setNumberValue(i + 1).build())
                .build();
            responseBuilder.addMatches(
                ScoredVector.newBuilder().setId("id_" + i).setScore(i * 0.05f).setMetadata(meta).build()
            );
        }

        when(embeddingModel.embed(anyString())).thenReturn(new float[1536]);
        when(pineconeClient.getIndexConnection(anyString())).thenReturn(pineconeIndex);
        when(pineconeIndex.queryByVector(anyInt(), anyList(), anyString(), any(), anyBoolean(), anyBoolean()))
            .thenReturn(responseBuilder.build());

        // RestClient throws to simulate Cohere failure
        when(restClient.post()).thenThrow(new RuntimeException("Cohere unavailable"));

        List<SourceChunk> result = retrievalService.retrieve(
            "What is X?", "doc123", "user456"
        );

        assertThat(result).hasSize(5);
        // Should be top 5 by score (descending)
        assertThat(result.get(0).score()).isGreaterThanOrEqualTo(result.get(1).score());
    }
}
