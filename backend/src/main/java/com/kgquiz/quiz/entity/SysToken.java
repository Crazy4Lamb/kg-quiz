package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_token")
public class SysToken {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String token;

    private Long userId;

    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}
