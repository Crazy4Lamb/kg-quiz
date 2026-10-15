package com.kgquiz.quiz.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.kgquiz.quiz.entity.LlmConfig;
import com.kgquiz.quiz.llm.LlmAuth;
import com.kgquiz.quiz.llm.LlmPresetCatalog;
import com.kgquiz.quiz.llm.LlmProtocol;
import com.kgquiz.quiz.mapper.LlmConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 大模型接入配置的读写与解析。
 *
 * <p><b>解析优先级</b>：本机构启用行 &gt; 平台启用行（orgId=NULL）&gt; null（交回
 * {@code LlmClient} 用环境变量兜底）。机构级存在的意义是多机构场景下各机构用自己的
 * Key 与模型，既避免平台 Key 被超额滥用，也让「教研对比不同模型」不必打扰其他机构。</p>
 *
 * <p>刻意<b>不缓存</b>：配置量极小（每机构一行），而模型调用本身有网络往返，
 * 每次解析的开销可忽略；换来的是「改完立刻生效」这个更重要的性质。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmConfigService {

    private final LlmConfigMapper mapper;

    /**
     * 解析当前生效配置。
     *
     * @return null 表示库里没有启用的记录，调用方应回落环境变量
     */
    public LlmClient.LlmSettings resolveSettings() {
        LlmConfig row = resolveRow();
        if (row == null) {
            return null;
        }
        return toSettings(row);
    }

    private LlmConfig resolveRow() {
        try {
            List<LlmConfig> rows = mapper.selectList(new LambdaQueryWrapper<LlmConfig>()
                    .eq(LlmConfig::getEnabled, 1));
            if (rows == null || rows.isEmpty()) {
                return null;
            }
            // 多租户：优先匹配当前用户所属机构，找不到再用平台行
            Long orgId = currentOrgId();
            if (orgId != null) {
                for (LlmConfig c : rows) {
                    if (orgId.equals(c.getOrgId())) {
                        return c;
                    }
                }
            }
            for (LlmConfig c : rows) {
                if (c.getOrgId() == null) {
                    return c;
                }
            }
            // 无本机构/平台配置时交回环境变量，绝不能借用其他机构的密钥。
            return null;
        } catch (Exception e) {
            // 配置表缺失或查询失败不该拖垮整个应用，退回环境变量
            log.debug("读取 llm_config 失败，回落环境变量配置: {}", e.getMessage());
            return null;
        }
    }

    private Long currentOrgId() {
        try {
            var user = com.kgquiz.quiz.auth.UserContext.peek();
            return user == null ? null : user.orgId();
        } catch (Exception e) {
            return null;
        }
    }

    private LlmClient.LlmSettings toSettings(LlmConfig row) {
        LlmProtocol protocol = LlmProtocol.of(row.getProtocol());
        LlmAuth auth = row.getAuth() == null || row.getAuth().isBlank()
                ? protocol.defaultAuth()
                : LlmAuth.of(row.getAuth());
        String chatPath = row.getChatPath() == null || row.getChatPath().isBlank()
                ? protocol.defaultChatPath()
                : row.getChatPath();
        return new LlmClient.LlmSettings(
                Boolean.TRUE.equals(row.getEnabled()),
                protocol,
                auth,
                row.getBaseUrl(),
                chatPath,
                row.getApiKey(),
                row.getAuthHeader(),
                row.getModel(),
                row.getTimeoutSeconds() == null ? 90 : row.getTimeoutSeconds(),
                row.getMaxTokens() == null ? 2400 : row.getMaxTokens(),
                row.getTemperature() == null ? 0.3 : row.getTemperature(),
                row.getPresetCode(),
                row.getOrgId() == null ? "平台配置" : "机构配置");
    }

    /** 读当前机构（或平台）的配置行，用于管理台回显 */
    public LlmConfig currentRow(Long orgId) {
        if (orgId != null) {
            LlmConfig own = mapper.selectOne(new LambdaQueryWrapper<LlmConfig>()
                    .eq(LlmConfig::getOrgId, orgId).last("LIMIT 1"));
            if (own != null) {
                return own;
            }
        }
        return mapper.selectOne(new LambdaQueryWrapper<LlmConfig>()
                .isNull(LlmConfig::getOrgId).last("LIMIT 1"));
    }

    /**
     * 保存配置（按 orgId 唯一，存在则更新）。
     *
     * <p>{@code apiKey} 传空串时<b>保留原值</b>而不是清空 —— 管理台回显的是掩码，
     * 用户不改 Key 时那个字段就是空的。若当空值处理，会出现「只改个温度结果 Key 没了」
     * 这种很难排查的问题。传 {@code null} 同样表示不改；想清空需显式传 {@code "CLEAR"}。</p>
     */
    public LlmConfig save(Long orgId, Map<String, Object> body) {
        LlmProtocol protocol = LlmProtocol.of(str(body.get("protocol")));
        LlmAuth auth = body.get("auth") == null ? protocol.defaultAuth() : LlmAuth.of(str(body.get("auth")));
        String chatPath = str(body.get("chatPath"));
        if (chatPath == null || chatPath.isBlank()) {
            chatPath = protocol.defaultChatPath();
        }

        LlmConfig row = mapper.selectOne(new LambdaQueryWrapper<LlmConfig>()
                .eq(orgId != null, LlmConfig::getOrgId, orgId)
                .isNull(orgId == null, LlmConfig::getOrgId)
                .last("LIMIT 1"));
        boolean isNew = row == null;
        if (isNew) {
            row = new LlmConfig();
            row.setOrgId(orgId);
        }

        row.setPresetCode(orDefault(str(body.get("presetCode")), "CUSTOM"));
        row.setVendor(orDefault(str(body.get("vendor")), ""));
        row.setProtocol(protocol.name());
        row.setAuth(auth.name());
        row.setAuthHeader(trimToNull(str(body.get("authHeader"))));
        row.setBaseUrl(orDefault(str(body.get("baseUrl")), ""));
        row.setChatPath(chatPath);
        row.setModel(orDefault(str(body.get("model")), ""));

        String key = str(body.get("apiKey"));
        if ("CLEAR".equals(key)) {
            row.setApiKey("");
        } else if (key != null && !key.isBlank()) {
            row.setApiKey(key.trim());
        }

        row.setTimeoutSeconds(body.get("timeoutSeconds") == null ? 90
                : Integer.parseInt(String.valueOf(body.get("timeoutSeconds")).trim()));
        row.setMaxTokens(body.get("maxTokens") == null ? 2400
                : Integer.parseInt(String.valueOf(body.get("maxTokens")).trim()));
        row.setTemperature(body.get("temperature") == null ? 0.3
                : Double.parseDouble(String.valueOf(body.get("temperature")).trim()));
        row.setEnabled(body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled"))));

        if (isNew) {
            mapper.insert(row);
            log.info("新建大模型配置 orgId={} preset={} protocol={}", orgId, row.getPresetCode(), row.getProtocol());
        } else {
            mapper.updateById(row);
            log.info("更新大模型配置 orgId={} preset={} protocol={}", orgId, row.getPresetCode(), row.getProtocol());
        }
        return row;
    }

    /** 切换启用状态 */
    public LlmConfig setEnabled(Long orgId, boolean enabled) {
        LlmConfig row = mapper.selectOne(new LambdaQueryWrapper<LlmConfig>()
                .eq(orgId != null, LlmConfig::getOrgId, orgId)
                .isNull(orgId == null, LlmConfig::getOrgId)
                .last("LIMIT 1"));
        if (row == null) {
            throw new com.kgquiz.quiz.common.BizException("还没有配置记录，请先保存一次");
        }
        row.setEnabled(enabled);
        mapper.updateById(row);
        return row;
    }

    /** 管理台用：当前生效配置（脱敏）+ 预设目录 + 支持的协议/鉴权枚举 */
    public Map<String, Object> overview(LlmClient llm) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("effective", llm.settings().toSafeMap());
        m.put("available", llm.available());
        String reason = llm.unavailableReason();
        m.put("reason", reason == null ? "" : reason);

        LlmConfig row = currentRow(currentOrgId());
        m.put("configured", row != null);
        m.put("current", row == null ? null : safeRow(row));
        m.put("presets", LlmPresetCatalog.allAsMaps());
        m.put("protocols", List.of(
                Map.of("value", LlmProtocol.OPENAI.name(), "label", LlmProtocol.OPENAI.label(),
                        "defaultPath", LlmProtocol.OPENAI.defaultChatPath(),
                        "defaultAuth", LlmProtocol.OPENAI.defaultAuth().name(),
                        "jsonMode", LlmProtocol.OPENAI.supportsJsonMode()),
                Map.of("value", LlmProtocol.ANTHROPIC.name(), "label", LlmProtocol.ANTHROPIC.label(),
                        "defaultPath", LlmProtocol.ANTHROPIC.defaultChatPath(),
                        "defaultAuth", LlmProtocol.ANTHROPIC.defaultAuth().name(),
                        "jsonMode", LlmProtocol.ANTHROPIC.supportsJsonMode()),
                Map.of("value", LlmProtocol.GEMINI.name(), "label", LlmProtocol.GEMINI.label(),
                        "defaultPath", LlmProtocol.GEMINI.defaultChatPath(),
                        "defaultAuth", LlmProtocol.GEMINI.defaultAuth().name(),
                        "jsonMode", LlmProtocol.GEMINI.supportsJsonMode()),
                Map.of("value", LlmProtocol.OLLAMA.name(), "label", LlmProtocol.OLLAMA.label(),
                        "defaultPath", LlmProtocol.OLLAMA.defaultChatPath(),
                        "defaultAuth", LlmProtocol.OLLAMA.defaultAuth().name(),
                        "jsonMode", LlmProtocol.OLLAMA.supportsJsonMode()),
                Map.of("value", LlmProtocol.VOLCENGINE.name(), "label", LlmProtocol.VOLCENGINE.label(),
                        "defaultPath", LlmProtocol.VOLCENGINE.defaultChatPath(),
                        "defaultAuth", LlmProtocol.VOLCENGINE.defaultAuth().name(),
                        "jsonMode", LlmProtocol.VOLCENGINE.supportsJsonMode())));
        m.put("auths", List.of(
                Map.of("value", LlmAuth.BEARER.name(), "label", LlmAuth.BEARER.label()),
                Map.of("value", LlmAuth.HEADER.name(), "label", LlmAuth.HEADER.label()),
                Map.of("value", LlmAuth.GOOG_HEADER.name(), "label", LlmAuth.GOOG_HEADER.label()),
                Map.of("value", LlmAuth.QUERY_KEY.name(), "label", LlmAuth.QUERY_KEY.label()),
                Map.of("value", LlmAuth.CUSTOM_HEADER.name(), "label", LlmAuth.CUSTOM_HEADER.label()),
                Map.of("value", LlmAuth.NONE.name(), "label", LlmAuth.NONE.label())));
        return m;
    }

    /** 单行脱敏视图 */
    public Map<String, Object> safeRow(LlmConfig row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("orgId", row.getOrgId());
        m.put("presetCode", row.getPresetCode());
        m.put("vendor", row.getVendor());
        m.put("protocol", row.getProtocol());
        m.put("protocolLabel", LlmProtocol.of(row.getProtocol()).label());
        m.put("auth", row.getAuth());
        m.put("authLabel", LlmAuth.of(row.getAuth()).label());
        m.put("authHeader", row.getAuthHeader());
        m.put("baseUrl", LlmClient.redact(row.getBaseUrl(), row.getApiKey()));
        m.put("chatPath", LlmClient.redact(row.getChatPath(), row.getApiKey()));
        m.put("model", row.getModel());
        m.put("timeoutSeconds", row.getTimeoutSeconds());
        m.put("maxTokens", row.getMaxTokens());
        m.put("temperature", row.getTemperature());
        m.put("enabled", row.getEnabled());
        m.put("hasKey", row.getApiKey() != null && !row.getApiKey().isBlank());
        m.put("maskedKey", mask(row.getApiKey()));
        m.put("updatedAt", row.getUpdatedAt() == null ? null : row.getUpdatedAt().toString());
        return m;
    }

    private static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 3);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String orDefault(String v, String d) {
        return v == null || v.isBlank() ? d : v.trim();
    }

    private static String trimToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
