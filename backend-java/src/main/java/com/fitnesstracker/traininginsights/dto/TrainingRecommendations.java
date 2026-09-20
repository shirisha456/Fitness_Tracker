package com.fitnesstracker.traininginsights.dto;

import java.time.LocalDate;
import java.util.List;

public record TrainingRecommendations(
        LocalDate generatedOn, List<TrainingRecommendation> recommendations) {}
