import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, message, Modal, Form, InputNumber, Input,
  Switch, Popconfirm, Alert, Row, Col, List, Select, Divider, Tooltip
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, StarOutlined, StarFilled, ReloadOutlined,
  SlidersOutlined, EyeOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';

/** 规则字段说明：与后端 RecommendService.Config 一一对应 */
const FIELDS = [
  { key: 'masteryWeight', label: '掌握度权重', min: 0, max: 1, step: 0.05,
    tip: '分数 = (1-掌握度)×该权重 + 归一化重要性×重要性权重。越大越偏向「先补缺」' },
  { key: 'importanceWeight', label: '知识点重要性权重', min: 0, max: 1, step: 0.05,
    tip: '越大越偏向「先学本册重点」。两权重之和通常为 1' },
  { key: 'weakThreshold', label: '薄弱线', min: 0, max: 1, step: 0.05,
    tip: '掌握度低于该值判为「薄弱」，优先安排学习' },
  { key: 'solidThreshold', label: '掌握线', min: 0, max: 1, step: 0.05,
    tip: '掌握度高于该值判为「已掌握」，转为低强度复习' },
  { key: 'prereqBoost', label: '前置提升加成', min: 0, max: 1, step: 0.05,
    tip: '若某知识点是其他薄弱知识点的前置，优先级分数额外加上该值' },
  { key: 'consolidateRatio', label: '巩固题配额占比', min: 0, max: 0.5, step: 0.05,
    tip: '出题时留给「已掌握」知识点的题目比例，防止遗忘' },
  { key: 'dailyTaskLimit', label: '每日任务条数', min: 1, max: 20, step: 1,
    tip: '学习任务流每次生成多少个知识点的任务' }
];

const pct = (v) => `${Math.round((Number(v) || 0) * 100)}%`;

/**
 * 学习优先级推荐规则配置。
 *
 * <p>这套参数原先是硬编码在出题引擎里的常数，现在落库可调，并且
 * <b>出题引擎与学习任务流共用同一份配置</b>——改这里，两边行为一起变。</p>
 */
