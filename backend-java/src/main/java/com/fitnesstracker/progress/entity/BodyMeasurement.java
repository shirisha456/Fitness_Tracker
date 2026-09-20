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

/**
 * One check-in per user per day, enforced by {@code UNIQUE(user_id, recorded_at)}.
 *
 * <p>That constraint is why {@code POST /measurements} is an upsert rather than a plain
 * insert — posting again for the same date updates in place and still returns 201.
 */
@Entity
@Table(name = "body_measurements")
public class BodyMeasurement {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "recorded_at", nullable = false)
    private LocalDate recordedAt;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(name = "body_fat_pct")
    private Double bodyFatPct;

    @Column(name = "waist_cm")
    private Double waistCm;

    @Column(name = "chest_cm")
    private Double chestCm;

    @Column(name = "hips_cm")
    private Double hipsCm;

    @Column(name = "arm_cm")
    private Double armCm;

    @Column(name = "notes", length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BodyMeasurement() {
        // JPA
    }

    public BodyMeasurement(UUID userId, LocalDate recordedAt) {
        this.userId = userId;
        this.recordedAt = recordedAt;
    }

    public void apply(Double weightKg, Double bodyFatPct, Double waistCm, Double chestCm,
                      Double hipsCm, Double armCm, String notes) {
        this.weightKg = weightKg;
        this.bodyFatPct = bodyFatPct;
        this.waistCm = waistCm;
        this.chestCm = chestCm;
        this.hipsCm = hipsCm;
        this.armCm = armCm;
        this.notes = notes;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getRecordedAt() {
        return recordedAt;
    }

    public void setRecordedAt(LocalDate recordedAt) {
        this.recordedAt = recordedAt;
    }

    public Double getWeightKg() {
        return weightKg;
    }

    public Double getBodyFatPct() {
        return bodyFatPct;
    }

    public Double getWaistCm() {
        return waistCm;
    }

    public Double getChestCm() {
        return chestCm;
    }

    public Double getHipsCm() {
        return hipsCm;
    }

    public Double getArmCm() {
        return armCm;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
