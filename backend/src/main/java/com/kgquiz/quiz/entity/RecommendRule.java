package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习优先级推荐规则。
 *
 * <p>{@link #config} 是一段 JSON，形如：</p>
 * <pre>
 * {
 *   "masteryWeight": 0.7,        // 掌握度缺口权重
 *   "importanceWeight": 0.3,     // 知识点重要性（归一化权重）权重
 *   "weakThreshold": 0.4,        // 低于此值算薄弱
 *   "solidThreshold": 0.7,       // 高于此值算已掌握（进巩固池）
 *   "prereqBoost": 0.15,         // 薄弱节点的前置知识点额外加权
 *   "consolidateRatio": 0.2,     // 巩固题占比
 *   "practiceRatio": 0.5,        // 学习任务里「练」的比例
 *   "dailyTaskLimit": 5          // 每日任务条数上限
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

    private Integer isDefault;

    private Integer enabled;

    private String config;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
