package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record NodeReq(
        @NotBlank String name,
        String description,
        String code,
        BigDecimal weight,
        Integer posX,
        Integer posY,
        Integer sortOrder) {
}
