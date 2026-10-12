package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

public record PlanGenerateReq(
        @NotNull Long studentId,
        Integer totalQuestions,
        Integer perNodeLimit) {
}
