package com.kgquiz.quiz.llm;

/**
 * 大模型请求协议（请求体形态）。
 *
 * <p><b>为什么要多协议</b>：最初只按 OpenAI 兼容写死 {@code /v1/chat/completions}，
 * 结果 Anthropic（Messages API）、Gemini（generateContent）、Ollama（本地推理）
 * 都各有各的请求体与响应体，套不上。把「协议」抽成独立维度后，新增厂商只要加一个
 * 枚举值 + 一个实现，业务代码（ContentService / StoryboardService / QuestionMappingService）
 * 完全不用动。</p>
 *
 * <p>注意与 {@link LlmAuth} 解耦：协议决定「body 长什么样、URL 怎么拼、响应怎么取」，
 * 鉴权决定「Key 放哪」。两者正交，自建网关常见「OpenAI 体 + 自定义 header」组合。</p>
 */
public enum LlmProtocol {

    /**
     * OpenAI 兼容（DeepSeek / 通义千问 / 智谱 / Kimi / 豆包 / vLLM / OpenAI 官方 / 几乎所有中转站）。
     * 请求 {@code POST {base-url}{chat-path}}，默认路径 {@code /v1/chat/completions}。
     */
    OPENAI,

    /**
     * Anthropic Messages API。
     * <p>差异要点：system 是<b>独立顶层字段</b>而非 messages 里的一条；鉴权走
     * {@code x-api-key} + 必带 {@code anthropic-version}；响应文本在
     * {@code content[0].text} 而不是 {@code choices[0].message.content}；
     * 温度与 max_tokens 都在 body 顶层。</p>
     */
    ANTHROPIC,

    /**
     * Google Gemini generateContent。
     * <p>差异要点：messages 叫 {@code contents}，一条内容拆成 {@code parts:[{text}]}；
     * system 指令用 {@code systemInstruction}；采样参数收在 {@code generationConfig}；
     * 响应取 {@code candidates[0].content.parts[0].text}；Key 走 URL 查询参数或
     * {@code x-goog-api-key} header。</p>
     */
    GEMINI,

    /**
     * Ollama 本地推理（{@code POST /api/chat}）。
     * <p>差异要点：与 OpenAI 类似但参数收在 {@code options} 下；无鉴权；
     * 响应取 {@code message.content}。</p>
     */
    OLLAMA,

    /**
     * 预留给豆包火山方舟等「OpenAI 体但路径不同」的兼容变体。
     * <p>请求体与响应体完全按 OpenAI 处理，仅端点路径可自定义 —— 大多数国产中转站属于此类，
     * 用 OPENAI + 自定义 chat-path 也能覆盖，这个值主要给「明确知道是方舟协议」时用。</p>
     */
    VOLCENGINE;

    /** 该协议的默认 chat 路径；预设可覆盖 */
    public String defaultChatPath() {
        return switch (this) {
            case OPENAI -> "/v1/chat/completions";
            case ANTHROPIC -> "/v1/messages";
            case GEMINI -> "/v1beta/models/{model}:generateContent";
            case OLLAMA -> "/api/chat";
            case VOLCENGINE -> "/api/v3/chat/completions";
        };
    }

    /** 该协议默认用哪种鉴权方式 */
    public LlmAuth defaultAuth() {
        return switch (this) {
            case ANTHROPIC -> LlmAuth.HEADER;
            case GEMINI -> LlmAuth.QUERY_KEY;
            case OLLAMA -> LlmAuth.NONE;
            default -> LlmAuth.BEARER;
        };
    }

    /** 是否需要额外的固定请求头（协议固有要求，非用户可配） */
    public boolean needsFixedHeaders() {
        return this == ANTHROPIC;
    }

    /**
     * 该协议是否支持「强制 JSON 输出」。
     *
     * <p>影响 {@code chatJson} 的提示词强度：Anthropic 没有 {@code response_format} 参数，
     * Gemini 有 {@code responseMimeType}。不支持的协议只能靠提示词约束 +
     * {@code LlmClient.parseJson} 兜底剥围栏。</p>
     */
    public boolean supportsJsonMode() {
        return this != ANTHROPIC;
    }

    public static LlmProtocol of(String name) {
        if (name == null || name.isBlank()) {
            return OPENAI;
        }
        for (LlmProtocol p : values()) {
            if (p.name().equalsIgnoreCase(name.trim())) {
                return p;
            }
        }
        return OPENAI;
    }

    public String label() {
        return switch (this) {
            case OPENAI -> "OpenAI 兼容";
            case ANTHROPIC -> "Anthropic Messages";
            case GEMINI -> "Google Gemini";
            case OLLAMA -> "Ollama 本地";
            case VOLCENGINE -> "火山方舟";
        };
    }
}
