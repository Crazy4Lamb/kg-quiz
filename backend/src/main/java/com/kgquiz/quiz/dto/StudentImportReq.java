package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * 学生批量导入到班级。
 *
 * <p>两种写法任选其一，都支持（同时给则 {@code names} 优先）：</p>
 * <ul>
 *   <li>{@code names} —— 前端从表格 / 文本框解析好的姓名数组，逐个建档；</li>
 *   <li>{@code csvText} —— 直接粘 Excel 里复制的多行文本，服务端按行拆分（每行一个姓名，
 *       可带制表符 / 逗号 / 中文逗号分隔的其他列，取第一列）。</li>
 * </ul>
 *
 * <p>姓名去重按「同班级内同名」判断：同班已有同名学生时记入 {@code duplicated} 而不重复建档，
 * 不会报错中断整批 —— 教务导入场景里一次几十行，一行重复不该让剩下的全废。</p>
 */
public record StudentImportReq(
        List<String> names,
        String csvText) {

    /** 姓名最长长度，与 student.name 字段对齐，避免超长直接撞数据库异常 */
    public static final int MAX_NAME_LEN = 64;

    /**
     * 把两种输入统一成一份清洗后的姓名列表。
     *
     * <p>清洗规则：去空白、丢空行、截断超长、按出现顺序去重（同一批里重复的只算一次）。
     * 真正的「是否已存在」判断在 Service 侧查库，这里只处理本批内部的重复。</p>
     */
    public java.util.List<String> resolvedNames() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (names != null) {
            for (String n : names) {
                String v = cleanName(n);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        if (csvText != null && !csvText.isBlank()) {
            for (String line : csvText.split("[\\r\\n]+")) {
                // 一行可能有多列（从 Excel 复制），只取第一列
                String first = line.split("[\\t,，]")[0];
                String v = cleanName(first);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return new java.util.ArrayList<>(out);
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        // 去掉从 Excel 复制常带的不可见字符（BOM、零宽空格）
        v = v.replaceAll("[\\uFEFF\\u200B\\u200C\\u200D]", "");
        if (v.isEmpty()) {
            return null;
        }
        return v.length() > MAX_NAME_LEN ? v.substring(0, MAX_NAME_LEN) : v;
    }
}