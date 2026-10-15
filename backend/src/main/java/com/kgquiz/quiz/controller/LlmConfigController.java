package com.kgquiz.quiz.controller;

import com.kgquiz.quiz.auth.CurrentUser;
import com.kgquiz.quiz.auth.RequireRole;
import com.kgquiz.quiz.auth.UserContext;
import com.kgquiz.quiz.auth.UserRole;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.common.ForbiddenException;
import com.kgquiz.quiz.common.R;
import com.kgquiz.quiz.llm.LlmAuth;
import com.kgquiz.quiz.llm.LlmProtocol;
import com.kgquiz.quiz.service.LlmClient;
import com.kgquiz.quiz.service.LlmConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型接入配置。
 *
 * <p>管的是「用哪个厂商、哪个模型、什么协议」这类运行期决策，因此做在库里而不是
 * application.yml —— 教研想对比几个模型时，不该为此重启服务。</p>
 *
 * <p>权限：读 TEACHER+（教师要知道自己生成的内容出自什么模型）；
 * 写仅 ADMIN（平台级配置，含密钥）。机构级配置同样限 ADMIN，
 * 避免任一机构管理员就能把平台的 Key 换成任意端点。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmConfigController {

    private final LlmClient llm;
    private final LlmConfigService configService;

    /**
     * 总览：当前生效配置（脱敏）+ 预设目录 + 协议/鉴权枚举。
     * 前端「系统设置 → 大模型接入」页与内容管理页的状态条都读它。
     */
    @GetMapping("/overview")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> overview() {
        return R.ok(configService.overview(llm));
    }

    /** 只看当前是否可用（内容管理页顶部的状态条用，比 overview 轻） */
    @GetMapping("/status")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> status() {
        LlmClient.LlmSettings s = llm.settings();
        Map<String, Object> m = new LinkedHashMap<>(s.toSafeMap());
        boolean available = llm.available();
        m.put("available", available);
        String reason = llm.unavailableReason();
        m.put("reason", reason == null ? "" : reason);
        return R.ok(m);
    }

    /**
     * 保存配置。
     *
     * <p>{@code apiKey} 传空 / 不传 = 保持原值（管理台回显的是掩码，用户不改 Key 时
     * 该字段天然是空）；传 {@code "CLEAR"} 才真的清空。</p>
     */
    @PostMapping("/config")
    @RequireRole({UserRole.ADMIN})
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> body) {
        Long orgId = resolveOrgId(body.get("orgId"));
        validate(body);
        return R.ok(configService.safeRow(configService.save(orgId, body)));
    }

    /** 启用 / 停用。停用后回落环境变量兜底配置 */
    @PostMapping("/config/enabled")
    @RequireRole({UserRole.ADMIN})
    public R<Map<String, Object>> setEnabled(@RequestBody Map<String, Object> body) {
        Long orgId = resolveOrgId(body.get("orgId"));
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return R.ok(configService.safeRow(configService.setEnabled(orgId, enabled)));
    }

    /**
     * 试连通：真发一次最小请求，回显耗时与模型首句。
     *
     * <p>刻意做成<b>真实调用</b>而不是「ping 一下端点」—— 配错的典型情况是端点通、
     * Key 错、或模型名不存在，只有真发一次才知道。返回里区分「配置问题」与「网络问题」，
     * 因为两者排查方向完全不同。</p>
     *
     * <p>不落库：传了 body 就用 body 试（可测「还没保存的配置」），
     * 不传则测当前生效配置。密钥同样不落库也不回显。</p>
     */
    @PostMapping("/test")
    @RequireRole({UserRole.TEACHER, UserRole.ADMIN})
    public R<Map<String, Object>> test(@RequestBody(required = false) Map<String, Object> body) {
        if (body != null && !body.isEmpty() && !UserContext.require().isAdmin()) {
            throw new ForbiddenException("仅平台管理员可测试未保存配置；教师可测试当前生效配置");
        }
        long start = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();

        // 用请求体覆盖当前配置，便于测「尚未保存」的组合
        LlmClient.LlmSettings base = llm.settings();
        LlmProtocol protocol = body == null || body.get("protocol") == null
                ? base.protocol()
                : LlmProtocol.of(String.valueOf(body.get("protocol")));
        LlmAuth auth = body == null || body.get("auth") == null
                ? base.auth()
                : LlmAuth.of(String.valueOf(body.get("auth")));
        String baseUrl = pick(body, "baseUrl", base.baseUrl());
        String chatPath = pick(body, "chatPath", base.chatPath());
        String model = pick(body, "model", base.model());
        String authHeader = pick(body, "authHeader", base.authHeader());
        String apiKey = body != null && body.get("apiKey") != null
                && !String.valueOf(body.get("apiKey")).isBlank()
                ? String.valueOf(body.get("apiKey")).trim()
                : base.apiKey();
        boolean sameDestination = java.util.Objects.equals(baseUrl, base.baseUrl())
                && java.util.Objects.equals(auth, base.auth())
                && java.util.Objects.equals(authHeader, base.authHeader());
        if (!sameDestination && (body == null || str(body.get("apiKey")) == null || str(body.get("apiKey")).isBlank())) {
            apiKey = null;
        }

        m.put("protocol", protocol.name());
        m.put("protocolLabel", protocol.label());
        m.put("auth", auth.name());
        m.put("model", model);
        // 只回显脱敏后的端点：URL 里可能带 key（QUERY_KEY 模式）
        m.put("endpoint", maskUrl(protocol, baseUrl, chatPath, auth, apiKey));

        if (baseUrl == null || baseUrl.isBlank()) {
            m.put("ok", false);
            m.put("stage", "CONFIG");
            m.put("message", "还没填服务地址（base-url）");
            return R.ok(m);
        }
        if (model == null || model.isBlank()) {
            m.put("ok", false);
            m.put("stage", "CONFIG");
            m.put("message", "还没填模型名");
            return R.ok(m);
        }
        if (auth.keyRequired() && (apiKey == null || apiKey.isBlank())) {
            m.put("ok", false);
            m.put("stage", "CONFIG");
            m.put("message", "该鉴权方式需要 API Key，但还没填");
            return R.ok(m);
        }

        try {
            LlmClient.LlmSettings draft = new LlmClient.LlmSettings(true, protocol, auth,
                    baseUrl, chatPath, apiKey, authHeader, model,
                    base.timeoutSeconds(), 64, 0, base.presetCode(), "连通性测试草稿");
            String reply = llm.chatWithSettings(draft,
                    "你是一个连通性测试端点，请只回复一句话，不超过 20 个字，不要加引号或解释。",
                    "回复「已连接」两个字即可。",
                    false);
            m.put("ok", true);
            m.put("costMs", System.currentTimeMillis() - start);
            m.put("stage", "DONE");
            m.put("reply", reply);
            m.put("message", "连接成功");
        } catch (Exception e) {
            long cost = System.currentTimeMillis() - start;
            m.put("ok", false);
            m.put("costMs", cost);
            m.put("stage", classify(e));
            m.put("message", LlmClient.redact(rootMsg(e), apiKey));
            m.put("hint", hint(e, protocol));
        }
        return R.ok(m);
    }

    /** 区分「配置错了」与「网络/服务挂了」—— 两者排查方向完全不同 */
    private static String classify(Exception e) {
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        if (msg.contains("401") || msg.contains("403") || msg.contains("unauthorized")
                || msg.contains("api key") || msg.contains("鉴权") || msg.contains("密钥")) {
            return "AUTH";
        }
        if (msg.contains("404") || msg.contains("model") && msg.contains("not")) {
            return "ENDPOINT";
        }
        if (msg.contains("429") || msg.contains("quota") || msg.contains("额度") || msg.contains("余额")) {
            return "QUOTA";
        }
        if (msg.contains("connect") || msg.contains("timeout") || msg.contains("timed out")
                || msg.contains("refused") || msg.contains("unknownhost") || msg.contains("网络")) {
            return "NETWORK";
        }
        return "UNKNOWN";
    }

    private static String hint(Exception e, LlmProtocol protocol) {
        return switch (classify(e)) {
            case "AUTH" -> "端点能通但认证没过：检查 API Key 是否填对、是否已过期。"
                    + (protocol == LlmProtocol.ANTHROPIC ? "Anthropic 用 x-api-key，不是 Bearer。" : "");
            case "ENDPOINT" -> "地址或模型名不对：base-url 不要带 chat-path；"
                    + (protocol == LlmProtocol.GEMINI ? "Gemini 的模型名要嵌在路径里（客户端已自动填）。"
                    : "从预设里重选一次厂商通常能解决。");
            case "QUOTA" -> "额度不足或限流：换个模型试试，或去厂商控制台充值。";
            case "NETWORK" -> "连不上服务：确认地址端口可达（本地 Ollama 要先 ollama serve），"
                    + "内网模型需确认网络策略。";
            default -> "看上面 message 的原文：多半是协议与端点不匹配，建议从预设重选。";
        };
    }

    private static String rootMsg(Exception e) {
        String last = e.getMessage();
        Throwable cur = e;
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                last = cur.getMessage();
            }
            cur = cur.getCause();
        }
        return last == null ? e.getClass().getSimpleName() : last;
    }

    private static String pick(Map<String, Object> body, String key, String fallback) {
        if (body == null || body.get(key) == null) {
            return fallback;
        }
        String v = String.valueOf(body.get(key)).trim();
        return v.isEmpty() ? fallback : v;
    }

    /** 端点回显时把可能含密钥的 query 参数打码 */
    private static String maskUrl(LlmProtocol protocol, String baseUrl, String chatPath,
                                  LlmAuth auth, String apiKey) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String path = chatPath == null || chatPath.isBlank()
                ? protocol.defaultChatPath() : chatPath;
        // {model} 占位原样回显：这里只是给人看端点长什么样，不发请求
        String url = baseUrl.replaceAll("/+$", "") + path;
        if (auth == LlmAuth.QUERY_KEY && apiKey != null && !apiKey.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "key=****";
        }
        return LlmClient.redact(url, apiKey);
    }

    /** 保存前校验，把常见配错挡在落库之前 */
    private static void validate(Map<String, Object> body) {
        String baseUrl = str(body.get("baseUrl"));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new BizException("请填写服务地址（base-url）");
        }
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new BizException("服务地址要以 http:// 或 https:// 开头：" + baseUrl);
        }
        String model = str(body.get("model"));
        if (model == null || model.isBlank()) {
            throw new BizException("请填写模型名");
        }
        numeric(body, "timeoutSeconds", 5, 600);
        numeric(body, "maxTokens", 1, 32000);
        numeric(body, "temperature", 0, 2);
        LlmProtocol protocol = LlmProtocol.of(str(body.get("protocol")));
        String chatPath = str(body.get("chatPath"));
        if (protocol == LlmProtocol.GEMINI
                && (chatPath == null || chatPath.isBlank() || !chatPath.contains("{model}"))) {
            throw new BizException("Gemini 的端点路径必须包含 {model} 占位，"
                    + "如 /v1beta/models/{model}:generateContent");
        }
        // base-url 里带了路径多半是复制粘贴多了：路径应单独填
        String b = baseUrl.replaceAll("/+$", "");
        String defaultPath = protocol.defaultChatPath();
        if (b.endsWith(defaultPath)) {
            throw new BizException("base-url 不要包含端点路径「" + defaultPath
                    + "」，路径请单独填在「端点路径」字段里");
        }
    }

    /** orgId 只有 ADMIN 能指定，避免机构管理员改到平台配置 */
    private static void numeric(Map<String, Object> body, String key, double min, double max) {
        if (body.get(key) == null) return;
        try {
            double value = Double.parseDouble(String.valueOf(body.get(key)));
            if (!Double.isFinite(value) || value < min || value > max
                    || (!"temperature".equals(key) && value != Math.rint(value))) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new BizException(key + " 必须在 " + min + " 到 " + max + " 范围内");
        }
    }

    private static Long resolveOrgId(Object raw) {
        CurrentUser user = UserContext.require();
        Long requested = raw == null ? null : Long.valueOf(String.valueOf(raw));
        if (user.role() == UserRole.ADMIN) {
            return requested;
        }
        return user.orgId();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }
}
