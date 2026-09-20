package com.fitnesstracker.ai.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record RecommendationsResponse(@NotNull List<String> recommendations) {}
