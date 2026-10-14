package com.kgquiz.quiz.storyboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.entity.Question;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内容自检门禁。
 *
 * <p>借鉴 edulab 的 {@code --check}：任何要产出成品内容的步骤，先跑一遍无副作用的检查，
 * 不通过就不往下走。对应到这里是——<b>IR 生成后、落草稿前</b>先自检。</p>
 *
 * <p>为什么比 edulab 更需要：大模型写的讲义里，"引用的题目不属于这个知识点"
 * "旁白提到了库里根本没有的答案" 这类错误现在完全拦不住，一旦流到学生手上就是知识污染。
 * 这些错误<b>不需要 LLM 就能查</b>，所以属于纯确定性检查。</p>
 *
 * <p>检查项：
 * <ol>
 *   <li><b>结构闭合</b>：段落序号连续、每段有旁白、kind 合法</li>
 *   <li><b>题目归属</b>：IR 引用的 questionIds 必须真的关联到该知识点（防幻觉）</li>
 *   <li><b>答案一致</b>：画面里出现的答案表述必须与题库 answer 一致（防模型算错）</li>
 *   <li><b>视觉锚点</b>：anchorLine 必须落在本段实际旁白行数内</li>
 * </ol>
 */
@Slf4j
public final class StoryboardChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> VALID_KINDS = Set.of(
            Storyboard.Segment.K_HOOK, Storyboard.Segment.K_TEACH, Storyboard.Segment.K_DEMO,
            Storyboard.Segment.K_EXAMPLE, Storyboard.Segment.K_PRACTICE, Storyboard.Segment.K_RECAP);

    /** 视觉最多落在旁白第 12 行；超过多半是模型把整段话都塞进一行导致的 */
    private static final int MAX_ANCHOR_LINE = 12;

    private StoryboardChecker() {
    }

    /**
     * 跑自检。
     *
     * @param sb                 待检 IR
     * @param questions          该知识点真实关联的题目（questionId → Question），用来核归属与答案
     * @return 报告：{@code passed} 全过、{@code needsReview} 存在 ERROR 级问题、{@code issues} 逐条
     */
    public static Map<String, Object> check(Storyboard sb, Map<Long, Question> questions) {
        List<Map<String, Object>> issues = new ArrayList<>();
        if (sb == null || sb.segmentCount() == 0) {
            issues.add(issue("ERROR", "STRUCTURE", "讲解 IR 为空，没有任何分段"));
            return report(issues);
        }

        int expect = 1;
        for (Storyboard.Segment s : sb.getSegments()) {
            String at = "第 " + (s.getIndex() == null ? "?" : s.getIndex()) + " 段";

            // ① 结构闭合
            if (s.getIndex() == null || s.getIndex() != expect) {
                issues.add(issue("ERROR", "STRUCTURE", at + "序号不连续（期望 " + expect + "），视觉锚点会错位"));
            }
            if (!VALID_KINDS.contains(s.getKind() == null ? "" : s.getKind())) {
                issues.add(issue("ERROR", "STRUCTURE", at + "分段类型非法：" + s.getKind()));
            }
            if (isBlank(s.getNarration())) {
                issues.add(issue("ERROR", "STRUCTURE", at + "没有旁白，学生端会渲染成空白段"));
            }
            expect++;

            // ④ 视觉锚点
            int narrationLines = countLines(s.getNarration());
            for (Storyboard.Visual v : s.getVisuals()) {
                if (v.getAnchorLine() != null
                        && (v.getAnchorLine() < 1 || v.getAnchorLine() > MAX_ANCHOR_LINE
                        || (narrationLines > 0 && v.getAnchorLine() > narrationLines))) {
                    issues.add(issue("WARN", "ANCHOR",
                            at + "画面锚点第 " + v.getAnchorLine() + " 行，超出该段旁白实际行数（"
                                    + narrationLines + "），渲染时会跳到错误位置"));
                }
                if (v.getAnchorRatio() != null
                        && (v.getAnchorRatio() < 0 || v.getAnchorRatio() > 1)) {
                    issues.add(issue("ERROR", "ANCHOR", at + "画面锚点比例越界：" + v.getAnchorRatio()));
                }
                if (isBlank(v.getText())) {
                    issues.add(issue("WARN", "ANCHOR", at + "画面要素没有内容"));
                }
            }

            // ② 题目归属 —— 这条最要紧，是防幻觉的主力
            for (Long qid : s.getQuestionIds()) {
                if (questions == null || !questions.containsKey(qid)) {
                    issues.add(issue("ERROR", "QUESTION_SCOPE",
                            at + "引用了不属于本知识点的题目 id=" + qid + "，大模型很可能编造了"));
                }
            }
        }

        // ③ 答案一致：画面里写了"答案 X"就必须与题库一致
        if (questions != null) {
            for (Storyboard.Segment s : sb.getSegments()) {
                for (Long qid : s.getQuestionIds()) {
                    Question q = questions.get(qid);
                    if (q == null) {
                        continue;
                    }
                    for (Storyboard.Visual v : s.getVisuals()) {
                        String conflict = findAnswerConflict(v.getText(), q.getAnswer());
                        if (conflict != null) {
                            issues.add(issue("ERROR", "ANSWER_MISMATCH", conflict));
                        }
                    }
                }
            }
        }

        return report(issues, sb.segmentCount());
    }

    /**
     * 画面文本里若出现"答案/答：X"，而 X 与题库答案不一致，就报冲突。
     *
     * <p>只做<b>高置信</b>判定：能干净抽出一个短 token 才比。抽不出来宁可放过，
     * 也不能因为解析不规范而误报——误报多了这道门禁就没人看了。</p>
     */
    private static String findAnswerConflict(String visualText, String realAnswer) {
        if (isBlank(visualText) || isBlank(realAnswer)) {
            return null;
        }
        String[] parts = visualText.split("答案|答[:：]|正确选项", -1);
        if (parts.length < 2) {
            return null;
        }
        String claimed = parts[1].trim();
        // 只取紧跟其后的短片段（标点或空格为界）
        claimed = claimed.split("[，,。；;\\s]")[0].trim();
        if (claimed.isEmpty() || claimed.length() > 12) {
            return null;
        }
        String real = realAnswer.trim();
        boolean same = real.equals(claimed)
                || real.contains(claimed)
                || normalize(claimed).equals(normalize(real));
        if (same) {
            return null;
        }
        return "画面里写的答案「" + claimed + "」与题库答案「" + real + "」不一致";
    }

    /** 去掉空白与常见标点后比对，避免 "0.27" vs "0.27。" 之类误判 */
    private static String normalize(String s) {
        return s.replaceAll("[\\s。．.，,、；;：:（）()【】\\[\\]]", "");
    }

    private static int countLines(String narration) {
        if (isBlank(narration)) {
            return 0;
        }
        return narration.split("\\R").length;
    }

    private static Map<String, Object> issue(String level, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("code", code);
        m.put("message", message);
        return m;
    }

    private static Map<String, Object> report(List<Map<String, Object>> issues) {
        return report(issues, 0);
    }

    private static Map<String, Object> report(List<Map<String, Object>> issues, int segmentCount) {
        long errors = issues.stream().filter(i -> "ERROR".equals(i.get("level"))).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("passed", errors == 0);
        m.put("errorCount", errors);
        m.put("warnCount", issues.size() - errors);
        m.put("needsReview", errors > 0);
        // 分段数带上，生成侧据此写摘要，免得出现「0分段」这种自相矛盾的文案
        m.put("segmentCount", segmentCount);
        m.put("issues", issues);
        return m;
    }

    /** 把报告存成 JSON 字符串（存 check_report 列） */
    public static String toJson(Map<String, Object> report) {
        if (report == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(report);
        } catch (Exception e) {
            log.warn("自检报告序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}