package com.kgquiz.quiz;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.kgquiz.quiz.mapper")
public class KgQuizApplication {

    public static void main(String[] args) {
        SpringApplication.run(KgQuizApplication.class, args);
    }
}
