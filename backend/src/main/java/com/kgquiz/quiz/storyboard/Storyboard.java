package com.kgquiz.quiz.storyboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识点讲解分镜 IR —— 讲解内容的<b>唯一真源</b>。
 *
 * <p>设计取舍：参考 edulab 的 {@code script.json}，先产出一份结构化中间表示，
 * 再交给不同渲染后端（Markdown 阅读 / 教案 / 后续视频）。这样每加一种呈现形式
 * 不用重写生成逻辑，也让内容可被逐段跳读、可被教案引用。</p>
 *
 * <p><b>关键设计：视觉动作锚定段落序号，而非绝对秒数。</b>
 * 早期视频脚本把动画写成「第 3.5 秒出现」，改一个字就要重排全片；
 * 这里每段的 {@link Visual#anchorLine} 表示"落在本段旁白的第几行"，
 * {@link Visual#anchorRatio} 表示该行内部的比例。位置是相对的，
 * 因此天然支持倍速、跳读、逐步呈现，也天然支持"先出 Markdown、以后出视频"。</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Storyboard {

    /** IR 结构版本；结构不兼容变化时递增，渲染器据此判断能否消费 */
    public static final int IR_VERSION = 1;

    private Long nodeId;
    private String nodeName;
    /** 一句话说明这个知识点要讲清什么 */
    private String objective;
    /** 讲解面向的学段，供渲染器决定语气 */
    private String audience;
    private List<Segment> segments = new ArrayList<>();

    // ==================== 分段 ====================

    /**
     * 一个讲解段落 = 一段旁白 + 若干画面要素。
     *
     * <p>段落用 {@link #kind} 而非标题区分，因为教案要按 kind 组织教学环节
     * （导入 → 新授 → 例题 → 练习 → 小结），标题是给人看的，kind 是给程序看的。</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Segment {

        /** 段落类型。渲染教案时按此顺序编排教学环节。 */
        public static final String K_HOOK = "HOOK";      // 情境导入
        public static final String K_TEACH = "TEACH";    // 概念新授
        public static final String K_DEMO = "DEMO";      // 推导 / 演算演示
        public static final String K_EXAMPLE = "EXAMPLE";// 例题精讲
        public static final String K_PRACTICE = "PRACTICE";// 随堂练习
        public static final String K_RECAP = "RECAP";    // 小结 / 回顾

        /** 序号，从 1 开始且连续。视觉锚点依赖它，所以不能乱序 */
        private Integer index;
        private String kind = K_TEACH;
        private String title;
        /** 旁白全文，一段内不要换行——换行即视为新的行锚点 */
        private String narration;
        /**
         * 预估时长（秒），<b>仅用于排期与进度条</b>，不参与视觉定位。
         * 没有它也能渲染（Markdown 完全不需要时长）。
         */
        private Integer seconds;
        private List<Visual> visuals = new ArrayList<>();
        /**
         * 本段引用的题库题目 id。教学环节里例题/练习段用它挂真实题目，
         * 避免教案与题库脱节，也让自检能核对"引用的题是否真属于该节点"。
         */
        private List<Long> questionIds = new ArrayList<>();
        /** 黑板 / 幻灯片要点，逐行 */
        private List<String> boardLines = new ArrayList<>();

        public Segment addVisual(Visual v) {
            if (v != null) {
                visuals.add(v);
            }
            return this;
        }

        public Segment withQuestion(Long qid) {
            if (qid != null && questionIds == null) {
                questionIds = new ArrayList<>();
            }
            if (qid != null) {
                questionIds.add(qid);
            }
            return this;
        }

        public Segment board(String... lines) {
            for (String l : lines) {
                if (l != null && !l.isBlank()) {
                    boardLines.add(l);
                }
            }
            return this;
        }
    }

    // ==================== 画面要素 ====================

    /**
     * 画面要素。
     *
     * <p>{@code anchorLine} 是段落内旁白的行号（从 1 起），{@code anchorRatio} 是该行
     * 内部的位置比例（0~1）。两者共同构成"相对时间"：渲染器实际播放时用
     * 「该段已播时长 × (行占比 + 行内比例)」换算绝对时间，因此倍速不影响对齐。</p>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Visual {

        public static final String V_TEXT = "TEXT";        // 文字/概念卡
        public static final String V_FORMULA = "FORMULA";  // 公式推导
        public static final String V_QUESTION = "QUESTION";// 题目
        public static final String V_DIAGRAM = "DIAGRAM";  // 图示

        private String type = V_TEXT;
        /** 画面上显示的内容。公式用 $...$ 包裹，前端可交给公式渲染 */
        private String text;
        /** 相对锚点：段落内旁白行号（从 1 起） */
        private Integer anchorLine;
        /** 相对锚点：该行内部比例 0~1 */
        private Double anchorRatio;
        /** 出现后停留秒数，0 表示跟随该段结束 */
        private Integer holdSeconds;

        public static Visual text(String text) {
            Visual v = new Visual();
            v.setType(V_TEXT);
            v.setText(text);
            return v;
        }

        public static Visual formula(String text) {
            Visual v = new Visual();
            v.setType(V_FORMULA);
            v.setText(text);
            return v;
        }

        public static Visual question(String text, Long questionId) {
            Visual v = new Visual();
            v.setType(V_QUESTION);
            v.setText(text);
            if (questionId != null) {
                v.setAnchorLine(1);
            }
            return v;
        }

        public Visual at(Integer line, Double ratio) {
            this.anchorLine = line;
            this.anchorRatio = ratio;
            return this;
        }

        public Visual hold(Integer seconds) {
            this.holdSeconds = seconds;
            return this;
        }
    }

    // ==================== 便捷方法 ====================

    public Storyboard seg(Segment s) {
        if (s != null) {
            segments.add(s);
        }
        return this;
    }

    /** 总预估时长（秒）；所有分段都没填时返回 0，渲染器应视为"未知时长"而非"零时长" */
    public int totalSeconds() {
        return segments.stream()
                .filter(s -> s.getSeconds() != null)
                .mapToInt(Segment::getSeconds).sum();
    }

    /**
     * 按 kind 取分段，教案编排教学环节时用。
     *
     * @return 形如 [HOOK, TEACH, EXAMPLE, PRACTICE, RECAP] 的顺序列表（去重保序）
     */
    public List<String> kindsInOrder() {
        List<String> out = new ArrayList<>();
        for (Segment s : segments) {
            if (s.getKind() != null && !out.contains(s.getKind())) {
                out.add(s.getKind());
            }
        }
        return out;
    }

    public int segmentCount() {
        return segments == null ? 0 : segments.size();
    }
}