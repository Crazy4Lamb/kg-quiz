package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 作业指派：把一份卷子发给一个学生。学生只能看到指派给自己的卷子。
 */
@Data
@TableName("exam_assignment")
public class ExamAssignment {

    /** 已发布待作答 */
    public static final String ST_ASSIGNED = "ASSIGNED";
    /** 已提交，含主观题待教师批改 */
    public static final String ST_SUBMITTED = "SUBMITTED";
    /** 已定稿（全客观题自动定稿，或教师批改完成） */
    public static final String ST_GRADED = "GRADED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long examId;

    private Long studentId;

    private String status;

    private BigDecimal totalScore;

    private BigDecimal fullScore;

    /** 待人工批改的题数 */
    private Integer pendingCount;

    private LocalDateTime submittedAt;

    private LocalDateTime gradedAt;

    private Long graderId;

    private LocalDateTime createdAt;
}
