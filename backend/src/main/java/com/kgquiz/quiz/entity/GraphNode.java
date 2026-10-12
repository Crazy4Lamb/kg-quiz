package com.kgquiz.quiz.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 知识点节点。
 *
 * <p>持久化在 Neo4j 的 {@code (:KgNode)} 节点上，{@link #id} 对应节点属性 {@code uid}。</p>
 *
 * <p><b>注意</b>：{@code id} 是 BIGINT 业务键，由 MySQL 的 {@code id_seq} 分配，
 * 与旧 {@code graph_node.id} 语义一致 —— {@code question_node.node_id}、
 * {@code student_node_state.source_node_id}、{@code exam_question.node_id}、
 * {@code answer_record.node_id} 以及 Qdrant 的 point id 都引用它。</p>
 */
@Data
public class GraphNode {

    private Long id;

    private Long graphId;

    /** 所属机构（租户）；由所属图谱决定，用于跨机构隔离 */
    private Long orgId;

    private String name;

    private String description;

    private String code;

    private BigDecimal weight;

    private Integer posX;

    private Integer posY;

    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
