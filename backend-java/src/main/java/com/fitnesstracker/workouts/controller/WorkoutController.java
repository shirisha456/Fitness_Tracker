package com.fitnesstracker.workouts.controller;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.security.CurrentUser;
import com.fitnesstracker.workouts.dto.WorkoutRequest;
import com.fitnesstracker.workouts.dto.WorkoutResponse;
import com.fitnesstracker.workouts.dto.WorkoutSummaryResponse;
import com.fitnesstracker.workouts.service.WorkoutService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workouts")
public class WorkoutController {

    private final WorkoutService workouts;

    public WorkoutController(WorkoutService workouts) {
        this.workouts = workouts;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WorkoutResponse> create(
            @CurrentUser User user, @Valid @RequestBody WorkoutRequest request) {
        return ApiResponse.of(workouts.create(user, request));
    }

    /** Returns summaries, not full detail — a different shape from {@code GET /{id}}. */
    @GetMapping
    public ApiResponse<List<WorkoutSummaryResponse>> list(
            @CurrentUser User user,
            @RequestParam(name = "date_from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ApiResponse.of(workouts.list(user, dateFrom, dateTo));
    }

    @GetMapping("/{workoutId}")
    public ApiResponse<WorkoutResponse> get(@CurrentUser User user, @PathVariable UUID workoutId) {
        return ApiResponse.of(workouts.get(user, workoutId));
    }

    /** Full replace, including the exercise collection. */
    @PutMapping("/{workoutId}")
    public ApiResponse<WorkoutResponse> update(
            @CurrentUser User user,
            @PathVariable UUID workoutId,
            @Valid @RequestBody WorkoutRequest request) {
        return ApiResponse.of(workouts.update(user, workoutId, request));
    }

    @DeleteMapping("/{workoutId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUser User user, @PathVariable UUID workoutId) {
        workouts.delete(user, workoutId);
    }
}
