package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点教学内容：讲解 / 讲义 / 例题 / 微课脚本 / 知识卡片 / 教案。
 *
 * <p>由大模型生成或教师编写，按机构隔离；{@link #status} 为 DRAFT 时学生端不可见。</p>
 *
 * <p><b>{@link #T_EXPLAIN} 是讲解的唯一真源</b>：它带 {@link #storyboard} 分镜 IR，
 * {@link #body} 只是 IR 的一个 Markdown 投影；{@link #T_LESSON_PLAN} 教案引用同一份 IR。
 * 两者不会各写一遍内容，所以讲义和教案不会说法不一致。</p>
 */
@Data
@TableName("node_content")
public class NodeContent {

    /** 内容类型 */
    public static final String T_EXPLAIN = "EXPLAIN";
    public static final String T_LECTURE = "LECTURE";
    public static final String T_EXAMPLE = "EXAMPLE";
    public static final String T_MICRO_LESSON = "MICRO_LESSON";
    public static final String T_CARD = "CARD";
    /** 教案：教师备课用，引用讲解 IR，不对学生可见 */
    public static final String T_LESSON_PLAN = "LESSON_PLAN";

    public static final String S_DRAFT = "DRAFT";
    public static final String S_PUBLISHED = "PUBLISHED";

    public static final String BY_LLM = "LLM";
    public static final String BY_TEACHER = "TEACHER";
    /** 大模型不可用时的规则模板兜底（内容由图谱与题库自动拼装） */
    public static final String BY_TEMPLATE = "TEMPLATE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orgId;

    private Long graphId;

    /** 知识点（Neo4j KgNode.uid） */
    private Long nodeId;

    private String type;

    private String title;

    private String summary;

    /** Markdown 正文：IR 的一个渲染后端，不是真源 */
    private String body;

    /**
     * 讲解分镜 IR（JSON）。仅 {@link #T_EXPLAIN} 有值。
     *
     * <p>这是讲解内容的真源；{@link #body} 由它渲染而来，改内容应改这里再重渲染。</p>
     */
    private String storyboard;

    /** 内容自检报告（JSON）：结构闭合 / 题目归属 / 答案一致 / 视觉锚点 */
    private String checkReport;

    /** 自检存在 ERROR 级问题，需人工复核；阻断自动发布 */
    private Integer needsReview;

    /** LLM 大模型生成 / TEACHER 教师编写 */
    private String generatedBy;

    private String model;

    private Integer version;

    /** DRAFT 草稿 / PUBLISHED 已发布 */
    private String status;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
