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

/** A water intake entry. Create and delete only — there is no update endpoint. */
@Entity
@Table(name = "water_entries")
public class WaterEntry {

    @Id
    @GeneratedValue
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "logged_at", nullable = false)
    private LocalDate loggedAt;

    @Column(name = "amount_ml", nullable = false)
    private int amountMl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected WaterEntry() {
        // JPA
    }

    public WaterEntry(UUID userId, LocalDate loggedAt, int amountMl) {
        this.userId = userId;
        this.loggedAt = loggedAt;
        this.amountMl = amountMl;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public LocalDate getLoggedAt() {
        return loggedAt;
    }

    public int getAmountMl() {
        return amountMl;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
