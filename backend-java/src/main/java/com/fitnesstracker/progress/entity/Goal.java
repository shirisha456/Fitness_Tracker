package com.fitnesstracker.progress.entity;

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

/** A weight-target goal. Not a training goal — the schema has no such concept. */
@Entity
@Table(name = "goals")
public class Goal {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "target_weight_kg")
    private Double targetWeightKg;

    @Column(name = "target_date")
    private LocalDate targetDate;

    /** Native enum; see PgEnumUserType for why this is not @Enumerated. */
    @Type(GoalStatusType.class)
    @Column(name = "status", nullable = false, columnDefinition = "goal_status")
    private GoalStatus status = GoalStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Goal() {
        // JPA
    }

    public Goal(UUID userId) {
        this.userId = userId;
    }

    public void apply(String title, Double targetWeightKg, LocalDate targetDate,
                      GoalStatus status) {
        this.title = title;
        this.targetWeightKg = targetWeightKg;
        this.targetDate = targetDate;
        this.status = status == null ? GoalStatus.ACTIVE : status;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public Double getTargetWeightKg() {
        return targetWeightKg;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
