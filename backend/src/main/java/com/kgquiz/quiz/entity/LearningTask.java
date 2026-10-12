package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学习任务：学生端「今日学习」的一条待办。
 *
 * <p>由诊断结果与推荐规则驱动生成，也可由教师手工布置（{@code source=TEACHER}）。</p>
 */
@Data
@TableName("learning_task")
public class LearningTask {

    public static final String T_DIAGNOSIS = "DIAGNOSIS";
    public static final String T_LEARN = "LEARN";
    public static final String T_PRACTICE = "PRACTICE";
    public static final String T_REVIEW = "REVIEW";

    public static final String S_PENDING = "PENDING";
    public static final String S_DONE = "DONE";
    public static final String S_SKIPPED = "SKIPPED";

    public static final String SRC_SYSTEM = "SYSTEM";
    public static final String SRC_TEACHER = "TEACHER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orgId;

    private Long studentId;

    private Long nodeId;

    private String type;

    private String title;

    /** 推荐理由，学生能看到「为什么让我做这个」 */
    private String reason;

    private Integer priority;

    private String source;

    private String status;

    private Long examId;

    private Long contentId;

    private LocalDateTime dueAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
}
