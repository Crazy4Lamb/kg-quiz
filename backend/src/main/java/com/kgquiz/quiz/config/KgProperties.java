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
     * 大模型接入（OpenAI 兼容协议）。
     *
     * <p>走 {@code POST {base-url}/v1/chat/completions}，因此 DeepSeek、通义千问（兼容模式）、
     * Kimi、智谱、vLLM、Ollama 等都能直接用，换厂商只需改 base-url / model / api-key。</p>
     *
     * <p>{@code api-key} 为空时视为「未配置」，相关内容生成与题目识别功能会明确提示未启用，
     * 但不会影响应用启动与其他功能。</p>
     */
    @Data
    public static class Llm {
        private boolean enabled = true;
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private int timeoutSeconds = 90;
        private int maxTokens = 2400;
        private double temperature = 0.3;
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
