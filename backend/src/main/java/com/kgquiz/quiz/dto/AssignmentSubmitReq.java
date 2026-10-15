package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 学生提交作答：只交答案原文，判分由后端完成。
 */
public record AssignmentSubmitReq(
        @NotEmpty(message = "答卷不能为空") @Valid List<@NotNull Item> items) {

    public record Item(
            @NotNull Long questionId,
            /** 客观题传选项文本（多选传逗号分隔），主观题传作答文本 */
            String answer) {
    }
}
