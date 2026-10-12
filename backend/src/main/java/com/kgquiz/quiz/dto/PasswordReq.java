package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordReq(
        String oldPassword,
        @NotBlank String newPassword) {
}
