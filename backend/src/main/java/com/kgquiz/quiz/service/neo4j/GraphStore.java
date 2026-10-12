package com.kgquiz.quiz.service.neo4j;

import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeRelation;
import com.kgquiz.quiz.entity.RelationType;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.MapAccessor;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 图存储：全部 Cypher 收敛在这里。
 *
 * <p>数据模型：</p>
 * <pre>
 *   (:KgGraph {uid, name, subject, textbook, grade, term, description, createdAt, updatedAt})
 *   (:KgNode  {uid, graphId, name, description, code, weight, posX, posY, sortOrder, createdAt, updatedAt})
 *   (:KgNode)-[:CONTAINS|SIMILAR|PREREQUISITE {uid, graphId, createdAt}]->(:KgNode)
 * </pre>
 *
 * <p>两点设计取舍：</p>
 * <ol>
 *   <li><b>关系用独立类型而非 type 属性</b> —— 这样才能直接写 {@code -[:PREREQUISITE*1..5]->}；
 *       代价是关系类型无法参数化（Cypher 语法限制），所以类型必须过 {@link #requireType} 白名单校验后再拼接。</li>
 *   <li><b>uid 是 BIGINT 业务键</b>，由 MySQL 的 {@code id_seq} 分配，不用 Neo4j 内部 id / elementId。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphStore {

    private final Driver driver;
    private final KgProperties kg;
    private final IdAllocator idAllocator;

    private static final String SCHEMA_FILE = "neo4j-schema.cypher";
    private static final String LABEL_GRAPH = "KgGraph";
    private static final String LABEL_NODE = "KgNode";

    // ---------------- 启动 ----------------

    @PostConstruct
    public void init() {
        if (!kg.getNeo4j().isEnabled()) {
            log.warn("Neo4j 未启用（kg.neo4j.enabled=false），图相关功能不可用");
            return;
        }
        try {
            driver.verifyConnectivity();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "无法连接 Neo4j（" + kg.getNeo4j().getUri() + "）: " + e.getMessage()
                            + " —— 请确认实例已启动；可用 NEO4J_URI / NEO4J_USER / NEO4J_PASSWORD 覆盖配置。", e);
        }
        applySchema();
        log.info("Neo4j 就绪: uri={}, database={}, 现有 {} 图谱 / {} 节点 / {} 关系",
                kg.getNeo4j().getUri(), kg.getNeo4j().getDatabase(),
                countGraphs(), countNodes(), countRelations());
    }

    /** 应用 {@code neo4j-schema.cypher}（约束与索引，全部 IF NOT EXISTS，可重复执行） */
    private void applySchema() {
        int n = executeScript(readResource(SCHEMA_FILE));
        log.debug("Neo4j schema 已同步（{} 条 DDL）", n);
    }

    /** 执行一段 Cypher 脚本：按 {@code ;} 切分，跳过空行与 {@code //} 注释行 */
    public int executeScript(String script) {
        List<String> statements = new ArrayList<>();
        for (String st : stripComments(script).split(";")) {
            String q = st.trim();
            if (!q.isEmpty()) {
                statements.add(q);
            }
        }
        write(tx -> {
            for (String q : statements) {
                tx.run(q).consume();
            }
            return null;
        });
        return statements.size();
    }

    /** 从 classpath 执行 Cypher 脚本 */
    public int executeScriptFromClasspath(String path) {
        return executeScript(readResource(path));
    }

    private static String readResource(String path) {
        StringBuilder sb = new StringBuilder();
        try (var in = new ClassPathResource(path).getInputStream();
             var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("读取 " + path + " 失败: " + e.getMessage(), e);
        }
        return sb.toString();
    }

    private static String stripComments(String script) {
        StringBuilder sb = new StringBuilder();
        for (String line : script.split("\n")) {
            if (line.trim().startsWith("//")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ---------------- 图谱 ----------------

    /**
     * 列图谱。{@code orgId} 非空时只返回该机构的图谱；
     * 平台管理员（ADMIN）传 null 表示跨机构查看。
     */
    public List<Graph> listGraphs(Long orgId, String subject, String textbook, String grade, String term) {
        StringBuilder c = new StringBuilder("MATCH (g:" + LABEL_GRAPH + ") WHERE 1 = 1");
        Map<String, Object> p = new HashMap<>();
        if (orgId != null) {
            c.append(" AND g.orgId = $orgId");
            p.put("orgId", orgId);
        }
        if (notBlank(subject)) {
            c.append(" AND g.subject = $subject");
            p.put("subject", subject);
        }
        if (notBlank(textbook)) {
            c.append(" AND g.textbook = $textbook");
            p.put("textbook", textbook);
        }
        if (notBlank(grade)) {
            c.append(" AND g.grade = $grade");
            p.put("grade", grade);
        }
        if (notBlank(term)) {
            c.append(" AND g.term = $term");
            p.put("term", term);
        }
        c.append(" RETURN g ORDER BY g.uid DESC");
        String cypher = c.toString();
        return read(tx -> {
            List<Graph> out = new ArrayList<>();
            for (Record r : tx.run(cypher, p).list()) {
                out.add(toGraph(r.get("g").asNode()));
            }
            return out;
        });
    }

    /** 该机构内的「学科/教材/年级/学期」四维是否已存在（对应 kg_graph_dim_org 唯一约束） */
    public boolean graphDimensionExists(Long orgId, String subject, String textbook, String grade, String term) {
        Map<String, Object> p = new HashMap<>();
        p.put("orgId", orgId);
        p.put("subject", nz(subject));
        p.put("textbook", nz(textbook));
        p.put("grade", nz(grade));
        p.put("term", nz(term));
        return read(tx -> tx.run(
                        "MATCH (g:" + LABEL_GRAPH + " {orgId: $orgId, subject: $subject, textbook: $textbook, "
                                + "grade: $grade, term: $term}) RETURN count(g) AS c", p)
                .single().get("c").asLong() > 0);
    }

    public Graph findGraph(Long uid) {
        return read(tx -> {
            var res = tx.run("MATCH (g:" + LABEL_GRAPH + " {uid: $uid}) RETURN g", Map.of("uid", uid));
            return res.hasNext() ? toGraph(res.next().get("g").asNode()) : null;
        });
    }

    public Graph insertGraph(Graph g) {
        LocalDateTime now = LocalDateTime.now();
        g.setId(idAllocator.next(IdAllocator.SEQ_GRAPH));
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        write(tx -> tx.run(
                "CREATE (g:" + LABEL_GRAPH + " {uid: $uid, orgId: $orgId, name: $name, subject: $subject, "
                        + "textbook: $textbook, grade: $grade, term: $term, description: $description, "
                        + "createdAt: $createdAt, updatedAt: $updatedAt})", graphParams(g)).consume());
        return g;
    }

    public void updateGraph(Graph g) {
        g.setUpdatedAt(LocalDateTime.now());
        write(tx -> tx.run(
                "MATCH (g:" + LABEL_GRAPH + " {uid: $uid}) "
                        + "SET g.name = $name, g.description = $description, g.updatedAt = $updatedAt",
                graphParams(g)).consume());
    }

    /** 删除图谱连同其全部节点与关系（关系随节点的 DETACH DELETE 一并消失） */
    public void deleteGraph(Long uid) {
        write(tx -> {
            tx.run("MATCH (n:" + LABEL_NODE + " {graphId: $uid}) DETACH DELETE n", Map.of("uid", uid)).consume();
            tx.run("MATCH (g:" + LABEL_GRAPH + " {uid: $uid}) DETACH DELETE g", Map.of("uid", uid)).consume();
            return null;
        });
    }

    public long countGraphs() {
        return read(tx -> tx.run("MATCH (g:" + LABEL_GRAPH + ") RETURN count(g) AS c").single().get("c").asLong());
    }

    /** 某机构下的图谱数 */
    public long countGraphs(Long orgId) {
        if (orgId == null) {
            return countGraphs();
        }
        return read(tx -> tx.run("MATCH (g:" + LABEL_GRAPH + " {orgId: $orgId}) RETURN count(g) AS c",
                Map.of("orgId", orgId)).single().get("c").asLong());
    }

    /**
     * 图域各类实体当前的最大 uid。种子数据用的是显式 uid，
     * 导入后要据此把 {@code id_seq} 顶上去，否则后续新建会撞号。
     */
    public Map<String, Long> maxUids() {
        return read(tx -> {
            Map<String, Long> m = new HashMap<>();
            m.put(IdAllocator.SEQ_GRAPH, tx.run(
                    "MATCH (g:" + LABEL_GRAPH + ") RETURN coalesce(max(g.uid), 0) AS v").single().get("v").asLong());
            m.put(IdAllocator.SEQ_NODE, tx.run(
                    "MATCH (n:" + LABEL_NODE + ") RETURN coalesce(max(n.uid), 0) AS v").single().get("v").asLong());
            m.put(IdAllocator.SEQ_RELATION, tx.run(
                    "MATCH ()-[r]->() RETURN coalesce(max(r.uid), 0) AS v").single().get("v").asLong());
            return m;
        });
    }

    // ---------------- 节点 ----------------

    public List<GraphNode> listNodes(Long graphId) {
        return read(tx -> {
            List<GraphNode> out = new ArrayList<>();
            for (Record r : tx.run(
                    "MATCH (n:" + LABEL_NODE + " {graphId: $graphId}) RETURN n ORDER BY n.sortOrder ASC, n.uid ASC",
                    Map.of("graphId", graphId)).list()) {
                out.add(toNode(r.get("n").asNode()));
            }
            return out;
        });
    }

    public GraphNode findNode(Long uid) {
        return read(tx -> {
            var res = tx.run("MATCH (n:" + LABEL_NODE + " {uid: $uid}) RETURN n", Map.of("uid", uid));
            return res.hasNext() ? toNode(res.next().get("n").asNode()) : null;
        });
    }

    /** 按节点子集取节点（自适应出题、报告等场景用） */
    public List<GraphNode> findNodes(Collection<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            return List.of();
        }
        return read(tx -> {
            List<GraphNode> out = new ArrayList<>();
            for (Record r : tx.run("MATCH (n:" + LABEL_NODE + ") WHERE n.uid IN $uids RETURN n",
                    Map.of("uids", List.copyOf(uids))).list()) {
                out.add(toNode(r.get("n").asNode()));
            }
            return out;
        });
    }

    public GraphNode insertNode(GraphNode n) {
        LocalDateTime now = LocalDateTime.now();
        n.setId(idAllocator.next(IdAllocator.SEQ_NODE));
        n.setCreatedAt(now);
        n.setUpdatedAt(now);
        Map<String, Object> p = nodeParams(n);
        write(tx -> tx.run(
                "CREATE (n:" + LABEL_NODE + " {uid: $uid, graphId: $graphId, orgId: $orgId, name: $name, "
                        + "description: $description, "
                        + "code: $code, weight: $weight, posX: $posX, posY: $posY, sortOrder: $sortOrder, "
                        + "createdAt: $createdAt, updatedAt: $updatedAt})", p).consume());
        return n;
    }

    /** 批量插入（UNWIND 单条语句，比逐条 CREATE 少很多往返） */
    public List<GraphNode> insertNodes(List<GraphNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (GraphNode n : nodes) {
            n.setId(idAllocator.next(IdAllocator.SEQ_NODE));
            n.setCreatedAt(now);
            n.setUpdatedAt(now);
            rows.add(nodeParams(n));
        }
        write(tx -> tx.run(
                "UNWIND $rows AS row CREATE (n:" + LABEL_NODE + " {uid: row.uid, graphId: row.graphId, "
                        + "orgId: row.orgId, "
                        + "name: row.name, description: row.description, code: row.code, weight: row.weight, "
                        + "posX: row.posX, posY: row.posY, sortOrder: row.sortOrder, "
                        + "createdAt: row.createdAt, updatedAt: row.updatedAt})",
                Map.of("rows", rows)).consume());
        return nodes;
    }

    public void updateNode(GraphNode n) {
        n.setUpdatedAt(LocalDateTime.now());
        write(tx -> tx.run(
                "MATCH (n:" + LABEL_NODE + " {uid: $uid}) SET n.name = $name, n.description = $description, "
                        + "n.code = $code, n.weight = $weight, n.posX = $posX, n.posY = $posY, "
                        + "n.sortOrder = $sortOrder, n.updatedAt = $updatedAt", nodeParams(n)).consume());
    }

    /** 只更新画布坐标（拖拽保存，避免整节点回写） */
    public void updateNodePosition(Long uid, Integer posX, Integer posY) {
        write(tx -> tx.run("MATCH (n:" + LABEL_NODE + " {uid: $uid}) SET n.posX = $posX, n.posY = $posY",
                Map.of("uid", uid, "posX", posX == null ? 0 : posX, "posY", posY == null ? 0 : posY)).consume());
    }

    /** 删除节点，其所有关系随之消失（DETACH DELETE） */
    public void deleteNode(Long uid) {
        write(tx -> tx.run("MATCH (n:" + LABEL_NODE + " {uid: $uid}) DETACH DELETE n", Map.of("uid", uid)).consume());
    }

    public long countNodes() {
        return read(tx -> tx.run("MATCH (n:" + LABEL_NODE + ") RETURN count(n) AS c").single().get("c").asLong());
    }

    /** 字面检索：名称或描述包含关键词（等价于原来的 SQL LIKE %kw%）；orgId 非空时限定机构 */
    public List<GraphNode> searchNodes(String keyword, Long orgId, Long graphId, int limit) {
        StringBuilder c = new StringBuilder("MATCH (n:" + LABEL_NODE
                + ") WHERE (n.name CONTAINS $kw OR n.description CONTAINS $kw)");
        Map<String, Object> p = new HashMap<>();
        p.put("kw", keyword == null ? "" : keyword);
        if (orgId != null) {
            c.append(" AND n.orgId = $orgId");
            p.put("orgId", orgId);
        }
        if (graphId != null) {
            c.append(" AND n.graphId = $graphId");
            p.put("graphId", graphId);
        }
        c.append(" RETURN n ORDER BY n.uid ASC LIMIT $limit");
        p.put("limit", limit);
        String cypher = c.toString();
        return read(tx -> {
            List<GraphNode> out = new ArrayList<>();
            for (Record r : tx.run(cypher, p).list()) {
                out.add(toNode(r.get("n").asNode()));
            }
            return out;
        });
    }

    // ---------------- 关系 ----------------

    public List<NodeRelation> listRelations(Long graphId) {
        return read(tx -> {
            List<NodeRelation> out = new ArrayList<>();
            for (Record r : tx.run(
                    "MATCH (a:" + LABEL_NODE + ")-[r]->(b:" + LABEL_NODE + ") WHERE r.graphId = $graphId "
                            + "RETURN r, a.uid AS fromUid, b.uid AS toUid, type(r) AS relType ORDER BY r.uid ASC",
                    Map.of("graphId", graphId)).list()) {
                out.add(toRelation(r));
            }
            return out;
        });
    }

    /**
     * 按关系类型取关系。类型下推到 Cypher（{@code -[:PREREQUISITE]->}），
     * 不必把全部关系捞回来再在应用层过滤 —— 这是换 Neo4j 之后能顺手拿到的收益。
     */
    public List<NodeRelation> listRelationsByType(Long graphId, String relationType) {
        String type = requireType(relationType);
        return read(tx -> {
            List<NodeRelation> out = new ArrayList<>();
            for (Record r : tx.run("MATCH (a:" + LABEL_NODE + ")-[r:" + type + "]->(b:" + LABEL_NODE + ") "
                    + "WHERE r.graphId = $graphId "
                    + "RETURN r, a.uid AS fromUid, b.uid AS toUid, type(r) AS relType ORDER BY r.uid ASC",
                    Map.of("graphId", graphId)).list()) {
                out.add(toRelation(r));
            }
            return out;
        });
    }

    public NodeRelation findRelation(Long uid) {
        return read(tx -> {
            var res = tx.run("MATCH (a:" + LABEL_NODE + ")-[r]->(b:" + LABEL_NODE + ") WHERE r.uid = $uid "
                            + "RETURN r, a.uid AS fromUid, b.uid AS toUid, type(r) AS relType",
                    Map.of("uid", uid));
            return res.hasNext() ? toRelation(res.next()) : null;
        });
    }

    public NodeRelation insertRelation(NodeRelation rel) {
        rel.setId(idAllocator.next(IdAllocator.SEQ_RELATION));
        rel.setCreatedAt(LocalDateTime.now());
        String type = requireType(rel.getRelationType());
        Map<String, Object> p = Map.of(
                "uid", rel.getId(),
                "graphId", rel.getGraphId(),
                "fromUid", rel.getFromNodeId(),
                "toUid", rel.getToNodeId(),
                "createdAt", dtStr(rel.getCreatedAt()));
        write(tx -> tx.run(
                "MATCH (a:" + LABEL_NODE + " {uid: $fromUid}), (b:" + LABEL_NODE + " {uid: $toUid}) "
                        + "CREATE (a)-[:" + type + " {uid: $uid, graphId: $graphId, createdAt: $createdAt}]->(b)", p)
                .consume());
        return rel;
    }

    /**
     * 批量插入关系。Cypher 不允许参数化关系类型，所以先按类型分组，
     * 每组一条 UNWIND 语句。
     */
    public List<NodeRelation> insertRelations(List<NodeRelation> relations) {
        if (relations == null || relations.isEmpty()) {
            return List.of();
        }
        Map<String, List<Map<String, Object>>> byType = new LinkedHashMap<>();
        for (NodeRelation rel : relations) {
            rel.setId(idAllocator.next(IdAllocator.SEQ_RELATION));
            rel.setCreatedAt(LocalDateTime.now());
            String type = requireType(rel.getRelationType());
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(Map.of(
                    "uid", rel.getId(),
                    "graphId", rel.getGraphId(),
                    "fromUid", rel.getFromNodeId(),
                    "toUid", rel.getToNodeId(),
                    "createdAt", dtStr(rel.getCreatedAt())));
        }
        write(tx -> {
            for (var e : byType.entrySet()) {
                tx.run("UNWIND $rows AS row MATCH (a:" + LABEL_NODE + " {uid: row.fromUid}), "
                        + "(b:" + LABEL_NODE + " {uid: row.toUid}) "
                        + "CREATE (a)-[:" + e.getKey() + " {uid: row.uid, graphId: row.graphId, "
                        + "createdAt: row.createdAt}]->(b)", Map.of("rows", e.getValue())).consume();
            }
            return null;
        });
        return relations;
    }

    /**
     * 改写关系。Cypher 里关系类型与方向都不可变，所以统一按「先删后建」处理，
     * 两条语句在同一个写事务内，uid 保持不变。
     */
    public void replaceRelation(NodeRelation rel) {
        String type = requireType(rel.getRelationType());
        Map<String, Object> p = Map.of(
                "uid", rel.getId(),
                "graphId", rel.getGraphId(),
                "fromUid", rel.getFromNodeId(),
                "toUid", rel.getToNodeId(),
                "createdAt", dtStr(rel.getCreatedAt() == null ? LocalDateTime.now() : rel.getCreatedAt()));
        write(tx -> {
            tx.run("MATCH ()-[r]->() WHERE r.uid = $uid DELETE r", Map.of("uid", rel.getId())).consume();
            tx.run("MATCH (a:" + LABEL_NODE + " {uid: $fromUid}), (b:" + LABEL_NODE + " {uid: $toUid}) "
                    + "CREATE (a)-[:" + type + " {uid: $uid, graphId: $graphId, createdAt: $createdAt}]->(b)", p)
                    .consume();
            return null;
        });
    }

    public void deleteRelation(Long uid) {
        write(tx -> tx.run("MATCH ()-[r]->() WHERE r.uid = $uid DELETE r", Map.of("uid", uid)).consume());
    }

    /** 同向同类型关系计数（去重用） */
    public long countRelation(Long graphId, Long fromUid, Long toUid, String relationType) {
        String type = requireType(relationType);
        return read(tx -> tx.run(
                "MATCH (a:" + LABEL_NODE + " {uid: $fromUid})-[r:" + type + "]->(b:" + LABEL_NODE + " {uid: $toUid}) "
                        + "WHERE r.graphId = $graphId RETURN count(r) AS c",
                Map.of("fromUid", fromUid, "toUid", toUid, "graphId", graphId)).single().get("c").asLong());
    }

    public long countRelations() {
        return read(tx -> tx.run("MATCH ()-[r]->() RETURN count(r) AS c").single().get("c").asLong());
    }

    /** 清空整个图域（种子数据重灌前用） */
    public void clearAll() {
        write(tx -> {
            tx.run("MATCH (n) DETACH DELETE n").consume();
            return null;
        });
    }

    // ---------------- 事务辅助 ----------------

    private Session session() {
        return driver.session(SessionConfig.forDatabase(kg.getNeo4j().getDatabase()));
    }

    private <T> T read(Function<TransactionContext, T> fn) {
        try (Session s = session()) {
            return s.executeRead(fn::apply);
        }
    }

    private <T> T write(Function<TransactionContext, T> fn) {
        try (Session s = session()) {
            return s.executeWrite(fn::apply);
        }
    }

    // ---------------- 映射 ----------------

    private static Map<String, Object> graphParams(Graph g) {
        Map<String, Object> p = new HashMap<>();
        p.put("uid", g.getId());
        p.put("orgId", g.getOrgId());
        p.put("name", nz(g.getName()));
        p.put("subject", nz(g.getSubject()));
        p.put("textbook", nz(g.getTextbook()));
        p.put("grade", nz(g.getGrade()));
        p.put("term", nz(g.getTerm()));
        p.put("description", nz(g.getDescription()));
        p.put("createdAt", dtStr(g.getCreatedAt()));
        p.put("updatedAt", dtStr(g.getUpdatedAt()));
        return p;
    }

    private static Map<String, Object> nodeParams(GraphNode n) {
        Map<String, Object> p = new HashMap<>();
        p.put("uid", n.getId());
        p.put("graphId", n.getGraphId());
        p.put("orgId", n.getOrgId());
        p.put("name", nz(n.getName()));
        p.put("description", nz(n.getDescription()));
        p.put("code", nz(n.getCode()));
        p.put("weight", n.getWeight() == null ? 1.0d : n.getWeight().doubleValue());
        p.put("posX", n.getPosX() == null ? 0 : n.getPosX());
        p.put("posY", n.getPosY() == null ? 0 : n.getPosY());
        p.put("sortOrder", n.getSortOrder() == null ? 0 : n.getSortOrder());
        p.put("createdAt", dtStr(n.getCreatedAt()));
        p.put("updatedAt", dtStr(n.getUpdatedAt()));
        return p;
    }

    private static Graph toGraph(Node n) {
        Graph g = new Graph();
        g.setId(n.get("uid").asLong());
        g.setOrgId(lng(n, "orgId"));
        g.setName(str(n, "name"));
        g.setSubject(str(n, "subject"));
        g.setTextbook(str(n, "textbook"));
        g.setGrade(str(n, "grade"));
        g.setTerm(str(n, "term"));
        g.setDescription(str(n, "description"));
        g.setCreatedAt(dt(n, "createdAt"));
        g.setUpdatedAt(dt(n, "updatedAt"));
        return g;
    }

    private static GraphNode toNode(Node n) {
        GraphNode g = new GraphNode();
        g.setId(n.get("uid").asLong());
        g.setGraphId(lng(n, "graphId"));
        g.setOrgId(lng(n, "orgId"));
        g.setName(str(n, "name"));
        g.setDescription(str(n, "description"));
        g.setCode(str(n, "code"));
        g.setWeight(dec(n, "weight"));
        g.setPosX(intg(n, "posX"));
        g.setPosY(intg(n, "posY"));
        g.setSortOrder(intg(n, "sortOrder"));
        g.setCreatedAt(dt(n, "createdAt"));
        g.setUpdatedAt(dt(n, "updatedAt"));
        return g;
    }

    private static NodeRelation toRelation(Record r) {
        Relationship rel = r.get("r").asRelationship();
        NodeRelation nr = new NodeRelation();
        nr.setId(rel.get("uid").asLong());
        nr.setGraphId(lng(rel, "graphId"));
        nr.setFromNodeId(r.get("fromUid").asLong());
        nr.setToNodeId(r.get("toUid").asLong());
        nr.setRelationType(r.get("relType").asString());
        nr.setCreatedAt(dt(rel, "createdAt"));
        return nr;
    }

    // ---------------- 取值辅助 ----------------

    private static String str(MapAccessor m, String key) {
        Value v = m.get(key);
        return v == null || v.isNull() ? null : v.asString();
    }

    private static Long lng(MapAccessor m, String key) {
        Value v = m.get(key);
        return v == null || v.isNull() ? null : v.asLong();
    }

    private static Integer intg(MapAccessor m, String key) {
        Value v = m.get(key);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static BigDecimal dec(MapAccessor m, String key) {
        Value v = m.get(key);
        return v == null || v.isNull() ? null : BigDecimal.valueOf(v.asDouble());
    }

    private static LocalDateTime dt(MapAccessor m, String key) {
        String s = str(m, key);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }
        try {
            // 兼容 Neo4j datetime() 输出的带 Z / 偏移量格式
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignored) {
            // 继续尝试其它格式
        }
        try {
            return LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String dtStr(LocalDateTime v) {
        return v == null ? null : v.toString();
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /**
     * 关系类型白名单校验。Cypher 无法参数化关系类型，类型必须拼进语句，
     * 所以这里是**注入防线**，绝不能省。
     */
    private static String requireType(String type) {
        if (!RelationType.CONTAINS.equals(type) && !RelationType.SIMILAR.equals(type)
                && !RelationType.PREREQUISITE.equals(type)) {
            throw new IllegalArgumentException("不支持的图关系类型: " + type);
        }
        return type;
    }
}
