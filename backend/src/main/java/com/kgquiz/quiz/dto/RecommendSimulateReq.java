package com.kgquiz.quiz.dto;

import java.util.Map;

/**
 * 沙盒模拟：用草稿配置算一遍推荐，与当前生效配置对照。
 *
 * <p>不落库 —— 目的是让管理员在保存之前就看到「改了会怎样」，
 * 而不是保存完再去学生身上验证。</p>
 *
 * @param studentId 模拟对象
 * @param strategy  草稿策略；不传按 BALANCED
 * @param config    草稿参数；为空则用该策略的权重预设
 * @param limit     返回条数；不传用规则里的 dailyTaskLimit
 */
public record RecommendSimulateReq(
        Long studentId,
        String strategy,
        Map<String, Object> config,
        Integer limit) {
}
