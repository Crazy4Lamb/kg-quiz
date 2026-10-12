package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 把卷子发给哪些学生。
 */
public record PaperAssignReq(
        @NotEmpty(message = "至少选择一名学生") List<Long> studentIds) {
}
