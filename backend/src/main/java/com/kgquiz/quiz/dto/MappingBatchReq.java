package com.kgquiz.quiz.dto;

import java.util.List;

/**
 * 题目知识点自动识别（批量）。
 *
 * @param onlyUnmapped 只识别「当前还没有任何知识点映射」的题，已有映射的跳过
 */
public record MappingBatchReq(
        List<Long> questionIds,
        Long graphId,
        Integer topK,
        Boolean onlyUnmapped) {
}
