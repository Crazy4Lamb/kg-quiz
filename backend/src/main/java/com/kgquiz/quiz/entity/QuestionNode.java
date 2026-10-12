package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("question_node")
public class QuestionNode {

    private Long questionId;

    private Long nodeId;
}
