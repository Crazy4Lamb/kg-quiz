package com.kgquiz.quiz.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 新建知识点节点请求。
 *
 * <p>在 {@link NodeReq} 的基础上增加可选的 {@code link}：创建节点的同时与某个已有节点
 * （锚点）建立一条关系。整个过程在同一事务内完成，任一环节失败则节点与关系一起回滚，
 * 不会留下孤立节点或悬空关系。</p>
 */
public record NodeCreateReq(
        @NotBlank String name,
        String description,
        String code,
        BigDecimal weight,
        Integer posX,
        Integer posY,
        Integer sortOrder,
        @Valid Link link) {

    /**
     * 新节点与锚点节点的关联。
     *
     * @param anchorNodeId   锚点节点 id（图谱中已存在的知识点）
     * @param relationType   CONTAINS / SIMILAR / PREREQUISITE（后置即 PREREQUISITE 的反向）
     * @param anchorIsSource 关系方向：true = 锚点 → 新节点，false = 新节点 → 锚点；缺省 true
     */
    public record Link(
            @NotNull Long anchorNodeId,
            @NotBlank String relationType,
            Boolean anchorIsSource) {
    }
}
