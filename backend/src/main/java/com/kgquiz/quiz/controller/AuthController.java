package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.Tokens;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.dto.AccountCreateReq;
import com.kgquiz.quiz.dto.LoginReq;
import com.kgquiz.quiz.dto.PasswordReq;
import com.kgquiz.quiz.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 登录（白名单，无需令牌） */
    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req.username(), req.password()));
    }

    /** 登出：作废当前令牌 */
    @PostMapping("/logout")
    public R<Void> logout(HttpServletRequest request) {
        authService.logout(Tokens.from(request));
        return R.ok();
    }

    /** 当前登录者信息（前端启动时用它恢复会话与菜单） */
    @GetMapping("/me")
    public R<Map<String, Object>> me() {
        return R.ok(authService.profile(UserContext.require()));
    }

    /** 修改自己的密码，改完所有会话失效需重新登录 */
    @PutMapping("/me/password")
    public R<Void> changeOwnPassword(@Valid @RequestBody PasswordReq req) {
        authService.changeOwnPassword(UserContext.require().userId(), req.oldPassword(), req.newPassword());
        return R.ok();
    }

    // ==================== 账号管理：平台管理员 / 机构管理员 ====================
    // 机构管理员只能看到并操作本机构账号（服务层按 org 强制过滤，跨机构一律 403）。

    @GetMapping("/accounts")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<List<Map<String, Object>>> accounts() {
        return R.ok(authService.listAccounts());
    }

    @PostMapping("/accounts")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Map<String, Object>> createAccount(@Valid @RequestBody AccountCreateReq req) {
        return R.ok(authService.createAccount(req));
    }

    @PutMapping("/accounts/{id}/password")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody PasswordReq req) {
        authService.resetPassword(id, req.newPassword());
        return R.ok();
    }

    @PutMapping("/accounts/{id}/enabled")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        authService.setEnabled(id, enabled);
        return R.ok();
    }

    @DeleteMapping("/accounts/{id}")
    @RequireRole({UserRole.ADMIN, UserRole.ORG_ADMIN})
    public R<Void> deleteAccount(@PathVariable Long id) {
        authService.deleteAccount(id, UserContext.require().userId());
        return R.ok();
    }
}
