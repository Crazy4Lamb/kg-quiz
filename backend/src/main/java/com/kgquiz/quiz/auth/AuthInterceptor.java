package com.kgquiz.quiz.auth;

import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Arrays;

/**
 * 登录校验 + 角色鉴权。
 * 先认证（无令牌 / 令牌失效 → 401），再按 @RequireRole 授权（角色不符 → 403）。
 * 未标注注解的接口只要求登录。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final AuthService authService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 跨域预检直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 非 Controller 方法（静态资源、错误页等）不参与鉴权
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        CurrentUser user = authService.resolve(Tokens.from(request));
        UserContext.set(user);

        RequireRole required = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required != null && Arrays.stream(required.value())
                .noneMatch(role -> role == user.role() || user.role().implies(role))) {
            // preHandle 抛错时 MVC 不会调用本拦截器的 afterCompletion。
            UserContext.clear();
            String allowed = Arrays.stream(required.value()).map(UserRole::label)
                    .reduce((a, b) -> a + " / " + b).orElse("");
            throw new ForbiddenException("当前为" + user.role().label() + "账号，无权访问该功能（需要" + allowed + "）");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 线程复用，必须清理，否则会串身份
        UserContext.clear();
    }
}
