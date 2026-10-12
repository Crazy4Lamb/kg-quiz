package com.kgquiz.quiz.dto;

/**
 * 教师给学生布置学习任务。
 *
 * @param type  任务类型：LEARN / PRACTICE / REVIEW，空则默认 LEARN
 * @param dueAt 截止时间，ISO-8601（如 2026-10-20T18:00），可为空
 */
public record TeacherTaskReq(
        Long studentId,
        Long nodeId,
        String type,
        String title,
        Long examId,
        String dueAt) {
}
