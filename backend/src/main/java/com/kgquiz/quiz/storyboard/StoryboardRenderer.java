package com.kgquiz.quiz.storyboard;

import com.kgquiz.quiz.entity.Question;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 讲解 IR 的渲染后端。
 *
 * <p>IR 是真源，本类是它的一个<b>纯函数</b>渲染器：{@code Markdown} 供学生阅读，
 * {@code lessonPlan} 供教师备课。两者读同一份 IR，所以改 IR 就同时改了讲义与教案，
 * 不会出现"讲义和教案说法不一致"这种最伤教研信任的问题。</p>
 *
 * <p>将来要出视频，只需再写一个 {@code toVideoScript(Storyboard)}，
 * 复用 {@link #kindLabel} / {@link #resolveQuestions}，不需要动生成侧。</p>
 */
@Slf4j
public final class StoryboardRenderer {

    /** 分段类型 → 中文标签。教案与讲义共用一套标签，避免两边叫法不一样 */
    public static String kindLabel(String kind) {
        if (kind == null) {
            return "讲解";
        }
        return switch (kind) {
            case Storyboard.Segment.K_HOOK -> "情境导入";
            case Storyboard.Segment.K_TEACH -> "概念新授";
            case Storyboard.Segment.K_DEMO -> "推导演示";
            case Storyboard.Segment.K_EXAMPLE -> "例题精讲";
            case Storyboard.Segment.K_PRACTICE -> "随堂练习";
            case Storyboard.Segment.K_RECAP -> "课堂小结";
            default -> "讲解";
        };
    }

    /** 教学环节的固定顺序：教案按此编排，IR 里缺哪环就跳过哪环 */
    private static final List<String> LESSON_FLOW = List.of(
            Storyboard.Segment.K_HOOK,
            Storyboard.Segment.K_TEACH,
            Storyboard.Segment.K_DEMO,
            Storyboard.Segment.K_EXAMPLE,
            Storyboard.Segment.K_PRACTICE,
            Storyboard.Segment.K_RECAP);

    // ==================== 后端一：Markdown（学生阅读）====================

    /**
     * 渲染成 Markdown 正文。这是 {@code node_content.body} 的来源。
     *
     * <p>格式约定：每段一个二级标题，正文是旁白，画面要素以引用块 / 列表呈现。
     * 逐段呈现的能力由前端按 {@code segments} 结构做，不必从 Markdown 里反推分段。</p>
     */
    public static String toMarkdown(Storyboard sb, Map<Long, Question> questions) {
        if (sb == null || sb.segmentCount() == 0) {
            return "_该知识点暂无讲解分镜内容。_\n";
        }
        StringBuilder md = new StringBuilder();
        if (notBlank(sb.getObjective())) {
            md.append("> **本讲要解决**：").append(sb.getObjective()).append("\n\n");
        }
        for (Storyboard.Segment s : sb.getSegments()) {
            md.append("## ").append(idx(s)).append(". ").append(title(s)).append("\n\n");
            if (notBlank(s.getNarration())) {
                md.append(s.getNarration().trim()).append("\n\n");
            }
            if (!s.getBoardLines().isEmpty()) {
                md.append("**板书 / 要点**\n\n");
                for (String line : s.getBoardLines()) {
                    md.append("- ").append(line).append("\n");
                }
                md.append("\n");
            }
            appendVisuals(md, s, questions);
            md.append("\n");
        }
        return md.toString().trim() + "\n";
    }

    private static void appendVisuals(StringBuilder md, Storyboard.Segment s,
                                       Map<Long, Question> questions) {
        if (!s.getVisuals().isEmpty()) {
            md.append("**画面**\n\n");
            for (Storyboard.Visual v : s.getVisuals()) {
                String marker = switch (v.getType() == null ? "" : v.getType()) {
                    case Storyboard.Visual.V_FORMULA -> "公式";
                    case Storyboard.Visual.V_QUESTION -> "题目";
                    case Storyboard.Visual.V_DIAGRAM -> "图示";
                    default -> "要点";
                };
                md.append("- **").append(marker).append("**：")
                        .append(nz(v.getText())).append("\n");
            }
            md.append("\n");
        }
        if (questions != null && !s.getQuestionIds().isEmpty()) {
            md.append("**配套题目**\n\n");
            for (Long qid : s.getQuestionIds()) {
                Question q = questions.get(qid);
                if (q == null) {
                    continue;
                }
                md.append("- ").append(oneLine(q.getStem()))
                        .append("（答案：").append(oneLine(q.getAnswer())).append("）\n");
            }
            md.append("\n");
        }
    }

    // ==================== 后端二：教案（教师备课）====================

    /**
     * 渲染成教案。教学过程直接引用 IR 的分段，而不是重新生成一遍内容——
     * 这就是"教师创建教案时参考讲解"的落地形态。
     */
    public static String toLessonPlan(Storyboard sb, Map<Long, Question> questions, String extra) {
        StringBuilder p = new StringBuilder();
        p.append("## 课题\n\n").append(nz(sb.getNodeName())).append("\n\n");

        p.append("## 教学目标\n\n");
        if (notBlank(sb.getObjective())) {
            p.append("- ").append(sb.getObjective()).append("\n");
        }
        p.append("- 能复述核心概念并独立完成本知识点的常规题目\n");
        if (notBlank(sb.getAudience())) {
            p.append("- 面向：").append(sb.getAudience()).append("\n");
        }
        p.append("\n");

        p.append("## 教学重点与难点\n\n");
        p.append("- 重点：").append(nz(sb.getNodeName())).append("的核心概念与基本方法\n");
        p.append("- 难点：从生活情境抽象到数学表达\n\n");

        p.append("## 教学过程\n\n");
        boolean anyStep = false;
        for (String kind : LESSON_FLOW) {
            List<Storyboard.Segment> group = byKind(sb, kind);
            if (group.isEmpty()) {
                continue;
            }
            anyStep = true;
            for (Storyboard.Segment s : group) {
                p.append("### ").append(idx(s)).append("｜").append(title(s))
                        .append("（约 ").append(s.getSeconds() == null ? "—" : s.getSeconds())
                        .append(" 秒）\n\n");
                p.append("**教师活动**：").append(nz(s.getNarration())).append("\n\n");
                if (!s.getBoardLines().isEmpty()) {
                    p.append("**板书**：").append(String.join(" / ", s.getBoardLines())).append("\n\n");
                }
                appendQuestionsForPlan(p, s, questions);
            }
        }
        if (!anyStep) {
            p.append("_该知识点暂无可引用的讲解分段，请先在教学内容中生成讲解。_\n\n");
        }

        p.append("## 作业布置\n\n");
        p.append("- 完成本知识点配套题库中的全部题目\n");
        p.append("- 用自己的话复述一遍本讲的核心概念\n\n");

        if (notBlank(extra)) {
            p.append("## 教师补充说明\n\n").append(extra.trim()).append("\n\n");
        }
        p.append("> 本教案由讲解 IR 渲染生成（讲解是唯一真源），修改讲解后重新生成即可同步。\n");
        return p.toString();
    }

    private static void appendQuestionsForPlan(StringBuilder p, Storyboard.Segment s,
                                               Map<Long, Question> questions) {
        if (questions == null || s.getQuestionIds().isEmpty()) {
            return;
        }
        p.append("**题目**：\n\n");
        for (Long qid : s.getQuestionIds()) {
            Question q = questions.get(qid);
            if (q != null) {
                p.append("- ").append(oneLine(q.getStem())).append("\n");
            }
        }
        p.append("\n");
    }

    private static List<Storyboard.Segment> byKind(Storyboard sb, String kind) {
        return sb.getSegments().stream().filter(s -> kind.equals(s.getKind())).toList();
    }

    // ==================== 摘要（列表页用）====================

    /** IR 摘要：一行说清「这份讲解讲了什么」，给列表页和教案顶部用 */
    public static Map<String, Object> toSummary(Storyboard sb) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (sb == null) {
            m.put("hasStoryboard", false);
            return m;
        }
        m.put("hasStoryboard", true);
        m.put("segmentCount", sb.segmentCount());
        m.put("totalSeconds", sb.totalSeconds());
        m.put("kinds", sb.kindsInOrder());
        m.put("kindLabels", sb.kindsInOrder().stream().map(StoryboardRenderer::kindLabel).toList());
        return m;
    }

    // ==================== 工具 ====================

    private static String idx(Storyboard.Segment s) {
        return s.getIndex() == null ? "?" : String.valueOf(s.getIndex());
    }

    private static String title(Storyboard.Segment s) {
        if (notBlank(s.getTitle())) {
            return s.getTitle();
        }
        return kindLabel(s.getKind());
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}