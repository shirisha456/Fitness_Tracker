package com.fitnesstracker.nutrition.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/** A logged meal. Macros are nullable — a user may record only calories. */
@Entity
@Table(name = "meals")
public class Meal {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "logged_at", nullable = false)
    private LocalDate loggedAt;

    @Column(name = "calories", nullable = false)
    private int calories;

    @Column(name = "protein_g")
    private Double proteinG;

    @Column(name = "carbs_g")
    private Double carbsG;

    @Column(name = "fat_g")
    private Double fatG;

    @Column(name = "notes", length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Meal() {
        // JPA
    }

    public Meal(UUID userId) {
        this.userId = userId;
    }

    /** Full replace: {@code PUT} overwrites every field, including nulling omitted macros. */
    public void apply(String name, LocalDate loggedAt, int calories,
                      Double proteinG, Double carbsG, Double fatG, String notes) {
        this.name = name;
        this.loggedAt = loggedAt;
        this.calories = calories;
        this.proteinG = proteinG;
        this.carbsG = carbsG;
        this.fatG = fatG;
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

    public LocalDate getLoggedAt() {
        return loggedAt;
    }

    public int getCalories() {
        return calories;
    }

    public Double getProteinG() {
        return proteinG;
    }

    public Double getCarbsG() {
        return carbsG;
    }

    public Double getFatG() {
        return fatG;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
