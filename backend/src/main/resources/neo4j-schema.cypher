// kg-quiz 图 schema（幂等，可重复执行）
//
// 数据模型：
//   (:KgGraph {uid, orgId, name, subject, textbook, grade, term, description, createdAt, updatedAt})
//   (:KgNode  {uid, graphId, orgId, name, description, code, weight, posX, posY, sortOrder, createdAt, updatedAt})
//   ()-[r:CONTAINS|SIMILAR|PREREQUISITE]->()  {uid, graphId, createdAt}
//
//   - orgId 是机构（租户）标识：图谱与节点都带机构，所有图查询都要带上它，跨机构不可见。
//
// 说明：
//   - uid 是 BIGINT 业务键，由 MySQL 的 id_seq 表分配，与旧 graph_node.id / node_relation.id 一一对应，
//     因为 question_node.node_id、student_node_state.source_node_id、exam_question.node_id、
//     answer_record.node_id、exam.plan JSON 以及 Qdrant 的 point id 都引用了这个数值。
//     Neo4j 内部 id / elementId 不稳定，绝不能用。
//   - 关系用独立类型而非 type 属性，这样可以直接写 -[:PREREQUISITE*1..5]->。

CREATE CONSTRAINT kg_graph_uid IF NOT EXISTS FOR (g:KgGraph) REQUIRE g.uid IS UNIQUE;
// 图谱维度的唯一性按机构隔离：不同机构可各自维护「人教版 · 数学 · 五年级 · 上学期」，
// 但同一机构内不允许重复。旧的全局唯一约束（kg_graph_dim）因此废弃。
DROP CONSTRAINT kg_graph_dim IF EXISTS;
CREATE CONSTRAINT kg_graph_dim_org IF NOT EXISTS FOR (g:KgGraph) REQUIRE (g.orgId, g.subject, g.textbook, g.grade, g.term) IS UNIQUE;
CREATE CONSTRAINT kg_node_uid IF NOT EXISTS FOR (n:KgNode) REQUIRE n.uid IS UNIQUE;
CREATE CONSTRAINT kg_contains_uid IF NOT EXISTS FOR ()-[r:CONTAINS]-() REQUIRE r.uid IS UNIQUE;
CREATE CONSTRAINT kg_similar_uid IF NOT EXISTS FOR ()-[r:SIMILAR]-() REQUIRE r.uid IS UNIQUE;
CREATE CONSTRAINT kg_prereq_uid IF NOT EXISTS FOR ()-[r:PREREQUISITE]-() REQUIRE r.uid IS UNIQUE;

CREATE INDEX kg_graph_org IF NOT EXISTS FOR (g:KgGraph) ON (g.orgId);
CREATE INDEX kg_node_org IF NOT EXISTS FOR (n:KgNode) ON (n.orgId);
CREATE INDEX kg_node_graph IF NOT EXISTS FOR (n:KgNode) ON (n.graphId);
CREATE INDEX kg_node_name IF NOT EXISTS FOR (n:KgNode) ON (n.name);
CREATE INDEX kg_node_code IF NOT EXISTS FOR (n:KgNode) ON (n.code);
CREATE INDEX kg_graph_subject IF NOT EXISTS FOR (g:KgGraph) ON (g.subject);
CREATE INDEX kg_contains_graph IF NOT EXISTS FOR ()-[r:CONTAINS]-() ON (r.graphId);
CREATE INDEX kg_similar_graph IF NOT EXISTS FOR ()-[r:SIMILAR]-() ON (r.graphId);
CREATE INDEX kg_prereq_graph IF NOT EXISTS FOR ()-[r:PREREQUISITE]-() ON (r.graphId);
