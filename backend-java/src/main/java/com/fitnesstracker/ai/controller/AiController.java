package com.fitnesstracker.ai.controller;

import com.fitnesstracker.ai.dto.ChatRequest;
import com.fitnesstracker.ai.dto.ChatResponse;
import com.fitnesstracker.ai.dto.GenerateMealRequest;
import com.fitnesstracker.ai.dto.GenerateMealResponse;
import com.fitnesstracker.ai.dto.GenerateWorkoutRequest;
import com.fitnesstracker.ai.dto.GeneratedWorkoutResponse;
import com.fitnesstracker.ai.dto.RecommendationsResponse;
import com.fitnesstracker.ai.service.AiService;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI coach endpoints.
 *
 * <p>Unlike training insights, these <em>fail</em> when the provider is unavailable — 503
 * when unconfigured or rate-limited, 502 when the response is unusable. That is the
 * existing contract and the frontend handles it.
 */
@RestController
@RequestMapping("/api/v1/ai")
public class AiController {

    private final AiService ai;

    public AiController(AiService ai) {
        this.ai = ai;
    }

    @PostMapping("/generate-workout")
    public ApiResponse<GeneratedWorkoutResponse> generateWorkout(
            @CurrentUser User user, @Valid @RequestBody GenerateWorkoutRequest request) {
        return ApiResponse.of(ai.generateWorkout(user, request));
    }

    @PostMapping("/generate-meals")
    public ApiResponse<GenerateMealResponse> generateMeals(
            @Valid @RequestBody GenerateMealRequest request) {
        return ApiResponse.of(ai.generateMeals(request));
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.of(new ChatResponse(ai.chat(request)));
    }

    @GetMapping("/recommendations")
    public ApiResponse<RecommendationsResponse> recommendations(@CurrentUser User user) {
        return ApiResponse.of(ai.getRecommendations(user));
    }
}
