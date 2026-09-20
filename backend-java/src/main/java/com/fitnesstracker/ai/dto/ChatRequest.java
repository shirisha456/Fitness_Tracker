package com.fitnesstracker.ai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequest(
        @NotNull @NotEmpty @Size(max = 20) @Valid List<ChatMessage> messages) {

    public record ChatMessage(
            @Pattern(regexp = "user|assistant") String role,
            @Size(min = 1, max = 4000) String content) {}
}
