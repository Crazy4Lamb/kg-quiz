package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.entity.GraphNode;
import com.kgquiz.quiz.entity.NodeContent;
import com.kgquiz.quiz.entity.Question;
import com.kgquiz.quiz.mapper.NodeContentMapper;
import com.kgquiz.quiz.storyboard.Storyboard;
import com.kgquiz.quiz.storyboard.StoryboardChecker;
import com.kgquiz.quiz.storyboard.StoryboardCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 讲解 IR 的生成与读取。
 *
 * <p>一条原则：<b>IR 是真源，Markdown 与教案都是它的投影</b>。
 * 所以本服务只负责「产出 IR」，渲染一律委托 {@link com.kgquiz.quiz.storyboard.StoryboardRenderer}。
 * 将来加视频渲染端，不需要动这里。</p>
 *
 * <p>降级链与既有内容生成一致：大模型不可用或返回不合法时退回规则模板，
 * 但<b>依然产出一份结构完整的 IR</b>（而不是退回纯 Markdown）——
 * 否则模板版和模型版的下游（教案、自检、逐段阅读）会走两套代码。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoryboardService {

    private final NodeContextService nodeContextService;
    private final LlmClient llm;
    private final NodeContentMapper nodeContentMapper;

    // ==================== 生成 ====================

    /**
     * 为知识点生成讲解 IR。
     *
     * @return 自检通过的 IR（自检不通过也会返回，但 {@code check} 里带 needsReview，
     *         由调用方决定落库还是打回）
     */
    public Generated generate(Long nodeId, String extraInstruction) {
        NodeContextService.NodeContext ctx = nodeContextService.of(nodeId);
        GraphNode node = ctx.node();
        Map<Long, Question> questions = questionMap(ctx);

        Storyboard sb;
        String generatedBy;
        String model = "";

        if (llm.available()) {
            try {
                sb = llm.chatJson(systemPrompt(), userPrompt(ctx, extraInstruction), Storyboard.class);
                if (sb == null || sb.segmentCount() == 0) {
                    throw new IllegalStateException("模型未返回有效分镜");
                }
                sb.setNodeId(nodeId);
                sb.setNodeName(node.getName());
                normalize(sb);
                generatedBy = NodeContent.BY_LLM;
                model = llm.model();
            } catch (Exception e) {
                log.warn("讲解 IR 生成失败，降级为规则模板：{}", e.getMessage());
                sb = template(ctx);
                generatedBy = NodeContent.BY_TEMPLATE;
            }
        } else {
            sb = template(ctx);
            generatedBy = NodeContent.BY_TEMPLATE;
        }

        // 关键：落库前先自检，不把明显错误的内容交给教师
        Map<String, Object> check = StoryboardChecker.check(sb, questions);

        Generated g = new Generated();
        g.storyboard = sb;
        g.questions = questions;
        g.check = check;
        g.generatedBy = generatedBy;
        g.model = model;
        return g;
    }

    /** 生成结果 */
    public static class Generated {
        public Storyboard storyboard;
        public Map<Long, Question> questions;
        public Map<String, Object> check;
        public String generatedBy;
        public String model;

        public boolean needsReview() {
            return Boolean.TRUE.equals(check.get("needsReview"));
        }

        public String storyboardJson() {
            return StoryboardCodec.write(storyboard);
        }

        public String checkJson() {
            return StoryboardChecker.toJson(check);
        }

        public String markdown() {
            return com.kgquiz.quiz.storyboard.StoryboardRenderer.toMarkdown(storyboard, questions);
        }
    }

    // ==================== 读取 ====================

    /**
     * 取某知识点的讲解 IR：优先取已发布那份，没有则取最新草稿。
     *
     * <p>教案生成要引讲解，学生阅读也要引讲解，两边都走这里，保证取到的是同一份。</p>
     */
    public NodeContent findExplain(Long nodeId) {
        List<NodeContent> list = listExplain(nodeId);
        if (list.isEmpty()) {
            return null;
        }
        for (NodeContent c : list) {
            if (NodeContent.S_PUBLISHED.equals(c.getStatus())
                    && c.getStoryboard() != null && !c.getStoryboard().isBlank()) {
                return c;
            }
        }
        // 无已发布版本时退回最新一份（可能只有草稿），让教师仍能拿它生成教案
        for (NodeContent c : list) {
            if (c.getStoryboard() != null && !c.getStoryboard().isBlank()) {
                return c;
            }
        }
        return null;
    }

    /** 讲解 IR 存在性，供前端提示「该知识点还没有讲解」 */
    public boolean hasExplain(Long nodeId) {
        NodeContent c = findExplain(nodeId);
        return c != null;
    }

    /** 某知识点的全部讲解版本（已发布优先，其次版本号高者） */
    public List<NodeContent> listExplain(Long nodeId) {
        return nodeContentMapper.selectList(new LambdaQueryWrapper<NodeContent>()
                .eq(NodeContent::getNodeId, nodeId)
                .eq(NodeContent::getType, NodeContent.T_EXPLAIN)
                .orderByDesc(NodeContent::getStatus)
                .orderByDesc(NodeContent::getVersion)
                .orderByDesc(NodeContent::getId));
    }

    // ==================== 提示词 ====================

    private String systemPrompt() {
        return """
                你是一位资深的 K-12 学科教研专家，负责把一个知识点拆成分镜脚本。
                请严格输出 JSON（不要 markdown 代码围栏），字段如下：
                {
                  "objective": "本讲要解决的一句话",
                  "audience": "面向的学段/年级",
                  "segments": [
                    {
                      "index": 1,
                      "kind": "HOOK|TEACH|DEMO|EXAMPLE|PRACTICE|RECAP",
                      "title": "该段小标题",
                      "narration": "完整旁白，一段内不要换行",
                      "seconds": 60,
                      "boardLines": ["板书要点1", "板书要点2"],
                      "questionIds": [题库中真实存在的题目id],
                      "visuals": [
                        {"type": "TEXT|FORMULA|QUESTION|DIAGRAM", "text": "画面文字",
                         "questionId": 题目id或null, "anchorLine": 1, "anchorRatio": 0.3, "holdSeconds": 8}
                      ]
                    }
                  ]
                }
                硬性要求：
                1. segments 的 index 必须从 1 开始连续递增；
                2. 每个 segment 必须有非空 narration；
                3. visuals.anchorLine 是「本段 narration 内部的行号」，不要写绝对秒数；
                4. questionIds 只能从下方给出的「已有配套题目」里选 id，**严禁编造**；
                5. 数学内容必须公式正确；若引用题目，narration 中的答案必须与该题答案一致；
                   引用题目答案的画面必须填写 questionId，且属于本段 questionIds；
                6. kind 的使用建议：HOOK 情境导入 → TEACH 概念新授 → DEMO 推导演示 →
                   EXAMPLE 例题 → PRACTICE 随堂练习 → RECAP 小结。
                """;
    }

    private String userPrompt(NodeContextService.NodeContext ctx, String extra) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 知识背景 ===\n").append(nodeContextService.describe(ctx)).append("\n");
        sb.append("=== 可用题目 id（questionIds 只能从这里选）===\n");
        if (ctx.questions().isEmpty()) {
            sb.append("（该知识点暂无配套题目，所有 segment 的 questionIds 必须为空数组）\n");
        } else {
            for (Question q : ctx.questions()) {
                sb.append("- id=").append(q.getId())
                        .append(" 题干：").append(oneLine(q.getStem()))
                        .append(" 答案：").append(oneLine(q.getAnswer())).append("\n");
            }
        }
        if (extra != null && !extra.isBlank()) {
            sb.append("\n=== 教师补充要求 ===\n").append(extra.trim()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 规则模板（未配 Key 时）====================

    /**
     * 无大模型时的规则模板。
     *
     * <p>刻意也产出完整 IR：HOOK → TEACH →（有题则 EXAMPLE）→（有题则 PRACTICE）→ RECAP，
     * 视觉锚点绑在旁白第 1 行内（如 0.1 / 0.5 / 0.8），语义上就是"这段话说到那儿时出这个画面"。
     * 这样学生端逐段阅读的效果与模型版一致。</p>
     */
    private Storyboard template(NodeContextService.NodeContext ctx) {
        GraphNode node = ctx.node();
        Storyboard sb = new Storyboard();
        sb.setNodeId(node.getId());
        sb.setNodeName(node.getName());
        sb.setAudience(ctx.graph() == null ? "" : ctx.graph().getGrade());

        String desc = nz(node.getDescription());
        sb.setObjective(desc.isEmpty()
                ? "理解「" + node.getName() + "」并能独立完成相关题目"
                : "理解并会用：" + oneLine(desc));

        int i = 1;
        // HOOK
        Storyboard.Segment hook = new Storyboard.Segment();
        hook.setIndex(i++);
        hook.setKind(Storyboard.Segment.K_HOOK);
        hook.setTitle("从生活情境说起");
        hook.setSeconds(40);
        hook.setNarration("同学们，在日常生活中经常会遇到跟「" + node.getName()
                + "」有关的问题。今天我们就从这些熟悉的场景出发，把它变成数学里的一个方法。");
        hook.addVisual(Storyboard.Visual.text("主题：「" + node.getName() + "」").hold(6));
        sb.seg(hook);

        // TEACH
        Storyboard.Segment teach = new Storyboard.Segment();
        teach.setIndex(i++);
        teach.setKind(Storyboard.Segment.K_TEACH);
        teach.setTitle("概念与方法");
        teach.setSeconds(90);
        teach.setNarration(desc.isEmpty()
                ? "「" + node.getName() + "」是本节的核心。下面把它讲清楚：它是什么、怎么算、什么时候用。"
                : nz(desc) + " 我们逐句拆开：先看它是什么，再看怎么用，遇到具体题目时按同样的步骤走。");
        teach.board("核心：" + node.getName(), "步骤：读题 → 列出已知 → 选择方法 → 检验结果");
        teach.addVisual(Storyboard.Visual.text("概念卡：").text(node.getName()).at(1, 0.15));
        if (!ctx.prerequisites().isEmpty()) {
            teach.addVisual(Storyboard.Visual.text("前提：")
                    .text(joinNames(ctx.prerequisites())).at(1, 0.55).hold(8));
        }
        sb.seg(teach);

        // EXAMPLE / PRACTICE：只挂真实题目，段内 narration 讲思路
        List<Question> qs = ctx.questions();
        if (!qs.isEmpty()) {
            int limit = Math.min(2, qs.size());
            for (int k = 0; k < limit; k++) {
                Question q = qs.get(k);
                Storyboard.Segment ex = new Storyboard.Segment();
                ex.setIndex(i++);
                ex.setKind(k == 0 ? Storyboard.Segment.K_EXAMPLE : Storyboard.Segment.K_PRACTICE);
                ex.setTitle((k == 0 ? "例题" : "练习") + "：" + oneLine(q.getStem()));
                ex.setSeconds(120);
                ex.setNarration("我们一起看这道题：" + nz(q.getStem())
                        + " 先想清楚已知和要解决的问题，再决定用什么方法，最后检验结果是否合理。");
                ex.board("已知 → 未知", "方法：从情境抽象到数学表达");
                ex.addVisual(Storyboard.Visual.question(nz(q.getStem()), q.getId()).at(1, 0.2));
                ex.withQuestion(q.getId());
                sb.seg(ex);
            }
        }

        // RECAP
        Storyboard.Segment recap = new Storyboard.Segment();
        recap.setIndex(i);
        recap.setKind(Storyboard.Segment.K_RECAP);
        recap.setTitle("这节课学到了什么");
        recap.setSeconds(40);
        recap.setNarration("回顾一下今天的内容：先明确" + node.getName()
                + "是什么，再按步骤算出结果，最后检查答案合不合理。只要抓住这三步，这类题目就能稳定做对。");
        recap.board("明确概念", "套用方法", "检查结果");
        recap.addVisual(Storyboard.Visual.text("小结：三个关键动作").at(1, 0.4));
        sb.seg(recap);

        return sb;
    }

    // ==================== 规整 ====================

    /**
     * 模型返回的 IR 规整：补齐缺失的 index、丢弃空段落、校正 kind。
     *
     * <p>不规整就直接落库的话，下游逐段阅读会因为某个 index 为 null 而错乱，
     * 而模型返回缺字段是常态，必须当成正常路径处理。</p>
     */
    private void normalize(Storyboard sb) {
        sb.setSegments(sb.getSegments() == null ? new ArrayList<>() : sb.getSegments());
        List<Storyboard.Segment> kept = new ArrayList<>();
        int idx = 1;
        for (Storyboard.Segment s : sb.getSegments()) {
            if (s == null) {
                continue;
            }
            if (s.getNarration() == null || s.getNarration().isBlank()) {
                continue;
            }
            if (s.getKind() == null || !java.util.Set.of(
                    Storyboard.Segment.K_HOOK, Storyboard.Segment.K_TEACH, Storyboard.Segment.K_DEMO,
                    Storyboard.Segment.K_EXAMPLE, Storyboard.Segment.K_PRACTICE,
                    Storyboard.Segment.K_RECAP).contains(s.getKind())) {
                s.setKind(Storyboard.Segment.K_TEACH);
            }
            s.setIndex(idx++);
            if (s.getVisuals() == null) {
                s.setVisuals(new ArrayList<>());
            }
            if (s.getQuestionIds() == null) {
                s.setQuestionIds(new ArrayList<>());
            }
            if (s.getBoardLines() == null) {
                s.setBoardLines(new ArrayList<>());
            }
            kept.add(s);
        }
        sb.setSegments(kept);
    }

    public static Map<Long, Question> questionMap(NodeContextService.NodeContext ctx) {
        Map<Long, Question> m = new LinkedHashMap<>();
        for (Question q : ctx.questions()) {
            m.put(q.getId(), q);
        }
        return m;
    }

    private static String joinNames(List<GraphNode> nodes) {
        return nodes.stream().map(GraphNode::getName).reduce((a, b) -> a + "、 " + b).orElse("");
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "…";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
