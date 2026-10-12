package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 教师批改：逐题改分 + 评语。提交即定稿。
 */
public record GradeReq(
        @NotEmpty(message = "没有要批改的题目") List<Item> items) {

    public record Item(
            @NotNull Long questionId,
            @NotNull @DecimalMin(value = "0", message = "分数不能为负") BigDecimal score,
            String comment) {
    }
}
