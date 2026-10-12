package com.kgquiz.quiz.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * 约定：HTTP 状态码与业务码一致，前端 axios 才能在 error 分支取到 message。
 * （早期版本业务异常返回 HTTP 200 + body.code=400，会被前端的成功分支吞掉，已修正。）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return build(HttpStatus.BAD_REQUEST, 400, e.getMessage());
    }

    /** 未登录或令牌失效：返回真正的 401，前端据此跳登录页 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<R<Void>> handleUnauthorized(UnauthorizedException e) {
        log.warn("未认证: {}", e.getMessage());
        return build(HttpStatus.UNAUTHORIZED, 401, e.getMessage());
    }

    /** 角色不足或越权访问他人数据：返回真正的 403 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<R<Void>> handleForbidden(ForbiddenException e) {
        log.warn("越权访问: {}", e.getMessage());
        return build(HttpStatus.FORBIDDEN, 403, e.getMessage());
    }

    /** 参数校验失败：拼成 "字段: 原因；字段: 原因"，不要把 Spring 的原始长串抛给前端 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .distinct()
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败: {}", message);
        return build(HttpStatus.BAD_REQUEST, 400, message.isEmpty() ? "参数校验失败" : message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<R<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return build(HttpStatus.BAD_REQUEST, 400, "缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<R<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        return build(HttpStatus.BAD_REQUEST, 400, "请求体格式错误，无法解析 JSON");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNoResource(NoResourceFoundException e) {
        return build(HttpStatus.NOT_FOUND, 404, "接口不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleOther(Exception e) {
        log.error("系统异常", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, 500, "系统异常: " + e.getMessage());
    }

    private ResponseEntity<R<Void>> build(HttpStatus status, int code, String message) {
        return ResponseEntity.status(status).body(R.fail(code, message));
    }

    private String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }
}
