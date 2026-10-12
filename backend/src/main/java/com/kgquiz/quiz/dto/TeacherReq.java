package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 教师新增 / 编辑。
 *
 * <p>{@code clazzIds} 是该教师带的班（可多班），{@code subject} 是授课科目（空表示班主任 / 全科）。
 * 新建时 {@code username} / {@code password} 必填；编辑时 {@code username} 只读、{@code password} 忽略。</p>
 */
public record TeacherReq(
        String username,
        String password,
        @NotBlank String displayName,
        Long campusId,
        String subject,
        List<Long> clazzIds) {
}
