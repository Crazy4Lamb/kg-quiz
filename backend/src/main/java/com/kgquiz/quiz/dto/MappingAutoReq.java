package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 一键为「还没有映射」的题目识别并写入知识点。
 *
 * @param minConfidence 置信度阈值，低于该值不落库，默认 0.6
 * @param maxQuestions  本次最多处理多少题，默认 50
 * @param dryRun        只预演不落库（先看结果再决定）
 */
public record MappingAutoReq(
        @NotNull Long graphId,
        Double minConfidence,
        Integer maxQuestions,
        Boolean dryRun) {
}
