package com.fitnesstracker.traininginsights.controller;

import com.fitnesstracker.ai.service.TrainingInsightExplainer;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.security.CurrentUser;
import com.fitnesstracker.traininginsights.domain.AnalysisRules;
import com.fitnesstracker.traininginsights.dto.ExerciseHistory;
import com.fitnesstracker.traininginsights.dto.ExerciseInsight;
import com.fitnesstracker.traininginsights.dto.TrainingOverview;
import com.fitnesstracker.traininginsights.dto.TrainingRecommendations;
import com.fitnesstracker.traininginsights.service.TrainingInsightsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Training insight endpoints.
 *
 * <p>Analytics are computed from the database alone, so these keep working unchanged when
 * OpenAI is not configured or is unavailable. {@code ?explain=true} asks the coach to
 * reword the explanation; on any AI failure the deterministic text stays and the status is
 * still 200.
 */
@RestController
@RequestMapping("/api/v1/training")
@Validated
public class TrainingInsightsController {

    private final TrainingInsightsService insights;
    private final TrainingInsightExplainer explainer;

    public TrainingInsightsController(
            TrainingInsightsService insights, TrainingInsightExplainer explainer) {
        this.insights = insights;
        this.explainer = explainer;
    }

    @GetMapping("/overview")
    public ApiResponse<TrainingOverview> overview(@CurrentUser User user) {
        return ApiResponse.of(insights.getOverview(user));
    }

    @GetMapping("/recommendations")
    public ApiResponse<TrainingRecommendations> recommendations(@CurrentUser User user) {
        return ApiResponse.of(insights.getRecommendations(user));
    }

    @GetMapping("/exercises/{exerciseId}/history")
    public ApiResponse<ExerciseHistory> history(
            @CurrentUser User user,
            @PathVariable UUID exerciseId,
            @RequestParam(name = "limit", required = false, defaultValue = "50")
                    @Min(1) @Max(AnalysisRules.HISTORY_MAX_SESSIONS) int limit) {
        return ApiResponse.of(insights.getExerciseHistory(user, exerciseId, limit));
    }

    /**
     * An exercise the caller has never logged returns {@code insufficient_data}, not 404 —
     * only an unknown exercise id is a 404.
     */
    @GetMapping("/exercises/{exerciseId}/insights")
    public ApiResponse<ExerciseInsight> insight(
            @CurrentUser User user,
            @PathVariable UUID exerciseId,
            @RequestParam(name = "explain", required = false, defaultValue = "false")
                    boolean explain) {
        // The insight is fully computed before `explain` is consulted, and the AI call
        // happens outside any transaction — so provider latency cannot hold a database
        // connection and provider failure cannot change a single computed field.
        ExerciseInsight insight = insights.getExerciseInsight(user, exerciseId);
        return ApiResponse.of(explain ? explainer.explain(insight) : insight);
    }
}
