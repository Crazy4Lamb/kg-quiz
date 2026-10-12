package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

public record GraphCreateReq(
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String textbook,
        @NotBlank String grade,
        @NotBlank String term,
        String description,
        /**
         * 归属机构。仅平台管理员需要填（它可跨机构建图谱）；
         * 机构管理员/教师留空即可，后端一律落在其所属机构。
         */
        Long orgId) {
}
