package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习优先级推荐规则。
 *
 * <p>{@link #strategy} 决定「按谁优先」，{@link #config} 里的权重决定「多想要」。
 * 分开是有意的：策略切换不该悄悄改掉用户调好的权重。</p>
 *
 * <p>{@link #config} 是一段 JSON，四组参数：</p>
 * <pre>
 * // 权重组（出题与推荐都吃）
 * {
 *   "masteryWeight": 0.7,        // 掌握缺口权重
 *   "importanceWeight": 0.3,     // 知识点重要性权重
 *   "prereqBoost": 0.15,         // 前置阻塞加成
 *   "consolidateRatio": 0.2      // 巩固题占比
 * }
 * // 阈值组
 * {
 *   "weakThreshold": 0.4,        // 低于此值算薄弱
 *   "solidThreshold": 0.7,       // 高于此值算已掌握
 *   "dailyTaskLimit": 5          // 每日知识点条数
 * }
 * // 推荐专属权重（只有学习推荐吃）
 * {
 *   "forgettingWeight": 0.2,     // 遗忘风险权重
 *   "uncertaintyWeight": 0.1,    // 低置信度权重
 *   "repetitionPenalty": 0.15    // 近期重复扣分
 * }
 * // 遗忘与置信度
 * {
 *   "forgettingHalfLifeDays": 14,// 掌握度减半所需天数
 *   "decayFloorRatio": 0.6,      // 衰减下限 = 历史峰值 × 该系数
 *   "confidenceFullPractice": 4  // 置信度达标练习次数
 * }
 * // 编排
 * {
 *   "weakQuotaRatio": 0.6,       // 薄弱保底占比
 *   "reviewQuotaRatio": 0.2,     // 复习配额占比
 *   "recentRepeatWindowDays": 2, // 近期重复窗口
 *   "maxPerParent": 2            // 同一父章节最多推几个
 * }
 * </pre>
 */
@Data
@TableName("recommend_rule")
public class RecommendRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属机构；null 表示平台默认规则 */
    private Long orgId;

    private String name;

    /** REMEDIATION 补弱优先 / SEQUENTIAL 顺序推进 / EXAM 应试优先 / BALANCED 均衡 */
    private String strategy;

    private Integer isDefault;

    private Integer enabled;

    private String config;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
