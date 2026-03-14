package com.askmydocs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record QueryRequest(
    @NotNull UUID documentId,
    @NotBlank String question
) {}
