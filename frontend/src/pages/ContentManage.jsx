import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, message, Select, Drawer, Form, Input, Switch,
  Alert, Modal, Checkbox, Divider, Empty, Popconfirm, Collapse, Badge, Tooltip
} from 'antd';
import {
  ThunderboltOutlined, ReloadOutlined, EditOutlined, EyeOutlined, DeleteOutlined,
  CloudUploadOutlined, RollbackOutlined, RobotOutlined, FileTextOutlined, PlusOutlined,
  SafetyCertificateOutlined, SyncOutlined, VideoCameraOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import Markdown from '../components/Markdown.jsx';
import { StoryboardPreview } from '../components/StoryboardReader.jsx';
import RowActions from '../components/RowActions.jsx';

const BY_LABEL = {
  LLM: { text: '大模型生成', color: 'blue' },
  TEACHER: { text: '教师编辑', color: 'green' },
  TEMPLATE: { text: '规则模板', color: 'orange' }
};

/** 内容类型 → 中文标签（前端兜底，后端 typeLabel 优先） */
const TYPE_TEXT = {
  EXPLAIN: '讲解分镜',
  LECTURE: '讲义',
  EXAMPLE: '例题精讲',
  MICRO_LESSON: '微课脚本',
  CARD: '知识卡片',
  LESSON_PLAN: '教案'
};

/**
 * 教学内容管理（教师 / 机构管理员）。
 *
 * <p>两步走：先看「哪些知识点还没有内容」，再按类型批量生成或逐个精修。
 * 未配置大模型 API Key 时后端自动降级为规则模板，页面会明确提示，不会假装成功。</p>
 */
export default function ContentManage() {
  const [graphs, setGraphs] = useState([]);
  const [graphId, setGraphId] = useState(null);
  const [overview, setOverview] = useState(null);
  const [status, setStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);

  // 批量生成表单
  const [batchForm] = Form.useForm();
  // 单个知识点抽屉
  const [node, setNode] = useState(null);
  const [nodeData, setNodeData] = useState(null);
  const [nodeLoading, setNodeLoading] = useState(false);
  // 生成 / 编辑弹窗
  const [genOpen, setGenOpen] = useState(false);
  const [genForm] = Form.useForm();
  const [editOpen, setEditOpen] = useState(false);
  const [editForm] = Form.useForm();

  const loadGraphs = async () => {
    try {
      const list = unwrap(await api.listGraphs()) || [];
      setGraphs(list);
      if (!graphId && list.length) {
        setGraphId(list[0].id);
      }
    } catch (e) {
      message.error(e.message);
    }
  };

  const loadStatus = async () => {
    try {
      setStatus(unwrap(await api.contentStatus()));
    } catch {
      setStatus(null);
    }
  };

  const loadOverview = async (id) => {
    if (!id) return;
    setLoading(true);
    try {
      setOverview(unwrap(await api.contentsByGraph(id)));
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadGraphs(); loadStatus(); }, []);
  useEffect(() => { if (graphId) loadOverview(graphId); }, [graphId]);

  const openNode = async (row) => {
    setNode(row);
    setNodeLoading(true);
    try {
      setNodeData(unwrap(await api.contentsByNode(row.nodeId)));
    } catch (e) {
      message.error(e.message);
    } finally {
      setNodeLoading(false);
    }
  };

  const reloadNode = async () => {
    if (!node) return;
    setNodeData(unwrap(await api.contentsByNode(node.nodeId)));
    loadOverview(graphId);
  };

  const batchGenerate = async () => {
    const v = await batchForm.validateFields();
    setBusy(true);
    try {
      const r = unwrap(await api.batchGenerateContent({
        graphId,
        types: v.types,
        onlyMissing: v.onlyMissing !== false,
        extraInstruction: v.extraInstruction || null
      }));
      message.success(`已生成 ${r.created} 篇内容（跳过 ${r.skipped} 篇）${r.failures?.length ? `，失败 ${r.failures.length} 篇` : ''}`);
      await loadOverview(graphId);
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const generateOne = async () => {
    const v = await genForm.validateFields();
    setBusy(true);
    try {
      const r = unwrap(await api.generateContent({
        nodeId: node.nodeId,
        type: v.type,
        extraInstruction: v.extraInstruction || null,
        replaceExisting: !!v.replaceExisting
      }));
      message.success(r.notice || '已生成，待发布');
      setGenOpen(false);
      genForm.resetFields();
      await reloadNode();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const saveEdit = async () => {
    const v = await editForm.validateFields();
    setBusy(true);
    try {
      await api.updateContent(v.id, { title: v.title, summary: v.summary, body: v.body });
      message.success('已保存（标记为教师编辑）');
      setEditOpen(false);
      await reloadNode();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const setStatusOf = async (item, next) => {
    try {
      await api.setContentStatus(item.id, next);
      message.success(next === 'PUBLISHED' ? '已发布，学生可见' : '已撤回为草稿');
      await reloadNode();
    } catch (e) {
      message.error(e.message);
    }
  };

  const removeContent = async (item) => {
    try {
      await api.deleteContent(item.id);
      message.success('已删除');
      await reloadNode();
    } catch (e) {
      message.error(e.message);
    }
  };

  /** 重新自检：教师改完内容后自用，确认没引入新问题 */
  const recheck = async (item) => {
    try {
      const r = unwrap(await api.recheckContent(item.id));
      if (r.passed) {
        message.success(`自检通过${r.warnCount ? `（${r.warnCount} 条提醒）` : ''}`);
      } else {
        message.warning(`自检发现 ${r.errorCount} 处问题，已标记待复核，暂不能发布`);
      }
      await reloadNode();
    } catch (e) {
      message.error(e.message);
    }
  };

  /**
   * 用讲解 IR 重新渲染正文。
   * 教案场景默认连带重渲染全部教案——IR 是真源，改了讲解教案就该跟着变。
   */
  const rerender = async (item, withPlans = false) => {
    try {
      const r = unwrap(await api.rerenderContent(item.id, { rerenderLessonPlans: withPlans }));
      message.success(`已重新渲染${r.rerenderedPlans ? `，同步更新 ${r.rerenderedPlans} 份教案` : ''}`);
      await reloadNode();
    } catch (e) {
      message.error(e.message);
    }
  };

  const checkBadge = (it) => {
    if (!it.check || it.check.passed === undefined) return null;
    if (it.check.passed) {
      return (
        <Tooltip title={it.check.warnCount ? `${it.check.warnCount} 条提醒` : '自检全部通过'}>
          <Tag color="green" icon={<SafetyCertificateOutlined />}>
            自检通过{it.check.warnCount ? `·${it.check.warnCount}提醒` : ''}
          </Tag>
        </Tooltip>
      );
    }
    return (
      <Tooltip
        title={
          <div>
            {(it.check.issues || []).filter((i) => i.level === 'ERROR').map((i) => (
              <div key={i.code}>· {i.message}</div>
            ))}
          </div>
        }
      >
        <Tag color="red" icon={<SafetyCertificateOutlined />}>
          待复核·{it.check.errorCount}处
        </Tag>
      </Tooltip>
    );
  };

  const columns = [
    {
      title: '知识点', dataIndex: 'nodeName',
      render: (v, row) => (
        <Space direction="vertical" size={0}>
          <Space size={6}>
            <span style={{ fontWeight: 600 }}>{v}</span>
            {row.publishedCount > 0
              ? <Tag color="green">已发布 {row.publishedCount}</Tag>
              : row.contentCount > 0
                ? <Tag color="orange">草稿 {row.contentCount}</Tag>
                : <Tag>无内容</Tag>}
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.nodeDescription}</Typography.Text>
        </Space>
      )
    },
    {
      title: '内容类型', width: 240,
      render: (_, row) => (row.types?.length
        ? <Space size={4} wrap>{row.types.map((t) => (
          <Tag key={t} color={t === 'EXPLAIN' ? 'geekblue' : t === 'LESSON_PLAN' ? 'purple' : undefined}>
            {TYPE_TEXT[t] || t}
          </Tag>
        ))}</Space>
        : <Typography.Text type="secondary">—</Typography.Text>)
    },
    { title: '内容数', dataIndex: 'contentCount', width: 80 },
    {
      title: '最近更新', dataIndex: 'updatedAt', width: 165,
      render: (v) => <Typography.Text type="secondary">{(v || '').replace('T', ' ').slice(0, 19) || '—'}</Typography.Text>
    },
    {
      title: '操作', width: 130,
      render: (_, row) => (
        <RowActions label={row.nodeName} primary={{ label: '编辑内容', onClick: () => openNode(row) }} items={[
          { key: 'generate', label: '生成讲解', icon: <ThunderboltOutlined />,
            onClick: () => { setNode(row); genForm.resetFields(); genForm.setFieldsValue({ type: 'EXPLAIN' }); setGenOpen(true); } }
        ]} />
      )
    }
  ];

  const items = nodeData?.items || [];

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>

      {status && (
        <Alert type="info" showIcon icon={<RobotOutlined />}
          message={status.available ? 'AI 辅助生成已就绪，生成后请审核再发布。' : '当前使用基础模板生成，生成后请审核再发布。'} />
      )}

      <Card size="small" title={<Space><FileTextOutlined />教学内容生成</Space>}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={() => { loadOverview(graphId); loadStatus(); }}>刷新</Button>}>
        <div className="content-graph-picker">
          <div style={{ width: 320, maxWidth: '100%' }}>
            <Typography.Text type="secondary" style={{ fontSize: 12, display: 'block' }}>选择图谱</Typography.Text>
            <Select style={{ width: '100%' }} value={graphId} placeholder="选择一张图谱"
              onChange={setGraphId}
              options={graphs.map((g) => ({
                value: g.id,
                label: `${g.name || `${g.subject}${g.textbook}${g.grade}${g.term}`}（${g.id}）`
              }))} />
          </div>
          {overview && (
            <Typography.Text type="secondary">
              共 {overview.total} 个知识点，其中 <b style={{ color: 'var(--color-success)' }}>{overview.withContent}</b> 个已有内容，
              <b style={{ color: 'var(--color-warning)' }}> {overview.total - overview.withContent}</b> 个还没有
            </Typography.Text>
          )}
        </div>

        <Divider style={{ margin: '14px 0 12px' }} />

        <Form form={batchForm} layout="vertical" className="content-batch-form" initialValues={{ types: ['LECTURE'], onlyMissing: true }}>
          <Form.Item name="types" label="生成类型" rules={[{ required: true, message: '至少选一种' }]}>
            <Checkbox.Group options={(status?.types || []).map((t) => ({ label: t.label, value: t.value }))} />
          </Form.Item>
          <Form.Item name="onlyMissing" label="只补缺失" valuePropName="checked">
            <Switch size="small" />
          </Form.Item>
          <Form.Item name="extraInstruction" label="补充要求" style={{ flex: '1 1 280px', minWidth: 0 }}>
            <Input placeholder="如：面向基础薄弱学生，多用生活例子" allowClear />
          </Form.Item>
          <Form.Item>
            <Button type="primary" icon={<ThunderboltOutlined />} loading={busy} disabled={!graphId}
              onClick={batchGenerate}>
              批量生成
            </Button>
          </Form.Item>
        </Form>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          内容较多时生成需要一些时间；开启「只补缺失」可跳过已有内容。
        </Typography.Text>
      </Card>

      <Card size="small" title="图谱内容覆盖情况">
        <Table rowKey="nodeId" size="small" loading={loading} dataSource={overview?.nodes || []}
          columns={columns} scroll={{ x: 820 }} pagination={{ pageSize: 12, hideOnSinglePage: true }} />
      </Card>

      {/* 单知识点内容管理 */}
      <Drawer title={node ? `「${node.nodeName}」的教学内容` : ''} width={860}
        open={!!node} onClose={() => setNode(null)} destroyOnClose
        extra={<Button type="primary" icon={<PlusOutlined />}
          onClick={() => { genForm.resetFields(); genForm.setFieldsValue({ type: 'EXPLAIN' }); setGenOpen(true); }}>
          生成新内容
        </Button>}>
        {nodeLoading ? (
          <Card loading />
        ) : items.length === 0 ? (
          <Empty description="这个知识点还没有任何内容">
            <Button type="primary" icon={<ThunderboltOutlined />}
              onClick={() => { genForm.resetFields(); genForm.setFieldsValue({ type: 'EXPLAIN' }); setGenOpen(true); }}>
              立即生成
            </Button>
          </Empty>
        ) : (
          <Collapse accordion defaultActiveKey={String(items[0]?.id)}
            items={items.map((it) => ({
              key: String(it.id),
              label: (
                <Space size={8}>
                  <Tag color={BY_LABEL[it.generatedBy]?.color}>{BY_LABEL[it.generatedBy]?.text || it.generatedBy}</Tag>
                  <span style={{ fontWeight: 600 }}>{it.typeLabel || TYPE_TEXT[it.type] || it.type}</span>
                  {it.type === 'EXPLAIN' && <Tag color="geekblue" icon={<VideoCameraOutlined />}>讲解真源</Tag>}
                  {it.type === 'LESSON_PLAN' && <Tag color="purple">引用讲解</Tag>}
                  {it.version > 1 && <Tag>v{it.version}</Tag>}
                  {it.status === 'PUBLISHED'
                    ? <Badge status="success" text="已发布" />
                    : <Badge status="default" text="草稿" />}
                  {checkBadge(it)}
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {(it.updatedAt || '').replace('T', ' ').slice(0, 19)}
                  </Typography.Text>
                </Space>
              ),
              extra: (
                <Space size={4} onClick={(e) => e.stopPropagation()}>
                  {it.status === 'PUBLISHED' ? (
                    <Button size="small" icon={<RollbackOutlined />}
                      onClick={() => setStatusOf(it, 'DRAFT')}>撤回</Button>
                  ) : (
                    <Tooltip title={it.needsReview ? '自检未通过，需先修正并重新自检' : ''}>
                      <Button size="small" type="primary" icon={<CloudUploadOutlined />}
                        onClick={() => setStatusOf(it, 'PUBLISHED')}>发布</Button>
                    </Tooltip>
                  )}
                  <Button size="small" icon={<EditOutlined />}
                    onClick={() => { editForm.setFieldsValue(it); setEditOpen(true); }}>编辑</Button>
                  {it.storyboard && (
                    <Tooltip title="用讲解 IR 重新渲染正文">
                      <Button size="small" icon={<SyncOutlined />}
                        onClick={() => rerender(it, it.type === 'EXPLAIN')}>重渲染</Button>
                    </Tooltip>
                  )}
                  {it.storyboard && (
                    <Tooltip title="重新跑一遍内容自检">
                      <Button size="small" icon={<SafetyCertificateOutlined />}
                        onClick={() => recheck(it)}>自检</Button>
                    </Tooltip>
                  )}
                  <Popconfirm title="确认删除这篇内容？" onConfirm={() => removeContent(it)}>
                    <Button size="small" danger icon={<DeleteOutlined />} />
                  </Popconfirm>
                </Space>
              ),
              children: (
                <Space direction="vertical" size={8} style={{ width: '100%' }}>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    标题：{it.title}　　模型：{it.model || '—'}
                  </Typography.Text>
                  {it.summary && <Alert type="info" showIcon message={it.summary} />}
                  {it.needsReview && (
                    <Alert type="error" showIcon
                      message="内容自检未通过，已阻止发布"
                      description={
                        <ul style={{ margin: '4px 0 0 18px', padding: 0 }}>
                          {(it.check?.issues || []).filter((i) => i.level === 'ERROR').map((i, k) => (
                            <li key={k} style={{ fontSize: 12 }}>{i.message}</li>
                          ))}
                        </ul>
                      }
                      action={<Button size="small" onClick={() => recheck(it)}>重新自检</Button>}
                    />
                  )}
                  {!it.check?.passed && !it.needsReview && !!it.check?.warnCount && (
                    <Alert type="warning" showIcon
                      message={`自检通过，但有 ${it.check.warnCount} 条提醒`}
                      description={
                        <ul style={{ margin: '4px 0 0 18px', padding: 0 }}>
                          {(it.check?.issues || []).filter((i) => i.level === 'WARN').map((i, k) => (
                            <li key={k} style={{ fontSize: 12 }}>{i.message}</li>
                          ))}
                        </ul>
                      }
                    />
                  )}
                  {it.storyboard && (
                    <Card size="small" title={<Space size={6}>
                      <VideoCameraOutlined />讲解分镜 IR
                      <Tag color="geekblue">{it.storyboardSummary?.segmentCount || 0} 段</Tag>
                      {it.storyboardSummary?.totalSeconds > 0 && (
                        <Tag>约 {it.storyboardSummary.totalSeconds} 秒</Tag>
                      )}
                    </Space>}
                      extra={<Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        下面这份分镜是内容的真源，正文由它渲染而来
                      </Typography.Text>}
                      styles={{ body: { maxHeight: 300, overflow: 'auto' } }}>
                      <StoryboardPreview storyboard={it.storyboard} />
                    </Card>
                  )}
                  <div style={{ maxHeight: 460, overflow: 'auto', border: '1px solid var(--color-border)', borderRadius: 6, padding: 14, background: 'var(--color-surface)' }}>
                    <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                      {it.storyboard ? '正文（IR 的 Markdown 渲染结果）' : '正文'}
                    </Typography.Text>
                    <Markdown text={it.body} />
                  </div>
                </Space>
              )
            }))} />
        )}
      </Drawer>

      {/* 生成弹窗 */}
      <Modal title={`为「${node?.nodeName || ''}」生成内容`} open={genOpen} onOk={generateOne}
        confirmLoading={busy} onCancel={() => setGenOpen(false)} okText="开始生成" destroyOnClose>
        <Form form={genForm} layout="vertical" initialValues={{ type: 'EXPLAIN' }}>
          <Form.Item name="type" label="内容类型" rules={[{ required: true }]}>
            <Select options={(status?.types || []).map((t) => ({ value: t.value, label: t.label }))} />
          </Form.Item>
          <Form.Item name="extraInstruction" label="补充要求（可选）">
            <Input.TextArea rows={3} placeholder="如：多讲一个生活中的例子；面向基础薄弱的学生，步骤写细一点" />
          </Form.Item>
          <Form.Item name="replaceExisting" label="覆盖已有同类型内容" valuePropName="checked">
            <Switch size="small" />
          </Form.Item>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={
              <Space direction="vertical" size={2}>
                <span>「讲解分镜」是讲解的唯一真源：正文由它渲染，教案也引用它。</span>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  生成后会先跑一遍自检（结构 / 题目归属 / 答案一致 / 视觉锚点），
                  有问题时标记「待复核」并阻止发布，修正并重新自检通过后才能发布。
                </Typography.Text>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  「教案」会自动引用本知识点已生成的讲解；还没生成讲解时会先自动生成一份。
                </Typography.Text>
              </Space>
            }
          />
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            生成后为草稿状态，需要点「发布」学生才能看到。覆盖生成会把旧版本退回草稿。
          </Typography.Text>
        </Form>
      </Modal>

      {/* 编辑弹窗 */}
      <Modal title="编辑内容" open={editOpen} onOk={saveEdit} confirmLoading={busy}
        onCancel={() => setEditOpen(false)} okText="保存" width={800} destroyOnClose>
        <Form form={editForm} layout="vertical">
          <Form.Item name="id" hidden><Input /></Form.Item>
          <Form.Item name="title" label="标题"><Input /></Form.Item>
          <Form.Item name="summary" label="摘要（学生端列表显示）"><Input /></Form.Item>
          <Form.Item name="body" label="正文（Markdown）" rules={[{ required: true, message: '正文不能为空' }]}>
            <Input.TextArea rows={16} style={{ fontFamily: 'ui-monospace, Consolas, monospace', fontSize: 13 }} />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
