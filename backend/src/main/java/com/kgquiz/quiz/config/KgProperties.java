package com.kgquiz.quiz.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "kg")
public class KgProperties {

    private Qdrant qdrant = new Qdrant();
    private Embedding embedding = new Embedding();
    private Exam exam = new Exam();
    private Neo4j neo4j = new Neo4j();
    private Llm llm = new Llm();

    /**
     * 大模型接入（默认 OpenAI 兼容协议，另支持 Anthropic / Gemini / Ollama）。
     *
     * <p>本类是<b>启动兜底配置</b>：运行时若 {@code llm_config} 表里有启用的记录，
     * 以表为准（可在管理台改，不需重启）；表里没有才用这里的值。保留它是为了让
     * 「不建表也能跑」的老路径继续有效。</p>
     *
     * <p>字段分三组：协议与鉴权决定报文形态；地址类决定打到哪里；采样类影响生成风格。</p>
     */
    @Data
    public static class Llm {
        /** 总开关。false 时内容生成一律走模板，题目识别只用语义召回 */
        private boolean enabled = true;

        /**
         * 请求协议：OPENAI（兼容绝大多数中转站）/ ANTHROPIC / GEMINI / OLLAMA / VOLCENGINE。
         * 与 {@link #auth} 正交：协议管报文，auth 管 Key 放哪。
         */
        private String protocol = "OPENAI";

        /**
         * 鉴权方式：BEARER（默认）/ HEADER（x-api-key）/ QUERY_KEY / GOOG_HEADER /
         * NONE（本地推理）/ CUSTOM_HEADER。
         * 留空则跟随协议默认值。
         */
        private String auth = "";

        /**
         * 服务根地址。<b>不要带 chat-path</b>，路径由 {@link #chatPath} 单独给。
         * 例：DeepSeek {@code https://api.deepseek.com}；通义
         * {@code https://dashscope.aliyuncs.com/compatible-mode}。
         */
        private String baseUrl = "https://api.deepseek.com";

        /** 端点路径。Gemini 需保留 {@code {model}} 占位；留空用协议默认值 */
        private String chatPath = "/v1/chat/completions";

        private String apiKey = "";

        /** CUSTOM_HEADER 时的 header 名，如 {@code X-Gateway-Token} */
        private String authHeader = "";

        private String model = "deepseek-chat";

        private int timeoutSeconds = 90;

        private int maxTokens = 2400;

        private double temperature = 0.3;

        /** 预设编码（如 deepseek / ollama-local），仅用于管理台回显厂商信息 */
        private String presetCode = "";
    }

    @Data
    public static class Neo4j {
        private boolean enabled = true;
        private String uri = "bolt://127.0.0.1:7687";
        private String username = "neo4j";
        private String password = "neo4j";
        private String database = "neo4j";
    }

    @Data
    public static class Qdrant {
        private boolean enabled = true;
        private String url = "http://localhost:6333";
        private String collection = "kg_nodes";
    }

    @Data
    public static class Embedding {
        private boolean enabled = true;
        private String modelPath = "./models/bge-small-zh-v1.5.onnx";
        private String tokenizerPath = "./models/tokenizer.json";
        private int dim = 512;
    }

    @Data
    public static class Exam {
        private double learningRate = 0.3;
        private double weakThreshold = 0.4;
        private double solidThreshold = 0.7;
        private double consolidateRatio = 0.2;
    }
}
