package com.fitnesstracker.workouts.service;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.workouts.dto.ExerciseCreateRequest;
import com.fitnesstracker.workouts.dto.ExerciseResponse;
import com.fitnesstracker.workouts.entity.Exercise;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import com.fitnesstracker.workouts.repository.ExerciseRepository;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The shared exercise library. Not user-owned. */
@Service
public class ExerciseService {

    private final ExerciseRepository exercises;
    private final ExerciseCreator creator;

    public ExerciseService(ExerciseRepository exercises, ExerciseCreator creator) {
        this.exercises = exercises;
        this.creator = creator;
    }

    @Transactional(readOnly = true)
    public List<ExerciseResponse> list(ExerciseCategory category) {
        List<Exercise> found = category == null
                ? exercises.findAllByOrderByName()
                : exercises.findByCategoryOrderByName(category);
        return found.stream().map(ExerciseResponse::from).toList();
    }

    /**
     * Get-or-create, case-insensitive on name.
     *
     * <p>Returns <b>201</b> even when the row already existed — a quirk of the reference
     * endpoint that the exercise picker depends on, so it is preserved.
     */
    @Transactional
    public ExerciseResponse getOrCreate(User user, ExerciseCreateRequest request) {
        String name = request.name().trim();
        return exercises.findByNameIgnoreCase(name)
                .map(ExerciseResponse::from)
                .orElseGet(() -> ExerciseResponse.from(creator.create(user, name, request)));
    }

    /**
     * The insert, in its own transaction.
     *
     * <p>Losing a race on the unique name must undo only this INSERT. A
     * {@code DataIntegrityViolationException} marks the surrounding transaction
     * rollback-only, so recovering inside it is impossible — the previous implementation uses
     * a SAVEPOINT for the same reason.
     */
    @Service
    public static class ExerciseCreator {

        private final ExerciseRepository exercises;

        public ExerciseCreator(ExerciseRepository exercises) {
            this.exercises = exercises;
        }

        @Transactional(propagation = Propagation.REQUIRES_NEW)
        public Exercise create(User user, String name, ExerciseCreateRequest request) {
            Exercise exercise = new Exercise(name, request.categoryOrDefault(), null, null);
            exercise.setCreatedByUserId(user.getId());
            try {
                return exercises.saveAndFlush(exercise);
            } catch (DataIntegrityViolationException raced) {
                return exercises.findByNameIgnoreCase(name).orElseThrow(() -> raced);
            }
        }
    }
}
