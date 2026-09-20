package com.fitnesstracker.traininginsights.dto;

import com.fitnesstracker.traininginsights.domain.MetricBasis;
import com.fitnesstracker.traininginsights.domain.TrendClassification;
import java.util.List;

/**
 * A verdict plus everything needed to answer "why did the system say this?".
 *
 * <p>{@code explanation} is deterministic prose by default. It is replaced by AI wording
 * only when explicitly requested and only when that call succeeds; every other field is
 * computed before the AI is even consulted.
 */
public record ExerciseInsight(
        ExerciseRef exercise,
        TrendClassification classification,
        MetricBasis metricBasis,
        int sessionsAnalyzed,
        DateRange dateRange,
        Double currentWeightKg,
        Double previousWeightKg,
        Double primaryChangePercent,
        Double volumeChangePercent,
        boolean plateauDetected,
        List<String> evidence,
        NextSessionSuggestion suggestion,
        String explanation,
        String explanationSource) {

    /** Returns a copy with AI prose substituted; every computed field is untouched. */
    public ExerciseInsight withExplanation(String text, String source) {
        return new ExerciseInsight(
                exercise, classification, metricBasis, sessionsAnalyzed, dateRange,
                currentWeightKg, previousWeightKg, primaryChangePercent, volumeChangePercent,
                plateauDetected, evidence, suggestion, text, source);
    }
}
