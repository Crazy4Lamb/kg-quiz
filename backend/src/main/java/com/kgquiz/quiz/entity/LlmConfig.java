package com.kgquiz.quiz.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 大模型接入配置（运行时可改，不必重启服务）。
 *
 * <p><b>为什么入库</b>：接哪个厂商、换哪个模型是运营决策而非部署决策。
 * 放 application.yml 意味着每换一次模型就要重启、改配置、可能还要回滚 ——
 * 而内容生成是要被教研频繁试的（同一篇讲义换几个模型对比很常见）。
 * 入库后管理台点一下即生效。</p>
 *
 * <p><b>两层结构</b>：{@code orgId = NULL} 为平台默认，机构可覆盖。解析时机构优先，
 * 这样多机构场景下各机构可用自己的 Key 与模型（也避免平台 Key 被滥用超额）。</p>
 *
 * <p><b>apiKey 落库的取舍</b>：明文存库换来了运行期可改。生产环境应改为 KMS 加密或
 * 独立的密钥管理服务；本项目定位是 Demo，故明文存储但<b>一律不出现在任何响应里</b>，
 * 管理台只回掩码，见 {@code LlmClient.LlmSettings#maskedKey}。</p>
 */
@Data
@TableName("llm_config")
public class LlmConfig {

    /** 主键 */
    private Long id;

    /** 预设编码：deepseek / ollama-local / anthropic…；CUSTOM 表示手工配 */
    private String presetCode;

    /** 厂商名，预设回填，便于列表展示 */
    private String vendor;

    /**
     * 请求协议：OPENAI / ANTHROPIC / GEMINI / OLLAMA / VOLCENGINE。
     * 决定请求体与响应解析方式，见 {@code LlmProtocol}。
     */
    private String protocol;

    /**
     * 鉴权方式：BEARER / HEADER / QUERY_KEY / GOOG_HEADER / NONE / CUSTOM_HEADER。
     * 与 protocol 正交，自建网关常用「OpenAI 体 + 自定义 header」。
     */
    private String auth;

    /** CUSTOM_HEADER 时的 header 名 */
    private String authHeader;

    /** 服务根地址，不含 chat-path */
    private String baseUrl;

    /** 端点路径，Gemini 需保留 {model} 占位 */
    private String chatPath;

    /** 密钥；NONE 鉴权时可为空 */
    private String apiKey;

    private String model;

    private Integer timeoutSeconds;

    private Integer maxTokens;

    private Double temperature;

    /** 1 启用 / 0 停用。停用后该机构回落��环境变量兜底配置 */
    private Boolean enabled;

    /** 所属机构；NULL = 平台默认 */
    private Long orgId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
