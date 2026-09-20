package com.fitnesstracker.auth.controller;

import com.fitnesstracker.auth.dto.ForgotPasswordRequest;
import com.fitnesstracker.auth.dto.LoginRequest;
import com.fitnesstracker.auth.dto.LogoutRequest;
import com.fitnesstracker.auth.dto.MeResponse;
import com.fitnesstracker.auth.dto.RefreshRequest;
import com.fitnesstracker.auth.dto.RegisterRequest;
import com.fitnesstracker.auth.dto.ResetPasswordRequest;
import com.fitnesstracker.auth.dto.TokenResponse;
import com.fitnesstracker.auth.dto.UserResponse;
import com.fitnesstracker.auth.dto.VerifyEmailResponse;
import com.fitnesstracker.auth.entity.User;
import com.fitnesstracker.auth.service.AuthService;
import com.fitnesstracker.auth.service.TokenRotationService;
import com.fitnesstracker.common.api.ApiResponse;
import com.fitnesstracker.profile.repository.ProfileRepository;
import com.fitnesstracker.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints. Paths, methods, status codes and {@code message} strings are copied from
 * {@code app/modules/auth/routes.py} — the Next.js BFF depends on all four.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
public class AuthController {

    private final AuthService authService;
    private final TokenRotationService rotationService;
    private final ProfileRepository profiles;

    public AuthController(
            AuthService authService,
            TokenRotationService rotationService,
            ProfileRepository profiles) {
        this.authService = authService;
        this.rotationService = rotationService;
        this.profiles = profiles;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.of(
                authService.register(request),
                "Registration successful. Please check your email to verify your account.");
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return ApiResponse.of(
                authService.login(request, userAgent(http), clientIp(http)));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(
            @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
        return ApiResponse.of(
                rotationService.rotate(request.refreshToken(), userAgent(http), clientIp(http)));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.refreshToken());
        return ApiResponse.of(null, "Logged out successfully");
    }

    @GetMapping("/verify-email")
    public ApiResponse<VerifyEmailResponse> verifyEmail(
            @RequestParam @NotBlank String token) {
        return ApiResponse.of(
                new VerifyEmailResponse(authService.verifyEmail(token)),
                "Email verified successfully");
    }

    @PostMapping("/resend-verification")
    public ApiResponse<Void> resendVerification(@CurrentUser User user) {
        authService.resendVerification(user);
        return ApiResponse.of(
                null,
                "If your email is unverified, a new verification link has been sent.");
    }

    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ApiResponse.of(
                null, "If an account exists with this email, a reset link has been sent.");
    }

    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.of(null, "Password reset successfully");
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me(@CurrentUser User user) {
        return ApiResponse.of(MeResponse.from(user, profiles.existsByUserId(user.getId())));
    }

    /**
     * The direct peer address, matching {@code request.client.host} in Starlette. nginx
     * forwards {@code X-Forwarded-For}, but the previous implementation does not read it, so
     * neither does this — the stored value stays consistent across both backends.
     */
    private static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }
}
