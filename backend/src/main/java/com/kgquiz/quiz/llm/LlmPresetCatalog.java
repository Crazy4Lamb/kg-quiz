package com.kgquiz.quiz.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预设模型目录：把各厂商「协议 + 端点 + 鉴权 + 推荐模型」打包成一条可一键填入的配置。
 *
 * <p><b>为什么需要预设</b>：让用户手填端点极易出错 —— 通义的兼容模式路径带
 * {@code /compatible-mode/v1}、Anthropic 少了 {@code anthropic-version} 直接 400、
 * Gemini 的模型名要嵌在 URL 路径里。这些细节没写在文档里几乎没人记得，
 * 而写错时的报错通常只有一句 404，很难自查。</p>
 *
 * <p>预设是<b>纯常量</b>，刻意不入库：它描述的是厂商的客观事实，不是机构配置。
 * 机构配置存在 {@code llm_config} 表里。</p>
 */
public final class LlmPresetCatalog {

    private LlmPresetCatalog() {
    }

    /**
     * 一条预设。
     *
     * @param code         稳定编码，存进 {@code llm_config.preset_code} 用于回溯
     * @param vendor       厂商名
     * @param protocol     协议
     * @param baseUrl      服务根地址
     * @param chatPath     端点路径
     * @param auth         鉴权方式
     * @param model        推荐模型
     * @param models       可选模型清单（供下拉）
     * @param keyRequired  是否需要 Key
     * @param note         使用提示，含价格/特点/坑
     * @param free         是否可零成本跑通（本地或免费额度）
     */
    public record Preset(
            String code,
            String vendor,
            LlmProtocol protocol,
            String baseUrl,
            String chatPath,
            LlmAuth auth,
            String model,
            List<String> models,
            boolean keyRequired,
            String note,
            boolean free) {
    }

    private static Preset p(String code, String vendor, LlmProtocol protocol, String baseUrl,
                            String chatPath, LlmAuth auth, String model, List<String> models,
                            boolean free, String note) {
        return new Preset(code, vendor, protocol, baseUrl, chatPath, auth, model, models, true, note, free);
    }

