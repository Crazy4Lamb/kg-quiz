package com.kgquiz.quiz.common;

/** 已登录但无权限 → HTTP 403 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
