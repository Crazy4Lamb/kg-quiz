package com.kgquiz.quiz.llm;

/**
 * API Key 的携带方式，与 {@link LlmProtocol} 正交。
 *
 * <p>为什么单独抽：同一个协议可能遇到不同鉴权。比如自建 vLLM 网关是
 * {@code OPENAI} 协议但要 {@code X-Api-Key} 自定义 header；而 Anthropic 官方
 * 只认 {@code x-api-key} 而不认 {@code Authorization: Bearer}。把两者拆开后，
 * 「OpenAI 体 + 自定义 header」这种组合不用为它新增一个协议。</p>
 */
public enum LlmAuth {

    /** {@code Authorization: Bearer <key>}，OpenAI / DeepSeek / 通义 / 智谱等主流 */
    BEARER,

    /** {@code x-api-key: <key>}，Anthropic 官方强制要求 */
    HEADER,

    /** URL 查询参数 {@code ?key=<key>}，Gemini 官方推荐，便于在网关上按 query 路由 */
    QUERY_KEY,

    /** {@code x-goog-api-key: <key>}，Gemini 备选，比 query 参数更安全（不进访问日志） */
    GOOG_HEADER,

    /** 不需要鉴权，Ollama 本地推理 */
    NONE,

    /** 自定义：{@code LlmConfig.authHeaderName} 指定 header 名，值即 key */
    CUSTOM_HEADER;

    public String label() {
        return switch (this) {
            case BEARER -> "Bearer Token（Authorization 头）";
            case HEADER -> "x-api-key 头";
            case QUERY_KEY -> "URL 参数 key";
            case GOOG_HEADER -> "x-goog-api-key 头";
            case NONE -> "无需鉴权";
            case CUSTOM_HEADER -> "自定义 Header";
        };
    }

    /** 是否需要 Key 才有意义 —— NONE 不需要 */
    public boolean keyRequired() {
        return this != NONE;
    }

    public static LlmAuth of(String name) {
        if (name == null || name.isBlank()) {
            return BEARER;
        }
        for (LlmAuth a : values()) {
            if (a.name().equalsIgnoreCase(name.trim())) {
                return a;
            }
        }
        return BEARER;
    }
}
