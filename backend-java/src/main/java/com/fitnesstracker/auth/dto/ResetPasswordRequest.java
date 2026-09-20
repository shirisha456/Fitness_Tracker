package com.fitnesstracker.auth.dto;

import com.fitnesstracker.common.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank String token,
        @NotBlank @Size(min = 8, max = 128) @StrongPassword String password,
        @NotBlank @Size(min = 8, max = 128) String passwordConfirm) {}
