package com.kgquiz.quiz.dto;

import java.util.Map;

/**
 * 保存推荐规则。
 *
 * @param id        为空则新建；不为空则更新该条
 * @param orgId     归属机构；平台管理员不传则落平台级（orgId = null）
 * @param name      规则名称
 * @param config    配置项（masteryWeight / importanceWeight / weakThreshold /
 *                  solidThreshold / prereqBoost / consolidateRatio / dailyTaskLimit）
 * @param enabled   是否启用
 * @param isDefault 是否为该机构的默认规则（同机构只保留一条）
 */
public record RecommendRuleReq(
        Long id,
        Long orgId,
        String name,
        Map<String, Object> config,
        Boolean enabled,
        Boolean isDefault) {
}
