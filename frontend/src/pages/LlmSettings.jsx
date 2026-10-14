import { useEffect, useMemo, useState } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, message, Form, Input, Select, InputNumber,
  Switch, Alert, Row, Col, Divider, Tooltip, Empty, Badge
} from 'antd';
import {
  CloudServerOutlined, ApiOutlined, ThunderboltOutlined, LinkOutlined,
  SafetyCertificateOutlined, InfoCircleOutlined, CheckCircleOutlined, CloseCircleOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const STAGE_LABEL = {
  CONFIG: '配置不完整',
  AUTH: '认证失败',
  ENDPOINT: '地址或模型不对',
  QUOTA: '额度不足',
  NETWORK: '网络不通',
  DONE: '连通',
  UNKNOWN: '未知错误'
};
const STAGE_COLOR = {
  CONFIG: 'default',
  AUTH: 'volcano',
  ENDPOINT: 'volcano',
  QUOTA: 'orange',
  NETWORK: 'red',
  DONE: 'green',
  UNKNOWN: 'default'
};

/**
 * 大模型接入设置。
 *
 * <p>设计要点是<b>预设优先</b>：厂商的协议、端点、鉴权方式都是容易配错的细节
 * （Anthropic 缺 anthropic-version 直接 400、Gemini 的模型名要嵌在路径里、
 * 通义的 base-url 必须带 /compatible-mode），让用户从下拉里选厂商自动填好，
 * 比给一个空表单让他手填要可靠得多。选「自定义」才展开全部字段。</p>
 *
 * <p>协议与鉴权<b>不由用户直接选</b>，而是从预设推导后只读展示。刻意这样：
 * 用户在不知情的情况下把 Anthropic 配成 Bearer 鉴权，只会看到一个没有信息量的
 * 401，而不会意识到是鉴权方式错了。改协议 = 换厂商预设。</p>
 */
export default function LlmSettings() {
  const [overview, setOverview] = useState(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState(null);
  const [form] = Form.useForm();

  const presets = overview?.presets || [];
  const effective = overview?.effective || {};
  const canWrite = true; // 后端按角色把关；这里只管展示

  const load = async () => {
    setLoading(true);
    try {
      const ov = unwrap(await api.llmOverview()) || {};
      setOverview(ov);
      const c = ov.current;
      form.setFieldsValue({
        presetCode: c?.presetCode || 'deepseek',
        protocol: c?.protocol || 'OPENAI',
        auth: c?.auth || 'BEARER',
        authHeader: c?.authHeader || '',
        baseUrl: c?.baseUrl || '',
        chatPath: c?.chatPath || '/v1/chat/completions',
        model: c?.model || '',
        apiKey: '',
        timeoutSeconds: c?.timeoutSeconds ?? 90,
        maxTokens: c?.maxTokens ?? 2400,
        temperature: c?.temperature ?? 0.3,
        enabled: c?.enabled ?? true
      });
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  /** 选预设 → 自动填协议/端点/鉴权/模型，用户只补 Key */
  const applyPreset = (code) => {
    const p = presets.find((x) => x.presetCode === code);
    if (!p) return;
    form.setFieldsValue({
      presetCode: code,
      protocol: p.protocol,
      auth: p.auth,
      authHeader: '',
      baseUrl: p.baseUrl,
      chatPath: p.chatPath,
      model: p.model
    });
    setResult(null);
    if (p.note) {
      message.info({ content: p.note, duration: 6 });
    }
  };

  // useWatch 必须在组件顶层调用，不能塞进 useMemo 里
  const watchedPreset = Form.useWatch('presetCode', form);
  const watchedEnabled = Form.useWatch('enabled', form);
  const selectedPreset = useMemo(
    () => presets.find((x) => x.presetCode === watchedPreset),
    [presets, watchedPreset]
  );
  const isCustom = watchedPreset === 'custom';

  const test = async () => {
    setTesting(true);
    setResult(null);
    try {
      const v = form.getFieldsValue();
      const r = unwrap(await api.testLlm({
        protocol: v.protocol,
        auth: v.auth,
        authHeader: v.authHeader,
        baseUrl: v.baseUrl,
        chatPath: v.chatPath,
        model: v.model,
        apiKey: v.apiKey
      })) || {};
      setResult(r);
      if (r.ok) {
        message.success(`连接成功，耗时 ${r.costMs}ms`);
      } else {
        message.warning(r.message);
      }
    } catch (e) {
      message.error(e.message);
    } finally {
      setTesting(false);
    }
  };

  const save = async () => {
    setSaving(true);
    try {
      const v = await form.validateFields();
      await api.saveLlmConfig({ ...v, orgId: overview?.current?.orgId ?? null });
      message.success('已保存，立即生效（无需重启）');
      setResult(null);
      await load();
    } catch (e) {
      if (e?.errorFields) return; // 表单校验失败，已在字段上提示
      message.error(e.message);
    } finally {
      setSaving(false);
    }
  };

  const toggleEnabled = async ( checked) => {
    try {
      await api.setLlmEnabled({ orgId: overview?.current?.orgId ?? null, enabled: checked });
      message.success(checked ? '已启用' : '已停用，将回落环境变量配置');
      await load();
    } catch (e) {
      message.error(e.message);
      await load();
    }
  };

  const presetOptions = presets.map((p) => ({
    value: p.presetCode,
    label: (
      <Space size={6}>
        <span>{p.vendor}</span>
        {p.free && <Tag color="green">零成本</Tag>}
      </Space>
    )
  }));

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small" loading={loading}>
        <Row gutter={[16, 12]} align="middle">
          <Col flex="1 1 320px">
            <Space direction="vertical" size={4}>
              <Space size={8} wrap>
                <CloudServerOutlined style={{ fontSize: 18 }} />
                <Typography.Text strong>大模型接入</Typography.Text>
                {overview?.available
                  ? <Tag color="green" icon={<CheckCircleOutlined />}>已就绪</Tag>
                  : <Tag color="default">未启用</Tag>}
                {effective.protocolLabel && (
                  <Tooltip title={`协议 ${effective.protocol}｜鉴权 ${effective.authLabel}`}>
                    <Tag color="blue" icon={<ApiOutlined />}>{effective.protocolLabel}</Tag>
                  </Tooltip>
                )}
                {effective.model && <Tag>{effective.model}</Tag>}
                {effective.source && <Tag>{effective.source}</Tag>}
              </Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {overview?.available
                  ? `内容生成与题目识别将由模型产出（当前：${effective.vendor || '自定义'} · ${effective.model}）。`
                  : (overview?.reason || '尚未配置 API Key，相关功能会降级为规则模板，功能不受影响。')}
              </Typography.Text>
            </Space>
          </Col>
          <Col flex="0 0 auto">
            <Space>
              <Typography.Text type="secondary">启用</Typography.Text>
              <Switch
                checked={watchedEnabled ?? overview?.current?.enabled ?? true}
                onChange={(v) => {
                  // 立即改表单值，让「保存」能把这个状态一起提交
                  form.setFieldValue('enabled', v);
                  toggleEnabled(v);
                }}
              />
              <Button icon={<ThunderboltOutlined />} loading={testing} onClick={test}>试连通</Button>
              <Button type="primary" loading={saving} onClick={save} disabled={!canWrite}>
                保存
              </Button>
            </Space>
          </Col>
        </Row>

        {result && (
          <Alert
            style={{ marginTop: 12 }}
            type={result.ok ? 'success' : 'error'}
            showIcon
            message={
              <Space size={8}>
                <Tag color={STAGE_COLOR[result.stage] || 'default'}>
                  {STAGE_LABEL[result.stage] || result.stage}
                </Tag>
                <span>{result.message}</span>
                {result.costMs != null && <Typography.Text type="secondary">{result.costMs}ms</Typography.Text>}
              </Space>
            }
            description={
              <Space direction="vertical" size={4}>
                {result.endpoint && (
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    端点：<code>{result.endpoint}</code>
                  </Typography.Text>
                )}
                {result.reply && <Typography.Text>模型回复：「{result.reply}」</Typography.Text>}
                {result.hint && <Typography.Text type="secondary">{result.hint}</Typography.Text>}
              </Space>
            }
          />
        )}
      </Card>

      <Form form={form} layout="vertical">
        <Row gutter={12}>
          <Col xs={24} lg={12}>
            <Card size="small" title="选择厂商预设"
              extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                选预设自动填好协议与端点，只需补 API Key
              </Typography.Text>}>
              <Form.Item name="presetCode" label="厂商 / 接入方式">
                <Select
                  options={presetOptions}
                  onChange={applyPreset}
                  showSearch
                  optionFilterProp="label"
                />
              </Form.Item>

              {selectedPreset?.note && (
                <Alert type="info" showIcon style={{ marginBottom: 12 }}
                  message={<Typography.Text style={{ fontSize: 12 }}>{selectedPreset.note}</Typography.Text>} />
              )}

              {isCustom && (
                <>
                  <Form.Item name="protocol" label="协议" rules={[{ required: true }]}>
                    <Select
                      disabled
                      options={(overview?.protocols || []).map((p) => ({
                        value: p.value,
                        label: `${p.label}${p.jsonMode ? '' : '（无原生 JSON 模式，靠提示词约束）'}`
                      }))}
                    />
                  </Form.Item>
                  <Form.Item name="auth" label="鉴权方式" rules={[{ required: true }]}>
                    <Select disabled options={(overview?.auths || []).map((a) => ({
                      value: a.value, label: a.label
                    }))} />
                  </Form.Item>
                  <Form.Item noStyle shouldUpdate={(a, b) => a.auth !== b.auth}>
                    {({ getFieldValue }) => getFieldValue('auth') === 'CUSTOM_HEADER' && (
                      <Form.Item name="authHeader" label="自定义 Header 名"
                        rules={[{ required: true, message: '请填写 header 名，如 X-Gateway-Token' }]}>
                        <Input placeholder="X-Gateway-Token" />
                      </Form.Item>
                    )}
                  </Form.Item>
                </>
              )}
            </Card>
          </Col>

          <Col xs={24} lg={12}>
            <Card size="small" title="接入参数">
              <Row gutter={12}>
                <Col span={24}>
                  <Form.Item
                    name="baseUrl"
                    label={<Space size={4}>服务地址 base-url<InfoCircleOutlined /></Space>}
                    extra="不要包含端点路径（如 /v1/chat/completions），路径单独填"
                    rules={[
                      { required: true, message: '请填写服务地址' },
                      { pattern: /^https?:\/\/.+/, message: '要以 http:// 或 https:// 开头' }
                    ]}
                  >
                    <Input placeholder="https://api.deepseek.com" />
                  </Form.Item>
                </Col>
                <Col span={24}>
                  <Form.Item name="chatPath" label="端点路径 chat-path"
                    extra="Gemini 必须保留 {model} 占位">
                    <Input placeholder="/v1/chat/completions" />
                  </Form.Item>
                </Col>
                <Col span={24}>
                  <Form.Item name="apiKey" label="API Key"
                    extra={overview?.current?.hasKey
                      ? `已保存：${overview.current.maskedKey}（留空表示不修改）`
                      : '未配置'}>
                    <Input.Password
                      placeholder={overview?.current?.hasKey ? '留空表示不修改' : '粘贴 API Key'}
                      autoComplete="new-password"
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} sm={12}>
                  <Form.Item name="model" label="模型名" rules={[{ required: true, message: '请填写模型名' }]}>
                    {selectedPreset?.models?.length ? (
                      <Select
                        showSearch
                        options={selectedPreset.models.map((m) => ({ value: m, label: m }))}
                        placeholder="选择或输入模型名"
                      />
                    ) : (
                      <Input placeholder="deepseek-chat" />
                    )}
                  </Form.Item>
                </Col>
                <Col xs={12} sm={6}>
                  <Form.Item name="timeoutSeconds" label="超时(秒)">
                    <InputNumber min={5} max={600} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col xs={12} sm={6}>
                  <Form.Item name="maxTokens" label="输出上限">
                    <InputNumber min={256} max={32000} step={256} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={24}>
                  <Form.Item name="temperature" label="采样温度"
                    extra="生成结构化内容建议 0.2~0.4；越高越发散">
                    <InputNumber min={0} max={2} step={0.1} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col span={24}>
                  <Form.Item name="enabled" label="启用该配置" valuePropName="checked"
                    hidden>
                    <Switch />
                  </Form.Item>
                </Col>
              </Row>
            </Card>
          </Col>
        </Row>
      </Form>

      <Card size="small" title="支持的内置预设">
        <Table
          size="small"
          rowKey="presetCode"
          pagination={false}
          dataSource={presets}
          columns={[
            {
              title: '厂商', dataIndex: 'vendor', width: 200,
              render: (v, r) => (
                <Space size={6}>
                  <span>{v}</span>
                  {r.free && <Tag color="green">零成本</Tag>}
                </Space>
              )
            },
            {
              title: '协议', dataIndex: 'protocolLabel', width: 150,
              render: (v, r) => (
                <Space size={4}>
                  <Tag color="blue">{v}</Tag>
                  {!r.jsonMode && <Tag>提示词约束</Tag>}
                </Space>
              )
            },
            {
              title: '鉴权', dataIndex: 'authLabel', width: 190,
              render: (v, r) => (r.keyRequired ? v : <Tag color="green">无需 Key</Tag>)
            },
            {
              title: '端点', width: 300,
              render: (_, r) => (
                <Typography.Text code style={{ fontSize: 11 }}>
                  {r.baseUrl || '（自行填写）'}{r.chatPath}
                </Typography.Text>
              )
            },
            {
              title: '推荐模型', dataIndex: 'model', width: 170,
              render: (v) => v || <Typography.Text type="secondary">—</Typography.Text>
            },
            {
              title: '说明', dataIndex: 'note',
              render: (v) => (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{v}</Typography.Text>
              )
            }
          ]}
        />
      </Card>

      <Card size="small">
        <Space direction="vertical" size={6}>
          <Space size={6}>
            <SafetyCertificateOutlined />
            <Typography.Text strong>关于降级</Typography.Text>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            未配置或调用失败时，内容生成会自动降级为规则模板（标注 generatedBy=TEMPLATE），
            题目识别降级为纯语义相似度。功能不中断，只是质量下降 —— 所以配置没到位不会让系统不可用。
          </Typography.Text>
          <Divider style={{ margin: '4px 0' }} />
          <Space size={6} align="start">
            <LinkOutlined style={{ marginTop: 3 }} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              API Key 存在数据库里且<b>明文</b>，这是 Demo 期的取舍（换来不重启就能换厂商）。
              生产环境应改为 KMS 加密或独立的密钥管理服务。无论如何，接口响应里只会返回掩码。
            </Typography.Text>
          </Space>
        </Space>
      </Card>
    </Space>
  );
}
