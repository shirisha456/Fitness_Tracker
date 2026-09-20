package com.fitnesstracker.workouts.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * A logged workout.
 *
 * <p>There is no planned/completed distinction in this schema: {@code performed_at} is a
 * log date and rows are written after the fact, so the child rows' sets, reps and weight
 * are actual performed values. The adaptive-training engine depends on that reading.
 */
@Entity
@Table(name = "workouts")
public class Workout {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "performed_at", nullable = false)
    private LocalDate performedAt;

    @Column(name = "notes", length = 2000)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Maintained by SQLAlchemy in Python, so Hibernate maintains it here. */
    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The one place a JPA cascade is genuinely right: {@code PUT /workouts/{id}} replaces
     * the whole child collection, so orphan removal is the behaviour, not a shortcut.
     * Every other relationship relies on the database's own ON DELETE CASCADE.
     *
     * <p>LAZY, and loaded explicitly with a fetch join where the response needs it —
     * {@code open-in-view=false} means a lazy access outside the service would fail loudly
     * rather than silently issuing per-row queries.
     */
    @OneToMany(mappedBy = "workout", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("orderIndex ASC")
    private List<WorkoutExercise> exercises = new ArrayList<>();

    protected Workout() {
        // JPA
    }

    public Workout(UUID userId, String name, LocalDate performedAt, String notes) {
        this.userId = userId;
        this.name = name;
        this.performedAt = performedAt;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public LocalDate getPerformedAt() {
        return performedAt;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<WorkoutExercise> getExercises() {
        return exercises;
    }

    public void updateDetails(String name, LocalDate performedAt, String notes) {
        this.name = name;
        this.performedAt = performedAt;
        this.notes = notes;
    }

    /** Full-replace semantics: {@code PUT} rebuilds the collection from the request. */
    public void replaceExercises(List<WorkoutExercise> replacements) {
        exercises.clear();
        for (int index = 0; index < replacements.size(); index++) {
            WorkoutExercise entry = replacements.get(index);
            // order_index comes from array position, not the client.
            entry.attachTo(this, index);
            exercises.add(entry);
        }
    }
}
