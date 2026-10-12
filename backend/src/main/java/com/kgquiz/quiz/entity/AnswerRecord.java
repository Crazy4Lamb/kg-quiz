package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("answer_record")
public class AnswerRecord {

    /** 机器判分 */
    public static final String BY_AUTO = "AUTO";
    /** 教师人工批改 */
    public static final String BY_MANUAL = "MANUAL";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentId;

    private Long examId;

    /** 所属指派（教师组卷的作业才有） */
    private Long assignmentId;

    private Long questionId;

    private Long nodeId;

    /** 学生作答原文 */
    private String studentAnswer;

    /** 机器判分：客观题按答案精确匹配；主观题按关键词命中率给参考分 */
    private BigDecimal autoScore;

    /** 实际得分，教师改分后以这里为准 */
    private BigDecimal score;

    private BigDecimal fullScore;

    private String gradedBy;

    private String comment;

    private LocalDateTime createdAt;
}
