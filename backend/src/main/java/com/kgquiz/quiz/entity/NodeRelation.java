package com.kgquiz.quiz.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 节点关系。
 *
 * <p>持久化为 Neo4j 的关系，{@link #relationType} 直接作为关系类型
 * （{@code :CONTAINS} / {@code :SIMILAR} / {@code :PREREQUISITE}），
 * 这样才能写变长路径 {@code -[:PREREQUISITE*1..5]->}。
 * {@link #id} 对应关系属性 {@code uid}。</p>
 */
@Data
public class NodeRelation {

    private Long id;

    private Long graphId;

    private Long fromNodeId;

    private Long toNodeId;

    /**
     * CONTAINS / SIMILAR / PREREQUISITE
     */
    private String relationType;

    private LocalDateTime createdAt;
}
