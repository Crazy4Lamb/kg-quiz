package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeContent;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.mapper.NodeContentMapper;
import com.kgquiz.quiz.service.neo4j.GraphStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识点教学内容生成与编辑。
 *
 * <p>优先走大模型生成；未配置 API Key 或调用失败时<b>降级为规则模板</b>
 * （用知识点说明 + 前置/后续关系 + 已有配套题目自动拼装），并明确标注
 * {@code generatedBy=TEMPLATE}，保证没有 Key 也能演示完整链路。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentService {

    private static final List<String> ALL_TYPES = List.of(
            NodeContent.T_LECTURE, NodeContent.T_EXAMPLE, NodeContent.T_MICRO_LESSON, NodeContent.T_CARD);

    private static final Map<String, String> TYPE_LABEL = Map.of(
            NodeContent.T_LECTURE, "讲义",
            NodeContent.T_EXAMPLE, "例题精讲",
            NodeContent.T_MICRO_LESSON, "微课脚本",
            NodeContent.T_CARD, "知识卡片");

    /** 仅用于把库里存成 JSON 数组字符串的题目选项还原成列表 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NodeContentMapper nodeContentMapper;
    private final GraphStore graphStore;
    private final NodeContextService nodeContextService;
    private final LlmClient llm;

    /** 合法的内容类型，供 Controller 校验 */
    public List<String> types() {
        return ALL_TYPES;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", llm.available());
        m.put("model", llm.model());
        m.put("reason", llm.available() ? "" : llm.unavailableReason());
        m.put("types", ALL_TYPES.stream()
                .map(t -> Map.of("value", t, "label", TYPE_LABEL.get(t))).toList());
        return m;
    }

    // ==================== 查询 ====================

    /** 教师视角：某知识点的全部内容（含草稿） */
    public Map<String, Object> listByNode(Long nodeId) {
        GraphNode node = graphStore.findNode(nodeId);
        if (node == null) {
            throw new BizException("知识点不存在: " + nodeId);
        }
        UserContext.checkOrgScope(node.getOrgId());
        List<NodeContent> items = nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                .eq(NodeContent::getNodeId, nodeId)
                .orderByAsc(NodeContent::getType)
                .orderByDesc(NodeContent::getVersion)
                .orderByDesc(NodeContent::getId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodeId", nodeId);
        result.put("nodeName", node.getName());
        result.put("nodeDescription", node.getDescription());
        result.put("items", items.stream().map(this::toView).toList());
        result.put("types", ALL_TYPES.stream()
                .map(t -> Map.of("value", t, "label", TYPE_LABEL.get(t))).toList());
        return result;
    }

    /** 图谱级内容一览：哪些知识点有内容 / 是草稿还是已发布 */
    public Map<String, Object> listByGraph(Long graphId) {
        List<GraphNode> nodes = graphStore.listNodes(graphId);
        List<Long> nodeIds = nodes.stream().map(GraphNode::getId).toList();
        List<NodeContent> all = nodeIds.isEmpty() ? List.of()
                : nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                .in(NodeContent::getNodeId, nodeIds)
                .orderByDesc(NodeContent::getId));

        Map<Long, List<NodeContent>> byNode = new LinkedHashMap<>();
        for (NodeContent c : all) {
            byNode.computeIfAbsent(c.getNodeId(), k -> new ArrayList<>()).add(c);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GraphNode node : nodes) {
            List<NodeContent> items = byNode.getOrDefault(node.getId(), List.of());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeId", node.getId());
            row.put("nodeName", node.getName());
            row.put("nodeDescription", node.getDescription());
            row.put("contentCount", items.size());
            row.put("publishedCount", items.stream()
                    .filter(c -> NodeContent.S_PUBLISHED.equals(c.getStatus())).count());
            row.put("types", items.stream().map(NodeContent::getType).distinct().toList());
            row.put("updatedAt", items.isEmpty() ? null : items.get(0).getUpdatedAt());
            rows.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("graphId", graphId);
        result.put("nodes", rows);
        result.put("withContent", rows.stream().filter(r -> ((Number) r.get("contentCount")).intValue() > 0).count());
        result.put("total", rows.size());
        return result;
    }

    /** 学生视角：只返回已发布内容 */
    public List<Map<String, Object>> studentContents(Long nodeId) {
        return nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                        .eq(NodeContent::getNodeId, nodeId)
                        .eq(NodeContent::getStatus, NodeContent.S_PUBLISHED)
                        .orderByAsc(NodeContent::getType)
                        .orderByDesc(NodeContent::getId))
                .stream().map(this::toView).toList();
    }

    public NodeContent get(Long id) {
        NodeContent c = nodeContentMapper.selectById(id);
        if (c == null) {
            throw new BizException("内容不存在: " + id);
        }
        UserContext.checkOrgScope(c.getOrgId());
        return c;
    }

    // ==================== 生成 ====================

    /**
     * 为知识点生成内容。
     *
     * @param type             内容类型，空则默认讲义
     * @param extraInstruction 教师额外要求（例如「面向基础薄弱的学生，多用生活例子」）
     * @param replaceExisting  同一知识点同类型是否覆盖（true 则递增版本、旧的置为草稿）
     */
    @Transactional
    public Map<String, Object> generate(Long nodeId, String type, String extraInstruction,
                                        boolean replaceExisting) {
        NodeContextService.NodeContext ctx = nodeContextService.of(nodeId);
        UserContext.checkOrgScope(ctx.node().getOrgId());
        String t = type == null || type.isBlank() ? NodeContent.T_LECTURE : type.trim().toUpperCase();
        if (!ALL_TYPES.contains(t)) {
            throw new BizException("不支持的内容类型: " + type + "（可选 " + String.join(" / ", ALL_TYPES) + "）");
        }

        String body;
        String summary;
        String generatedBy;
        String model = "";
        if (llm.available()) {
            try {
                String raw = llm.chat(systemPrompt(), userPrompt(ctx, t, extraInstruction));
                body = raw.trim();
                summary = firstLine(body);
                generatedBy = NodeContent.BY_LLM;
                model = llm.model();
            } catch (Exception e) {
                log.warn("大模型生成失败，降级为模板：{}", e.getMessage());
                body = templateBody(ctx, t);
                summary = TYPE_LABEL.get(t) + "（模板生成：" + e.getMessage() + "）";
                generatedBy = NodeContent.BY_TEMPLATE;
            }
        } else {
            body = templateBody(ctx, t);
            summary = TYPE_LABEL.get(t) + "（" + llm.unavailableReason() + "，已用规则模板生成）";
            generatedBy = NodeContent.BY_TEMPLATE;
        }

        int version = 1;
        if (replaceExisting) {
            List<NodeContent> exists = nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                    .eq(NodeContent::getNodeId, nodeId).eq(NodeContent::getType, t));
            if (!exists.isEmpty()) {
                version = exists.stream().mapToInt(c -> c.getVersion() == null ? 1 : c.getVersion())
                        .max().orElse(1) + 1;
                // 旧版本退回草稿，避免学生看到两版
                for (NodeContent old : exists) {
                    old.setStatus(NodeContent.S_DRAFT);
                    nodeContentMapper.updateById(old);
                }
            }
        }

        CurrentUser me = UserContext.peek();
        NodeContent content = new NodeContent();
        content.setOrgId(ctx.node().getOrgId());
        content.setGraphId(ctx.node().getGraphId());
        content.setNodeId(nodeId);
        content.setType(t);
        content.setTitle(ctx.node().getName() + " · " + TYPE_LABEL.get(t));
        content.setSummary(summary);
        content.setBody(body);
        content.setGeneratedBy(generatedBy);
        content.setModel(model);
        content.setVersion(version);
        content.setStatus(NodeContent.S_DRAFT);
        content.setCreatedBy(me == null ? null : me.userId());
        nodeContentMapper.insert(content);

        Map<String, Object> result = toView(content);
        result.put("llmUsed", NodeContent.BY_LLM.equals(generatedBy));
        result.put("notice", NodeContent.BY_LLM.equals(generatedBy)
                ? "已由大模型生成，待发布" : summary);
        return result;
    }

    /** 批量生成：给整张图谱的所有知识点（或仅缺内容的知识点）生成指定类型 */
    @Transactional
    public Map<String, Object> batchGenerate(Long graphId, List<String> types, boolean onlyMissing,
                                             String extraInstruction) {
        List<String> targetTypes = (types == null || types.isEmpty()) ? List.of(NodeContent.T_LECTURE)
                : types.stream().map(s -> s.trim().toUpperCase()).filter(ALL_TYPES::contains).toList();
        if (targetTypes.isEmpty()) {
            throw new BizException("请指定至少一个有效的内容类型");
        }
        List<GraphNode> nodes = graphStore.listNodes(graphId);
        if (nodes.isEmpty()) {
            throw new BizException("该图谱下没有知识点: " + graphId);
        }
        UserContext.checkOrgScope(nodes.get(0).getOrgId());

        List<Long> nodeIds = nodes.stream().map(GraphNode::getId).toList();
        List<NodeContent> existing = nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                .in(NodeContent::getNodeId, nodeIds));

        int created = 0;
        int skipped = 0;
        List<Map<String, Object>> failures = new ArrayList<>();
        for (GraphNode node : nodes) {
            for (String t : targetTypes) {
                boolean has = existing.stream().anyMatch(c -> c.getNodeId().equals(node.getId())
                        && t.equals(c.getType()));
                if (onlyMissing && has) {
                    skipped++;
                    continue;
                }
                try {
                    generate(node.getId(), t, extraInstruction, has);
                    created++;
                } catch (Exception e) {
                    log.warn("批量生成失败 node={} type={}: {}", node.getId(), t, e.getMessage());
                    failures.add(Map.of("nodeId", node.getId(), "nodeName", node.getName(),
                            "type", t, "reason", String.valueOf(e.getMessage())));
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("graphId", graphId);
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("failures", failures);
        result.put("llmUsed", llm.available());
        result.put("notice", llm.available() ? "已由大模型生成" : llm.unavailableReason() + "，已用规则模板生成");
        return result;
    }

    // ==================== 编辑与发布 ====================

    @Transactional
    public Map<String, Object> update(Long id, String title, String summary, String body) {
        NodeContent content = get(id);
        if (title != null && !title.isBlank()) {
            content.setTitle(title.trim());
        }
        if (summary != null) {
            content.setSummary(summary.trim());
        }
        if (body != null && !body.isBlank()) {
            content.setBody(body);
        }
        content.setGeneratedBy(NodeContent.BY_TEACHER);
        nodeContentMapper.updateById(content);
        return toView(content);
    }

    @Transactional
    public Map<String, Object> setStatus(Long id, String status) {
        NodeContent content = get(id);
        String s = NodeContent.S_PUBLISHED.equalsIgnoreCase(status)
                ? NodeContent.S_PUBLISHED : NodeContent.S_DRAFT;
        content.setStatus(s);
        nodeContentMapper.updateById(content);
        return toView(content);
    }

    @Transactional
    public void delete(Long id) {
        get(id);
        nodeContentMapper.deleteById(id);
    }

    // ==================== 提示词与模板 ====================

    private String systemPrompt() {
        return """
                你是一位资深的 K-12 学科教研专家，擅长把知识点拆解成学生能自学的材料。
                要求：
                1. 严格输出 Markdown，不要输出任何解释性的开场白或结束语；
                2. 只讲当前知识点，不要大段展开前置知识点；
                3. 数学内容必须公式正确、步骤完整，例题要给解题过程而不只是答案；
                4. 语言面向对应年级的学生，避免超纲术语；
                5. 使用二级标题（##）分节，不要使用一级标题。
                """;
    }

    private String userPrompt(NodeContextService.NodeContext ctx, String type, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为下面这个知识点生成一份【").append(TYPE_LABEL.get(type)).append("】。\n\n");
        sb.append("=== 知识背景 ===\n").append(nodeContextService.describe(ctx)).append("\n");
        sb.append("=== 输出要求 ===\n").append(typeRequirement(type)).append("\n");
        if (extra != null && !extra.isBlank()) {
            sb.append("\n=== 教师补充要求 ===\n").append(extra.trim()).append("\n");
        }
        return sb.toString();
    }

    private String typeRequirement(String type) {
        return switch (type) {
            case NodeContent.T_LECTURE -> """
                    ## 学习目标（3 条，可观测、可检验）
                    ## 知识讲解（概念、算理/原理、关键结论，配必要的公式）
                    ## 典型例题（2-3 道，含完整解题步骤）
                    ## 易错点提醒（2-3 条，指出学生常犯的错误与原因）
                    ## 小结（3-5 条要点）
                    """;
            case NodeContent.T_EXAMPLE -> """
                    ## 例题精讲
                    给出 3 道由易到难的例题，每道包含：
                    - 题目
                    - 思路分析
                    - 完整解答
                    - 变式训练（1 道，不给答案）
                    最后给出「本题型通用解法」总结。
                    """;
            case NodeContent.T_MICRO_LESSON -> """
                    这是一份微课视频脚本，总时长 5-8 分钟，按镜头分节：
                    ## 镜头 N｜时长 X 秒
                    - 画面：具体画面 / 动画描述
                    - 旁白：完整口播稿
                    - 字幕要点：屏幕上出现的文字
                    结尾给出「课堂互动提问」2 个。
                    """;
            default -> """
                    ## 一页速记卡
                    用要点式短句呈现：核心概念、关键公式/规则、记忆口诀、易混对比。
                    全文控制在 300 字以内，便于打印成卡片。
                    """;
        };
    }

    /**
     * 无大模型时的规则模板：用知识点说明、前后关系与已有题目自动拼装，
     * 结构上与讲义保持一致，学生仍可正常阅读。
     */
    private String templateBody(NodeContextService.NodeContext ctx, String type) {
        GraphNode node = ctx.node();
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(node.getName()).append("\n\n");
        if (notBlank(node.getDescription())) {
            sb.append(node.getDescription()).append("\n\n");
        }
        switch (type) {
            case NodeContent.T_LECTURE -> {
                sb.append("## 学习目标\n\n")
                        .append("- 理解「").append(node.getName()).append("」的含义与适用范围\n")
                        .append("- 能独立完成本知识点的常规题目\n")
                        .append("- 能说出常见错误并自行检查\n\n");
                if (!ctx.prerequisites().isEmpty()) {
                    sb.append("## 学习前提\n\n建议先复习：")
                            .append(joinNames(ctx.prerequisites())).append("\n\n");
                }
                sb.append("## 典型例题\n\n");
                appendQuestions(sb, ctx);
                sb.append("## 小结\n\n- 先明确概念，再套用方法，最后检查结果是否合理。\n");
            }
            case NodeContent.T_EXAMPLE -> {
                sb.append("## 例题精讲\n\n");
                appendQuestions(sb, ctx);
            }
            case NodeContent.T_MICRO_LESSON -> {
                sb.append("## 镜头 1｜时长 20 秒\n\n")
                        .append("- 画面：展示本知识点的生活情境\n")
                        .append("- 旁白：同学们，今天我们学习「").append(node.getName()).append("」。\n")
                        .append("- 字幕要点：").append(node.getName()).append("\n\n")
                        .append("## 镜头 2｜时长 120 秒\n\n")
                        .append("- 画面：逐步推导 / 演示\n")
                        .append("- 旁白：").append(nz(node.getDescription())).append("\n")
                        .append("- 字幕要点：关键结论\n\n");
                if (!ctx.successors().isEmpty()) {
                    sb.append("## 镜头 3｜时长 40 秒\n\n")
                            .append("- 画面：与后续知识点的衔接\n")
                            .append("- 旁白：掌握之后，我们就可以学习")
                            .append(joinNames(ctx.successors())).append("了。\n\n");
                }
                sb.append("## 课堂互动提问\n\n1. 你能用自己的话说说这个知识点的要点吗？\n")
                        .append("2. 如果条件变一变，结论还成立吗？\n");
            }
            default -> {
                sb.append("**核心要点**\n\n- ").append(nz(node.getDescription())).append("\n");
                if (!ctx.prerequisites().isEmpty()) {
                    sb.append("- 前提：").append(joinNames(ctx.prerequisites())).append("\n");
                }
                if (!ctx.successors().isEmpty()) {
                    sb.append("- 后续：").append(joinNames(ctx.successors())).append("\n");
                }
            }
        }
        sb.append("\n> 本内容由规则模板生成（未配置大模型 API Key）。配置后重新生成即可获得模型撰写的版本。\n");
        return sb.toString();
    }

    private void appendQuestions(StringBuilder sb, NodeContextService.NodeContext ctx) {
        if (ctx.questions().isEmpty()) {
            sb.append("_该知识点暂无配套题目，建议先在题库中补充。_\n\n");
            return;
        }
        int i = 1;
        int limit = Math.min(3, ctx.questions().size());
        for (int k = 0; k < limit; k++) {
            Question q = ctx.questions().get(k);
            sb.append("**例").append(i++).append("** ").append(nz(q.getStem())).append("\n\n");
            List<String> options = parseOptions(q.getOptions());
            if (options != null && !options.isEmpty()) {
                // 选项在库里是 JSON 数组字符串，渲染成 A. / B. 列表，别把原始 JSON 甩给学生
                for (int o = 0; o < options.size(); o++) {
                    sb.append("- ").append(optionLabel(o)).append(". ").append(nz(options.get(o))).append("\n");
                }
                sb.append("\n");
            } else if (notBlank(q.getOptions())) {
                sb.append(nz(q.getOptions())).append("\n\n");
            }
            sb.append("解答：").append(nz(q.getAnswer())).append("\n\n");
            if (notBlank(q.getAnalysis())) {
                sb.append("解析：").append(q.getAnalysis()).append("\n\n");
            }
        }
    }

    private static String optionLabel(int index) {
        return index < 26 ? String.valueOf((char) ('A' + index)) : String.valueOf(index + 1);
    }

    private static List<String> parseOptions(String json) {
        if (json == null || json.isBlank() || !json.trim().startsWith("[")) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return null;
        }
    }

    private static String joinNames(List<GraphNode> nodes) {
        return nodes.stream().map(GraphNode::getName).reduce((a, b) -> a + "、 " + b).orElse("");
    }

    private Map<String, Object> toView(NodeContent c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("orgId", c.getOrgId());
        m.put("graphId", c.getGraphId());
        m.put("nodeId", c.getNodeId());
        m.put("type", c.getType());
        m.put("typeLabel", TYPE_LABEL.getOrDefault(c.getType(), c.getType()));
        m.put("title", c.getTitle());
        m.put("summary", c.getSummary());
        m.put("body", c.getBody());
        m.put("generatedBy", c.getGeneratedBy());
        m.put("model", c.getModel());
        m.put("version", c.getVersion());
        m.put("status", c.getStatus());
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private static String firstLine(String body) {
        if (body == null) {
            return "";
        }
        for (String line : body.split("\n")) {
            String flat = line.replaceAll("^[#>*\\-\\s]+", "").trim();
            if (!flat.isEmpty()) {
                return flat.length() <= 120 ? flat : flat.substring(0, 120);
            }
        }
        return "";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
