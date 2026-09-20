package com.fitnesstracker.workouts.controller;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.security.CurrentUser;
import com.fitnesstracker.workouts.dto.ExerciseCreateRequest;
import com.fitnesstracker.workouts.dto.ExerciseResponse;
import com.fitnesstracker.workouts.entity.ExerciseCategory;
import com.fitnesstracker.workouts.service.ExerciseService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/exercises")
public class ExerciseController {

    private final ExerciseService exercises;

    public ExerciseController(ExerciseService exercises) {
        this.exercises = exercises;
    }

    @GetMapping
    public ApiResponse<List<ExerciseResponse>> list(
            @RequestParam(required = false) ExerciseCategory category) {
        return ApiResponse.of(exercises.list(category));
    }

    /** 201 even when the named exercise already existed — see {@link ExerciseService}. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ExerciseResponse> create(
            @CurrentUser User user, @Valid @RequestBody ExerciseCreateRequest request) {
        return ApiResponse.of(exercises.getOrCreate(user, request));
    }
}
