package com.kgquiz.quiz.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次「调模型」的规范化描述 —— 协议实现只做<b>编解码</b>，不发请求、不管 HTTP。
 *
 * @param protocol     请求协议
 * @param baseUrl      服务根地址（不含 chat-path），如 {@code https://api.deepseek.com}
 * @param chatPath     端点路径，可能含 {@code {model}} 占位（Gemini 必需）
 * @param auth         鉴权方式
 * @param authHeader   CUSTOM_HEADER 时的 header 名
 * @param apiKey       密钥，NONE 时可为空
 * @param model        模型标识
 * @param systemPrompt 系统提示词，可为空
 * @param userPrompt   用户提示词
 * @param temperature  采样温度
 * @param maxTokens    输出上限
 * @param jsonMode     是否要求模型直接输出 JSON（仅部分协议真正支持）
 */
public record LlmRequest(
        LlmProtocol protocol,
        String baseUrl,
        String chatPath,
        LlmAuth auth,
        String authHeader,
        String apiKey,
        String model,
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens,
        boolean jsonMode) {

    /** 完整 URL：拼 base + path，并把 Gemini 的 {model} 占位替换掉 */
    public String fullUrl() {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        String path = chatPath == null || chatPath.isBlank()
                ? protocol.defaultChatPath()
                : (chatPath.startsWith("/") ? chatPath : "/" + chatPath);
        return base + path.replace("{model}", model == null ? "" : model);
    }

    /** Gemini 走查询参数鉴权时要把 key 拼到 URL 上 */
    public String fullUrlWithQueryKey() {
        String url = fullUrl();
        if (auth == LlmAuth.QUERY_KEY && apiKey != null && !apiKey.isBlank()) {
            url += (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
        return url;
    }

    /** OpenAI 风格的消息体（OPENAI / VOLCENGINE 共用） */
    Map<String, Object> openAiBody() {
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        if (jsonMode) {
            // 主流 OpenAI 兼容端点都支持；不支持的实现会忽略或报错，
            // 真报错时 LlmClient 会降级为纯提示词约束重试
            body.put("response_format", Map.of("type", "json_object"));
        }
        return body;
    }

    /** Anthropic Messages 请求体：system 是顶层字段，不塞进 messages */
    Map<String, Object> anthropicBody() {
        List<Map<String, String>> messages = List.of(
                Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("temperature", temperature);
        body.put("messages", messages);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", systemPrompt);
        }
        return body;
    }

    /** Gemini generateContent 请求体：contents + parts，采样参数在 generationConfig */
    Map<String, Object> geminiBody() {
        Map<String, Object> textPart = Map.of("text", userPrompt == null ? "" : userPrompt);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("role", "user");
        content.put("parts", List.of(textPart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(content));
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        }
        Map<String, Object> gen = new LinkedHashMap<>();
        gen.put("temperature", temperature);
        gen.put("maxOutputTokens", maxTokens);
        if (jsonMode) {
            gen.put("responseMimeType", "application/json");
        }
        body.put("generationConfig", gen);
        return body;
    }

    /** Ollama 请求体：参数收在 options 下，无 response_format */
    Map<String, Object> ollamaBody() {
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt == null ? "" : userPrompt));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        body.put("options", Map.of(
                "temperature", temperature,
                "num_predict", maxTokens));
        // 关键：Ollama 会原样吐 </script> 之类内容，需要 format=json 约束
        if (jsonMode) {
            body.put("format", "json");
        }
        return body;
    }
}
