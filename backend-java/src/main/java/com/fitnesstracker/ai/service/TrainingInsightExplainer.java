package com.fitnesstracker.ai.service;

import com.fitnesstracker.ai.provider.AiProvider;
import com.fitnesstracker.ai.provider.AiProviderException;
import com.fitnesstracker.traininginsights.dto.ExerciseInsight;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Rewords an already-computed insight.
 *
 * <p>This is the only place the LLM touches training analytics, and it can change exactly
 * two fields: the prose and its source label. The classification, the metrics and the
 * evidence are computed before this class is reached, so AI availability cannot alter
 * them — and when the provider fails, the deterministic explanation simply stays.
 */
@Service
public class TrainingInsightExplainer {

    private static final Logger log = LoggerFactory.getLogger(TrainingInsightExplainer.class);

    private static final String SYSTEM_PROMPT =
            "You are Fitness Tracker's AI fitness coach. You will be given a training "
                    + "analysis that has ALREADY been computed from the user's logged workouts. "
                    + "Restate it in one or two short, plain, encouraging sentences addressed "
                    + "to the user. Do not calculate anything. Do not introduce any number, "
                    + "date or classification that is not in the analysis. Do not give medical "
                    + "advice or comment on pain or injury. If the analysis says the data is "
                    + "insufficient, say so plainly rather than guessing.";

    private final AiProvider provider;

    public TrainingInsightExplainer(AiProvider provider) {
        this.provider = provider;
    }

    /**
     * Returns the insight with AI prose if the call succeeds, unchanged if it does not.
     *
     * <p>Swallowing the provider failure is the point: a missing API key, a rate limit or
     * an outage must degrade the wording, never the endpoint.
     */
    public ExerciseInsight explain(ExerciseInsight insight) {
        try {
            String text = provider.complete(List.of(
                    AiProvider.Message.system(SYSTEM_PROMPT),
                    AiProvider.Message.user(buildPrompt(insight))), 180).trim();
            return text.isEmpty() ? insight : insight.withExplanation(text, "ai");
        } catch (AiProviderException ex) {
            log.info("ai explanation unavailable, keeping deterministic text: {}", ex.getKind());
            return insight;
        }
    }

    /**
     * The compact, already-computed summary the model is allowed to see.
     *
     * <p>Deliberately not the user's workout history: the model gets a handful of derived
     * numbers and the verdict, so it has nothing to recalculate and nothing private to
     * leak. Free-text workout notes are never included — the analytics layer never sees
     * them either.
     */
    public static String buildPrompt(ExerciseInsight insight) {
        StringBuilder prompt = new StringBuilder()
                .append("Exercise: ").append(insight.exercise().name()).append('\n')
                .append("Classification: ").append(insight.classification().getValue())
                .append('\n')
                .append("Sessions analyzed: ").append(insight.sessionsAnalyzed()).append('\n')
                .append("Metric basis: ").append(insight.metricBasis().getValue()).append('\n');

        if (insight.dateRange() != null) {
            prompt.append("Date range: ").append(insight.dateRange().fromDate())
                    .append(" to ").append(insight.dateRange().toDate()).append('\n');
        }
        if (insight.currentWeightKg() != null) {
            prompt.append("Latest top load: ").append(insight.currentWeightKg())
                    .append(" kg\n");
        }
        if (insight.previousWeightKg() != null) {
            prompt.append("Previous top load: ").append(insight.previousWeightKg())
                    .append(" kg\n");
        }
        if (insight.primaryChangePercent() != null) {
            prompt.append("Change in primary metric: ").append(insight.primaryChangePercent())
                    .append("%\n");
        }
        if (insight.volumeChangePercent() != null) {
            prompt.append("Change in session volume: ").append(insight.volumeChangePercent())
                    .append("%\n");
        }
        if (!insight.evidence().isEmpty()) {
            prompt.append("Evidence:\n");
            insight.evidence().forEach(line -> prompt.append("- ").append(line).append('\n'));
        }
        return prompt.toString().stripTrailing();
    }
}
