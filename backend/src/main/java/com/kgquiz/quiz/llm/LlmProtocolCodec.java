package com.kgquiz.quiz.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 协议适配器：把 {@link LlmRequest} 编成 HTTP 请求，并从响应里抠出纯文本。
 *
 * <p>每个协议只关心两件事：<b>body 怎么组</b>、<b>文本藏在哪</b>。HTTP 发送、重试、
 * 脱敏、超时全在 {@code LlmClient} 里，不下沉到这里 —— 这样加协议时不必碰传输逻辑。</p>
 *
 * <p>刻意做成无状态单方法，不引接口：只有 5 个协议、每段逻辑 10~20 行，
 * 抽象成接口反而多一层跳转。注册表用 {@link #registry()} 一次性建好，避免每次调用 switch。</p>
 */
public final class LlmProtocolCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmProtocolCodec() {
    }

    /**
     * 把请求编成 HTTP 实体所需的四元组：body / headers / url / jsonMode。
     *
     * @param req 规范化请求描述
     * @return body（可能为 null，如鉴权走 query 时 body 仍需要，见下）；实际返回 record
     */
    public record Encoded(Object body, Map<String, String> headers, String url) {
    }

    /** 编解码策略 */
    private interface Codec {
        Map<String, Object> body(LlmRequest req);

        String text(JsonNode root);

        /** 协议固有的额外请求头（与用户配置的鉴权无关） */
        default Map<String, String> fixedHeaders(LlmRequest req) {
            return Map.of();
        }
    }

    private static final Map<LlmProtocol, Codec> REGISTRY = buildRegistry();

    private static Map<LlmProtocol, Codec> buildRegistry() {
        return Map.of(
                LlmProtocol.OPENAI, new Codec() {
                    @Override
                    public Map<String, Object> body(LlmRequest req) {
                        return req.openAiBody();
                    }

                    @Override
                    public String text(JsonNode root) {
                        return root.path("choices").path(0).path("message").path("content").asText("");
                    }
                },
                LlmProtocol.VOLCENGINE, new Codec() {
                    @Override
                    public Map<String, Object> body(LlmRequest req) {
                        return req.openAiBody();
                    }

                    @Override
                    public String text(JsonNode root) {
                        return root.path("choices").path(0).path("message").path("content").asText("");
                    }
                },
                LlmProtocol.ANTHROPIC, new Codec() {
                    @Override
                    public Map<String, Object> body(LlmRequest req) {
                        return req.anthropicBody();
                    }

                    @Override
                    public String text(JsonNode root) {
                        // content 是数组，可能被拆成 text + thinking 多个块，取第一个 type=text 的
                        for (JsonNode block : root.path("content")) {
                            if ("text".equals(block.path("type").asText())) {
                                return block.path("text").asText("");
                            }
                        }
                        return root.path("content").path(0).path("text").asText("");
                    }

                    @Override
                    public Map<String, String> fixedHeaders(LlmRequest req) {
                        // anthropic-version 是强制的，缺了直接 400；不带则无法使用该协议
                        return Map.of("anthropic-version", "2023-06-01");
                    }
                },
                LlmProtocol.GEMINI, new Codec() {
                    @Override
                    public Map<String, Object> body(LlmRequest req) {
                        return req.geminiBody();
                    }

                    @Override
                    public String text(JsonNode root) {
                        return root.path("candidates").path(0).path("content").path("parts").path(0)
                                .path("text").asText("");
                    }
                },
                LlmProtocol.OLLAMA, new Codec() {
                    @Override
                    public Map<String, Object> body(LlmRequest req) {
                        return req.ollamaBody();
                    }

                    @Override
                    public String text(JsonNode root) {
                        return root.path("message").path("content").asText("");
                    }
                });
    }

    public static Map<LlmProtocol, Codec> registry() {
        return REGISTRY;
    }

    /** 编码：body + headers + url（含鉴权） */
    public static Encoded encode(LlmRequest req) {
        Codec codec = REGISTRY.get(req.protocol());
        if (codec == null) {
            throw new IllegalArgumentException("未支持的协议：" + req.protocol());
        }
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>(codec.fixedHeaders(req));
        headers.putAll(authHeaders(req));
        return new Encoded(codec.body(req), headers,
                req.auth() == LlmAuth.QUERY_KEY ? req.fullUrlWithQueryKey() : req.fullUrl());
    }

    /** 按鉴权方式生成请求头；key 为空时返回空表（由上层判定是否可用） */
    private static Map<String, String> authHeaders(LlmRequest req) {
        String key = req.apiKey();
        if (req.auth() == LlmAuth.NONE || key == null || key.isBlank()) {
            return Map.of();
        }
        return switch (req.auth()) {
            case BEARER -> Map.of("Authorization", "Bearer " + key);
            case HEADER -> Map.of("x-api-key", key);
            case GOOG_HEADER -> Map.of("x-goog-api-key", key);
            case CUSTOM_HEADER -> Map.of(
                    req.authHeader() == null || req.authHeader().isBlank()
                            ? "x-api-key" : req.authHeader(), key);
            case QUERY_KEY -> Map.of();  // 已在 URL 上
            case NONE -> Map.of();
        };
    }

    /**
     * 解码：从原始响应 JSON 取出文本，并识别各协议的错误体。
     *
     * @return 文本；失败时抛带协议原始报错的异常信息（调用方转成 BizException）
     */
    public static String decode(LlmRequest req, String raw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw == null || raw.isBlank() ? "{}" : raw);
        } catch (Exception e) {
            throw new LlmDecodeException("响应不是合法 JSON：" + abbreviate(raw));
        }
        // OpenAI 风格错误：{"error":{"message":...}}；Gemini 是 {"error":{"status":..,"message":..}}
        if (root.has("error") && !root.get("error").isNull()) {
            JsonNode err = root.get("error");
            String msg = err.path("message").asText(err.toString());
            throw new LlmDecodeException("服务端返回错误：" + msg);
        }
        // Anthropic 的错误也是 {"error":{"type":..,"message":..}}，已被上面覆盖
        String text = REGISTRY.get(req.protocol()).text(root);
        if (text == null || text.isBlank()) {
            throw new LlmDecodeException("返回内容为空：" + abbreviate(raw));
        }
        return text;
    }

    /** 解码失败（可预期）——上层据此决定是否降级 */
    public static class LlmDecodeException extends RuntimeException {
        public LlmDecodeException(String message) {
            super(message);
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /** 列出支持的全部协议名，供前端下拉与校验用 */
    public static List<String> supportedProtocols() {
        return List.of(LlmProtocol.OPENAI.name(), LlmProtocol.ANTHROPIC.name(),
                LlmProtocol.GEMINI.name(), LlmProtocol.OLLAMA.name(), LlmProtocol.VOLCENGINE.name());
    }
}
