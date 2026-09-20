package com.fitnesstracker.ai.dto;

import java.util.List;

public record GeneratedWorkoutResponse(String name, List<GeneratedExercise> exercises) {}
