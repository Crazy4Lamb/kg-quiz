package com.kgquiz.quiz.dto;

/** 教师编辑教学内容（任意字段为空则保持原值） */
public record ContentUpdateReq(
        String title,
        String summary,
        String body) {
}
