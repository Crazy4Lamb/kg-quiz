package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 批量为整张图谱的知识点生成教学内容。
 *
 * @param types           要生成的内容类型列表，空则只生成讲义
 * @param onlyMissing     只给「还没有该类内容」的知识点生成，已有则跳过
 * @param extraInstruction 统一的教师补充要求
 */
public record ContentBatchReq(
        @NotNull Long graphId,
        List<String> types,
        Boolean onlyMissing,
        String extraInstruction) {
}