    private static final List<Preset> PRESETS = List.of(
            // ---------- 国内主流：便宜、中文强 ----------
            p("deepseek", "DeepSeek（深度求索）", LlmProtocol.OPENAI,
                    "https://api.deepseek.com", "/v1/chat/completions", LlmAuth.BEARER,
                    "deepseek-chat", List.of("deepseek-chat", "deepseek-reasoner"), false,
                    "性价比最高，¥1/百万 token 足够跑几万次生成。base-url 不要带 /v1（客户端会自动拼）。"
                            + "deepseek-reasoner 是推理模型，温度强制为 1，不适合结构化输出。"),

            p("qwen", "阿里云百炼（通义千问）", LlmProtocol.OPENAI,
                    "https://dashscope.aliyuncs.com/compatible-mode", "/v1/chat/completions", LlmAuth.BEARER,
                    "qwen-plus", List.of("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long"), false,
                    "注意 base-url 必须带 /compatible-mode，否则 404。有免费额度，新号可用几天。"
                            + "qwen-long 支持超长上下文，适合批量生成整张图谱内容。"),

            p("zhipu", "智谱 GLM", LlmProtocol.OPENAI,
                    "https://open.bigmodel.cn/api/paas", "/v4/chat/completions", LlmAuth.BEARER,
                    "glm-4-flash", List.of("glm-4-flash", "glm-4-plus", "glm-4-air"), false,
                    "base-url 到 /api/paas 为止，路径是 /v4/chat/completions（版本号在路径里）。"
                            + "GLM-4-Flash 免费，适合 Demo 阶段。"),

            p("moonshot", "月之暗面 Kimi", LlmProtocol.OPENAI,
                    "https://api.moonshot.cn", "/v1/chat/completions", LlmAuth.BEARER,
                    "moonshot-v1-8k", List.of("moonshot-v1-8k", "moonshot-v1-32k", "kimi-k2-0905-preview"), false,
                    "长文本能力突出，32k 版适合一次性喂整张图谱的节点关系。"),

            p("volcengine", "火山引擎方舟（豆包）", LlmProtocol.VOLCENGINE,
                    "https://ark.cn-beijing.volces.com/api", "/v3/chat/completions", LlmAuth.BEARER,
                    "doubao-1-5-pro-32k", List.of("doubao-1-5-pro-32k", "doubao-1-5-lite-32k"), false,
                    "模型名是「推理接入点 ID」（ep-xxx），需在控制台创建后填入，填模型名会 404。"),

            p("baichuan", "百川智能", LlmProtocol.OPENAI,
                    "https://api.baichuan-ai.com", "/v1/chat/completions", LlmAuth.BEARER,
                    "Baichuan4", List.of("Baichuan4", "Baichuan3-Turbo"), false,
                    "OpenAI 完全兼容，改 base-url 即可。"),

            // ---------- 海外 ----------
            p("openai", "OpenAI 官方", LlmProtocol.OPENAI,
                    "https://api.openai.com", "/v1/chat/completions", LlmAuth.BEARER,
                    "gpt-4o-mini", List.of("gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "o3-mini"), false,
                    "结构化输出最稳（原生 response_format=json_object）。需境外网络与境外账号。"),

            p("anthropic", "Anthropic Claude", LlmProtocol.ANTHROPIC,
                    "https://api.anthropic.com", "/v1/messages", LlmAuth.HEADER,
                    "claude-sonnet-4-20250514",
                    List.of("claude-sonnet-4-20250514", "claude-opus-4-20250514",
                            "claude-3-5-haiku-20241022"), false,
                    "注意两点：鉴权用 x-api-key 而非 Authorization；必须带 anthropic-version 头"
                            + "（客户端已自动补）。该协议无 response_format，JSON 输出靠提示词约束。"),

            p("gemini", "Google Gemini", LlmProtocol.GEMINI,
                    "https://generativelanguage.googleapis.com",
                    "/v1beta/models/{model}:generateContent", LlmAuth.GOOG_HEADER,
                    "gemini-2.0-flash", List.of("gemini-2.0-flash", "gemini-1.5-pro", "gemini-1.5-flash"), false,
                    "模型名嵌在 URL 路径里（客户端已用 {model} 占位自动填）。"
                            + "有免费额度，JSON 模式原生支持（responseMimeType=application/json）。"),

            // ---------- 本地 / 自建：零成本、数据不出内网 ----------
            p("ollama-local", "Ollama 本地推理", LlmProtocol.OLLAMA,
                    "http://localhost:11434", "/api/chat", LlmAuth.NONE,
                    "qwen2.5:7b", List.of("qwen2.5:7b", "qwen2.5:14b", "llama3.1:8b", "qwen2:0.5b"), true,
                    "零成本、数据不出内网，JSON 输出用 format=json 原生约束。"
                            + "需先 ollama pull qwen2.5:7b；7B 模型写讲义质量一般，但足够跑通流程。"),

            p("vllm-self", "自建 vLLM / 兼容网关", LlmProtocol.OPENAI,
                    "http://localhost:8000", "/v1/chat/completions", LlmAuth.BEARER,
                    "Qwen/Qwen2.5-7B-Instruct", List.of("Qwen/Qwen2.5-7B-Instruct", "meta-llama/Llama-3-8B-Instruct"), false,
                    "私有化部署走这条。鉴权方式可改成 x-api-key 或自定义 header 以适配网关。"
                    ),

            p("custom", "自定义端点", LlmProtocol.OPENAI,
                    "", "/v1/chat/completions", LlmAuth.BEARER,
                    "", List.of(), false,
                    "适合中转站 / 内部 AI 网关。协议与鉴权可自由组合（如 OpenAI 体 + 自定义 header）。"));
    }

    public static List<Preset> all() {
        return PRESETS;
    }

    public static Preset byCode(String code) {
        if (code == null) {
            return null;
        }
        for (Preset p : PRESETS) {
            if (p.code().equalsIgnoreCase(code.trim())) {
                return p;
            }
        }
        return null;
    }

    /** 预设转成可直接回填的字段 Map（不含 api-key，密钥永远由用户填） */
    public static Map<String, Object> toFields(Preset p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("presetCode", p.code());
        m.put("vendor", p.vendor());
        m.put("protocol", p.protocol().name());
        m.put("protocolLabel", p.protocol().label());
        m.put("baseUrl", p.baseUrl());
        m.put("chatPath", p.chatPath());
        m.put("auth", p.auth().name());
        m.put("authLabel", p.auth().label());
        m.put("model", p.model());
        m.put("models", p.models());
        m.put("keyRequired", p.keyRequired());
        m.put("free", p.free());
        m.put("note", p.note());
        return m;
    }

    public static List<Map<String, Object>> allAsMaps() {
        return PRESETS.stream().map(LlmPresetCatalog::toFields).toList();
    }
}
