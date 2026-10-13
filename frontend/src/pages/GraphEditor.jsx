import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ReactFlow, Background, Controls, MiniMap, MarkerType,
  applyNodeChanges, applyEdgeChanges, Handle, Position
} from '@xyflow/react';
import {
  Card, Button, Space, Modal, Form, Input, InputNumber, Select, Drawer,
  Popconfirm, message, Typography, Tag, Tooltip, Table, Switch, Progress, Divider, Alert, Radio, Dropdown, Collapse
} from 'antd';
import {
  PlusOutlined, SaveOutlined, ArrowLeftOutlined, DeleteOutlined, TrophyOutlined,
  NodeIndexOutlined, EditOutlined, ApartmentOutlined, ImportOutlined, InfoCircleOutlined, MoreOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const RELATION_META = {
  CONTAINS: { label: '包含', color: 'var(--color-muted)', dashed: false },
  SIMILAR: { label: '相近', color: 'var(--color-primary)', dashed: true },
  PREREQUISITE: { label: '前置', color: 'var(--color-success)', dashed: false }
};

/**
 * 「关联新增」的关联方式。全部站在「新知识点 ↔ 选中知识点」的视角描述，
 * 提交时折算成后端的 (relationType, anchorIsSource)。
 */
const LINK_OPTIONS = [
  { value: 'NONE', label: '不建立关联（独立知识点）' },
  { value: 'PRE', label: '前置 —— 新知识点是「选中知识点」的前置' },
  { value: 'POST', label: '后置 —— 新知识点是「选中知识点」的后置' },
  { value: 'CHILD', label: '包含 —— 新知识点是「选中知识点」的子知识点' },
  { value: 'PARENT', label: '被包含 —— 新知识点是「选中知识点」的父知识点' },
  { value: 'SIMILAR', label: '相近 —— 两者内容相近' }
];

const LINK_TO_REQUEST = {
  NONE: null,
  PRE: { relationType: 'PREREQUISITE', anchorIsSource: false },
  POST: { relationType: 'PREREQUISITE', anchorIsSource: true },
  CHILD: { relationType: 'CONTAINS', anchorIsSource: true },
  PARENT: { relationType: 'CONTAINS', anchorIsSource: false },
  SIMILAR: { relationType: 'SIMILAR', anchorIsSource: true }
};

/** 新节点相对锚点节点的落位方向（画布 y 轴向下） */
const LINK_OFFSET = {
  NONE: { x: 190, y: 120 },
  PRE: { x: 0, y: -150 },
  POST: { x: 0, y: 150 },
  CHILD: { x: 200, y: 110 },
  PARENT: { x: -200, y: -110 },
  SIMILAR: { x: 230, y: 0 }
};

/** 加一点抖动，避免连续新增时节点完全重叠 */
const jitter = (v) => Math.round(v + (Math.random() * 70 - 35));

const IMPORT_SAMPLE = `第3单元 小数除法
  除数是整数的小数除法
  除数是小数的小数除法
    商的近似数
    循环小数
第4单元 可能性
  事件发生的可能性`;

/**
 * 把教研粘贴进来的大纲文本解析成批量导入项。
 *
 * 每行一个知识点，用<b>缩进</b>表达层级（2 空格 / 4 空格 / Tab 都行，按实际缩进宽度排序推断层级）；
 * 行内可用 | 追加描述与权重：`知识点名称|描述|权重`；行首的 "1."、"一、"、"-" 等编号会被忽略。
 */
function parseOutline(text) {
  const raw = [];
  for (const line of String(text || '').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const indent = line.match(/^[ \t]*/)[0].replace(/\t/g, '    ').length;
    const body = line.trim()
      .replace(/^[-*•·]\s*/, '')
      .replace(/^\d+\s*[.、)）]\s*/, '')
      .replace(/^[一二三四五六七八九十]+\s*[、.)）]\s*/, '');
    if (!body) continue;
    const [name, description, weight] = body.split(/[|｜]/).map(s => (s ?? '').trim());
    if (!name) continue;
    raw.push({ name, description: description || undefined, weight: weight ? Number(weight) : undefined, indent });
  }
  const indents = [...new Set(raw.map(r => r.indent))].sort((a, b) => a - b);
  return raw.map(r => ({ ...r, level: indents.indexOf(r.indent) }));
}

