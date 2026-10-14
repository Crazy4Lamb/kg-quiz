package com.kgquiz.quiz.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.config.KgProperties;
import com.kgquiz.quiz.llm.LlmAuth;
import com.kgquiz.quiz.llm.LlmPresetCatalog;
import com.kgquiz.quiz.llm.LlmProtocol;
import com.kgquiz.quiz.llm.LlmProtocolCodec;
import com.kgquiz.quiz.llm.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大模型客户端。
 *
 * <p><b>多协议</b>：请求体与响应解析全部委托 {@link LlmProtocolCodec}，因此除 OpenAI 兼容外
 * 也支持 Anthropic Messages / Gemini generateContent / Ollama。协议与鉴权方式解耦，
 * 「OpenAI 体 + 自定义 header」这类自建网关无需新增协议。</p>
 *
 * <p><b>配置优先级</b>：运行时 {@code llm_config}（机构覆盖 &gt; 平台默认）&gt; 环境变量 /
 * application.yml。运行时配置优先，这样换厂商不用重启服务；环境变量仍作为 bootstrap 兜底，
 * 保证没有数据库配置时行为跟以前一致。</p>
 *
 * <p><b>对外契约刻意不变</b>：{@link #available()} / {@link #chat} / {@link #chatJson} /
 * {@link #model()} / {@link #unavailableReason()} 签名保持原样，
 * ContentService / StoryboardService / QuestionMappingService 三处调用点无需改动。</p>
 *
 * <p>未配置 Key 时 {@link #available()} 为 false，调用方据此降级（内容生成走模板、
 * 题目识别只给语义召回结果），并给出明确提示，<b>不阻塞应用启动</b>。</p>
 */
@Slf4j
@Component
public class LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KgProperties kg;
    /** 运行时配置（可选依赖：单元测试或配置表为空时不阻塞） */
    private final ObjectProvider<LlmConfigService> configServiceProvider;

    private final Map<String, RestClient> clientCache = new java.util.concurrent.ConcurrentHashMap<>();

    public LlmClient(KgProperties kg, ObjectProvider<LlmConfigService> configServiceProvider) {
        this.kg = kg;
        this.configServiceProvider = configServiceProvider;
    }

    // ------------------------------------------------------------------
    // 配置解析
    // ------------------------------------------------------------------

    /**
     * 取当前生效的配置：运行时库 &gt; 环境变量/yaml。
     *
     * <p>每次调用都重新解析而非缓存，因为配置可在运行中被管理员改；模型调用本身
     * 有网络往返，这点开销可忽略。</p>
     */
    public LlmSettings settings() {
        LlmConfigService svc = configServiceProvider.getIfAvailable();
        if (svc != null) {
            LlmSettings fromDb = svc.resolveSettings();
            if (fromDb != null) {
                return fromDb;
            }
        }
        return settingsFromEnv();
    }

    /** 环境变量 / application.yml 兜底配置 */
    private LlmSettings settingsFromEnv() {
        KgProperties.Llm cfg = kg.getLlm();
        LlmProtocol protocol = LlmProtocol.of(cfg.getProtocol());
        LlmAuth auth = cfg.getAuth() == null || cfg.getAuth().isBlank()
                ? protocol.defaultAuth()
                : LlmAuth.of(cfg.getAuth());
        String preset = cfg.getPresetCode() == null ? null : cfg.getPresetCode();
        return new LlmSettings(
                cfg.isEnabled(),
                protocol,
                auth,
                cfg.getBaseUrl(),
                cfg.getChatPath(),
                cfg.getApiKey(),
                cfg.getAuthHeader(),
                cfg.getModel(),
                cfg.getTimeoutSeconds(),
                cfg.getMaxTokens(),
                cfg.getTemperature(),
                preset,
                "环境变量 / application.yml");
    }

    /** 是否可用：启用 + （不需要 Key 或已配 Key） */
    public boolean available() {
        LlmSettings s = settings();
        if (!s.enabled()) {
            return false;
        }
        // 本地推理（Ollama / 无鉴权）不需要 Key 就算可用 —— 这类场景零成本，不该被挡住
        return !s.auth().keyRequired() || (s.apiKey() != null && !s.apiKey().isBlank());
    }

    /** 未配置时的统一提示文案，避免各处写法不一致 */
    public String unavailableReason() {
        LlmSettings s = settings();
        if (!s.enabled()) {
            return "大模型功能已关闭（kg.llm.enabled=false）";
        }
        if (!s.auth().keyRequired()) {
            return null; // 无需鉴权的本地推理，不算「未配置」
        }
        return "尚未配置大模型 API Key（可在「系统设置 → 大模型接入」里填，或设环境变量 LLM_API_KEY）";
    }

    public String model() {
        return settings().model();
    }

    /** 当前协议，供前端状态条展示（老调用方无感） */
    public LlmProtocol protocol() {
        return settings().protocol();
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /** 单轮对话，返回模型输出文本 */
    public String chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, false);
    }

    /**
     * 单轮对话。
     *
     * @param jsonMode 是否要求 JSON 输出。调用方自己解析 JSON 时传 true，
     *                  协议支持则带 response_format / responseMimeType，不支持则仅靠提示词
     */
    public String chat(String systemPrompt, String userPrompt, boolean jsonMode) {
        LlmSettings s = settings();
        if (!available()) {
            throw new BizException(unavailableReason());
        }
        long start = System.currentTimeMillis();
        String url = "(未构造)";
        try {
            LlmRequest req = toRequest(s, systemPrompt, userPrompt, jsonMode);
            LlmProtocolCodec.Encoded enc = LlmProtocolCodec.encode(req);
            url = enc.url();

            String raw = client(s).post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> enc.headers().forEach(h::set))
                    .body(enc.body())
                    .retrieve()
                    .body(String.class);

            String text = LlmProtocolCodec.decode(req, raw);
            log.info("大模型调用成功 protocol={} model={} 耗时={}ms 输出{}字",
                    s.protocol(), s.model(), System.currentTimeMillis() - start, text.length());
            return text;
        } catch (LlmProtocolCodec.LlmDecodeException e) {
            // 解码失败是可预期的（换端点/换厂商时常见），给明确提示而不是 500
            throw new BizException("大模型响应无法解析（" + s.protocol().label() + "）：" + e.getMessage());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("大模型调用失败 protocol={} model={} url={}: {}",
                    s.protocol(), s.model(), url, e.getMessage());
            throw new BizException("大模型调用失败：" + rootMessage(e));
        }
    }

    /** 剥一层，把 RestClient 的嵌套异常提到最外层，错误信息才有可读性 */
    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        String last = e.getMessage();
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                last = cur.getMessage();
            }
            cur = cur.getCause();
        }
        return last;
    }

    private LlmRequest toRequest(LlmSettings s, String systemPrompt, String userPrompt, boolean jsonMode) {
        return new LlmRequest(
                s.protocol(),
                s.baseUrl(),
                s.chatPath(),
                s.auth(),
                s.authHeader(),
                s.apiKey(),
                s.model(),
                systemPrompt,
                userPrompt,
                s.temperature(),
                s.maxTokens(),
                jsonMode && s.protocol().supportsJsonMode());
    }

    /** 每个配置指纹一个 RestClient，换端点/换协议时自动换实例 */
    private RestClient client(LlmSettings s) {
        String fingerprint = s.protocol() + "|" + s.baseUrl() + "|" + s.authHeader()
                + "|" + s.timeoutSeconds() + "|" + (s.apiKey() == null ? "" : s.apiKey());
        return clientCache.computeIfAbsent(fingerprint, k -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            Duration timeout = Duration.ofSeconds(Math.max(5, s.timeoutSeconds()));
            factory.setConnectTimeout(timeout);
            factory.setReadTimeout(timeout);
            return RestClient.builder()
                    .requestFactory(factory)
                    .build();
        });
    }

    // ------------------------------------------------------------------
    // 结构化输出
    // ------------------------------------------------------------------

    /**
     * 结构化输出：要求模型返回 JSON，容忍被 ```json 围栏包裹或前后带说明文字的情况。
     */
    public <T> T chatJson(String systemPrompt, String userPrompt, Class<T> type) {
        return parseJson(chat(systemPrompt, userPrompt, true), type);
    }

    /** 从模型输出里抠出 JSON 并反序列化（供需要 Map/List 的场景复用） */
    public static <T> T parseJson(String text, Class<T> type) {
        JsonNode node = extractJson(text);
        try {
            return MAPPER.treeToValue(node, type);
        } catch (Exception e) {
            throw new BizException("大模型返回的 JSON 无法解析：" + e.getMessage());
        }
    }

    public static JsonNode extractJson(String text) {
        if (text == null) {
            throw new BizException("大模型返回内容为空");
        }
        String s = text.trim();
        // 剥掉 ```json ... ``` / ``` ... ``` 围栏
        if (s.startsWith("```")) {
            int first = s.indexOf('\n');
            int last = s.lastIndexOf("```");
            if (first > 0 && last > first) {
                s = s.substring(first + 1, last).trim();
            }
        }
        try {
            return MAPPER.readTree(s);
        } catch (Exception ignored) {
            // 模型有时会在 JSON 前后加一句说明，退化为「取第一个 { 到最后一个 }」
            int start = s.indexOf('{');
            int end = s.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return MAPPER.readTree(s.substring(start, end + 1));
                } catch (Exception e) {
                    throw new BizException("大模型返回的内容不是合法 JSON：" + abbreviate(s));
                }
            }
            throw new BizException("大模型返回的内容不是合法 JSON：" + abbreviate(s));
        }
    }

    /** 解析为 Map 的便捷方法 */
    public Map<String, Object> parseJsonMap(String text) {
        JsonNode node = extractJson(text);
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * 生效配置的只读快照。apiKey 永不出现在这里 —— 管理台要用掩码回显，
     * 泄漏真实 Key 到前端等于把密钥交给任何能开页面的人。
     */
    public record LlmSettings(
            boolean enabled,
            LlmProtocol protocol,
            LlmAuth auth,
            String baseUrl,
            String chatPath,
            String apiKey,
            String authHeader,
            String model,
            int timeoutSeconds,
            int maxTokens,
            double temperature,
            String presetCode,
            String source) {

        public boolean keyRequired() {
            return auth.keyRequired();
        }

        /** 掩码：只留首尾各 3 位，中间打点 */
        public String maskedKey() {
            if (apiKey == null || apiKey.isBlank()) {
                return "";
            }
            if (apiKey.length() <= 8) {
                return "****";
            }
            return apiKey.substring(0, 3) + "****" + apiKey.substring(apiKey.length() - 3);
        }

        public Map<String, Object> toSafeMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("enabled", enabled);
            m.put("protocol", protocol.name());
            m.put("protocolLabel", protocol.label());
            m.put("auth", auth.name());
            m.put("authLabel", auth.label());
            m.put("baseUrl", baseUrl);
            m.put("chatPath", chatPath);
            m.put("authHeader", authHeader);
            m.put("model", model);
            m.put("timeoutSeconds", timeoutSeconds);
            m.put("maxTokens", maxTokens);
            m.put("temperature", temperature);
            m.put("presetCode", presetCode);
            m.put("source", source);
            m.put("keyRequired", keyRequired());
            m.put("hasKey", apiKey != null && !apiKey.isBlank());
            m.put("maskedKey", maskedKey());
            m.put("supportsJsonMode", protocol.supportsJsonMode());
            LlmPresetCatalog.Preset preset = LlmPresetCatalog.byCode(presetCode);
            m.put("vendor", preset == null ? null : preset.vendor());
            m.put("note", preset == null ? null : preset.note());
            return m;
        }
    }
}
