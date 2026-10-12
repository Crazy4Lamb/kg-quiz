package com.kgquiz.quiz.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 机器判分。
 *
 * <p>客观题（单选 / 判断 / 多选 / 填空）可以精确判定，直接给分；
 * 简答题没法精确判定，就按<b>要点覆盖率</b>给一个参考分并标记"待人工复核"，
 * 由教师在批改台上确认或改写 —— 这样学生交卷就能立刻看到分数，
 * 老师也不用从头逐题批。</p>
 */
public final class AutoGrader {

    private AutoGrader() {
    }

    private static final String T_SINGLE = "SINGLE_CHOICE";
    private static final String T_MULTI = "MULTI_CHOICE";
    private static final String T_TF = "TRUE_FALSE";
    private static final String T_FILL = "FILL_BLANK";

    /** 主观题覆盖率低于该值时，在批改台标红提示 */
    public static final double SHORT_WARN_RATIO = 0.6;

    /**
     * @param score       机器给出的分数
     * @param needsReview 是否必须由教师复核（主观题恒为 true）
     * @param note        判分说明，会作为初始评语落库
     */
    public record Result(BigDecimal score, boolean needsReview, String note) {
    }

    public static Result grade(String questionType, String correctAnswer,
                               BigDecimal fullScore, String studentAnswer) {
        BigDecimal full = fullScore == null ? BigDecimal.ZERO : fullScore;
        String type = questionType == null ? "" : questionType.trim().toUpperCase();
        String correct = correctAnswer == null ? "" : correctAnswer.trim();
        String given = studentAnswer == null ? "" : studentAnswer.trim();

        if (given.isEmpty()) {
            return new Result(BigDecimal.ZERO, false, "未作答");
        }

        if (T_MULTI.equals(type)) {
            boolean ok = !multiSet(correct).isEmpty() && multiSet(given).equals(multiSet(correct));
            return new Result(ok ? full : BigDecimal.ZERO, false,
                    ok ? "多选全对" : "多选/漏选，本题不得分");
        }
        if (T_SINGLE.equals(type) || T_TF.equals(type)) {
            boolean ok = norm(given).equals(norm(correct));
            return new Result(ok ? full : BigDecimal.ZERO, false, ok ? "回答正确" : "回答错误");
        }
        if (T_FILL.equals(type)) {
            // 一个空可以有多种写法，用 | 分隔
            for (String alt : correct.split("\\|")) {
                if (!alt.isBlank() && norm(alt).equals(norm(given))) {
                    return new Result(full, false, "填空正确");
                }
            }
            return new Result(BigDecimal.ZERO, false, "填空错误，正确答案：" + correct);
        }

        // 简答题：要点覆盖率给参考分，始终标记待复核
        double ratio = coverage(correct, given);
        BigDecimal score = full.multiply(BigDecimal.valueOf(ratio))
                .setScale(1, RoundingMode.HALF_UP);
        if (score.compareTo(full) > 0) {
            score = full;
        }
        return new Result(score, true, String.format("要点覆盖约 %.0f%%%s", ratio * 100,
                ratio < SHORT_WARN_RATIO ? "，疑似未答到要点" : ""));
    }

    /** 参考答案 vs 学生答案的要点覆盖率：去掉标点空白后，相邻二字组合的命中比例 */
    public static double coverage(String reference, String given) {
        String ref = norm(reference);
        String stu = norm(given);
        if (ref.isEmpty()) {
            return 0;
        }
        if (ref.length() < 2) {
            return stu.contains(ref) ? 1 : 0;
        }
        Set<String> grams = new LinkedHashSet<>();
        for (int i = 0; i + 2 <= ref.length(); i++) {
            grams.add(ref.substring(i, i + 2));
        }
        long hit = grams.stream().filter(stu::contains).count();
        return (double) hit / grams.size();
    }

    /** 多选题答案归一化为集合：支持 , ，、 空格 分隔，并忽略大小写 */
    private static Set<String> multiSet(String raw) {
        Set<String> set = new LinkedHashSet<>();
        if (raw == null) {
            return set;
        }
        for (String part : raw.split("[,，、;；\\s]+")) {
            String v = norm(part);
            if (!v.isEmpty()) {
                set.add(v);
            }
        }
        return set;
    }

    /** 归一化：去掉所有空白与常见标点，全角转半角，统一小写 */
    static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            char ch = c;
            // 全角 ASCII → 半角
            if (ch >= 0xFF01 && ch <= 0xFF5E) {
                ch = (char) (ch - 0xFEE0);
            }
            if ("，。；、,.;:：!！?？()（）[]【】\"'“”‘’…—-_/\\|".indexOf(ch) >= 0) {
                continue;
            }
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString();
    }
}
