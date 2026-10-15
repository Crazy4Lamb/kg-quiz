import { useEffect, useMemo, useState } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, message, Modal, Form, Input,
  Switch, Popconfirm, Alert, Row, Col, List, Select, Divider, Tooltip, Slider
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, StarOutlined, StarFilled, ReloadOutlined,
  SlidersOutlined, EyeOutlined, ExperimentOutlined, SaveOutlined, ArrowUpOutlined, ArrowDownOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

const pct = (v) => `${Math.round((Number(v) || 0) * 100)}%`;
const fmt = (v, max) => (Number(max) <= 1 ? pct(v) : String(v));

/**
 * 学习优先级推荐配置中心。
 *
 * 三个能力：① 策略 + 多因子权重可调（后端 /recommend/params 给元数据，前端动态渲染，不硬编码字段）；
 * ② 沙盒模拟——用草稿配置算一遍推荐结果，和当前生效配置做「进/出/位移」对比，调参前先看效果；
 * ③ 规则落库——保存为机构/平台规则，出题引擎与学习任务流共用。
 */
export default function RecommendRules() {
  const session = readSession();
  const role = session?.user?.role;
  const canSave = role === ROLE.ORG_ADMIN || role === ROLE.ADMIN;

  const [meta, setMeta] = useState(null);
  const [rules, setRules] = useState([]);
  const [activeRule, setActiveRule] = useState(null); // 当前生效规则（用于对比）
  const [draft, setDraft] = useState({ strategy: 'BALANCED', config: {} });
  const [loading, setLoading] = useState(true);

  // 学生选择 + 模拟
  const [students, setStudents] = useState([]);
  const [simStudent, setSimStudent] = useState(null);
  const [sim, setSim] = useState(null);
  const [simulating, setSimulating] = useState(false);

  // 规则编辑弹窗
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState(null); // 正在编辑的规则（null=新建）
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const [m, rs] = await Promise.all([api.recommendParams(), api.listRecommendRules()]);
      const metaData = unwrap(m);
      const ruleList = unwrap(rs) || [];
      setMeta(metaData);
      setRules(ruleList);
      // 生效规则：优先本机构默认，否则平台默认
      const eff = ruleList.find((r) => r.isDefault === 1 && r.orgId != null)
        || ruleList.find((r) => r.isDefault === 1) || null;
      setActiveRule(eff);
      // 草稿初始化为生效规则（没有则取默认元数据）
      const initStrategy = eff?.strategy || 'BALANCED';
      const initConfig = eff?.configMap || metaData?.defaults || {};
      setDraft({ strategy: initStrategy, config: { ...initConfig } });
      if (!simStudent && ruleList.length >= 0) {
        // 学生列表稍后加载，这里先不依赖
      }
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const loadStudents = async () => {
    try {
      const list = unwrap(await api.listStudents()) || [];
      setStudents(list);
      if (list.length && !simStudent) setSimStudent(list[0].id);
    } catch {
      // 教师督导范围外可能为空，忽略
    }
  };

  useEffect(() => { load(); loadStudents(); }, []);

  const pickStrategy = (value) => {
    const preset = meta?.strategies?.find((s) => s.value === value)?.preset;
    setDraft((d) => ({ strategy: value, config: preset ? { ...preset } : d.config }));
    setSim(null);
  };

  const setParam = (key, val) => {
    setDraft((d) => ({ ...d, config: { ...d.config, [key]: val } }));
    setSim(null);
  };

  const doSimulate = async () => {
    if (!simStudent) { message.warning('请先选择一名学生'); return; }
    setSimulating(true);
    try {
      const r = unwrap(await api.simulateRecommend({
        studentId: simStudent,
        strategy: draft.strategy,
        config: draft.config,
        limit: 12
      }));
      setSim(r);
    } catch (e) {
      message.error(e.message);
    } finally {
      setSimulating(false);
    }
  };

  // 草稿相对生效规则的字段变化
  const changedFields = useMemo(() => {
    if (!activeRule?.configMap) return [];
    const base = activeRule.configMap;
    const out = [];
    Object.keys(draft.config).forEach((k) => {
      if (Number(base[k]) !== Number(draft.config[k])) {
        out.push({ key: k, from: base[k], to: draft.config[k] });
      }
    });
    if (activeRule.strategy !== draft.strategy) out.unshift({ key: '__strategy', from: activeRule.strategy, to: draft.strategy });
    return out;
  }, [draft, activeRule]);

  const openEditor = (rule) => {
    setEditing(rule || null);
    setOpen(true);
  };

  // 弹窗初始值：新建用 BALANCED 预设，编辑用规则现有值（Form 仅在 open 时挂载，每次打开重新应用）
  const editorInitial = (() => {
    if (editing) {
      return {
        id: editing.id, name: editing.name, enabled: editing.enabled === 1,
        isDefault: editing.isDefault === 1, strategy: editing.strategy, ...(editing.configMap || {})
      };
    }
    const preset = meta?.strategies?.find((s) => s.value === 'BALANCED')?.preset || meta?.defaults || {};
    return { name: '自定义规则', enabled: true, isDefault: false, strategy: 'BALANCED', ...preset };
  })();

  const saveRule = async () => {
    const v = await form.validateFields();
    setBusy(true);
    try {
      await api.saveRecommendRule({
        id: v.id ?? null,
        orgId: null, // 后端按当前用户机构落库；平台管理员落 null
        name: v.name,
        strategy: v.strategy,
        enabled: !!v.enabled,
        isDefault: !!v.isDefault,
        config: meta?.groups?.reduce((acc, g) => {
          g.params.forEach((p) => { acc[p.key] = v[p.key] ?? draft.config[p.key]; });
          return acc;
        }, {}) || draft.config
      });
      message.success('规则已保存');
      setOpen(false);
      await load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const remove = async (rule) => {
    try {
      await api.deleteRecommendRule(rule.id);
      message.success('已删除');
      await load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const columns = [
    {
      title: '规则', dataIndex: 'name',
      render: (v, row) => (
        <Space size={6}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          {row.isDefault === 1 && <Tag color="gold" icon={<StarFilled />}>默认</Tag>}
          {row.orgId == null ? <Tag>平台级</Tag> : <Tag color="blue">机构级</Tag>}
          {row.enabled === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>}
        </Space>
      )
    },
    {
      title: '策略', dataIndex: 'strategy',
      render: (v) => <Tag>{meta?.strategies?.find((s) => s.value === v)?.label || v}</Tag>
    },
    {
      title: '参数摘要', render: (_, row) => {
        const c = row.configMap || {};
        return (
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            掌握缺口 {c.masteryWeight} · 重要性 {c.importanceWeight} · 遗忘 {c.forgettingWeight} ·
            薄弱线 {pct(c.weakThreshold)} · 每日 {c.dailyTaskLimit} 条
          </Typography.Text>
        );
      }
    },
    {
      title: '操作', width: 230,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(row)}>编辑</Button>
          <Popconfirm title="确认删除该规则？" onConfirm={() => remove(row)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      )
    }
  ];

  const strategyLabel = (v) => meta?.strategies?.find((s) => s.value === v)?.label || v;

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="info" showIcon icon={<SlidersOutlined />}
        message="推荐同时决定「学什么」（每日学习任务）与「练什么」（自适应出题）"
        description="六类因子（掌握缺口 / 重要性 / 遗忘风险 / 低置信度 / 前置阻塞 / 近期重复）加权打分。改这里，两边一起变。教师可查看并沙盒模拟，落库保存需机构/平台管理员。" />

      {/* 策略 + 参数编辑器 */}
      <Card size="small" title={<Space><SlidersOutlined />推荐策略与参数</Space>}
        extra={
          <Space>
            <Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>
            <Button size="small" type="primary" icon={<ExperimentOutlined />}
              loading={simulating} onClick={doSimulate}>沙盒模拟</Button>
            {canSave
              ? <Button size="small" icon={<SaveOutlined />} onClick={() => openEditor(null)}>保存为规则</Button>
              : <Tooltip title="保存规则需机构/平台管理员"><Button size="small" icon={<SaveOutlined />} disabled>保存为规则</Button></Tooltip>}
          </Space>
        }>
        <div style={{ marginBottom: 8 }}>
          <Typography.Text strong>① 选择策略（决定因子组合方式）</Typography.Text>
        </div>
        <Row gutter={12} style={{ marginBottom: 16 }}>
          {(meta?.strategies || []).map((s) => {
            const selected = draft.strategy === s.value;
            return (
              <Col span={6} key={s.value}>
                <Card size="small" hoverable onClick={() => pickStrategy(s.value)}
                  style={{ borderColor: selected ? '#1677ff' : '#f0f0f0', borderWidth: selected ? 2 : 1 }}>
                  <div style={{ fontWeight: 600, color: selected ? '#1677ff' : undefined }}>{s.label}</div>
                  <div style={{ fontSize: 12, color: '#888', minHeight: 34 }}>{s.desc}</div>
                  {selected && <Tag color="blue">当前草稿</Tag>}
                </Card>
              </Col>
            );
          })}
        </Row>

        <Divider style={{ margin: '0 0 12px' }} orientation="left">② 调节因子权重</Divider>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 8 }}>
          <span>沙盒模拟对象：</span>
          <Select style={{ width: 240 }} value={simStudent} placeholder="选择学生"
            onChange={setSimStudent} showSearch optionFilterProp="label"
            options={students.map((s) => ({ value: s.id, label: `${s.name}（${s.id}）` }))} />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            调参后点「沙盒模拟」，看推荐顺序怎么变
          </Typography.Text>
        </div>
        {(meta?.groups || []).map((g) => (
          <div key={g.key} style={{ marginBottom: 12 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between' }}>
              <Typography.Text strong>{g.title}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>{g.desc}</Typography.Text>
            </div>
            <Row gutter={[24, 0]}>
              {g.params.map((p) => (
                <Col span={12} key={p.key} style={{ paddingTop: 4 }}>
                  <Tooltip title={p.tip}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <span style={{ width: 130, fontSize: 13 }}>{p.label}</span>
                      <Slider style={{ flex: 1 }} min={p.min} max={p.max} step={p.step}
                        value={Number(draft.config[p.key] ?? p.min)}
                        onChange={(val) => setParam(p.key, val)}
                        tooltip={{ formatter: (v) => fmt(v, p.max) }} />
                      <span style={{ width: 52, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                        {fmt(draft.config[p.key] ?? p.min, p.max)}
                      </span>
                    </div>
                  </Tooltip>
                </Col>
              ))}
            </Row>
          </div>
        ))}

        {/* 草稿 vs 生效 对比 */}
        <Divider style={{ margin: '4px 0 12px' }} orientation="left">③ 草稿 vs 当前生效规则</Divider>
        {changedFields.length === 0
          ? <Alert type="success" showIcon message="草稿与当前生效规则一致，无需保存。" />
          : (
            <List size="small" dataSource={changedFields}
              renderItem={(c) => (
                <List.Item>
                  {c.key === '__strategy'
                    ? <span>策略：<Tag>{strategyLabel(c.from)}</Tag> → <Tag color="blue">{strategyLabel(c.to)}</Tag></span>
                    : <span>{meta?.groups?.flatMap((g) => g.params).find((p) => p.key === c.key)?.label || c.key}：
                        <Typography.Text type="secondary">{fmt(c.from, 1)}</Typography.Text> →
                        <Typography.Text strong>{fmt(c.to, 1)}</Typography.Text></span>}
                </List.Item>
              )} />
          )}
      </Card>

      {/* 沙盒模拟结果 */}
        {sim && (
          <Card size="small" title={<Space><ExperimentOutlined />沙盒模拟结果（{sim.strategyLabel}）</Space>}
            extra={<Button size="small" icon={<ReloadOutlined />} loading={simulating} onClick={doSimulate}>重算</Button>}>
            <SimResult sim={sim} />
          </Card>
        )}

      {/* 规则列表 */}
      <Card size="small" title={`已存规则（${rules.length}）`}
        extra={<Button size="small" type="primary" icon={<PlusOutlined />}
          disabled={!canSave} onClick={() => openEditor(null)}>新建规则</Button>}>
        <Table rowKey="id" size="small" loading={loading} dataSource={rules} columns={columns}
          pagination={{ pageSize: 8, hideOnSinglePage: true }} />
      </Card>

      <Modal title={editing?.id ? '编辑推荐规则' : '新建推荐规则'} open={open}
        onOk={saveRule} confirmLoading={busy} onCancel={() => setOpen(false)} width={760} okText="保存">
        {open && (
          <Form form={form} layout="vertical" initialValues={editorInitial}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请填写名称' }]}>
                <Input placeholder="如：基础薄弱班专用" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch size="small" /></Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="isDefault" label="设为默认" valuePropName="checked"><Switch size="small" /></Form.Item>
            </Col>
          </Row>
          <Form.Item name="strategy" label="策略" rules={[{ required: true }]}
            tooltip="保存后该策略即为此规则的生效策略；权重以页面上方滑块（草稿）为准">
            <Select options={(meta?.strategies || []).map((s) => ({ value: s.value, label: s.label }))} />
          </Form.Item>
          <Alert type="warning" showIcon message="参数已在上方的调节面板编辑，保存时将采用草稿中的值。"
            description="这里仅填写名称、策略与开关；具体的因子权重以页面上方滑块（当前草稿）为准。" />
          </Form>
        )}
      </Modal>
    </Space>
  );
}

function StudentPicker({ value, onChange, students }) {
  return (
    <Space style={{ marginBottom: 12 }}>
      <span>模拟对象：</span>
      <Select style={{ width: 240 }} value={value} placeholder="选择学生"
        onChange={onChange} showSearch optionFilterProp="label"
        options={students.map((s) => ({ value: s.id, label: `${s.name}（${s.id}）` }))} />
    </Space>
  );
}

/** 模拟前后对比：进/出/位移 */
function SimResult({ sim }) {
  const diff = sim.diff || {};
  const recOf = (m) => (
    <Space size={6}>
      <span style={{ fontWeight: 600 }}>{m.nodeName}</span>
      {m.oldRank != null && <Tag>{m.oldRank}</Tag>}
      <ArrowUpOutlined style={{ color: '#52c41a' }} />
      <Tag color="blue">{m.newRank}</Tag>
      {m.delta != null && <Typography.Text type="secondary">↑{m.delta}</Typography.Text>}
    </Space>
  );
  return (
    <Row gutter={12}>
      <Col span={8}>
        <Card size="small" title={<Tag color="green">新进入推荐（{diff.gainedCount || 0}）</Tag>}>
          <List size="small" dataSource={diff.entered || []} locale={{ emptyText: '无' }}
            renderItem={(m) => <List.Item>{recOf(m)}</List.Item>} />
        </Card>
      </Col>
      <Col span={8}>
        <Card size="small" title={<Tag color="red">掉出推荐（{diff.lostCount || 0}）</Tag>}>
          <List size="small" dataSource={diff.left || []} locale={{ emptyText: '无' }}
            renderItem={(m) => (
              <List.Item>
                <Space size={6}><span style={{ fontWeight: 600 }}>{m.nodeName}</span>
                  <Tag>{m.oldRank}</Tag><ArrowDownOutlined style={{ color: '#ff4d4f' }} /></Space>
              </List.Item>)} />
        </Card>
      </Col>
      <Col span={8}>
        <Card size="small" title={<Tag color="orange">排名位移（{diff.movedCount || 0}）</Tag>}>
          <List size="small" dataSource={diff.moved || []} locale={{ emptyText: '无' }}
            renderItem={(m) => <List.Item>{recOf(m)}</List.Item>} />
        </Card>
      </Col>
    </Row>
  );
}
