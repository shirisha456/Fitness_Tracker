package com.fitnesstracker.traininginsights.service;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.traininginsights.domain.AnalysisRules;
import com.fitnesstracker.traininginsights.domain.LoggedSet;
import com.fitnesstracker.traininginsights.domain.SuggestionKind;
import com.fitnesstracker.traininginsights.domain.TrainingAnalytics;
import com.fitnesstracker.traininginsights.domain.TrendClassification;
import com.fitnesstracker.traininginsights.dto.DateRange;
import com.fitnesstracker.traininginsights.dto.ExerciseHistory;
import com.fitnesstracker.traininginsights.dto.ExerciseInsight;
import com.fitnesstracker.traininginsights.dto.ExerciseRef;
import com.fitnesstracker.traininginsights.dto.PersonalBests;
import com.fitnesstracker.traininginsights.dto.SessionMetrics;
import com.fitnesstracker.traininginsights.dto.TrainingOverview;
import com.fitnesstracker.traininginsights.dto.TrainingRecommendation;
import com.fitnesstracker.traininginsights.dto.TrainingRecommendations;
import com.fitnesstracker.traininginsights.repository.TrainingHistoryRepository;
import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.repository.ExerciseRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for training insights.
 *
 * <p>Three responsibilities and no more: fetch rows, hand them to the pure engine, and —
 * only when explicitly asked — swap the deterministic explanation for AI prose. It never
 * computes a metric itself, and it never lets an AI failure turn into a failed request.
 */
@Service
public class TrainingInsightsService {

    /**
     * Suggestions worth surfacing unprompted. "Maintain" and "not enough history" are true
     * but not actionable, so they stay on the exercise's own page.
     */
    private static final Set<SuggestionKind> ACTIONABLE = Set.of(
            SuggestionKind.SMALL_PROGRESSION,
            SuggestionKind.REVIEW_EXERCISE,
            SuggestionKind.CONSULT_PROFESSIONAL);

    private static final Set<TrendClassification> NOTABLE = Set.of(
            TrendClassification.PROGRESSING,
            TrendClassification.POSSIBLE_PLATEAU,
            TrendClassification.DECLINING);

    private final TrainingHistoryRepository history;
    private final ExerciseRepository exercises;
    private final Clock clock;

