package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 班级新增 / 编辑。
 *
 * <p>年级 / 教材 / 学期放在班级上，用于定位该班对应的知识图谱维度。</p>
 *
 * <p>{@code parentClazzId} 表达班级分层：非空表示本班挂靠在哪个班下面
 * （平行班 / 实验班挂在常规班之下，或「年级组」性质的班收纳下属班）。
 * 为空表示顶级班级。<b>只支持一层</b>，不支持任意深度树 —— 传父班的父班会被拒绝。</p>
 */
public record ClazzReq(
        @NotNull Long campusId,
        @NotBlank String name,
        String grade,
        String subject,
        String textbook,
        String term,
        Long headTeacherId,
        Long parentClazzId) {
}
