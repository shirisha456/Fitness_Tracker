package com.fitnesstracker.progress.service;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.progress.dto.GoalRequest;
import com.fitnesstracker.progress.dto.GoalResponse;
import com.fitnesstracker.progress.dto.GoalUpdateRequest;
import com.fitnesstracker.progress.dto.MeasurementRequest;
import com.fitnesstracker.progress.dto.MeasurementResponse;
import com.fitnesstracker.progress.entity.BodyMeasurement;
import com.fitnesstracker.progress.entity.Goal;
import com.fitnesstracker.progress.entity.GoalStatus;
import com.fitnesstracker.progress.repository.BodyMeasurementRepository;
import com.fitnesstracker.progress.repository.GoalRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Body measurements and weight goals.
 *
 * <p>A port of {@code app/modules/progress/service.py}. This is tracking, not diagnosis:
 * nothing here interprets a measurement, and no threshold implies anything medical.
 */
@Service
public class ProgressService {

    private final BodyMeasurementRepository measurements;
    private final GoalRepository goals;

    public ProgressService(BodyMeasurementRepository measurements, GoalRepository goals) {
        this.measurements = measurements;
        this.goals = goals;
    }

    // --- measurements --------------------------------------------------------

    /**
     * Upsert, not insert.
     *
     * <p>{@code UNIQUE(user_id, recorded_at)} allows one check-in per day, so posting again
     * for a date the user already has updates that row in place — and still returns 201,
     * which is what the documented contract does and what the measurement form relies on.
     * Read-then-write rather than letting the constraint raise, mirroring the original.
     */
    @Transactional
    public MeasurementResponse upsertMeasurement(User user, MeasurementRequest request) {
        BodyMeasurement measurement = measurements
                .findByUserIdAndRecordedAt(user.getId(), request.recordedAt())
                .orElseGet(() -> new BodyMeasurement(user.getId(), request.recordedAt()));
        applyTo(measurement, request);
        return MeasurementResponse.from(measurements.saveAndFlush(measurement));
    }

    @Transactional(readOnly = true)
    public List<MeasurementResponse> listMeasurements(
            User user, LocalDate dateFrom, LocalDate dateTo) {
        return measurements.findForUser(user.getId(), dateFrom, dateTo).stream()
                .map(MeasurementResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public MeasurementResponse getMeasurement(User user, UUID measurementId) {
        return MeasurementResponse.from(loadOwnedMeasurement(user, measurementId));
    }

    @Transactional
    public MeasurementResponse updateMeasurement(
            User user, UUID measurementId, MeasurementRequest request) {
        BodyMeasurement measurement = loadOwnedMeasurement(user, measurementId);
        measurement.setRecordedAt(request.recordedAt());
        applyTo(measurement, request);
        return MeasurementResponse.from(measurements.saveAndFlush(measurement));
    }

    @Transactional
    public void deleteMeasurement(User user, UUID measurementId) {
        measurements.delete(loadOwnedMeasurement(user, measurementId));
    }

    private void applyTo(BodyMeasurement measurement, MeasurementRequest request) {
        measurement.apply(request.weightKg(), request.bodyFatPct(), request.waistCm(),
                request.chestCm(), request.hipsCm(), request.armCm(), request.notes());
    }

    private BodyMeasurement loadOwnedMeasurement(User user, UUID measurementId) {
        return measurements.findById(measurementId)
                .filter(measurement -> measurement.getUserId().equals(user.getId()))
                .orElseThrow(() -> new AppException(
                        ErrorCode.NOT_FOUND, "Measurement not found", 404));
    }

    // --- goals ---------------------------------------------------------------

    @Transactional
    public GoalResponse createGoal(User user, GoalRequest request) {
        Goal goal = new Goal(user.getId());
        goal.apply(request.title(), request.targetWeightKg(), request.targetDate(),
                GoalStatus.ACTIVE);
        return GoalResponse.from(goals.saveAndFlush(goal));
    }

    @Transactional(readOnly = true)
    public List<GoalResponse> listGoals(User user) {
        return goals.findByUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(GoalResponse::from).toList();
    }

    @Transactional
    public GoalResponse updateGoal(User user, UUID goalId, GoalUpdateRequest request) {
        Goal goal = loadOwnedGoal(user, goalId);
        goal.apply(request.title(), request.targetWeightKg(), request.targetDate(),
                request.statusOrDefault());
        return GoalResponse.from(goals.saveAndFlush(goal));
    }

    @Transactional
    public void deleteGoal(User user, UUID goalId) {
        goals.delete(loadOwnedGoal(user, goalId));
    }

    private Goal loadOwnedGoal(User user, UUID goalId) {
        return goals.findById(goalId)
                .filter(goal -> goal.getUserId().equals(user.getId()))
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "Goal not found", 404));
    }
}
