package com.askmydocs.service;

import com.askmydocs.dto.SourceChunk;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GenerationService {

    private static final Logger log = LoggerFactory.getLogger(GenerationService.class);
    private static final String SYSTEM_PROMPT =
        "You are a document assistant. Answer ONLY from the provided context. " +
        "If the answer is not in the context, say \"I couldn't find that in the document.\" " +
        "Always cite the page number for each fact.";

    // ~4 chars per token — hard limit at 6000 tokens worth of context
    private static final int MAX_CONTEXT_CHARS = 6000 * 4;

    private final AnthropicChatModel chatModel;

    public Flux<String> stream(String question, List<SourceChunk> chunks) {
        String context = buildContext(chunks);
        String userContent = "Context:\n" + context + "\n\nQuestion: " + question;

        var prompt = new Prompt(List.of(
            new SystemMessage(SYSTEM_PROMPT),
            new UserMessage(userContent)
        ));

        return chatModel.stream(prompt)
            .mapNotNull(response -> response.getResult().getOutput().getContent())
            .filter(text -> text != null && !text.isEmpty());
    }

    private String buildContext(List<SourceChunk> chunks) {
        var sb = new StringBuilder();
        int totalChars = 0;

        for (int i = 0; i < chunks.size(); i++) {
            SourceChunk chunk = chunks.get(i);
            String block = "[CHUNK " + (i + 1) + " — page " + chunk.page() + "]\n"
                + chunk.chunkText() + "\n\n";

            if (totalChars + block.length() > MAX_CONTEXT_CHARS) {
                log.warn("context_truncated dropped_chunk_index={}", i);
                break;
            }
            sb.append(block);
            totalChars += block.length();
        }
        return sb.toString();
    }
}
