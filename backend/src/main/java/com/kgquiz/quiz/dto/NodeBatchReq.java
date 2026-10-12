package com.kgquiz.quiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

/**
 * 知识点批量导入请求。
 *
 * <p>前端把教研在 Excel 里整理好的大纲（用缩进表达层级）解析成 items，{@code level}
 * 即缩进层级：level 0 是顶层，level n 的父节点是它上方最近的 level n-1 节点。
 * 后端据此自动建立 CONTAINS 关系，无需人工一条条连。</p>
 *
 * <p>{@code parentNodeId} 可选：指定后，所有 level 0 的节点都挂到该已存在节点之下，
 * 用于「往某个章节里批量补知识点」。</p>
 */
public record NodeBatchReq(
        @NotEmpty @Valid List<Item> items,
        Long parentNodeId) {

    public record Item(
            @NotBlank String name,
            String description,
            String code,
            BigDecimal weight,
            Integer level) {
    }
}
