package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识点学习记录流水（只追加，不改写）。
 *
 * <p>与 {@link LearningTask} 的分工：任务表记「该做什么」，本表记「实际做了什么、
 * 掌握度因此怎么变了」。任务可以被重排覆盖，记录不行 —— 推荐算法的近期重复惩罚、
 * 遗忘间隔、置信度三个因子全部依赖这张表。</p>
 */
@Data
@TableName("learning_record")
public class LearningRecord {

    /** 看了知识点讲解 */
    public static final String A_VIEW = "VIEW";
    /** 做了练习并判分 */
    public static final String A_PRACTICE = "PRACTICE";
    /** 入学 / 章节诊断 */
    public static final String A_DIAGNOSIS = "DIAGNOSIS";
    /** 复习回顾 */
    public static final String A_REVIEW = "REVIEW";
    /** 完成任务 */
    public static final String A_TASK_DONE = "TASK_DONE";
    /** 教师改分导致的掌握度修正 */
    public static final String A_ADJUST = "ADJUST";

    public static final String R_TASK = "TASK";
    public static final String R_EXAM = "EXAM";
    public static final String R_CONTENT = "CONTENT";
    public static final String R_ANSWER = "ANSWER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orgId;

    private Long studentId;

    /** 知识点；无知识点级事件（如入学诊断整体）为空 */
    private Long nodeId;

    private String action;

    private String refType;

    private Long refId;

    private Long taskId;

    private BigDecimal scoreRate;

    private Integer durationSec;

    private Integer questionCount;

    private BigDecimal masteryBefore;

    private BigDecimal masteryAfter;

    private String note;

    private LocalDateTime createdAt;
}
