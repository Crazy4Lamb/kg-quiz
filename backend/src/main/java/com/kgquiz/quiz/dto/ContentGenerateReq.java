package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 生成知识点教学内容。
 *
 * @param type             内容类型：LECTURE / EXAMPLE / MICRO_LESSON / CARD，空则默认讲义
 * @param extraInstruction 教师补充要求，直接拼进提示词
 * @param replaceExisting  同一知识点同类型是否覆盖生成（true 则版本号 +1，旧版退回草稿）
 */
public record ContentGenerateReq(
        @NotNull Long nodeId,
        String type,
        String extraInstruction,
        Boolean replaceExisting) {
}
