package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("question")
public class Question {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属机构（题库按机构隔离） */
    private Long orgId;

    private String subject;

    private String textbook;

    /**
     * SINGLE_CHOICE / MULTI_CHOICE / TRUE_FALSE / FILL_BLANK / SHORT_ANSWER
     */
    private String questionType;

    private String stem;

    /**
     * 选项 JSON 数组字符串，如 ["A. 1", "B. 2"]，非选择题为 null
     */
    private String options;

    private String answer;

    private String analysis;

    private BigDecimal score;

    private Integer difficulty;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