function KnowledgeNode({ data, selected }) {
  return (
    <div style={{
      padding: '8px 12px', borderRadius: 8, minWidth: 90, textAlign: 'center',
      background: selected ? 'var(--color-soft)' : 'var(--color-surface)',
      border: `2px solid ${selected ? 'var(--color-primary)' : 'var(--color-border)'}`,
      boxShadow: selected ? '0 0 0 3px var(--color-soft)' : '0 1px 4px rgba(0,0,0,0.1)',
      fontSize: 12
    }}>
      <Handle type="target" position={Position.Top} style={{ background: 'var(--color-primary)' }} />
      <div style={{ fontWeight: 600 }}>{data.label}</div>
      {data.weight != null && (
        <div style={{ fontSize: 10, color: 'var(--color-muted)' }}>权重 {data.weight}</div>
      )}
      <Handle type="source" position={Position.Bottom} style={{ background: 'var(--color-success)' }} />
    </div>
  );
}

const nodeTypes = { knowledge: KnowledgeNode };

export default function GraphEditor() {
  const { id } = useParams();
  const nav = useNavigate();
  const [graph, setGraph] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [edges, setEdges] = useState([]);
  const [loading, setLoading] = useState(true);
  const [nodeDrawer, setNodeDrawer] = useState(null); // { id, ...form }
  const [nodeForm] = Form.useForm();
  const [addOpen, setAddOpen] = useState(false);
  const [addForm] = Form.useForm();
  const [anchorNode, setAnchorNode] = useState(null); // { id, name, posX, posY } | null
  const [selectedNodeId, setSelectedNodeId] = useState(null);
  const [ctxMenu, setCtxMenu] = useState(null); // { x, y, nodeId, name, posX, posY }
  const [connectModal, setConnectModal] = useState(null); // { source, target }
  const [connectForm] = Form.useForm();
  const [rankOpen, setRankOpen] = useState(false);
  const [rankData, setRankData] = useState(null);
  const [rankBias, setRankBias] = useState(false);
  const [infoOpen, setInfoOpen] = useState(false);
  const [infoForm] = Form.useForm();
  const [relEdit, setRelEdit] = useState(null); // { id, from, to, relationType }
  const [relForm] = Form.useForm();
  const [importOpen, setImportOpen] = useState(false);
  const [importForm] = Form.useForm();
  const [importText, setImportText] = useState('');
  const [importParent, setImportParent] = useState(null);
  const [importResult, setImportResult] = useState(null);

  const dirtyRef = useRef(false);
  // 用 ref 保存选中节点，重新加载画布后仍能恢复选中态（方便连续挂载）
  const selectedRef = useRef(null);

  const openRank = async (bias = rankBias) => {
    try {
      const r = await api.pageRank(id, { weightBias: bias, topN: 30 });
      setRankData(unwrap(r));
      setRankOpen(true);
    } catch (e) {
      message.error(e.message);
    }
  };

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.graphDetail(id);
      const d = unwrap(r);
      setGraph(d.graph);
      setNodes((d.nodes || []).map(n => ({
        id: String(n.id),
        type: 'knowledge',
        selected: String(n.id) === selectedRef.current,
        position: { x: Number(n.posX) || 0, y: Number(n.posY) || 0 },
        data: { label: n.name, weight: n.weight, description: n.description }
      })));
      setEdges((d.relations || []).map(rel => {
        const meta = RELATION_META[rel.relationType] || {};
        return {
          id: String(rel.id),
          source: String(rel.fromNodeId),
          target: String(rel.toNodeId),
          label: meta.label,
          data: { relationType: rel.relationType },
          animated: false,
          style: { stroke: meta.color, strokeDasharray: meta.dashed ? '6 4' : undefined },
          markerEnd: { type: MarkerType.ArrowClosed, color: meta.color, width: 15, height: 15 }
        };
      }));
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => { load(); }, [load]);

  const onNodesChange = useCallback((changes) => {
    setNodes(ns => applyNodeChanges(changes, ns));
    if (changes.some(c => c.type === 'position' && c.dragging === false)) {
      dirtyRef.current = true;
    }
  }, []);

  const onEdgesChange = useCallback((changes) => {
    setEdges(es => applyEdgeChanges(changes, es));
  }, []);

  const onSelectionChange = useCallback(({ nodes: sel }) => {
    const next = sel.length === 1 ? sel[0].id : null;
    selectedRef.current = next;
    setSelectedNodeId(prev => (prev === next ? prev : next));
  }, []);

  const onConnect = useCallback((connection) => {
    setConnectModal({ source: connection.source, target: connection.target });
  }, []);

  const saveLayout = async () => {
    const items = nodes.map(n => ({
      nodeId: Number(n.id), posX: Math.round(n.position.x), posY: Math.round(n.position.y)
    }));
    await api.savePositions(id, items);
    dirtyRef.current = false;
    message.success('布局已保存');
  };

  const submitConnect = async (values) => {
    try {
      await api.createRelation(id, {
        fromNodeId: Number(connectModal.source),
        toNodeId: Number(connectModal.target),
        relationType: values.relationType
      });
      message.success('关系已创建');
      setConnectModal(null);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 节点 ----------

  const openNodeById = (nodeId) => {
    const nid = Number(nodeId);
    api.graphDetail(id).then(r => {
      const d = unwrap(r);
      const raw = (d.nodes || []).find(n => n.id === nid);
      if (raw) {
        setNodeDrawer(raw);
        nodeForm.setFieldsValue({
          name: raw.name, description: raw.description, code: raw.code,
          weight: raw.weight, sortOrder: raw.sortOrder
        });
      }
    });
  };

  const saveNode = async () => {
    const values = await nodeForm.validateFields();
    try {
      await api.updateNode(id, nodeDrawer.id, values);
      message.success('节点已保存');
      setNodeDrawer(null);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const deleteNodeById = (nodeId, name) => {
    Modal.confirm({
      title: `删除知识点「${name}」？`,
      content: '将级联删除它的全部关系、题目关联与学生学习状态，不可恢复。',
      okButtonProps: { danger: true },
      onOk: async () => {
        await api.deleteNode(id, nodeId);
        message.success('节点已删除');
        if (selectedRef.current === String(nodeId)) {
          selectedRef.current = null;
          setSelectedNodeId(null);
        }
        setNodeDrawer(null);
        load();
      }
    });
  };

  /** 打开新增弹窗：anchor 为 null 表示自由新增，否则为「关联新增」 */
  const openAdd = (anchor) => {
    setAnchorNode(anchor || null);
    addForm.resetFields();
    addForm.setFieldsValue({ weight: 1, sortOrder: 0, linkMode: anchor ? 'POST' : 'NONE' });
    setAddOpen(true);
  };

  const openAddFromSelection = () => {
    const n = nodes.find(x => x.id === selectedNodeId);
    if (!n) {
      message.info('请先在画布中单击选中一个知识点');
      return;
    }
    openAdd({ id: n.id, name: n.data.label, posX: n.position.x, posY: n.position.y });
  };

  const submitAdd = async (values) => {
    const { linkMode, ...rest } = values;
    const body = { ...rest };
    try {
      if (anchorNode) {
        const off = LINK_OFFSET[linkMode] || LINK_OFFSET.NONE;
        body.posX = jitter(Number(anchorNode.posX || 0) + off.x);
        body.posY = jitter(Number(anchorNode.posY || 0) + off.y);
        const link = LINK_TO_REQUEST[linkMode];
        if (link) {
          body.link = { anchorNodeId: Number(anchorNode.id), ...link };
        }
      } else {
        body.posX = 80 + Math.round(Math.random() * 400);
        body.posY = 80 + Math.round(Math.random() * 300);
      }
      await api.createNode(id, body);
      message.success(anchorNode ? '知识点已添加并建立关联' : '知识点已添加');
      setAddOpen(false);
      addForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const deleteEdge = async (edge) => {
    try {
      await api.deleteRelation(id, Number(edge.id));
      message.success('关系已删除');
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 图谱基本信息 ----------

  const openInfo = () => {
    infoForm.setFieldsValue({ name: graph?.name, description: graph?.description });
    setInfoOpen(true);
  };

  const saveInfo = async () => {
    const values = await infoForm.validateFields();
    try {
      await api.updateGraph(id, {
        name: values.name,
        subject: graph.subject, textbook: graph.textbook,
        grade: graph.grade, term: graph.term,
        description: values.description
      });
      message.success('图谱信息已更新');
      setInfoOpen(false);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 关系编辑 ----------

  const nameOf = (nid) => nodes.find(n => n.id === String(nid))?.data?.label || `#${nid}`;

  const openRelEdit = (edge) => {
    setRelEdit({
      id: Number(edge.id),
      from: Number(edge.source),
      to: Number(edge.target),
      relationType: edge.data?.relationType || 'PREREQUISITE'
    });
    relForm.setFieldsValue({ relationType: edge.data?.relationType || 'PREREQUISITE' });
  };

  const saveRel = async () => {
    const values = await relForm.validateFields();
    try {
      await api.updateRelation(id, relEdit.id, {
        fromNodeId: relEdit.from,
        toNodeId: relEdit.to,
        relationType: values.relationType
      });
      message.success('关系已更新');
      setRelEdit(null);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const removeRel = async () => {
    try {
      await api.deleteRelation(id, relEdit.id);
      message.success('关系已删除');
      setRelEdit(null);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 批量导入知识点 ----------

  const openImport = () => {
    setImportText('');
    setImportResult(null);
    setImportParent(null);
    setImportOpen(true);
  };

  const importItems = parseOutline(importText);

  const submitImport = async () => {
    if (!importItems.length) {
      message.warning('请先粘贴大纲内容');
      return;
    }
    try {
      const r = await api.batchNodes(id, { items: importItems, parentNodeId: importParent });
      const d = unwrap(r) || {};
      setImportResult(d);
      message.success(`已导入 ${d.created} 个知识点、${d.relationCount} 条包含关系`);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  if (loading) return <Card loading style={{ height: '70vh' }} />;

  const selectedName = nodes.find(n => n.id === selectedNodeId)?.data?.label;

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <div className="graph-topbar">
          <div className="graph-heading">
            <Button type="text" aria-label="返回图谱列表" icon={<ArrowLeftOutlined />} onClick={() => nav('/graphs')} />
            <div className="graph-title"><h2>{graph?.name}</h2><span className="row-meta">{graph?.subject} · {graph?.textbook} · {graph?.grade} · {graph?.term}</span></div>
          </div>
          <div className="toolbar-actions">
            <Dropdown trigger={['click']} menu={{ items: [
              { key: 'info', icon: <EditOutlined />, label: '编辑图谱信息' },
              { key: 'import', icon: <ImportOutlined />, label: '批量导入知识点' },
              { key: 'rank', icon: <TrophyOutlined />, label: '核心知识点排序' }
            ], onClick: ({ key }) => {
              if (key === 'info') openInfo();
              else if (key === 'import') openImport();
              else openRank();
            } }}><Button icon={<MoreOutlined />}>更多</Button></Dropdown>
            <Button icon={<PlusOutlined />} onClick={() => openAdd(null)}>添加知识点</Button>
            <Button type="primary" icon={<SaveOutlined />} onClick={saveLayout}>保存布局</Button>
          </div>
        </div>
        <div className="graph-legend">
          <div className="graph-legend-items">{Object.entries(RELATION_META).map(([key, relation]) => (
            <span key={key}><i style={{ display: 'inline-block', width: 16, borderTop: `2px ${relation.dashed ? 'dashed' : 'solid'} ${relation.color}`, marginRight: 5, verticalAlign: 'middle' }} />{relation.label}</span>
          ))}</div>
          {selectedNodeId ? <Button type="link" size="small" icon={<NodeIndexOutlined />} onClick={openAddFromSelection}>关联新增：{selectedName}</Button>
            : <span>单击选择知识点 · 双击编辑</span>}
        </div>
      </Card>

      <div className="graph-canvas">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onSelectionChange={onSelectionChange}
          onConnect={onConnect}
          onNodeDoubleClick={(_, node) => openNodeById(node.id)}
          onNodeContextMenu={(e, node) => {
            e.preventDefault();
            selectedRef.current = node.id;
            setSelectedNodeId(node.id);
            setNodes(nds => nds.map(n => (n.id === node.id ? { ...n, selected: true } : { ...n, selected: false })));
            setCtxMenu({
              x: Math.min(e.clientX, window.innerWidth - 230),
              y: Math.min(e.clientY, window.innerHeight - 210),
              nodeId: node.id,
              name: node.data.label,
              posX: node.position.x,
              posY: node.position.y
            });
          }}
          onPaneClick={() => setCtxMenu(null)}
          onEdgeDoubleClick={(_, edge) => openRelEdit(edge)}
          onEdgeClick={(_, edge) => openRelEdit(edge)}
          edgesFocusable
          fitView
        >
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </div>

      <Collapse ghost size="small" className="graph-guide" items={[{ key: 'guide', label: '操作指南', children: <Typography.Paragraph type="secondary" style={{ marginBottom: 0, fontSize: 12 }}>
        操作说明：拖拽节点调整布局（点"保存布局"持久化）；单击选中节点后点"关联新增"，或直接在节点上点右键，可新增知识点并同时指定与它的关系（前置/后置/包含/被包含/相近）；
        双击节点编辑属性；<b>点击连线可修改关系类型、交换方向或删除</b>；从一个节点底部圆点拖到另一节点顶部圆点创建关系；
        "批量导入"支持把 Excel 里的大纲按缩进直接粘贴成知识点树。
      </Typography.Paragraph> }]} />

      {/* 节点右键菜单 */}
      {ctxMenu && (
        <>
          <div
            style={{ position: 'fixed', inset: 0, zIndex: 1050 }}
            onClick={() => setCtxMenu(null)}
            onContextMenu={(e) => { e.preventDefault(); setCtxMenu(null); }}
          />
          <div style={{
            position: 'fixed', left: ctxMenu.x, top: ctxMenu.y, zIndex: 1100,
            background: 'var(--color-surface)', borderRadius: 8, border: '1px solid var(--color-border)',
            boxShadow: '0 6px 20px rgba(0,0,0,0.16)', padding: 4, minWidth: 200
          }}>
            <div style={{ padding: '6px 10px', fontSize: 12, color: 'var(--color-muted)' }}>{ctxMenu.name}</div>
            <Divider style={{ margin: '4px 0' }} />
            <Button type="text" block style={{ justifyContent: 'flex-start' }} icon={<ApartmentOutlined />}
              onClick={() => {
                openAdd({ id: ctxMenu.nodeId, name: ctxMenu.name, posX: ctxMenu.posX, posY: ctxMenu.posY });
                setCtxMenu(null);
              }}>
              关联新增知识点
            </Button>
            <Button type="text" block style={{ justifyContent: 'flex-start' }} icon={<EditOutlined />}
              onClick={() => { openNodeById(ctxMenu.nodeId); setCtxMenu(null); }}>
              编辑属性
            </Button>
            <Button type="text" block danger style={{ justifyContent: 'flex-start' }} icon={<DeleteOutlined />}
              onClick={() => { const c = ctxMenu; setCtxMenu(null); deleteNodeById(c.nodeId, c.name); }}>
              删除节点
            </Button>
          </div>
        </>
      )}

      {/* 新增节点（自由新增 / 关联新增） */}
      <Modal
        title={anchorNode ? `关联新增知识点 —— 基于「${anchorNode.name}」` : '添加知识点节点'}
        open={addOpen}
        onOk={() => addForm.submit()}
        onCancel={() => setAddOpen(false)}
        forceRender
        okText="添加"
      >
        <Form form={addForm} layout="vertical" onFinish={submitAdd} initialValues={{ weight: 1, sortOrder: 0 }}>
          <Form.Item name="name" label="知识点名称" rules={[{ required: true }]}>
            <Input placeholder="如：小数乘法" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="知识点内容描述（用于语义检索）" />
          </Form.Item>
          <Form.Item name="code" label="编码">
            <Input placeholder="如 MATH-5A-001" />
          </Form.Item>
          <Space>
            <Form.Item name="weight" label="重要性权重">
              <InputNumber min={0} max={10} step={0.1} />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} />
            </Form.Item>
          </Space>

          {anchorNode && (
            <>
              <Divider style={{ margin: '4px 0 12px' }}>与「{anchorNode.name}」的关联</Divider>
              <Form.Item name="linkMode" label="关联关系" rules={[{ required: true }]}>
                <Select options={LINK_OPTIONS} />
              </Form.Item>
              <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
                后置关系反向即为前置，无需重复维护；新节点会自动布局在锚点节点的对应方位（前置在上、后置在下、相近在右），
                添加后可继续基于同一锚点连续新增。
              </Typography.Paragraph>
            </>
          )}
        </Form>
      </Modal>

      {/* 创建关系 */}
      <Modal title="创建关系" open={!!connectModal} onOk={() => connectForm.submit()}
        onCancel={() => setConnectModal(null)} destroyOnHidden>
        <Form form={connectForm} layout="vertical" onFinish={submitConnect} initialValues={{ relationType: 'PREREQUISITE' }}>
          <Typography.Paragraph>
            从节点 #{connectModal?.source} 指向节点 #{connectModal?.target}
          </Typography.Paragraph>
          <Form.Item name="relationType" label="关系类型" rules={[{ required: true }]}>
            <Select options={Object.entries(RELATION_META).map(([k, v]) => ({
              value: k, label: `${v.label}（${k}）`
            }))} />
          </Form.Item>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
            前置：A 是 B 的前置知识（后置为反向查询，无需单独创建）；包含：父知识点包含子知识点；相近：对称关系。
          </Typography.Paragraph>
        </Form>
      </Modal>

      {/* 核心知识点排序（PageRank） */}
      <Drawer title="核心知识点排序（PageRank）" width={560} open={rankOpen}
        onClose={() => setRankOpen(false)}
        extra={
          <Space>
            <span style={{ fontSize: 12 }}>按权重个性化</span>
            <Switch checked={rankBias} onChange={(v) => { setRankBias(v); openRank(v); }} />
          </Space>
        }>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          基于前置依赖关系计算知识点影响力：被越多后续知识点依赖的节点排名越高，代表"牵一发动全身"的枢纽知识点。
          开启"按权重个性化"后会结合节点重要性权重（weight）计算。
        </Typography.Paragraph>
        <Table
          size="small"
          rowKey="nodeId"
          pagination={false}
          dataSource={rankData?.ranking || []}
          columns={[
            {
              title: '排名', width: 60, render: (_, __, i) => (
                <Tag color={i === 0 ? 'gold' : i < 3 ? 'orange' : 'default'}>{i + 1}</Tag>
              )
            },
            { title: '知识点', dataIndex: 'name' },
            { title: '权重', dataIndex: 'weight', width: 70 },
            {
              title: '影响力', dataIndex: 'score', width: 140, render: v => (
                <Progress percent={Math.round((Number(v) || 0) * 100)} size="small" />
              )
            },
            { title: '支撑后续', dataIndex: 'supportCount', width: 90 },
            { title: '依赖前置', dataIndex: 'prereqCount', width: 90 }
          ]}
        />
      </Drawer>

      {/* 节点编辑抽屉 */}
      <Drawer title={`编辑节点：${nodeDrawer?.name ?? ''}`} open={!!nodeDrawer}
        onClose={() => setNodeDrawer(null)} width={420}
        extra={
          <Space>
            <Popconfirm title="删除节点将级联删除其关系，确认？" onConfirm={() => deleteNodeById(nodeDrawer.id, nodeDrawer.name)}>
              <Button danger icon={<DeleteOutlined />}>删除</Button>
            </Popconfirm>
            <Button type="primary" onClick={saveNode}>保存</Button>
          </Space>
        }>
        <Button block icon={<ApartmentOutlined />} style={{ marginBottom: 12 }}
          onClick={() => {
            const a = { id: nodeDrawer.id, name: nodeDrawer.name, posX: nodeDrawer.posX, posY: nodeDrawer.posY };
            setNodeDrawer(null);
            openAdd(a);
          }}>
          基于该知识点关联新增
        </Button>
        <Form form={nodeForm} layout="vertical">
          <Form.Item name="name" label="知识点名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="description" label="描述（用于语义检索）">
            <Input.TextArea rows={4} />
          </Form.Item>
          <Form.Item name="code" label="编码">
            <Input />
          </Form.Item>
          <Form.Item name="weight" label="重要性权重（影响出题优先级）">
            <InputNumber min={0} max={10} step={0.1} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="sortOrder" label="排序">
            <InputNumber min={0} style={{ width: '100%' }} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* 图谱基本信息 */}
      <Modal title="编辑图谱信息" open={infoOpen} onOk={saveInfo}
        onCancel={() => setInfoOpen(false)} destroyOnHidden width={480}>
        <Form form={infoForm} layout="vertical">
          <Form.Item name="name" label="图谱名称" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item label="所属维度">
            <Input disabled value={`${graph?.subject} · ${graph?.textbook} · ${graph?.grade} · ${graph?.term}`} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} />
          </Form.Item>
          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
            学科/教材/年级/学期是图谱的唯一标识，如需改动请用列表页的「复制」。
          </Typography.Paragraph>
        </Form>
      </Modal>

      {/* 关系编辑 */}
      <Modal title="编辑关系" open={!!relEdit} onOk={saveRel}
        onCancel={() => setRelEdit(null)} destroyOnHidden width={480}
        footer={[
          <Popconfirm key="del" title="删除该关系？" onConfirm={removeRel}>
            <Button danger style={{ float: 'left' }}>删除关系</Button>
          </Popconfirm>,
          <Button key="cancel" onClick={() => setRelEdit(null)}>取消</Button>,
          <Button key="ok" type="primary" onClick={saveRel}>保存</Button>
        ]}>
        <div style={{ marginBottom: 12, padding: '10px 12px', background: 'var(--color-background)', borderRadius: 6 }}>
          <Space align="center">
            <Tag color="blue">{nameOf(relEdit?.from)}</Tag>
            <span style={{ color: 'var(--color-muted)' }}>→</span>
            <Tag color="green">{nameOf(relEdit?.to)}</Tag>
            <Button size="small" onClick={() => setRelEdit(r => ({ ...r, from: r.to, to: r.from }))}>
              交换方向
            </Button>
          </Space>
        </div>
        <Form form={relForm} layout="vertical">
          <Form.Item name="relationType" label="关系类型" rules={[{ required: true }]}>
            <Select options={[
              { value: 'PREREQUISITE', label: '前置 —— 左边是右边的前置知识' },
              { value: 'CONTAINS', label: '包含 —— 左边包含右边（父子知识点）' },
              { value: 'SIMILAR', label: '相近 —— 内容相近（对称）' }
            ]} />
          </Form.Item>
        </Form>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
          后置关系是前置的反向，把方向交换过来即可；相近关系交换方向无意义。
        </Typography.Paragraph>
      </Modal>

      {/* 批量导入知识点 */}
      <Modal title="批量导入知识点" open={importOpen}
        onCancel={() => { setImportOpen(false); setImportResult(null); }}
        width={760} destroyOnHidden
        footer={[
          <Button key="sample" onClick={() => setImportText(IMPORT_SAMPLE)}>填入示例</Button>,
          <Button key="cancel" onClick={() => { setImportOpen(false); setImportResult(null); }}>关闭</Button>,
          <Button key="ok" type="primary" onClick={submitImport} disabled={!importItems.length}>
            导入 {importItems.length || ''} 个知识点
          </Button>
        ]}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="按缩进识别层级，自动建立「包含」关系"
          description={<>每行一个知识点，行首缩进（空格或 Tab）表示层级；行内可用竖线追加描述与权重：<code>名称|描述|权重</code>。同名知识点会自动跳过。</>} />
        <Form form={importForm} layout="vertical">
          <Form.Item label="顶层挂到（可选）" style={{ marginBottom: 12 }}>
            <Select allowClear placeholder="不选则作为顶层知识点" style={{ width: 320 }}
              value={importParent} onChange={setImportParent}
              options={nodes.map(n => ({ value: Number(n.id), label: n.data.label }))} />
          </Form.Item>
        </Form>
        <Input.TextArea rows={9} value={importText} onChange={e => setImportText(e.target.value)}
          placeholder={IMPORT_SAMPLE} style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }} />

        {importItems.length > 0 && !importResult && (
          <>
            <Divider style={{ margin: '12px 0 8px' }}>解析预览（共 {importItems.length} 行）</Divider>
            <Table size="small" rowKey={(r, i) => `${r.name}-${i}`} pagination={{ pageSize: 6 }}
              dataSource={importItems}
              columns={[
                { title: '层级', dataIndex: 'level', width: 70, render: v => <Tag>{`L${v}`}</Tag> },
                {
                  title: '知识点', dataIndex: 'name', render: (v, r) => (
                    <span style={{ paddingLeft: r.level * 16 }}>{v}</span>
                  )
                },
                { title: '描述', dataIndex: 'description', ellipsis: true },
                { title: '权重', dataIndex: 'weight', width: 70 }
              ]} />
          </>
        )}

        {importResult && (
          <>
            <Divider style={{ margin: '12px 0 8px' }}>导入结果</Divider>
            <Alert type="success" showIcon style={{ marginBottom: 8 }}
              message={`新增 ${importResult.created} 个知识点，建立 ${importResult.relationCount} 条包含关系`} />
            {importResult.skipped?.length > 0 && (
              <Alert type="warning" showIcon
                message={`跳过 ${importResult.skipped.length} 条`}
                description={
                  <ul style={{ margin: 0, paddingLeft: 18 }}>
                    {importResult.skipped.map((s, i) => <li key={i}>{s.name} —— {s.reason}</li>)}
                  </ul>
                } />
            )}
          </>
        )}
      </Modal>
    </div>
  );
}