    public TrainingInsightsService(
            TrainingHistoryRepository history, ExerciseRepository exercises, Clock clock) {
        this.history = history;
        this.exercises = exercises;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(java.time.ZoneOffset.UTC));
    }

    private ExerciseRef resolveExercise(UUID exerciseId) {
        Exercise exercise = exercises.findById(exerciseId)
                .orElseThrow(() -> new AppException(
                        ErrorCode.NOT_FOUND, "Exercise not found", 404));
        return new ExerciseRef(exercise.getId(), exercise.getName(), exercise.getCategory());
    }

    /**
     * The deterministic verdict for one exercise.
     *
     * <p>The insight is fully computed before the caller's {@code explain} flag is even
     * considered — see {@code TrainingInsightsAiService} — so the analytics half of this
     * endpoint cannot be affected by OpenAI's availability.
     */
    @Transactional(readOnly = true)
    public ExerciseInsight getExerciseInsight(User user, UUID exerciseId) {
        ExerciseRef exercise = resolveExercise(exerciseId);
        List<LoggedSet> entries = history.findExerciseHistory(
                user.getId(), exerciseId, today(), AnalysisRules.HISTORY_LOOKBACK_DAYS);
        return TrainingAnalytics.buildExerciseInsight(exercise, entries);
    }

    @Transactional(readOnly = true)
    public ExerciseHistory getExerciseHistory(User user, UUID exerciseId, int limit) {
        ExerciseRef exercise = resolveExercise(exerciseId);
        List<LoggedSet> entries = history.findExerciseHistory(
                user.getId(), exerciseId, today(), AnalysisRules.HISTORY_LOOKBACK_DAYS);

        List<SessionMetrics> sessions = TrainingAnalytics.buildSessions(entries);
        // Personal bests are computed over the full queried history, then the table is
        // trimmed — so a record is never lost just because it fell outside the page.
        PersonalBests personalBests = TrainingAnalytics.findPersonalBests(sessions);
        List<SessionMetrics> trimmed = (limit > 0 && sessions.size() > limit)
                ? List.copyOf(sessions.subList(sessions.size() - limit, sessions.size()))
                : sessions;

        DateRange dateRange = trimmed.isEmpty() ? null : new DateRange(
                trimmed.get(0).performedAt(),
                trimmed.get(trimmed.size() - 1).performedAt());
        return new ExerciseHistory(exercise, trimmed, personalBests, dateRange);
    }

    @Transactional(readOnly = true)
    public TrainingOverview getOverview(User user) {
        LocalDate today = today();
        RecentInsights recent = recentInsights(user, today);
        List<LocalDate> workoutDates = history.findWorkoutDates(
                user.getId(), today, AnalysisRules.CONSISTENCY_TREND_WEEKS * 7);

        return new TrainingOverview(
                today,
                new DateRange(today.minusDays(AnalysisRules.OVERVIEW_LOOKBACK_DAYS), today),
                TrainingAnalytics.computeConsistency(workoutDates, today),
                TrainingAnalytics.computeVolumeTrend(recent.allEntries(), today),
                recent.insights());
    }

    /** Deterministic, evidence-carrying suggestions. No LLM involved at all. */
    @Transactional(readOnly = true)
    public TrainingRecommendations getRecommendations(User user) {
        LocalDate today = today();
        List<TrainingRecommendation> recommendations = recentInsights(user, today).insights()
                .stream()
                .filter(insight -> ACTIONABLE.contains(insight.suggestion().kind()))
                .map(insight -> new TrainingRecommendation(
                        insight.exercise(), insight.classification(),
                        insight.suggestion(), insight.evidence()))
                .toList();
        return new TrainingRecommendations(today, recommendations);
    }

    /**
     * Compact deterministic training signals for the existing AI coach prompt.
     *
     * <p>Already-computed conclusions, never raw history — the coach explains these
     * numbers, it does not derive them.
     */
    @Transactional(readOnly = true)
    public List<String> buildCoachSignals(User user) {
        LocalDate today = today();
        List<LocalDate> workoutDates = history.findWorkoutDates(
                user.getId(), today, AnalysisRules.CONSISTENCY_TREND_WEEKS * 7);
        var consistency = TrainingAnalytics.computeConsistency(workoutDates, today);

        List<String> lines = new ArrayList<>();
        lines.add("Training frequency: " + consistency.workoutsLast7Days()
                + " sessions in the last 7 days vs " + consistency.workoutsPrevious7Days()
                + " the week before");

        List<ExerciseInsight> notable = recentInsights(user, today).insights().stream()
                .filter(insight -> NOTABLE.contains(insight.classification()))
                .toList();
        notable.stream().limit(3).forEach(insight ->
                lines.add(insight.exercise().name() + ": "
                        + insight.classification().getValue()));
        if (notable.isEmpty()) {
            lines.add("No exercise has enough comparable history for a trend verdict yet.");
        }
        return List.copyOf(lines);
    }

    private record RecentInsights(List<ExerciseInsight> insights, List<LoggedSet> allEntries) {}

    /**
     * Per-exercise insights over the overview window, most recently trained first.
     *
     * <p>Returns the flattened rows alongside them so callers that also need workload
     * totals do not re-query. One database round trip serves every exercise.
     */
    private RecentInsights recentInsights(User user, LocalDate today) {
        var recent = history.findRecentHistoryByExercise(
                user.getId(), today, AnalysisRules.OVERVIEW_LOOKBACK_DAYS);
        Map<UUID, List<LoggedSet>> byExercise = recent.history();

        List<ExerciseInsight> insights = new ArrayList<>();
        for (Map.Entry<UUID, List<LoggedSet>> entry : byExercise.entrySet()) {
            insights.add(TrainingAnalytics.buildExerciseInsight(
                    recent.exercises().get(entry.getKey()), entry.getValue()));
        }

        // Sorted by (most recent session, name) descending — matching the previous implementation,
        // where the reverse sort applies to the name too.
        insights.sort(Comparator
                .comparing((ExerciseInsight insight) -> byExercise.get(insight.exercise().id())
                        .stream().map(LoggedSet::performedAt).max(Comparator.naturalOrder())
                        .orElseThrow())
                .thenComparing(insight -> insight.exercise().name())
                .reversed());

        List<ExerciseInsight> capped = insights.size() > AnalysisRules.OVERVIEW_MAX_EXERCISES
                ? List.copyOf(insights.subList(0, AnalysisRules.OVERVIEW_MAX_EXERCISES))
                : List.copyOf(insights);

        List<LoggedSet> allEntries = byExercise.values().stream().flatMap(List::stream).toList();
        return new RecentInsights(capped, allEntries);
    }
}
