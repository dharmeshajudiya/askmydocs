package com.askmydocs.desktop.model;

import java.util.List;

public record ChatMessage(
    Role role,
    String content,
    List<SourceChunk> sources
) {
    public enum Role { USER, ASSISTANT }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of());
    }

    public static ChatMessage assistant(String text, List<SourceChunk> sources) {
        return new ChatMessage(Role.ASSISTANT, text, sources);
    }
}
