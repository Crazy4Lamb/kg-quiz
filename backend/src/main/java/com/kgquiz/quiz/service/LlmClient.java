package com.kgquiz.quiz.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kgquiz.quiz.common.BizException;
import com.kgquiz.quiz.config.KgProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型客户端（OpenAI 兼容协议）。
 *
 * <p>只做一件事：把 system + user 两段提示词发出去，拿回纯文本。
 * 结构化输出由 {@link #chatJson} 负责剥掉 markdown 代码围栏并反序列化。</p>
 *
 * <p>设计上<b>不阻塞应用启动</b>：未配置 api-key 时 {@link #available()} 为 false，
 * 调用方据此降级（内容生成走模板、题目识别只给语义召回结果），并给出明确提示。</p>
 */
@Slf4j
@Component
public class LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KgProperties kg;
    private volatile RestClient restClient;

    public LlmClient(KgProperties kg) {
        this.kg = kg;
    }

    /** 是否可用：启用且已配置 api-key */
    public boolean available() {
        KgProperties.Llm cfg = kg.getLlm();
        return cfg.isEnabled() && cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    /** 未配置时的统一提示文案，避免各处写法不一致 */
    public String unavailableReason() {
        if (!kg.getLlm().isEnabled()) {
            return "大模型功能已关闭（kg.llm.enabled=false）";
        }
        return "尚未配置大模型 API Key（环境变量 LLM_API_KEY 或 kg.llm.api-key）";
    }

    public String model() {
        return kg.getLlm().getModel();
    }

    /** 单轮对话，返回模型输出文本 */
    public String chat(String systemPrompt, String userPrompt) {
        if (!available()) {
            throw new BizException(unavailableReason());
        }
        KgProperties.Llm cfg = kg.getLlm();
        List<Map<String, Object>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", cfg.getModel());
        body.put("messages", messages);
        body.put("temperature", cfg.getTemperature());
        body.put("max_tokens", cfg.getMaxTokens());
        body.put("stream", false);

        try {
            String raw = client().post()
                    .uri("/v1/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            JsonNode root = MAPPER.readTree(raw == null ? "{}" : raw);
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                throw new BizException("大模型返回错误：" + error.path("message").asText(error.toString()));
            }
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.isNull()) {
                throw new BizException("大模型返回内容为空：" + abbreviate(raw));
            }
            String text = content.asText();
            log.debug("LLM 调用成功 model={} 输出 {} 字", cfg.getModel(), text.length());
            return text;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("大模型调用失败: {}", e.getMessage());
            throw new BizException("大模型调用失败：" + e.getMessage());
        }
    }

    /**
     * 结构化输出：要求模型返回 JSON，容忍被 ```json 围栏包裹或前后带说明文字的情况。
     */
    public <T> T chatJson(String systemPrompt, String userPrompt, Class<T> type) {
        String text = chat(systemPrompt, userPrompt);
        return parseJson(text, type);
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

    private RestClient client() {
        if (restClient == null) {
            synchronized (this) {
                if (restClient == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    Duration timeout = Duration.ofSeconds(Math.max(5, kg.getLlm().getTimeoutSeconds()));
                    factory.setConnectTimeout(timeout);
                    factory.setReadTimeout(timeout);
                    String base = kg.getLlm().getBaseUrl();
                    restClient = RestClient.builder()
                            .baseUrl(base == null ? "" : base.replaceAll("/+$", ""))
                            .requestFactory(factory)
                            .defaultHeader("Authorization", "Bearer " + kg.getLlm().getApiKey())
                            .build();
                }
            }
        }
        return restClient;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }
}
