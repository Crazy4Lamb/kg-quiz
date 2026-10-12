package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("student_node_state")
public class StudentNodeState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long studentGraphId;

    private Long sourceNodeId;

    private BigDecimal mastery;

    private LocalDateTime lastPracticedAt;
}
