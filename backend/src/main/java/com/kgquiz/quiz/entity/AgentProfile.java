package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 编排配置（配置域的中间层）。
 *
 * <p><b>边界约定：本表不承载任何数据权限。</b>
 * 它只描述「这个 Agent 面向谁、用什么提示词、跑哪个模型」，
 * 「能访问哪些数据」永远由 Service 层按 {@code UserContext} 实时计算。</p>
 *
 * <p>{@link #systemPrompt} 一期只落库不开放在线编辑；工具绑定见 {@link AgentTool}。</p>
 */
@Data
@TableName("agent_profile")
public class AgentProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编码，唯一；种子用固定值便于前端与预演直接引用 */
    private String code;

    /** 所属机构；null 表示平台级 Agent（非 ADMIN 不可改删） */
    private Long orgId;

    private String name;

    private String description;

    /** 面向角色：STUDENT / TEACHER / ADMIN */
    private String targetRole;

    /** 系统提示词 */
    private String systemPrompt;

    /** 模型标识；留空表示用后端默认模型 */
    private String model;

    private Double temperature;

    private Integer maxTokens;

    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}