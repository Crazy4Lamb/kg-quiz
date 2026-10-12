package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识点教学内容：讲义 / 例题 / 微课脚本 / 知识卡片。
 *
 * <p>由大模型生成或教师编写，按机构隔离；{@link #status} 为 DRAFT 时学生端不可见。</p>
 */
@Data
@TableName("node_content")
public class NodeContent {

    /** 内容类型 */
    public static final String T_LECTURE = "LECTURE";
    public static final String T_EXAMPLE = "EXAMPLE";
    public static final String T_MICRO_LESSON = "MICRO_LESSON";
    public static final String T_CARD = "CARD";

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

    /** Markdown 正文 */
    private String body;

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
