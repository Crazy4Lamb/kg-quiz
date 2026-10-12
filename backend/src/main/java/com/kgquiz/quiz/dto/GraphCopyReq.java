package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 复制图谱请求：以某个模板图谱为蓝本，复制出全部节点与关系，落到新的
 * 学科/教材/年级/学期 维度下（例如把「人教 五年级上」复制成「人教 五年级下」再改）。
 */
public record GraphCopyReq(
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String textbook,
        @NotBlank String grade,
        @NotBlank String term,
        String description) {
}
