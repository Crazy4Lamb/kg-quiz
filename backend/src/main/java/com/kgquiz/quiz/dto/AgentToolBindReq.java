package com.kgquiz.quiz.dto;

import java.util.List;

/**
 * 全量覆盖某个 Agent 的工具绑定。
 *
 * @param agentId  Agent
 * @param toolKeys 工具标识全集；传空列表表示解绑全部。key 必须在工具目录里存在。
 */
public record AgentToolBindReq(
        Long agentId,
        List<String> toolKeys) {
}