package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用观测记录，喂给工具注册表的「调用次数 / 成功率 / 平均耗时」。
 *
 * <p>一期只有上下文预演会产生记录；接入真实 Agent 运行时后同表复用。</p>
 */
@Data
@TableName("agent_call_log")
public class AgentCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    private String toolKey;

    private Long operatorUserId;

    /** 预演时的目标学生；真实调用为会话学生 */
    private Long targetStudentId;

    private Integer ok;

    private Long costMs;

    private String error;

    private LocalDateTime createdAt;
}