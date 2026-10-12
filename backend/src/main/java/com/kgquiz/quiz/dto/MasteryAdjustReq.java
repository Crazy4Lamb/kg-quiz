package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 人工修正某学生在某知识点上的掌握度（老师干预）。
 * 掌握度范围 0-1，置 0 即为重置。
 */
public record MasteryAdjustReq(
        @NotNull Long nodeId,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal mastery) {
}
