package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 确认写入题目-知识点映射。
 *
 * @param replace true 则先清掉该题原有全部映射，再用 nodeIds 覆盖
 */
public record MappingApplyReq(
        @NotNull Long questionId,
        List<Long> nodeIds,
        Boolean replace) {
}
