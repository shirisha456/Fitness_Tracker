package com.fitnesstracker.traininginsights.dto;

import com.fitnesstracker.traininginsights.domain.TrendClassification;
import java.util.List;

public record TrainingRecommendation(
        ExerciseRef exercise,
        TrendClassification classification,
        NextSessionSuggestion suggestion,
        List<String> evidence) {}
