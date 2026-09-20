package com.fitnesstracker.nutrition.controller;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.nutrition.dto.DailyNutritionSummary;
import com.fitnesstracker.nutrition.dto.MealRequest;
import com.fitnesstracker.nutrition.dto.MealResponse;
import com.fitnesstracker.nutrition.dto.WaterEntryRequest;
import com.fitnesstracker.nutrition.dto.WaterEntryResponse;
import com.fitnesstracker.nutrition.service.NutritionService;
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

/**
 * Meals, water entries and the daily summary.
 *
 * <p>Three route prefixes on one controller because that is how the Python router groups
 * them ({@code meals_router}, {@code water_router}, {@code nutrition_router}) and the
 * paths are the contract.
 */
@RestController
@RequestMapping("/api/v1")
public class NutritionController {

    private final NutritionService nutrition;

    public NutritionController(NutritionService nutrition) {
        this.nutrition = nutrition;
    }

    // --- meals ---------------------------------------------------------------

    @PostMapping("/meals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MealResponse> createMeal(
            @CurrentUser User user, @Valid @RequestBody MealRequest request) {
        return ApiResponse.of(nutrition.createMeal(user, request));
    }

    @GetMapping("/meals")
    public ApiResponse<List<MealResponse>> listMeals(
            @CurrentUser User user,
            @RequestParam(name = "date_from", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        return ApiResponse.of(nutrition.listMeals(user, dateFrom, dateTo));
    }

    @GetMapping("/meals/{mealId}")
    public ApiResponse<MealResponse> getMeal(@CurrentUser User user, @PathVariable UUID mealId) {
        return ApiResponse.of(nutrition.getMeal(user, mealId));
    }

    @PutMapping("/meals/{mealId}")
    public ApiResponse<MealResponse> updateMeal(
            @CurrentUser User user,
            @PathVariable UUID mealId,
            @Valid @RequestBody MealRequest request) {
        return ApiResponse.of(nutrition.updateMeal(user, mealId, request));
    }

    @DeleteMapping("/meals/{mealId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMeal(@CurrentUser User user, @PathVariable UUID mealId) {
        nutrition.deleteMeal(user, mealId);
    }

    // --- water ---------------------------------------------------------------

    @PostMapping("/water-entries")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WaterEntryResponse> createWaterEntry(
            @CurrentUser User user, @Valid @RequestBody WaterEntryRequest request) {
        return ApiResponse.of(nutrition.createWaterEntry(user, request));
    }

    @GetMapping("/water-entries")
    public ApiResponse<List<WaterEntryResponse>> listWaterEntries(
            @CurrentUser User user,
            @RequestParam(name = "date", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.of(nutrition.listWaterEntries(user, date));
    }

    /** There is no update endpoint for water — create and delete only, as in Python. */
    @DeleteMapping("/water-entries/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWaterEntry(@CurrentUser User user, @PathVariable UUID entryId) {
        nutrition.deleteWaterEntry(user, entryId);
    }

    // --- summary -------------------------------------------------------------

    @GetMapping("/nutrition/summary")
    public ApiResponse<DailyNutritionSummary> summary(
            @CurrentUser User user,
            @RequestParam(name = "date")
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.of(nutrition.dailySummary(user, date));
    }
}
