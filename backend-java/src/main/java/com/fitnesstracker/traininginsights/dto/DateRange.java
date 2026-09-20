package com.fitnesstracker.traininginsights.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;

/**
 * Serialises with the keys {@code from} and {@code to}.
 *
 * <p>Those are Pydantic serialisation aliases in the Python schema and the frontend reads
 * them directly, so the names are pinned rather than derived from the field names.
 */
public record DateRange(
        @JsonProperty("from") LocalDate fromDate, @JsonProperty("to") LocalDate toDate) {}
