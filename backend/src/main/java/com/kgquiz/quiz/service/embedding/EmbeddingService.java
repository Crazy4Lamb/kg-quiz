package com.kgquiz.quiz.service.embedding;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.kgquiz.quiz.config.KgProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 embedding 服务：bge-small-zh-v1.5 (ONNX) + HuggingFace tokenizer
 * 模型缺失时不阻塞启动，语义检索自动降级。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final KgProperties kgProperties;

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private volatile boolean available = false;
    private int maxSeq = 512;

    public EmbeddingService(KgProperties kgProperties) {
        this.kgProperties = kgProperties;
    }

    @PostConstruct
    public void init() {
        if (!kgProperties.getEmbedding().isEnabled()) {
            log.warn("embedding 未启用，语义检索不可用");
            return;
        }
        Path model = Path.of(kgProperties.getEmbedding().getModelPath());
        Path tk = Path.of(kgProperties.getEmbedding().getTokenizerPath());
        if (!Files.exists(model) || !Files.exists(tk)) {
            log.warn("embedding 模型文件缺失: {} / {}，语义检索不可用（LIKE 检索仍可用）",
                    model.toAbsolutePath(), tk.toAbsolutePath());
            return;
        }
        try {
            env = OrtEnvironment.getEnvironment();
            session = env.createSession(model.toString(), new OrtSession.SessionOptions());
            tokenizer = HuggingFaceTokenizer.newInstance(tk);
            available = true;
            // 预热：走内部实现，绕过 available 检查
            doEmbed("测试");
            log.info("embedding 模型加载成功: {}，模型输入={}", model, session.getInputNames());
        } catch (Exception e) {
            available = false;
            log.warn("embedding 模型加载失败，语义检索不可用: {}", e.toString());
        }
    }

    @PreDestroy
    public void destroy() {
        try {
            if (session != null) {
                session.close();
            }
            if (tokenizer != null) {
                tokenizer.close();
            }
        } catch (Exception ignored) {
        }
    }

    public boolean available() {
        return available;
    }

    public int dim() {
        return kgProperties.getEmbedding().getDim();
    }

    /**
     * 生成文本向量，CLS pooling + L2 归一化
     */
    public List<Float> embed(String text) {
        if (!available) {
            throw new IllegalStateException("embedding 不可用");
        }
        return doEmbed(text);
    }

    /**
     * 实际向量计算：CLS pooling + L2 归一化
     */
    private List<Float> doEmbed(String text) {
        try {
            Encoding encoding = tokenizer.encode(text);
            long[] ids = encoding.getIds();
            long[] mask = encoding.getAttentionMask();

            // 截断 + padding 到 maxSeq
            long[] inputIds = new long[maxSeq];
            long[] attention = new long[maxSeq];
            int len = Math.min(ids.length, maxSeq);
            for (int i = 0; i < len; i++) {
                inputIds[i] = ids[i];
                attention[i] = mask[i];
            }

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", OnnxTensor.createTensor(env, new long[][]{inputIds}));
            inputs.put("attention_mask", OnnxTensor.createTensor(env, new long[][]{attention}));
            // 部分导出版本要求 token_type_ids 输入
            if (session.getInputNames().contains("token_type_ids")) {
                long[] typeIds = new long[maxSeq];
                inputs.put("token_type_ids", OnnxTensor.createTensor(env, new long[][]{typeIds}));
            }

            try {
                try (OrtSession.Result result = session.run(inputs)) {
                    return l2Normalize(poolCls(result.get(0).getValue()));
                }
            } finally {
                inputs.values().forEach(t -> {
                    try {
                        t.close();
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException("embedding 计算失败: " + e.getMessage(), e);
        }
    }

    /**
     * 兼容两种常见导出：last_hidden_state [1, seq, hidden] 取 CLS；已池化 [1, hidden] 直接取
     */
    private float[] poolCls(Object raw) {
        if (raw instanceof float[][][] hidden) {
            return hidden[0][0];
        }
        if (raw instanceof float[][] pooled) {
            return pooled[0];
        }
        throw new IllegalStateException("未知的模型输出形状: " + raw.getClass().getName());
    }

    private List<Float> l2Normalize(float[] vec) {
        double sum = 0;
        for (float v : vec) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        List<Float> out = new ArrayList<>(vec.length);
        for (float v : vec) {
            out.add((float) (v / (norm == 0 ? 1 : norm)));
        }
        return out;
    }
}
