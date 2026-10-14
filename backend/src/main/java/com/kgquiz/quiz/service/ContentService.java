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
import com.kgquiz.quiz.storyboard.Storyboard;
import com.kgquiz.quiz.storyboard.StoryboardChecker;
import com.kgquiz.quiz.storyboard.StoryboardCodec;
import com.kgquiz.quiz.storyboard.StoryboardRenderer;
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
            NodeContent.T_EXPLAIN, NodeContent.T_LECTURE, NodeContent.T_EXAMPLE,
            NodeContent.T_MICRO_LESSON, NodeContent.T_CARD, NodeContent.T_LESSON_PLAN);

    private static final Map<String, String> TYPE_LABEL = Map.of(
            NodeContent.T_EXPLAIN, "讲解分镜",
            NodeContent.T_LECTURE, "讲义",
            NodeContent.T_EXAMPLE, "例题精讲",
            NodeContent.T_MICRO_LESSON, "微课脚本",
            NodeContent.T_CARD, "知识卡片",
            NodeContent.T_LESSON_PLAN, "教案");

    /**
     * 走讲解 IR 路径的类型。
     *
     * <p>这两个类型不在这里直接生成正文，而是先产出 IR（{@link StoryboardService}），
     * 再由渲染器投影出 body。这样「每个知识点一个讲解」与「教案参考讲解」才成立。</p>
     */
    private static final List<String> IR_TYPES = List.of(
            NodeContent.T_EXPLAIN, NodeContent.T_LESSON_PLAN);

    /** 仅用于把库里存成 JSON 数组字符串的题目选项还原成列表 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NodeContentMapper nodeContentMapper;
    private final GraphStore graphStore;
    private final NodeContextService nodeContextService;
    private final LlmClient llm;
    private final StoryboardService storyboardService;

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

    /**
 * 学生视角：只返回已发布内容。
     *
     * <p>教案（{@link NodeContent#T_LESSON_PLAN}）对教师可见、对学生不可见——
     * 它是备课产物，不是学习材料。</p>
     */
    public List<Map<String, Object>> studentContents(Long nodeId) {
        return nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                        .eq(NodeContent::getNodeId, nodeId)
                        .eq(NodeContent::getStatus, NodeContent.S_PUBLISHED)
                        .ne(NodeContent::getType, NodeContent.T_LESSON_PLAN)
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
        // 教案与讲解走 IR 路径：先产 IR，再投影正文，其余类型仍是原来的直接生成
        if (IR_TYPES.contains(t)) {
            return generateFromStoryboard(nodeId, t, extraInstruction, replaceExisting);
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

    /**
     * 讲解 / 教案的生成：IR 优先，正文由 IR 投影。
     *
     * <p>教案不自己产 IR，而是<b>引用同知识点的讲解 IR</b>——这正是"教师创建教案时
     * 参考讲解"的落地方式：改一次讲解，讲案跟着变，不会出现两套说法。</p>
     */
    private Map<String, Object> generateFromStoryboard(Long nodeId, String type, String extra,
                                                       boolean replaceExisting) {
        NodeContextService.NodeContext ctx = nodeContextService.of(nodeId);
        UserContext.checkOrgScope(ctx.node().getOrgId());

        Storyboard sb;
        Map<Long, Question> questions;
        Map<String, Object> check;
        String generatedBy;
        String model = "";
        String body;
        String storyboardJson = null;
        NodeContent source = null;

        if (NodeContent.T_LESSON_PLAN.equals(type)) {
            // 教案：引用已有讲解 IR，缺讲解时现生成一份，避免"没讲解就没教案"
            source = storyboardService.findExplain(nodeId);
            if (source != null) {
                sb = StoryboardCodec.read(source.getStoryboard());
                questions = questionMapOf(ctx);
            } else {
                log.info("知识点 {} 尚无讲解 IR，为生成教案先产一份", nodeId);
                StoryboardService.Generated g = storyboardService.generate(nodeId, extra);
                sb = g.storyboard;
                questions = g.questions;
                check = g.check;
                generatedBy = g.generatedBy;
                model = g.model;
                body = StoryboardRenderer.toLessonPlan(sb, questions, extra);
                storyboardJson = null; // 教案不自带 IR，它引用讲解那份
                return persistIrResult(ctx, type, body, storyboardJson, check, generatedBy, model,
                        replaceExisting, source == null
                                ? "该知识点原本没有讲解，已先自动生成一份讲解 IR 作为教案依据。"
                                : "教案已按本知识点的讲解分镜生成。");
            }
            check = readCheckReport(source);
            // 教案不自带 IR，分段数要沿用被引用的那份讲解，否则摘要会写「0分段」
            if (sb != null) {
                check = new LinkedHashMap<>(check);
                check.put("segmentCount", sb.segmentCount());
            }
            generatedBy = NodeContent.BY_TEMPLATE; // 教案是 IR 的确定性渲染，不经大模型
            body = StoryboardRenderer.toLessonPlan(sb, questions, extra);
        } else {
            // 讲解：现生成 IR
            StoryboardService.Generated g = storyboardService.generate(nodeId, extra);
            sb = g.storyboard;
            questions = g.questions;
            check = g.check;
            generatedBy = g.generatedBy;
            model = g.model;
            storyboardJson = g.storyboardJson();
            body = g.markdown();
        }
        return persistIrResult(ctx, type, body, storyboardJson, check, generatedBy, model,
                replaceExisting, null);
    }

    /**
     * 落库（IR 路径）。
     *
     * <p>自检有 ERROR 时仍会落草稿，但打上 {@code needsReview=1}——
     * 前端会显示"待人工复核"，教师确认后才允许发布。这样既不丢生成结果，
     * 又不会把明显错误的内容自动放行到学生端。</p>
     */
    private Map<String, Object> persistIrResult(NodeContextService.NodeContext ctx, String type,
                                                String body, String storyboardJson,
                                                Map<String, Object> check, String generatedBy,
                                                String model, boolean replaceExisting, String notice) {
        Long nodeId = ctx.node().getId();
        int version = 1;
        if (replaceExisting) {
            List<NodeContent> exists = nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                    .eq(NodeContent::getNodeId, nodeId).eq(NodeContent::getType, type));
            if (!exists.isEmpty()) {
                version = exists.stream().mapToInt(c -> c.getVersion() == null ? 1 : c.getVersion())
                        .max().orElse(1) + 1;
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
        content.setType(type);
        content.setTitle(ctx.node().getName() + " · " + TYPE_LABEL.get(type));
        content.setSummary(summaryOf(type, check, generatedBy));
        content.setBody(body);
        content.setStoryboard(storyboardJson);
        content.setCheckReport(StoryboardChecker.toJson(check));
        content.setNeedsReview(Boolean.TRUE.equals(check.get("needsReview")) ? 1 : 0);
        content.setGeneratedBy(generatedBy);
        content.setModel(model);
        content.setVersion(version);
        content.setStatus(NodeContent.S_DRAFT);
        content.setCreatedBy(me == null ? null : me.userId());
        nodeContentMapper.insert(content);

        Map<String, Object> result = toView(content);
        result.put("llmUsed", NodeContent.BY_LLM.equals(generatedBy));
        result.put("check", check);
        result.put("notice", notice != null ? notice
                : (NodeContent.BY_LLM.equals(generatedBy) ? "已生成讲解分镜，待复核后发布"
                : TYPE_LABEL.get(type) + "已用规则模板生成"));
        return result;
    }

    private Map<Long, Question> questionMapOf(NodeContextService.NodeContext ctx) {
        Map<Long, Question> m = new LinkedHashMap<>();
        for (Question q : ctx.questions()) {
            m.put(q.getId(), q);
        }
        return m;
    }

    private Map<String, Object> readCheckReport(NodeContent source) {
        if (source == null || source.getCheckReport() == null) {
            return Map.of("passed", true, "needsReview", false, "issues", List.of());
        }
        try {
            return MAPPER.readValue(source.getCheckReport(), Map.class);
        } catch (Exception e) {
            return Map.of("passed", true, "needsReview", false, "issues", List.of());
        }
    }

    private String summaryOf(String type, Map<String, Object> check, String generatedBy) {
        boolean review = Boolean.TRUE.equals(check.get("needsReview"));
        // 分段数从 IR 摘要里取；教案是引用渲染、没有自己的 IR，取不到时就不写这一段，
        // 免得出现「0分段」这种自相矛盾的文案
        Object segCount = check.get("segmentCount");
        StringBuilder s = new StringBuilder(TYPE_LABEL.get(type));
        s.append("（").append(NodeContent.BY_LLM.equals(generatedBy) ? "大模型生成" : "规则模板生成");
        if (segCount != null) {
            s.append("，").append(segCount).append(" 个分段");
        }
        s.append("）");
        if (review) {
            s.append("；自检发现 ").append(check.get("errorCount")).append(" 处问题，待人工复核");
        }
        return s.toString();
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

    /**
     * 用讲解 IR 重新渲染正文。
     *
     * <p>IR 是真源，body 只是投影，所以改了 IR（或换了引用它的题目）之后，
     * 正文和教案都要能一键重投影，否则 IR 与投影会悄悄漂移。</p>
     *
     * @param explainContentId 讲解内容 id；不传则取该知识点最新的讲解
     * @param rerenderPlan     是否连带重渲染引用了该讲解的教案
     */
    @Transactional
    public Map<String, Object> rerender(Long contentId, Long explainContentId, boolean rerenderPlan) {
        NodeContent target = get(contentId);
        NodeContent explain = explainContentId != null
                ? get(explainContentId)
                : storyboardService.findExplain(target.getNodeId());
        if (explain == null) {
            throw new BizException("该知识点还没有讲解，无法渲染");
        }
        if (!NodeContent.T_EXPLAIN.equals(explain.getType())) {
            throw new BizException("指定的不是讲解内容（应为 EXPLAIN）");
        }
        Storyboard sb = StoryboardCodec.read(explain.getStoryboard());
        if (sb == null) {
            throw new BizException("讲解内容里没有可用的分镜 IR");
        }
        NodeContextService.NodeContext ctx = nodeContextService.of(target.getNodeId());
        Map<Long, Question> questions = questionMapOf(ctx);

        String body = NodeContent.T_LESSON_PLAN.equals(target.getType())
                ? StoryboardRenderer.toLessonPlan(sb, questions, null)
                : StoryboardRenderer.toMarkdown(sb, questions);
        target.setBody(body);
        target.setGeneratedBy(NodeContent.BY_TEACHER);
        nodeContentMapper.updateById(target);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", toView(target));
        result.put("explainContentId", explain.getId());

        int plans = 0;
        if (rerenderPlan) {
            List<NodeContent> planList = nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                    .eq(NodeContent::getNodeId, target.getNodeId())
                    .eq(NodeContent::getType, NodeContent.T_LESSON_PLAN));
            for (NodeContent p : planList) {
                p.setBody(StoryboardRenderer.toLessonPlan(sb, questions, null));
                p.setGeneratedBy(NodeContent.BY_TEACHER);
                p.setUpdatedAt(java.time.LocalDateTime.now());
                nodeContentMapper.updateById(p);
                plans++;
            }
        }
        result.put("rerenderedPlans", plans);
        return result;
    }

    /**
     * 内容自检：对已落库的内容重跑一次门禁。
     *
     * <p>教师改完讲解后可以自己点一下「重新自检」，确认改动没有引入新问题
     * （比如新加的题目 id 不属于本知识点）。</p>
     */
    public Map<String, Object> recheck(Long contentId) {
        NodeContent content = get(contentId);
        Storyboard sb = StoryboardCodec.read(content.getStoryboard());
        if (sb == null) {
            return Map.of("passed", false, "needsReview", true,
                    "issues", List.of(Map.of("level", "ERROR", "code", "NO_IR",
                            "message", "该内容没有讲解 IR，无法自检")));
        }
        NodeContextService.NodeContext ctx = nodeContextService.of(content.getNodeId());
        Map<String, Object> check = StoryboardChecker.check(sb, questionMapOf(ctx));
        content.setCheckReport(StoryboardChecker.toJson(check));
        content.setNeedsReview(Boolean.TRUE.equals(check.get("needsReview")) ? 1 : 0);
        nodeContentMapper.updateById(content);
        Map<String, Object> result = new LinkedHashMap<>(check);
        result.put("contentId", content.getId());
        return result;
    }

    /**
     * 学生端逐段讲解数据。
     *
     * <p>只取<b>已发布</b>的讲解；没发布就返回 {@code available=false}，
     * 让前端显示"该知识点还没有讲解"而不是空白页。附带的题目只给题干与题型，
     * 不给答案——答案在交卷判分时才出现。</p>
     */
    public Map<String, Object> studentStoryboard(Long nodeId) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeContent explain = storyboardService.findExplain(nodeId);
        boolean ok = explain != null
                && NodeContent.S_PUBLISHED.equals(explain.getStatus());
        result.put("available", ok);
        if (!ok) {
            result.put("hasDraft", explain != null);
            result.put("reason", explain == null ? "该知识点还没有讲解内容"
                    : "讲解内容尚未发布");
            return result;
        }
        Storyboard sb = StoryboardCodec.read(explain.getStoryboard());
        result.put("contentId", explain.getId());
        result.put("nodeId", nodeId);
        result.put("title", explain.getTitle());
        result.put("summary", explain.getSummary());
        result.put("storyboard", sb);
        result.put("summaryInfo", StoryboardRenderer.toSummary(sb));

        // 题目只给题干，答案不给
        NodeContextService.NodeContext ctx = nodeContextService.of(nodeId);
        List<Map<String, Object>> qMeta = new ArrayList<>();
        for (Question q : ctx.questions()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("stem", q.getStem());
            m.put("type", q.getQuestionType());
            m.put("difficulty", q.getDifficulty());
            qMeta.add(m);
        }
        result.put("questions", qMeta);
        return result;
    }

    /**
     * 教师手工编辑内容。
     *
     * <p>对讲解（EXPLAIN）额外做一次自检：正文被手改后可能与 IR 里的题目/答案脱节，
     * 标记 {@code needsReview} 让教师知道该复核。</p>
     */
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
            // 正文被手改后重新过一遍门禁：新增的题目引用 / 答案表述可能有误
            Storyboard sb = StoryboardCodec.read(content.getStoryboard());
            if (sb != null) {
                NodeContextService.NodeContext ctx = nodeContextService.of(content.getNodeId());
                Map<String, Object> check = StoryboardChecker.check(sb, questionMapOf(ctx));
                content.setCheckReport(StoryboardChecker.toJson(check));
                content.setNeedsReview(Boolean.TRUE.equals(check.get("needsReview")) ? 1 : 0);
            }
        }
        content.setGeneratedBy(NodeContent.BY_TEACHER);
        nodeContentMapper.updateById(content);
        return toView(content);
    }

    /**
     * 发布 / 撤回。
     *
     * <p><b>硬边界</b>：自检存在 ERROR 级问题（needsReview=1）时拒绝发布。
     * 这就是「花钱/落库前拦一道」的意义所在——错误内容不能流到学生手上。
     * 教师要先改对、重新自检通过，才能发布。</p>
     */
    @Transactional
    public Map<String, Object> setStatus(Long id, String status) {
        NodeContent content = get(id);
        String s = NodeContent.S_PUBLISHED.equalsIgnoreCase(status)
                ? NodeContent.S_PUBLISHED : NodeContent.S_DRAFT;
        if (NodeContent.S_PUBLISHED.equals(s) && content.getNeedsReview() != null
                && content.getNeedsReview() == 1) {
            Map<String, Object> check = parseCheck(content.getCheckReport());
            throw new BizException("内容自检未通过，已阻止发布。请先修正问题并重新自检："
                    + briefIssues(check));
        }
        content.setStatus(s);
        nodeContentMapper.updateById(content);
        return toView(content);
    }

    private String briefIssues(Map<String, Object> check) {
        Object issues = check.get("issues");
        if (!(issues instanceof List<?> list) || list.isEmpty()) {
            return "详见自检报告";
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Object o : list) {
            if (n >= 3) {
                sb.append(" 等 ").append(list.size()).append(" 项");
                break;
            }
            if (o instanceof Map<?, ?> m) {
                sb.append("[").append(m.get("message")).append("] ");
            }
            n++;
        }
        return sb.toString();
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
        m.put("needsReview", c.getNeedsReview() != null && c.getNeedsReview() == 1);
        // IR 结构给前端做逐段阅读与视觉锚点；正文仍是 IR 的 Markdown 投影
        Storyboard sb = StoryboardCodec.read(c.getStoryboard());
        m.put("storyboard", sb);
        m.put("storyboardSummary", StoryboardRenderer.toSummary(sb));
        m.put("check", parseCheck(c.getCheckReport()));
        m.put("createdAt", c.getCreatedAt());
        m.put("updatedAt", c.getUpdatedAt());
        return m;
    }

    private Map<String, Object> parseCheck(String json) {
        if (notBlank(json)) {
            try {
                return MAPPER.readValue(json, Map.class);
            } catch (Exception ignored) {
                // 自检报告解析失败不该阻断内容展示，降级成"无报告"
            }
        }
        return Map.of("passed", true, "needsReview", false, "issues", List.of());
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
