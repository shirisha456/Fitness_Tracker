package com.fitnesstracker.traininginsights.dto;

import java.time.LocalDate;

/**
 * Deterministic records over whatever history was queried.
 *
 * <p>Every field is null when the history contains no weighted session — the engine
 * reports nothing rather than inventing a record. Ties are dated from when the record was
 * <em>first</em> set, not the last time it was matched.
 */
public record PersonalBests(
        Double heaviestWeightKg,
        LocalDate heaviestWeightOn,
        Double bestSessionVolumeKg,
        LocalDate bestSessionVolumeOn,
        Integer mostRepsAtHeaviestWeight,
        LocalDate mostRepsAtHeaviestWeightOn) {

    public static PersonalBests empty() {
        return new PersonalBests(null, null, null, null, null, null);
    }
}
