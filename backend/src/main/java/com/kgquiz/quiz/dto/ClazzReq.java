package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 班级新增 / 编辑。
 *
 * <p>年级 / 教材 / 学期放在班级上，用于定位该班对应的知识图谱维度。</p>
 */
public record ClazzReq(
        @NotNull Long campusId,
        @NotBlank String name,
        String grade,
        String subject,
        String textbook,
        String term,
        Long headTeacherId) {
}
