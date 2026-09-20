package com.fitnesstracker.traininginsights.dto;

import com.fitnesstracker.traininginsights.domain.SuggestionKind;

/**
 * A suggestion the user chooses whether to apply.
 *
 * <p>Nothing is ever changed automatically, and a numeric load is offered only where the
 * history supports one — see {@code TrainingAnalytics#buildSuggestion}.
 */
public record NextSessionSuggestion(
        SuggestionKind kind,
        String message,
        String rationale,
        Double currentLoadKg,
        Double suggestedLoadKg) {

    public static NextSessionSuggestion of(SuggestionKind kind, String message, String rationale) {
        return new NextSessionSuggestion(kind, message, rationale, null, null);
    }
}
