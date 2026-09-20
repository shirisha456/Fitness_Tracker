package com.fitnesstracker.profile.service;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.exception.AppException;
import com.fitnesstracker.profile.dto.ProfileRequest;
import com.fitnesstracker.profile.dto.ProfileResponse;
import com.fitnesstracker.profile.entity.Profile;
import com.fitnesstracker.profile.repository.ProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One profile per user.
 *
 * <p>{@code GET} before any {@code PUT} is a 404 — the frontend relies on that, and on
 * {@code has_profile} from {@code /auth/me}, to decide whether to show the create or the
 * edit form.
 */
@Service
public class ProfileService {

    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public ProfileResponse get(User user) {
        return profiles.findByUserId(user.getId())
                .map(ProfileResponse::from)
                .orElseThrow(() -> new AppException(
                        ErrorCode.NOT_FOUND, "Profile not found", 404));
    }

    /**
     * Upsert with full-replace semantics: an omitted field is written as NULL.
     *
     * <p>The {@code UNIQUE(user_id)} constraint is what guarantees one row; this reads
     * first rather than relying on the constraint to raise, mirroring the previous implementation.
     */
    @Transactional
    public ProfileResponse upsert(User user, ProfileRequest request) {
        Profile profile = profiles.findByUserId(user.getId())
                .orElseGet(() -> new Profile(user.getId()));
        profile.apply(request.displayName(), request.dateOfBirth(), request.sex(),
                request.heightCm(), request.fitnessGoal(), request.activityLevel());
        return ProfileResponse.from(profiles.saveAndFlush(profile));
    }
}
