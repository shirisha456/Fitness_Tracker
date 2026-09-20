package com.fitnesstracker.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.ai.dto.ChatRequest;
import com.fitnesstracker.ai.dto.GenerateMealRequest;
import com.fitnesstracker.ai.dto.GenerateMealResponse;
import com.fitnesstracker.ai.dto.GenerateWorkoutRequest;
import com.fitnesstracker.ai.dto.GeneratedExercise;
import com.fitnesstracker.ai.dto.GeneratedWorkoutResponse;
import com.fitnesstracker.ai.dto.LlmWorkout;
import com.fitnesstracker.ai.dto.RecommendationsResponse;
import com.fitnesstracker.ai.provider.AiProvider;
import com.fitnesstracker.ai.provider.AiProviderException;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.nutrition.service.NutritionService;
import com.fitnesstracker.progress.dto.MeasurementResponse;
import com.fitnesstracker.progress.service.ProgressService;
import com.fitnesstracker.traininginsights.service.TrainingInsightsService;
import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.repository.ExerciseRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The AI coach.
 *
 * <p><b>The architectural rule this whole feature rests on:</b> deterministic application
 * logic computes facts; the LLM explains them. Nothing here calculates a training metric,
 * and {@link #getRecommendations} hands the model already-computed verdicts from
 * {@link TrainingInsightsService} rather than a workout history to interpret.
 *
 * <p>Deliberately <b>not</b> {@code @Transactional}. Every database read finishes and its
 * transaction closes before the provider is called; holding a Hikari connection and any
 * row locks for the length of a 20-second upstream call would turn a slow provider into a
 * database outage.
 */
@Service
public class AiService {

    private static final String COACH_SYSTEM_PROMPT =
            "You are Fitness Tracker's AI fitness coach. Give practical, encouraging, "
                    + "concise advice about workouts, nutrition, and general fitness. You are "
                    + "not a medical professional: for injuries, pain, or medical conditions, "
                    + "advise the user to consult a doctor. Keep responses short and actionable.";

    private final AiProvider provider;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final ExerciseRepository exercises;
    private final NutritionService nutrition;
    private final ProgressService progress;
    private final TrainingInsightsService trainingInsights;
    private final Clock clock;

    public AiService(
            AiProvider provider,
            ObjectMapper objectMapper,
            Validator validator,
            ExerciseRepository exercises,
            NutritionService nutrition,
            ProgressService progress,
            TrainingInsightsService trainingInsights,
            Clock clock) {
        this.provider = provider;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.exercises = exercises;
        this.nutrition = nutrition;
        this.progress = progress;
        this.trainingInsights = trainingInsights;
        this.clock = clock;
    }

    // --- workout generation --------------------------------------------------

    public GeneratedWorkoutResponse generateWorkout(User user, GenerateWorkoutRequest request) {
        // Prompt-visible exercises are scoped to the curated library plus this user's own.
        // Exercise names are free text any user can write and they are concatenated into
        // the system prompt verbatim — an unscoped list would let one user inject
        // instructions into every other user's prompt.
        List<Exercise> visible = exercises.findVisibleForPrompt(user.getId());
        Map<String, java.util.UUID> byLowerName = new HashMap<>();
        for (Exercise exercise : visible) {
            byLowerName.putIfAbsent(exercise.getName().toLowerCase(Locale.ROOT), exercise.getId());
        }
        String names = visible.stream().map(Exercise::getName).collect(Collectors.joining(", "));

        String systemPrompt = "You are a fitness coach creating a workout plan for the "
                + "Fitness Tracker app. You must only use exercises from this exact list: "
                + names + ". Respond with JSON matching this shape: "
                + "{\"name\": string, \"exercises\": [{\"exercise_name\": string, "
                + "\"sets\": int, \"reps\": int, \"notes\": string|null}]}";
        String userPrompt = "Goal: " + request.goal() + "\n"
                + "Available equipment: "
                + (request.equipment() == null || request.equipment().isBlank()
                        ? "any" : request.equipment()) + "\n"
                + "Session length: " + request.durationOrDefault() + " minutes\n"
                + "Difficulty: " + request.difficultyOrDefault();

        LlmWorkout workout = readValidated(
                provider.completeJson(List.of(
                        AiProvider.Message.system(systemPrompt),
                        AiProvider.Message.user(userPrompt)), 800),
                LlmWorkout.class);

        List<GeneratedExercise> resolved = new ArrayList<>();
        for (LlmWorkout.LlmExercise item : workout.exercises()) {
            resolved.add(new GeneratedExercise(
                    item.exerciseName(),
                    byLowerName.get(item.exerciseName().toLowerCase(Locale.ROOT)),
                    item.sets(),
                    item.reps(),
                    item.notes()));
        }
        return new GeneratedWorkoutResponse(workout.name(), List.copyOf(resolved));
    }

    // --- meal generation -----------------------------------------------------

    public GenerateMealResponse generateMeals(GenerateMealRequest request) {
        String systemPrompt = "You are a nutrition assistant for the Fitness Tracker app. "
                + "Suggest 3 realistic meal ideas. Respond with JSON matching this shape: "
                + "{\"suggestions\": [{\"name\": string, \"estimated_calories\": int, "
                + "\"protein_g\": number, \"carbs_g\": number, \"fat_g\": number}]}";
        String userPrompt = "Meal type: " + request.mealType() + "\n"
                + "Dietary restrictions: "
                + (request.dietaryRestrictions() == null || request.dietaryRestrictions().isBlank()
                        ? "none" : request.dietaryRestrictions()) + "\n"
                + "Target calories: "
                + (request.targetCalories() == null
                        ? "no specific target" : request.targetCalories());

        return readValidated(
                provider.completeJson(List.of(
                        AiProvider.Message.system(systemPrompt),
                        AiProvider.Message.user(userPrompt)), 600),
                GenerateMealResponse.class);
    }

    // --- chat ----------------------------------------------------------------

    public String chat(ChatRequest request) {
        List<AiProvider.Message> messages = new ArrayList<>();
        messages.add(AiProvider.Message.system(COACH_SYSTEM_PROMPT));
        for (ChatRequest.ChatMessage message : request.messages()) {
            messages.add(new AiProvider.Message(message.role(), message.content()));
        }
        return provider.complete(List.copyOf(messages), 400);
    }

    // --- recommendations -----------------------------------------------------

    /**
     * Coaching tips grounded in already-computed signals.
     *
     * <p>The database reads happen first and complete before the provider is called; the
     * model receives conclusions ("Squat: possible_plateau"), never a history to interpret.
     */
    public RecommendationsResponse getRecommendations(User user) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        int recentWorkouts = trainingInsights.getOverview(user).consistency().workoutsLast7Days();
        var summary = nutrition.dailySummary(user, today);
        List<MeasurementResponse> measurements =
                progress.listMeasurements(user, null, today);
        List<String> trainingSignals = trainingInsights.buildCoachSignals(user);

        List<String> context = new ArrayList<>();
        context.add("Workouts logged in the last 7 days: " + recentWorkouts);
        context.add("Calories logged today: " + summary.totalCalories());
        context.add("Recent training signals (already computed — do not recalculate or "
                + "contradict):");
        trainingSignals.forEach(signal -> context.add("- " + signal));

        if (!measurements.isEmpty()) {
            MeasurementResponse latest = measurements.get(0);
            context.add("Latest weight: " + latest.weightKg() + "kg on " + latest.recordedAt());
            if (measurements.size() > 1) {
                MeasurementResponse previous = measurements.get(1);
                context.add("Previous weight: " + previous.weightKg() + "kg on "
                        + previous.recordedAt());
            }
        } else {
            context.add("No body measurements logged yet.");
        }

        String systemPrompt = "You are Fitness Tracker's AI fitness coach. Based on the "
                + "user's recent activity, give 2-3 short, specific, encouraging tips (one "
                + "sentence each). Respond with JSON matching this shape: "
                + "{\"recommendations\": [string, ...]}";

        return readValidated(
                provider.completeJson(List.of(
                        AiProvider.Message.system(systemPrompt),
                        AiProvider.Message.user(String.join("\n", context))), 300),
                RecommendationsResponse.class);
    }

    // --- structured output validation ----------------------------------------

    /**
     * Parses and validates model output.
     *
     * <p>Model output is never trusted. JSON mode constrains the model to parseable JSON,
     * not to our schema, and a truncated response is not even parseable — both are upstream
     * failures, so both surface as 502 rather than an unhandled 500.
     */
    private <T> T readValidated(String rawJson, Class<T> type) {
        T value;
        try {
            value = objectMapper.readValue(rawJson, type);
        } catch (Exception ex) {
            throw AiProviderException.unexpectedShape(ex);
        }
        if (value == null) {
            throw AiProviderException.malformedResponse();
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw AiProviderException.unexpectedShape(
                    new IllegalArgumentException(violations.iterator().next().getMessage()));
        }
        return value;
    }
}
