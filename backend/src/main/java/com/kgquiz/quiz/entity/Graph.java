package com.kgquiz.quiz.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识图谱（模板）。
 *
 * <p>持久化在 Neo4j 的 {@code (:KgGraph)} 节点上，{@link #id} 对应节点属性 {@code uid}。</p>
 */
@Data
public class Graph {

    private Long id;

    /** 所属机构（租户）；图谱按机构隔离 */
    private Long orgId;

    private String name;

    private String subject;

    private String textbook;

    private String grade;

    private String term;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
