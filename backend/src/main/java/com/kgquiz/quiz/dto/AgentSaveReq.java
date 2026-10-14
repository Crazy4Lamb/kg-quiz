package com.kgquiz.quiz.dto;

/**
 * 保存 Agent 编排配置。
 *
 * <p><b>注意这里没有任何「数据范围」字段</b> —— 那是后端鉴权的事，
 * 不该由配置决定。</p>
 *
 * @param id            为空则新建；不为空则更新该条
 * @param code          业务编码（新建时必填，唯一）
 * @param orgId         归属机构；平台管理员不传则落平台级
 * @param name          名称
 * @param description   一句话说明
 * @param targetRole    面向角色 STUDENT / TEACHER / ADMIN
 * @param systemPrompt  系统提示词（二期开放在线编辑）
 * @param model         模型标识，留空用后端默认
 * @param temperature   采样温度
 * @param maxTokens     单次响应上限
 * @param enabled       是否启用
 */
public record AgentSaveReq(
        Long id,
        String code,
        Long orgId,
        String name,
        String description,
        String targetRole,
        String systemPrompt,
        String model,
        Double temperature,
        Integer maxTokens,
        Boolean enabled) {
}