package com.fitnesstracker.profile.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One row per user, enforced by {@code UNIQUE(user_id)}.
 *
 * <p>Every field is optional. {@code PUT /profile} is a full replace, so an omitted field
 * is written as NULL rather than left alone — that is a PUT, not a PATCH, and the profile
 * form sends the complete object every time.
 */
@Entity
@Table(name = "profiles")
public class Profile {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(name = "display_name", length = 100)
    private String displayName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Type(SexType.class)
    @Column(name = "sex", columnDefinition = "profile_sex")
    private Sex sex;

    @Column(name = "height_cm")
    private Double heightCm;

    @Column(name = "fitness_goal", length = 255)
    private String fitnessGoal;

    @Type(ActivityLevelType.class)
    @Column(name = "activity_level", columnDefinition = "profile_activity_level")
    private ActivityLevel activityLevel;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Profile() {
        // JPA
    }

    public Profile(UUID userId) {
        this.userId = userId;
    }

    public void apply(String displayName, LocalDate dateOfBirth, Sex sex, Double heightCm,
                      String fitnessGoal, ActivityLevel activityLevel) {
        this.displayName = displayName;
        this.dateOfBirth = dateOfBirth;
        this.sex = sex;
        this.heightCm = heightCm;
        this.fitnessGoal = fitnessGoal;
        this.activityLevel = activityLevel;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public Sex getSex() {
        return sex;
    }

    public Double getHeightCm() {
        return heightCm;
    }

    public String getFitnessGoal() {
        return fitnessGoal;
    }

    public ActivityLevel getActivityLevel() {
        return activityLevel;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
