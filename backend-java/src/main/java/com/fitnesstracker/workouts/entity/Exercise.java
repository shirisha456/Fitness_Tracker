package com.fitnesstracker.workouts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;

/** Shared, seeded exercise library. Not user-owned. */
@Entity
@Table(name = "exercises")
public class Exercise {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /** See {@code User.role} for why this is a UserType, not NAMED_ENUM. */
    @Type(ExerciseCategoryType.class)
    @Column(name = "category", nullable = false, columnDefinition = "exercise_category")
    private ExerciseCategory category;

    @Column(name = "muscle_group", length = 100)
    private String muscleGroup;

    @Column(name = "equipment", length = 100)
    private String equipment;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** NULL marks a seeded library entry; non-null marks a user's custom exercise. */
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;

    protected Exercise() {
        // JPA
    }

    public Exercise(String name, ExerciseCategory category, String muscleGroup, String equipment) {
        this.name = name;
        this.category = category;
        this.muscleGroup = muscleGroup;
        this.equipment = equipment;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ExerciseCategory getCategory() {
        return category;
    }

    public void setCategory(ExerciseCategory category) {
        this.category = category;
    }

    public String getMuscleGroup() {
        return muscleGroup;
    }

    public UUID getCreatedByUserId() {
        return createdByUserId;
    }

    /** Non-null marks a user's custom exercise; NULL marks a seeded library entry. */
    public void setCreatedByUserId(UUID createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public String getEquipment() {
        return equipment;
    }
}
