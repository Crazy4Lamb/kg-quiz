package com.kgquiz.quiz.service.neo4j;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 图域业务键分配器。
 *
 * <p>Neo4j 的内部 id / elementId 会随导入、恢复、重启变化，不能作为业务引用。
 * 但 {@code question_node.node_id}、{@code student_node_state.source_node_id}、
 * {@code exam_question.node_id}、{@code answer_record.node_id}、{@code exam.plan} 里的
 * {@code items[].nodeId}，以及 Qdrant 的 point id，全部引用了图节点的数值 id。</p>
 *
 * <p>所以这里用一个极简的 MySQL 序列表来发号：行锁保证并发不重号，且发号与 Neo4j 写入解耦，
 * 不需要在 Neo4j 里维护热节点计数器。</p>
 */
@Service
@RequiredArgsConstructor
public class IdAllocator {

    public static final String SEQ_GRAPH = "graph";
    public static final String SEQ_NODE = "node";
    public static final String SEQ_RELATION = "relation";

    private final JdbcTemplate jdbcTemplate;

    /** 取下一个 id（原子，带行锁） */
    @Transactional
    public long next(String name) {
        jdbcTemplate.update(
                "INSERT INTO id_seq(name, val) VALUES(?, 0) ON DUPLICATE KEY UPDATE val = val", name);
        Long cur = jdbcTemplate.queryForObject(
                "SELECT val FROM id_seq WHERE name = ? FOR UPDATE", Long.class, name);
        long next = (cur == null ? 0L : cur) + 1L;
        jdbcTemplate.update("UPDATE id_seq SET val = ? WHERE name = ?", next, name);
        return next;
    }

    /**
     * 把序列推进到至少 {@code value}。用于种子数据导入后对齐 ——
     * 种子里是显式 id，导入后必须把序列顶上去，否则后续新建会和种子撞号。
     */
    @Transactional
    public void bumpTo(String name, long value) {
        jdbcTemplate.update("INSERT IGNORE INTO id_seq(name, val) VALUES(?, ?)", name, value);
        jdbcTemplate.update("UPDATE id_seq SET val = ? WHERE name = ? AND val < ?", value, name, value);
    }
}
