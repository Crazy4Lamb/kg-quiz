package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.Graph;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.entity.QuestionNode;
import com.kgquiz.quiz.mapper.QuestionMapper;
import com.kgquiz.quiz.mapper.QuestionNodeMapper;
import com.kgquiz.quiz.service.embedding.EmbeddingService;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import com.kgquiz.quiz.service.qdrant.QdrantClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题目自动识别知识点映射。
 *
 * <p>两道工序：</p>
 * <ol>
 *   <li><b>召回</b>：拿题干向量在 Qdrant 里找最相近的知识点（候选集）。Qdrant 不可用时
 *       退化为「字面 bigram 相似度」在本地算分，功能不中断。</li>
 *   <li><b>判定</b>：把候选集交给大模型，让它结合题干、选项、答案挑出真正对应的知识点
 *       并给出置信度与理由。未配置大模型时可以只走召回的相似度阈值。</li>
 * </ol>
 *
 * <p>识别结果<b>不直接落库</b>，先回给教研确认，再由 {@link #apply} 写入映射关系。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuestionMappingService {

    /** 召回候选数量 */
    private static final int RECALL_TOPK = 8;
    /** 纯向量模式下的相似度阈值（低于此值不作为建议） */
    private static final double VECTOR_MIN_SCORE = 0.35;
    /** 本地 bigram 兜底模式的相似度阈值 */
    private static final double LOCAL_MIN_SCORE = 0.12;
    /** 本地兜底时最多参与打分的节点数（避免大图谱拖慢） */
    private static final int LOCAL_SCAN_LIMIT = 400;
    /** 召回打分：语义分与字面分的融合权重 */
    private static final double SEMANTIC_WEIGHT = 0.65;
    private static final double LOCAL_WEIGHT = 0.35;

    private final QuestionMapper questionMapper;
    private final QuestionNodeMapper questionNodeMapper;
    private final GraphStore graphStore;
    private final EmbeddingService embeddingService;
    private final QdrantClient qdrantClient;
    private final VectorIndexService vectorIndexService;
    private final LlmClient llm;

    // ==================== 识别 ====================

    /**
     * 给单道题识别知识点。
     *
     * @param graphId 限定在该图谱内召回；为空则在整个机构的知识点里找
     * @param topK    返回几条建议，默认 3
     */
    public Map<String, Object> suggest(Long questionId, Long graphId, Integer topK) {
        Question question = requireQuestion(questionId);
        int k = topK == null ? 3 : Math.max(1, Math.min(8, topK));

        String text = questionText(question);
        List<Candidate> candidates = recall(text, graphId, question.getOrgId());

        List<Map<String, Object>> candidateViews = new ArrayList<>();
        for (Candidate c : candidates) {
            candidateViews.add(Map.of(
                    "nodeId", c.node.getId(),
                    "nodeName", c.node.getName(),
                    "nodeDescription", nz(c.node.getDescription()),
                    "graphId", c.node.getGraphId(),
                    "score", round4(c.score),
                    "source", c.source));
        }

        List<Map<String, Object>> matches;
        String mode;
        String notice;
        if (llm.available() && !candidates.isEmpty()) {
            try {
                matches = judgeByLlm(question, candidates, k);
                mode = "LLM";
                notice = "已由大模型结合题干与候选知识点判定";
            } catch (Exception e) {
                log.warn("大模型判定失败，退回相似度阈值：{}", e.getMessage());
                matches = judgeByScore(candidates, k);
                mode = "VECTOR";
                notice = "大模型判定失败（" + e.getMessage() + "），已退回相似度阈值";
            }
        } else {
            matches = judgeByScore(candidates, k);
            mode = "VECTOR";
            notice = llm.available()
                    ? "没有召回任何候选知识点，请检查题库与图谱是否已建立向量索引"
                    : llm.unavailableReason() + "，已按语义相似度给出建议";
        }

        Set<Long> current = currentNodeIds(questionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionId", questionId);
        result.put("stem", question.getStem());
        result.put("questionType", question.getQuestionType());
        result.put("graphId", graphId);
        result.put("mode", mode);
        result.put("llmAvailable", llm.available());
        result.put("semanticAvailable", vectorIndexService.available());
        result.put("notice", notice);
        result.put("candidates", candidateViews);
        result.put("matches", matches);
        result.put("currentNodeIds", current);
        return result;
    }

    /** 批量识别（题库列表用；只识别还没有映射的题） */
    public Map<String, Object> batchSuggest(List<Long> questionIds, Long graphId, Integer topK,
                                            boolean onlyUnmapped) {
        if (questionIds == null || questionIds.isEmpty()) {
            throw new BizException("请选择至少一道题");
        }
        List<Map<String, Object>> results = new ArrayList<>();
        int skipped = 0;
        for (Long id : questionIds) {
            Question q = questionMapper.selectById(id);
            if (q == null) {
                continue;
            }
            if (q.getOrgId() != null) {
                UserContext.checkOrgScope(q.getOrgId());
            }
            if (onlyUnmapped && !currentNodeIds(id).isEmpty()) {
                skipped++;
                continue;
            }
            try {
                results.add(suggest(id, graphId, topK));
            } catch (Exception e) {
                log.warn("题目 #{} 识别失败：{}", id, e.getMessage());
                results.add(Map.of("questionId", id, "error", String.valueOf(e.getMessage())));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", results.size());
        out.put("skipped", skipped);
        out.put("mode", llm.available() ? "LLM" : "VECTOR");
        out.put("results", results);
        return out;
    }

    /** 一键把「还没有任何知识点映射」的题目识别并写入映射 */
    @Transactional
    public Map<String, Object> autoMapUnmapped(Long graphId, Double minConfidence, Integer maxQuestions,
                                               boolean dryRun) {
        if (graphId == null) {
            throw new BizException("请指定要映射到哪张图谱");
        }
        if (graphStore.findGraph(graphId) == null) {
            throw new BizException("图谱不存在: " + graphId);
        }
        // 阈值的量纲随识别模式而变：大模型给的是「判断确定的程度」（0-1 主观值），
        // 纯向量给的是「语义 + 字面融合相似度」（短中文题干通常只有 0.35-0.5），
        // 所以未显式指定时按模式取不同默认值，否则纯向量模式下一个都映射不上。
        double threshold = minConfidence == null
                ? (llm.available() ? 0.6 : 0.45)
                : Math.max(0, Math.min(1, minConfidence));
        int cap = maxQuestions == null ? 50 : Math.max(1, Math.min(500, maxQuestions));

        Set<Long> mapped = new HashSet<>();
        for (QuestionNode link : questionNodeMapper.selectList(new LambdaQueryWrapper<>())) {
            mapped.add(link.getQuestionId());
        }
        List<Long> nodeIds = graphStore.listNodes(graphId).stream().map(GraphNode::getId).toList();
        Long orgId = graphStore.findGraph(graphId).getOrgId();
        List<Question> pool = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(orgId != null, Question::getOrgId, orgId)
                .orderByDesc(Question::getId));

        List<Map<String, Object>> applied = new ArrayList<>();
        int scanned = 0;
        int skippedMapped = 0;
        for (Question q : pool) {
            if (applied.size() >= cap) {
                break;
            }
            if (mapped.contains(q.getId())) {
                skippedMapped++;
                continue;
            }
            scanned++;
            List<Map<String, Object>> matches;
            try {
                matches = (List<Map<String, Object>>) suggest(q.getId(), graphId, 3).get("matches");
            } catch (Exception e) {
                continue;
            }
            List<Long> accepted = new ArrayList<>();
            for (Map<String, Object> m : matches) {
                Object conf = m.get("confidence");
                double c = conf instanceof Number n ? n.doubleValue() : 0;
                Long nid = m.get("nodeId") instanceof Number n ? n.longValue() : null;
                if (nid != null && c >= threshold && nodeIds.contains(nid)) {
                    accepted.add(nid);
                }
            }
            if (accepted.isEmpty()) {
                continue;
            }
            if (!dryRun) {
                apply(q.getId(), accepted, false);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questionId", q.getId());
            row.put("stem", abbreviate(q.getStem()));
            row.put("nodeIds", accepted);
            row.put("matches", matches);
            applied.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphId", graphId);
        out.put("dryRun", dryRun);
        out.put("scanned", scanned);
        out.put("skippedMapped", skippedMapped);
        out.put("mapped", applied.size());
        out.put("threshold", threshold);
        out.put("mode", llm.available() ? "LLM" : "VECTOR");
        out.put("items", applied);
        return out;
    }

    // ==================== 写入映射 ====================

    /**
     * 把识别结果写入题目-知识点映射。
     *
     * @param replace true 则先清掉该题原有的全部映射
     */
    @Transactional
    public Map<String, Object> apply(Long questionId, List<Long> nodeIds, boolean replace) {
        Question question = requireQuestion(questionId);
        LinkedHashSet<Long> targets = new LinkedHashSet<>();
        if (nodeIds != null) {
            targets.addAll(nodeIds);
        }
        if (replace) {
            questionNodeMapper.delete(new LambdaQueryWrapper<QuestionNode>()
                    .eq(QuestionNode::getQuestionId, questionId));
        } else {
            targets.removeAll(currentNodeIds(questionId));
        }
        int added = 0;
        for (Long nodeId : targets) {
            GraphNode node = graphStore.findNode(nodeId);
            if (node == null) {
                throw new BizException("知识点不存在: " + nodeId);
            }
            UserContext.checkOrgScope(node.getOrgId());
            QuestionNode link = new QuestionNode();
            link.setQuestionId(questionId);
            link.setNodeId(nodeId);
            questionNodeMapper.insert(link);
            added++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionId", questionId);
        result.put("added", added);
        result.put("nodeIds", currentNodeIds(questionId));
        result.put("orgId", question.getOrgId());
        return result;
    }

    @Transactional
    public Map<String, Object> unmap(Long questionId, Long nodeId) {
        requireQuestion(questionId);
        questionNodeMapper.delete(new LambdaQueryWrapper<QuestionNode>()
                .eq(QuestionNode::getQuestionId, questionId)
                .eq(QuestionNode::getNodeId, nodeId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionId", questionId);
        result.put("nodeIds", currentNodeIds(questionId));
        return result;
    }

    // ==================== 召回 ====================

    private record Candidate(GraphNode node, double score, String source) {
    }

    /** 召回候选知识点：优先语义检索，不可用时退化为本地 bigram 相似度 */
    private List<Candidate> recall(String text, Long graphId, Long orgId) {
        if (vectorIndexService.available()) {
            try {
                List<Float> vector = embeddingService.embed(text);
                List<Map<String, Object>> raw = qdrantClient.search(vector, RECALL_TOPK * 2, graphId);
                List<Candidate> out = new ArrayList<>();
                for (Map<String, Object> hit : qdrantClient.extractHits(raw)) {
                    Long id = hit.get("id") instanceof Number n ? n.longValue() : null;
                    double score = hit.get("score") instanceof Number n ? n.doubleValue() : 0;
                    if (id == null || score < 0.2) {
                        continue;
                    }
                    GraphNode node = graphStore.findNode(id);
                    if (node == null) {
                        continue;
                    }
                    if (orgId != null && node.getOrgId() != null && !orgId.equals(node.getOrgId())) {
                        continue;
                    }
                    out.add(new Candidate(node, score, "semantic"));
                }
                if (!out.isEmpty()) {
                    // 纯语义分对短中文题干不够稳（小模型容易把「乘」和「除」拉到一起），
                    // 再融合字面 bigram 相似度，共同决定候选顺序
                    Set<String> query = bigrams(text);
                    List<Candidate> blended = new ArrayList<>();
                    for (Candidate c : out) {
                        double local = cosine(query, bigrams(
                                c.node().getName() + " " + nz(c.node().getDescription())));
                        blended.add(new Candidate(c.node(),
                                SEMANTIC_WEIGHT * c.score() + LOCAL_WEIGHT * local, "semantic"));
                    }
                    blended.sort(Comparator.comparingDouble(Candidate::score).reversed());
                    return blended.size() <= RECALL_TOPK ? blended : blended.subList(0, RECALL_TOPK);
                }
            } catch (Exception e) {
                log.warn("语义召回失败，退回本地相似度：{}", e.getMessage());
            }
        }
        return recallLocally(text, graphId, orgId);
    }

    /** 本地兜底：拿题干与「知识点名 + 说明」做字符 bigram 相似度 */
    private List<Candidate> recallLocally(String text, Long graphId, Long orgId) {
        List<GraphNode> nodes = new ArrayList<>();
        if (graphId != null) {
            nodes.addAll(graphStore.listNodes(graphId));
        } else {
            for (Graph g : graphStore.listGraphs(orgId, null, null, null, null)) {
                nodes.addAll(graphStore.listNodes(g.getId()));
            }
        }
        if (nodes.size() > LOCAL_SCAN_LIMIT) {
            nodes = nodes.subList(0, LOCAL_SCAN_LIMIT);
        }
        Set<String> q = bigrams(text);
        List<Candidate> out = new ArrayList<>();
        for (GraphNode node : nodes) {
            String target = node.getName() + " " + nz(node.getDescription());
            double score = cosine(q, bigrams(target));
            if (score >= LOCAL_MIN_SCORE) {
                out.add(new Candidate(node, score, "local"));
            }
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed());
        return out.size() <= RECALL_TOPK ? out : out.subList(0, RECALL_TOPK);
    }

    // ==================== 判定 ====================

    /** 大模型判定：只在候选集里挑，杜绝编造不存在于图谱的知识点 */
    private List<Map<String, Object>> judgeByLlm(Question question, List<Candidate> candidates, int topK) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 题目 ===\n");
        sb.append("题型：").append(question.getQuestionType()).append("\n");
        sb.append("题干：").append(nz(question.getStem())).append("\n");
        if (question.getOptions() != null && !question.getOptions().isBlank()) {
            sb.append("选项：").append(question.getOptions()).append("\n");
        }
        sb.append("参考答案：").append(nz(question.getAnswer())).append("\n");
        if (question.getAnalysis() != null && !question.getAnalysis().isBlank()) {
            sb.append("解析：").append(question.getAnalysis()).append("\n");
        }
        sb.append("\n=== 候选知识点（只能从这里选）===\n");
        for (Candidate c : candidates) {
            sb.append("- id=").append(c.node.getId())
                    .append(" 名称：").append(c.node.getName());
            if (c.node.getDescription() != null && !c.node.getDescription().isBlank()) {
                sb.append("；说明：").append(abbreviate(c.node.getDescription()));
            }
            sb.append("\n");
        }
        sb.append("\n请判断这道题考查了上面哪些知识点，按相关度从高到低最多给 ")
                .append(topK).append(" 个。\n")
                .append("要求：\n")
                .append("1. 只能使用上面出现过的 id，不要编造；\n")
                .append("2. 如果这道题与任何候选都不匹配，返回空的 matches 数组；\n")
                .append("3. confidence 为 0-1 之间的数值，代表你有多确定；\n")
                .append("4. reason 用一句中文说明依据（题目里的什么信息指向该知识点）。\n")
                .append("输出 JSON：{\"matches\":[{\"nodeId\":1,\"confidence\":0.9,\"reason\":\"...\"}]}");

        String system = """
                你是一位 K-12 教研专家，负责把题目归到知识图谱的知识点上。
                你只输出 JSON，不要输出任何解释文字或 markdown 代码围栏。
                """;
        String text = llm.chat(system, sb.toString());
        JsonNode root = LlmClient.extractJson(text);
        JsonNode arr = root.path("matches");
        Set<Long> allowed = new HashSet<>();
        Map<Long, Candidate> byId = new LinkedHashMap<>();
        for (Candidate c : candidates) {
            allowed.add(c.node.getId());
            byId.put(c.node.getId(), c);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode m : arr) {
                long nid = m.path("nodeId").asLong(-1);
                if (nid <= 0 || !allowed.contains(nid)) {
                    continue;
                }
                double conf = m.path("confidence").asDouble(0.8);
                conf = Math.max(0, Math.min(1, conf));
                GraphNode node = byId.get(nid).node;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("nodeId", nid);
                row.put("nodeName", node.getName());
                row.put("confidence", round4(conf));
                row.put("reason", m.path("reason").asText(""));
                row.put("source", "LLM");
                row.put("score", round4(byId.get(nid).score));
                out.add(row);
                if (out.size() >= topK) {
                    break;
                }
            }
        }
        return out;
    }

    /** 无大模型时：按相似度阈值取前 topK，置信度即相似度 */
    private List<Map<String, Object>> judgeByScore(List<Candidate> candidates, int topK) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Candidate c : candidates) {
            double min = "local".equals(c.source()) ? LOCAL_MIN_SCORE : VECTOR_MIN_SCORE;
            if (c.score() < min) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", c.node.getId());
            row.put("nodeName", c.node.getName());
            row.put("confidence", round4(c.score()));
            row.put("reason", "local".equals(c.source())
                    ? "题干与知识点名称 / 说明的字面相似度 " + pct(c.score())
                    : "题干与知识点的综合相似度（语义 + 字面）" + pct(c.score()));
            row.put("source", "local".equals(c.source()) ? "LOCAL" : "VECTOR");
            row.put("score", round4(c.score()));
            out.add(row);
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    // ==================== 内部工具 ====================

    private Question requireQuestion(Long questionId) {
        Question q = questionMapper.selectById(questionId);
        if (q == null) {
            throw new BizException("题目不存在: " + questionId);
        }
        if (q.getOrgId() != null) {
            UserContext.checkOrgScope(q.getOrgId());
        } else if (!UserContext.require().isAdmin()) {
            throw new BizException("题目不存在: " + questionId);
        }
        return q;
    }

    private Set<Long> currentNodeIds(Long questionId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        questionNodeMapper.selectList(new LambdaQueryWrapper<QuestionNode>()
                        .eq(QuestionNode::getQuestionId, questionId))
                .forEach(l -> ids.add(l.getNodeId()));
        return ids;
    }

    /** 题目的可检索文本：题干 + 选项（题型信息帮助语义定位） */
    private String questionText(Question q) {
        StringBuilder sb = new StringBuilder(nz(q.getStem()));
        if (q.getOptions() != null && !q.getOptions().isBlank()) {
            sb.append("\n").append(q.getOptions());
        }
        return sb.toString();
    }

    private static Set<String> bigrams(String text) {
        String s = text == null ? "" : text.replaceAll("\\s+", "");
        Set<String> set = new HashSet<>();
        for (int i = 0; i + 1 < s.length(); i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    private static double cosine(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String s : a) {
            if (b.contains(s)) {
                inter++;
            }
        }
        return inter / Math.sqrt((double) a.size() * b.size());
    }

    private static String pct(double v) {
        return Math.round(v * 1000) / 10.0 + "%";
    }

    private static double round4(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
