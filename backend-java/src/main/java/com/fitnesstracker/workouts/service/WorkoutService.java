package com.fitnesstracker.workouts.service;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.workouts.dto.WorkoutExerciseRequest;
import com.fitnesstracker.workouts.dto.WorkoutRequest;
import com.fitnesstracker.workouts.dto.WorkoutResponse;
import com.fitnesstracker.workouts.dto.WorkoutSummaryResponse;
import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.entity.Workout;
import com.fitnesstracker.workouts.entity.WorkoutExercise;
import com.fitnesstracker.workouts.repository.ExerciseRepository;
import com.fitnesstracker.workouts.repository.WorkoutRepository;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Workout CRUD.
 *
 * <p>Ownership is enforced per query, the way the previous implementations do it: a workout
 * belonging to someone else is <b>404</b>, never 403 — whether a given id exists is not
 * something another user should be able to learn.
 */
@Service
public class WorkoutService {

    private final WorkoutRepository workouts;
    private final ExerciseRepository exercises;

    public WorkoutService(WorkoutRepository workouts, ExerciseRepository exercises) {
        this.workouts = workouts;
        this.exercises = exercises;
    }

    @Transactional(readOnly = true)
    public List<WorkoutSummaryResponse> list(User user, LocalDate dateFrom, LocalDate dateTo) {
        return workouts.findSummaries(user.getId(), dateFrom, dateTo);
    }

    @Transactional(readOnly = true)
    public WorkoutResponse get(User user, UUID workoutId) {
        return WorkoutResponse.from(loadOwned(user, workoutId));
    }

    @Transactional
    public WorkoutResponse create(User user, WorkoutRequest request) {
        Workout workout = new Workout(
                user.getId(), request.name(), request.performedAt(), request.notes());
        workout.replaceExercises(buildEntries(request.exercisesOrEmpty()));
        workouts.saveAndFlush(workout);
        return WorkoutResponse.from(workout);
    }

    @Transactional
    public WorkoutResponse update(User user, UUID workoutId, WorkoutRequest request) {
        Workout workout = loadOwned(user, workoutId);
        workout.updateDetails(request.name(), request.performedAt(), request.notes());
        workout.replaceExercises(buildEntries(request.exercisesOrEmpty()));
        workouts.saveAndFlush(workout);
        return WorkoutResponse.from(workout);
    }

    @Transactional
    public void delete(User user, UUID workoutId) {
        workouts.delete(loadOwned(user, workoutId));
    }

    private Workout loadOwned(User user, UUID workoutId) {
        return workouts.findByIdWithExercises(workoutId)
                .filter(workout -> workout.getUserId().equals(user.getId()))
                .orElseThrow(() -> new AppException(
                        ErrorCode.NOT_FOUND, "Workout not found", 404));
    }

    /**
     * Resolves every referenced exercise in <b>one</b> query, then builds the child rows.
     *
     * <p>Looking each id up individually would be an N+1 on write. Unknown ids are reported
     * together, in the documented message format.
     */
    private List<WorkoutExercise> buildEntries(List<WorkoutExerciseRequest> requested) {
        if (requested.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = requested.stream()
                .map(WorkoutExerciseRequest::exerciseId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<UUID, Exercise> found = exercises.findAllById(ids).stream()
                .collect(Collectors.toMap(Exercise::getId, Function.identity()));

        List<UUID> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new AppException(
                    ErrorCode.BAD_REQUEST,
                    "Unknown exercise id(s): "
                            + missing.stream().map(UUID::toString).collect(Collectors.joining(", ")),
                    400);
        }

        return requested.stream()
                .map(entry -> new WorkoutExercise(
                        found.get(entry.exerciseId()),
                        entry.sets(),
                        entry.reps(),
                        entry.weightKg(),
                        entry.notes()))
                .toList();
    }
}
