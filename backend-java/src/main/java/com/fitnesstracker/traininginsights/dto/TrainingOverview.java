package com.fitnesstracker.traininginsights.dto;

import java.time.LocalDate;
import java.util.List;

public record TrainingOverview(
        LocalDate generatedOn,
        DateRange dateRange,
        ConsistencyMetrics consistency,
        VolumeTrend volume,
        List<ExerciseInsight> exercises) {}