export default function RecommendRules() {
  const [rules, setRules] = useState([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();

  // 推荐预览
  const [students, setStudents] = useState([]);
  const [previewStudent, setPreviewStudent] = useState(null);
  const [recs, setRecs] = useState([]);
  const [previewing, setPreviewing] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      setRules(unwrap(await api.listRecommendRules()) || []);
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
      if (list.length && !previewStudent) {
        setPreviewStudent(list[0].id);
      }
    } catch {
      // 教师看督导范围外的学生列表可能为空，忽略
    }
  };

  useEffect(() => { load(); loadStudents(); }, []);

  useEffect(() => {
    if (previewStudent) {
      doPreview(previewStudent);
    }
  }, [previewStudent]);

  const doPreview = async (studentId) => {
    setPreviewing(true);
    try {
      setRecs(unwrap(await api.recommendPreview({ studentId, limit: 8 })) || []);
    } catch (e) {
      setRecs([]);
      message.error(e.message);
    } finally {
      setPreviewing(false);
    }
  };

  const openEditor = (rule) => {
    form.resetFields();
    if (rule) {
      form.setFieldsValue({
        id: rule.id,
        name: rule.name,
        enabled: rule.enabled === 1,
        isDefault: rule.isDefault === 1,
        ...(rule.configMap || {})
      });
    } else {
      form.setFieldsValue({
        name: '自定义规则', enabled: true, isDefault: false,
        masteryWeight: 0.7, importanceWeight: 0.3, weakThreshold: 0.4,
        solidThreshold: 0.7, prereqBoost: 0.15, consolidateRatio: 0.2, dailyTaskLimit: 5
      });
    }
    setOpen(true);
  };

  const save = async () => {
    const v = await form.validateFields();
    setBusy(true);
    try {
      await api.saveRecommendRule({
        id: v.id ?? null,
        orgId: null,
        name: v.name,
        enabled: !!v.enabled,
        isDefault: !!v.isDefault,
        config: FIELDS.reduce((acc, f) => ({ ...acc, [f.key]: v[f.key] }), {})
      });
      message.success('规则已保存');
      setOpen(false);
      await load();
      if (previewStudent) doPreview(previewStudent);
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const markDefault = async (rule) => {
    try {
      await api.saveRecommendRule({
        id: rule.id,
        orgId: rule.orgId,
        name: rule.name,
        enabled: true,
        isDefault: true,
        config: rule.configMap
      });
      message.success('已设为默认规则');
      await load();
    } catch (e) {
      message.error(e.message);
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
          {row.orgId == null && <Tag>平台级</Tag>}
          {row.enabled === 1 ? <Tag color="green">启用</Tag> : <Tag>停用</Tag>}
        </Space>
      )
    },
    {
      title: '参数', render: (_, row) => {
        const c = row.configMap || {};
        return (
          <Space size={10} wrap>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              掌握度 {c.masteryWeight} / 重要性 {c.importanceWeight}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              薄弱线 {pct(c.weakThreshold)} / 掌握线 {pct(c.solidThreshold)}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              前置加成 {c.prereqBoost} / 巩固配额 {pct(c.consolidateRatio)} / 每日 {c.dailyTaskLimit} 条
            </Typography.Text>
          </Space>
        );
      }
    },
    {
      title: '操作', width: 230,
      render: (_, row) => (
        <Space size={4}>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(row)}>编辑</Button>
          {row.isDefault !== 1 && (
            <Tooltip title="设为本机构 / 平台的生效规则">
              <Button size="small" icon={<StarOutlined />} onClick={() => markDefault(row)}>设默认</Button>
            </Tooltip>
          )}
          <Popconfirm title="确认删除该规则？" onConfirm={() => remove(row)}>
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Alert type="info" showIcon icon={<SlidersOutlined />}
        message="这套参数同时决定「学什么」（每日学习任务）和「练什么」（自适应出题）"
        description="分数 = (1 - 掌握度) × 掌握度权重 + 归一化重要性 × 重要性权重；若该知识点是其他薄弱知识点的前置，再加前置提升加成。已掌握的知识点沉到最后，只安排低强度复习。" />

      <Card size="small" title={`推荐规则（${rules.length}）`}
        extra={
          <Space>
            <Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>
            <Button size="small" type="primary" icon={<PlusOutlined />} onClick={() => openEditor(null)}>
              新建规则
            </Button>
          </Space>
        }>
        <Table rowKey="id" size="small" loading={loading} dataSource={rules} columns={columns}
          pagination={{ pageSize: 8, hideOnSinglePage: true }} />
      </Card>

      <Card size="small" title={<Space><EyeOutlined />推荐效果预览</Space>}
        extra={
          <Space size={8}>
            <Select style={{ width: 220 }} value={previewStudent} placeholder="选择学生"
              onChange={setPreviewStudent}
              options={students.map((s) => ({ value: s.id, label: `${s.name}（${s.id}）` }))}
              showSearch optionFilterProp="label" />
            <Button size="small" icon={<ReloadOutlined />} disabled={!previewStudent}
              loading={previewing} onClick={() => doPreview(previewStudent)}>重新计算</Button>
          </Space>
        }>
        <Row gutter={12}>
          <Col span={14}>
            <List size="small" loading={previewing} dataSource={recs}
              locale={{ emptyText: '选择一名学生后展示推荐顺序' }}
              renderItem={(r, i) => (
                <List.Item>
                  <Space direction="vertical" size={2} style={{ width: '100%' }}>
                    <Space size={8}>
                      <Tag color="blue">{i + 1}</Tag>
                      <span style={{ fontWeight: 600 }}>{r.nodeName}</span>
                      <Tag color={r.tier === 'WEAK' ? 'red' : r.tier === 'SOLID' ? 'green' : 'orange'}>
                        {r.tier === 'WEAK' ? '薄弱' : r.tier === 'SOLID' ? '已掌握' : '提升中'}
                      </Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        掌握度 {pct(r.mastery)} · 重要性 {r.weight} · 优先级 {r.score}
                      </Typography.Text>
                    </Space>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {(r.reasons || []).join('；')}
                    </Typography.Text>
                  </Space>
                </List.Item>
              )} />
          </Col>
          <Col span={10}>
            <Alert type="warning" showIcon
              message="调参小提示"
              description={
                <ul style={{ paddingLeft: 18, marginBottom: 0, fontSize: 12 }}>
                  <li>想让系统更积极补弱：提高「掌握度权重」、提高「薄弱线」。</li>
                  <li>想让重点知识点优先：提高「重要性权重」。</li>
                  <li>学生抱怨前置没学好就出后面的题：提高「前置提升加成」。</li>
                  <li>想减少每日负担：降低「每日任务条数」。</li>
                </ul>
              } />
          </Col>
        </Row>
      </Card>

      <Modal title={form.getFieldValue('id') ? '编辑推荐规则' : '新建推荐规则'} open={open}
        onOk={save} confirmLoading={busy} onCancel={() => setOpen(false)} width={720} okText="保存">
        <Form form={form} layout="vertical">
          <Form.Item name="id" hidden><InputNumber /></Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请填写名称' }]}>
                <Input placeholder="如：基础薄弱班专用" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch size="small" />
              </Form.Item>
            </Col>
            <Col span={6}>
              <Form.Item name="isDefault" label="设为默认" valuePropName="checked">
                <Switch size="small" />
              </Form.Item>
            </Col>
          </Row>
          <Divider style={{ margin: '0 0 12px' }} />
          <Row gutter={12}>
            {FIELDS.map((f) => (
              <Col span={8} key={f.key}>
                <Form.Item name={f.key} label={f.label} tooltip={f.tip}
                  rules={[{ required: true, message: '必填' }]}>
                  <InputNumber style={{ width: '100%' }} min={f.min} max={f.max} step={f.step} />
                </Form.Item>
              </Col>
            ))}
          </Row>
        </Form>
      </Modal>
    </Space>
  );
}
