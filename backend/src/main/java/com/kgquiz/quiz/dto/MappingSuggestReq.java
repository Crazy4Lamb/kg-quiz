package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 题目知识点自动识别（单题）。
 *
 * @param graphId 限定在该图谱内召回候选；为空则在整机构知识点里检索
 * @param topK    返回几条建议，默认 3
 */
public record MappingSuggestReq(
        @NotNull Long questionId,
        Long graphId,
        Integer topK) {
}
