package com.kgquiz.quiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

/**
 * 题目批量导入请求。
 *
 * <p>教研在 Excel 里通常只写知识点<b>名称</b>，所以在 {@code nodeIds} 之外额外支持
 * {@code nodeNames}：后端会在 {@code graphId} 指定的图谱内按名称精确匹配成节点 id。
 * 两者可以混用，取并集。</p>
 */
public record QuestionBatchReq(
        Long graphId,
        /** 归属机构：机构管理员/教师留空即落自己机构；平台管理员必须指定 */
        Long orgId,
        @NotEmpty @Valid List<Item> items) {

    public record Item(
            @NotBlank String subject,
            @NotBlank String textbook,
            @NotBlank String questionType,
            @NotBlank String stem,
            List<String> options,
            @NotBlank String answer,
            String analysis,
            @NotNull BigDecimal score,
            @NotNull Integer difficulty,
            List<Long> nodeIds,
            List<String> nodeNames) {
    }
}
