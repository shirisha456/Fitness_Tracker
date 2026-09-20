package com.fitnesstracker.profile.controller;

import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.profile.dto.ProfileRequest;
import com.fitnesstracker.profile.dto.ProfileResponse;
import com.fitnesstracker.profile.service.ProfileService;
import com.fitnesstracker.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    /** 404 until the user has saved a profile — the frontend branches on this. */
    @GetMapping
    public ApiResponse<ProfileResponse> get(@CurrentUser User user) {
        return ApiResponse.of(profiles.get(user));
    }

    @PutMapping
    public ApiResponse<ProfileResponse> upsert(
            @CurrentUser User user, @Valid @RequestBody ProfileRequest request) {
        return ApiResponse.of(profiles.upsert(user, request));
    }
}
