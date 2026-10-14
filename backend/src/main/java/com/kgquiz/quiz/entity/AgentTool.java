package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Agent 与工具的绑定。
 *
 * <p>这里的 {@code enabled} 只表达「这个 Agent 是否挂载了这个工具」，
 * 即<b>功能可见性</b>；它<b>不</b>表达数据权限。
 * Agent 拿到工具后能访问什么，仍由 Service 层按 {@code UserContext} 判定。</p>
 */
@Data
@TableName("agent_tool")
public class AgentTool {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long agentId;

    /** 工具标识，对应 {@code AgentToolCatalog} 中的条目 */
    private String toolKey;

    private Integer enabled;

    /** 工具级参数覆盖 JSON，如 {"minConfidence":0.6} */
    private String paramOverride;

    private Integer sortOrder;
}