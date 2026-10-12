package com.kgquiz.quiz.auth;

import jakarta.servlet.http.HttpServletRequest;

/** 从请求中取出登录令牌，兼容标准 Authorization 头与自定义头 */
public final class Tokens {

    public static final String HEADER = "Authorization";
    public static final String BEARER = "Bearer ";

    private Tokens() {
    }

    public static String from(HttpServletRequest request) {
        String auth = request.getHeader(HEADER);
        if (auth != null && auth.startsWith(BEARER)) {
            String value = auth.substring(BEARER.length()).trim();
            if (!value.isEmpty()) {
                return value;
            }
        }
        String custom = request.getHeader("X-Token");
        if (custom != null && !custom.isBlank()) {
            return custom.trim();
        }
        String param = request.getParameter("token");
        return param == null || param.isBlank() ? null : param.trim();
    }
}
