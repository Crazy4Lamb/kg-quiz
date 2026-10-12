package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 教师组卷：从题库里挑选题目组成一份卷子。
 */
public record PaperCreateReq(
        @NotBlank(message = "请填写卷子名称") String name,
        String subject,
        String textbook,
        String description,
        @NotEmpty(message = "至少选择一道题") List<Long> questionIds) {
}
