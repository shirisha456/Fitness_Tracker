package com.fitnesstracker.traininginsights.repository;

import com.fitnesstracker.traininginsights.domain.AnalysisRules;
import com.fitnesstracker.traininginsights.domain.LoggedSet;
import com.fitnesstracker.traininginsights.dto.ExerciseRef;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The only place training analytics touch the database.
 *
 * <p>Three queries cover the whole feature and none is issued per exercise — the overview
 * joins every exercise at once and groups in Java, so adding a seventh exercise to a
 * user's routine does not add a seventh round trip.
 *
 * <p>Ownership is enforced inside the SQL: every statement filters on
 * {@code workouts.user_id}. A caller passing another user's {@code exercise_id} gets an
 * empty result, never someone else's history.
 *
 * <p><b>Index coverage — no new index is introduced by this feature.</b>
 * {@code ix_workouts_user_performed (user_id, performed_at)} drives the outer filter and
 * ordering, and {@code ix_workout_exercises_workout_id} serves the join. Driving from
 * {@code workout_exercises.exercise_id} instead would be far less selective than the
 * per-user filter, so an index on that column would not be used here.
 *
 * <p>Written with the EntityManager rather than Spring Data derived queries because the
 * result is a flat projection of two joined tables, not an entity — loading entities here
 * would pull columns the engine never reads.
 */
@Repository
public class TrainingHistoryRepository {

    private final EntityManager entityManager;

    public TrainingHistoryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Every logged set of one exercise for one user, within the lookback window.
     *
     * <p>Future-dated workouts are excluded: the schema has no planned/completed flag, so a
     * workout dated after today cannot be treated as performed.
     */
    public List<LoggedSet> findExerciseHistory(
            UUID userId, UUID exerciseId, LocalDate today, int lookbackDays) {
        List<Object[]> rows = entityManager.createQuery("""
                SELECT w.id, w.performedAt, w.createdAt, we.sets, we.reps, we.weightKg,
                       we.notes, w.notes
                  FROM WorkoutExercise we
                  JOIN we.workout w
                 WHERE w.userId = :userId
                   AND we.exercise.id = :exerciseId
                   AND w.performedAt >= :dateFrom
                   AND w.performedAt <= :today
                 ORDER BY w.performedAt, w.createdAt
                """, Object[].class)
                .setParameter("userId", userId)
                .setParameter("exerciseId", exerciseId)
                .setParameter("dateFrom", today.minusDays(lookbackDays))
                .setParameter("today", today)
                .getResultList();

        List<LoggedSet> history = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            history.add(toLoggedSet(row, 0));
        }
        return history;
    }

    /** Exercise identities and their logged sets, for the overview. One query. */
    public RecentHistory findRecentHistoryByExercise(
            UUID userId, LocalDate today, int lookbackDays) {
        List<Object[]> rows = entityManager.createQuery("""
                SELECT e.id, e.name, e.category,
                       w.id, w.performedAt, w.createdAt, we.sets, we.reps, we.weightKg,
                       we.notes, w.notes
                  FROM WorkoutExercise we
                  JOIN we.workout w
                  JOIN we.exercise e
                 WHERE w.userId = :userId
                   AND w.performedAt >= :dateFrom
                   AND w.performedAt <= :today
                 ORDER BY w.performedAt, w.createdAt
                """, Object[].class)
                .setParameter("userId", userId)
                .setParameter("dateFrom", today.minusDays(lookbackDays))
                .setParameter("today", today)
                .getResultList();

        Map<UUID, ExerciseRef> refs = new LinkedHashMap<>();
        Map<UUID, List<LoggedSet>> history = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID exerciseId = (UUID) row[0];
            refs.computeIfAbsent(exerciseId, id -> new ExerciseRef(
                    id, (String) row[1], (ExerciseCategory) row[2]));
            history.computeIfAbsent(exerciseId, id -> new ArrayList<>())
                    .add(toLoggedSet(row, 3));
        }
        return new RecentHistory(refs, history);
    }

    public record RecentHistory(
            Map<UUID, ExerciseRef> exercises, Map<UUID, List<LoggedSet>> history) {}

    /**
     * Dates of the user's workouts, for the consistency counts.
     *
     * <p>Queried separately from the exercise join because a workout logged with no
     * exercises still counts as a session the user showed up for, and an inner join would
     * silently drop it.
     */
    public List<LocalDate> findWorkoutDates(UUID userId, LocalDate today, int lookbackDays) {
        return entityManager.createQuery("""
                SELECT w.performedAt FROM Workout w
                 WHERE w.userId = :userId
                   AND w.performedAt >= :dateFrom
                   AND w.performedAt <= :today
                 ORDER BY w.performedAt
                """, LocalDate.class)
                .setParameter("userId", userId)
                .setParameter("dateFrom", today.minusDays(lookbackDays))
                .setParameter("today", today)
                .getResultList();
    }

    /**
     * Builds one row, screening the free-text notes here and passing only a boolean on.
     *
     * <p>The note text stays in this layer, so it cannot reach the analytics engine, a log
     * line, an error message or an AI prompt.
     */
    private static LoggedSet toLoggedSet(Object[] row, int offset) {
        String exerciseNotes = (String) row[offset + 6];
        String workoutNotes = (String) row[offset + 7];
        return new LoggedSet(
                (UUID) row[offset],
                (LocalDate) row[offset + 1],
                (Instant) row[offset + 2],
                (Integer) row[offset + 3],
                (Integer) row[offset + 4],
                (Double) row[offset + 5],
                AnalysisRules.mentionsMedicalConcern(exerciseNotes, workoutNotes));
    }
}
