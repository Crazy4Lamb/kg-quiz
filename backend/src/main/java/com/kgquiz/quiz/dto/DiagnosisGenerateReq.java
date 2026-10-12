package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 生成入学诊断卷。
 *
 * @param perNode      每个知识点最多抽几题（默认 1，上限 3）
 * @param maxQuestions 整卷题目上限（默认 20）
 */
public record DiagnosisGenerateReq(
        @NotNull Long studentId,
        Integer perNode,
        Integer maxQuestions) {
}
