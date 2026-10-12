package com.kgquiz.quiz.common;

/** 未登录 / 令牌失效 → HTTP 401 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
