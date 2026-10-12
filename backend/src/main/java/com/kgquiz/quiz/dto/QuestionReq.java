package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

public record QuestionReq(
        @NotBlank String subject,
        @NotBlank String textbook,
        @NotBlank String questionType,
        @NotBlank String stem,
        List<String> options,
        @NotBlank String answer,
        String analysis,
        @NotNull BigDecimal score,
        @NotNull Integer difficulty,
        @NotEmpty List<Long> nodeIds,
        /** 归属机构：机构管理员/教师留空即落自己机构；平台管理员必须指定 */
        Long orgId) {
}
