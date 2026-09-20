package com.fitnesstracker.nutrition.service;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.nutrition.dto.DailyNutritionSummary;
import com.fitnesstracker.nutrition.dto.MealRequest;
import com.fitnesstracker.nutrition.dto.MealResponse;
import com.fitnesstracker.nutrition.dto.MealTotals;
import com.fitnesstracker.nutrition.dto.WaterEntryRequest;
import com.fitnesstracker.nutrition.dto.WaterEntryResponse;
import com.fitnesstracker.nutrition.entity.Meal;
import com.fitnesstracker.nutrition.entity.WaterEntry;
import com.fitnesstracker.nutrition.repository.MealRepository;
import com.fitnesstracker.nutrition.repository.WaterEntryRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Meals, water and the daily summary.
 *
 * <p>A direct port of {@code app/modules/nutrition/service.py}. No new behaviour: the
 * migration brief is explicit that nutrition features are not to be invented here.
 * Ownership is checked per row, and a row belonging to someone else is 404.
 */
@Service
public class NutritionService {

    private final MealRepository meals;
    private final WaterEntryRepository waterEntries;

    public NutritionService(MealRepository meals, WaterEntryRepository waterEntries) {
        this.meals = meals;
        this.waterEntries = waterEntries;
    }

    // --- meals ---------------------------------------------------------------

    @Transactional
    public MealResponse createMeal(User user, MealRequest request) {
        Meal meal = new Meal(user.getId());
        applyTo(meal, request);
        return MealResponse.from(meals.saveAndFlush(meal));
    }

    @Transactional(readOnly = true)
    public List<MealResponse> listMeals(User user, LocalDate dateFrom, LocalDate dateTo) {
        return meals.findForUser(user.getId(), dateFrom, dateTo).stream()
                .map(MealResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MealResponse getMeal(User user, UUID mealId) {
        return MealResponse.from(loadOwnedMeal(user, mealId));
    }

    /** Full replace — omitted macros become null, as PUT semantics require. */
    @Transactional
    public MealResponse updateMeal(User user, UUID mealId, MealRequest request) {
        Meal meal = loadOwnedMeal(user, mealId);
        applyTo(meal, request);
        return MealResponse.from(meals.saveAndFlush(meal));
    }

    @Transactional
    public void deleteMeal(User user, UUID mealId) {
        meals.delete(loadOwnedMeal(user, mealId));
    }

    private void applyTo(Meal meal, MealRequest request) {
        meal.apply(request.name(), request.loggedAt(), request.calories(),
                request.proteinG(), request.carbsG(), request.fatG(), request.notes());
    }

    private Meal loadOwnedMeal(User user, UUID mealId) {
        return meals.findById(mealId)
                .filter(meal -> meal.getUserId().equals(user.getId()))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Meal not found", 404));
    }

    // --- water ---------------------------------------------------------------

    @Transactional
    public WaterEntryResponse createWaterEntry(User user, WaterEntryRequest request) {
        return WaterEntryResponse.from(waterEntries.saveAndFlush(
                new WaterEntry(user.getId(), request.loggedAt(), request.amountMl())));
    }

    @Transactional(readOnly = true)
    public List<WaterEntryResponse> listWaterEntries(User user, LocalDate day) {
        return waterEntries.findForUser(user.getId(), day).stream()
                .map(WaterEntryResponse::from).toList();
    }

    @Transactional
    public void deleteWaterEntry(User user, UUID entryId) {
        WaterEntry entry = waterEntries.findById(entryId)
                .filter(candidate -> candidate.getUserId().equals(user.getId()))
                .orElseThrow(() -> new AppException(
                        ErrorCode.NOT_FOUND, "Water entry not found", 404));
        waterEntries.delete(entry);
    }

    // --- summary -------------------------------------------------------------

    /**
     * Two aggregate queries, not a load-and-sum in Java.
     *
     * <p>Summing in the database keeps the work proportional to the day, not to the user's
     * whole history, and is what the previous implementation does.
     */
    @Transactional(readOnly = true)
    public DailyNutritionSummary dailySummary(User user, LocalDate day) {
        MealTotals totals = meals.totalsForDay(user.getId(), day);
        int water = waterEntries.totalMlForDay(user.getId(), day);
        return new DailyNutritionSummary(
                day,
                (int) totals.calories(),
                totals.proteinG(),
                totals.carbsG(),
                totals.fatG(),
                water);
    }
}
