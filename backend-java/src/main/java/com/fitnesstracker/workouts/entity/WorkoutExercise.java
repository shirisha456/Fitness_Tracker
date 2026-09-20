package com.fitnesstracker.workouts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One exercise within a workout, with the values actually performed.
 *
 * <p>{@code weight_kg} is {@code double precision} in PostgreSQL and is mapped to
 * {@code Double}, not {@code BigDecimal}: the column stores no more precision than a
 * double, and the API returns {@code 45.0} which {@code Double} reproduces exactly. See
 * docs/java-schema-compatibility.md.
 */
@Entity
@Table(name = "workout_exercises")
public class WorkoutExercise {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workout_id", nullable = false)
    private Workout workout;

    /**
     * The exercise is loaded eagerly only through an explicit fetch join in the repository;
     * LAZY here keeps a list query from pulling the library row per child.
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercise_id", nullable = false)
    private Exercise exercise;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "sets", nullable = false)
    private int sets;

    @Column(name = "reps", nullable = false)
    private int reps;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(name = "notes", length = 500)
    private String notes;

    protected WorkoutExercise() {
        // JPA
    }

    public WorkoutExercise(Exercise exercise, int sets, int reps, Double weightKg, String notes) {
        this.exercise = exercise;
        this.sets = sets;
        this.reps = reps;
        this.weightKg = weightKg;
        this.notes = notes;
    }

    void attachTo(Workout workout, int orderIndex) {
        this.workout = workout;
        this.orderIndex = orderIndex;
    }

    public UUID getId() {
        return id;
    }

    public Exercise getExercise() {
        return exercise;
    }

    public int getOrderIndex() {
        return orderIndex;
    }

    public int getSets() {
        return sets;
    }

    public int getReps() {
        return reps;
    }

    public Double getWeightKg() {
        return weightKg;
    }

    public String getNotes() {
        return notes;
    }
}
