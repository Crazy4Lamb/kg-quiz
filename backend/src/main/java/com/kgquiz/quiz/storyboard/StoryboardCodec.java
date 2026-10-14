package com.kgquiz.quiz.storyboard;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 讲解 IR 的序列化工具。
 *
 * <p>IR 以 JSON 字符串存 {@code node_content.storyboard}。这里只做「转字符串 / 从字符串还原」
 * 两件事，<b>不做任何内容加工</b>——渲染是渲染器的责任。</p>
 *
 * <p>反序列化刻意宽容（忽略未知字段）：IR_VERSION 递增后旧数据仍可读，
 * 渲染器读到不认识的 kind / type 时按 TEXT 兜底即可，不会因为格式演进而整体崩掉。</p>
 */
@Slf4j
public final class StoryboardCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private StoryboardCodec() {
    }

    public static String write(Storyboard sb) {
        if (sb == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(sb);
        } catch (Exception e) {
            log.warn("讲解 IR 序列化失败：{}", e.getMessage());
            return null;
        }
    }

    /** 从 JSON 还原；解析失败返回 null 而不是抛异常——旧数据不该让整个列表接口挂掉 */
    public static Storyboard read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Storyboard.class);
        } catch (Exception e) {
            log.warn("讲解 IR 解析失败，按无 IR 处理：{}", e.getMessage());
            return null;
        }
    }
}