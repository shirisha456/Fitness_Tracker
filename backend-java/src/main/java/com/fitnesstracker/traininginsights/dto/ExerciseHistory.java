package com.fitnesstracker.traininginsights.dto;

import java.util.List;

public record ExerciseHistory(
        ExerciseRef exercise,
        List<SessionMetrics> sessions,
        PersonalBests personalBests,
        DateRange dateRange) {}
