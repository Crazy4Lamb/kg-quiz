package com.kgquiz.quiz.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建学生时选择的教材维度，用于定位要复制的模板图谱。
 * username / password 可选：填了就用指定登录名开户，不填则自动生成 stu{id} + 默认密码。
 */
public record StudentCreateReq(
        @NotBlank String name,
        @NotBlank String subject,
        @NotBlank String textbook,
        @NotBlank String grade,
        @NotBlank String term,
        String username,
        String password,
        /** 归属机构与班级：机构管理员/教师留空即可（落在自己机构）；平台管理员必须指定机构 */
        Long orgId,
        Long campusId,
        Long clazzId) {
}
