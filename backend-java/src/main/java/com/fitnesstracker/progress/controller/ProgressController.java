package com.fitnesstracker.progress.controller;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.progress.dto.GoalRequest;
import com.fitnesstracker.progress.dto.GoalResponse;
import com.fitnesstracker.progress.dto.GoalUpdateRequest;
import com.fitnesstracker.progress.dto.MeasurementRequest;
import com.fitnesstracker.progress.dto.MeasurementResponse;
import com.fitnesstracker.progress.service.ProgressService;
import com.fitnesstracker.security.CurrentUser;
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

/** Body measurements and goals, on the two route prefixes the Python router uses. */
@RestController
@RequestMapping("/api/v1")
public class ProgressController {

    private final ProgressService progress;

    public ProgressController(ProgressService progress) {
        this.progress = progress;
    }

    // --- measurements --------------------------------------------------------

    /** 201 even when it updated an existing day's row — see {@code ProgressService}. */
    @PostMapping("/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MeasurementResponse> createMeasurement(
            @CurrentUser User user, @Valid @RequestBody MeasurementRequest request) {
        return ApiResponse.of(progress.upsertMeasurement(user, request));
    }

    @GetMapping("/measurements")
    public ApiResponse<List<MeasurementResponse>> listMeasurements(
            @CurrentUser User user,
            @RequestParam(name = "date_from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ApiResponse.of(progress.listMeasurements(user, dateFrom, dateTo));
    }

    @GetMapping("/measurements/{measurementId}")
    public ApiResponse<MeasurementResponse> getMeasurement(
            @CurrentUser User user, @PathVariable UUID measurementId) {
        return ApiResponse.of(progress.getMeasurement(user, measurementId));
    }

    @PutMapping("/measurements/{measurementId}")
    public ApiResponse<MeasurementResponse> updateMeasurement(
            @CurrentUser User user,
            @PathVariable UUID measurementId,
            @Valid @RequestBody MeasurementRequest request) {
        return ApiResponse.of(progress.updateMeasurement(user, measurementId, request));
    }

    @DeleteMapping("/measurements/{measurementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMeasurement(@CurrentUser User user, @PathVariable UUID measurementId) {
        progress.deleteMeasurement(user, measurementId);
    }

    // --- goals ---------------------------------------------------------------

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GoalResponse> createGoal(
            @CurrentUser User user, @Valid @RequestBody GoalRequest request) {
        return ApiResponse.of(progress.createGoal(user, request));
    }

    @GetMapping("/goals")
    public ApiResponse<List<GoalResponse>> listGoals(@CurrentUser User user) {
        return ApiResponse.of(progress.listGoals(user));
    }

    @PutMapping("/goals/{goalId}")
    public ApiResponse<GoalResponse> updateGoal(
            @CurrentUser User user,
            @PathVariable UUID goalId,
            @Valid @RequestBody GoalUpdateRequest request) {
        return ApiResponse.of(progress.updateGoal(user, goalId, request));
    }

    @DeleteMapping("/goals/{goalId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGoal(@CurrentUser User user, @PathVariable UUID goalId) {
        progress.deleteGoal(user, goalId);
    }
}
