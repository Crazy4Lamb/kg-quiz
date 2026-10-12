import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, InputNumber, Select, Space,
  Popconfirm, message, Tag, Typography, Alert, Divider, Checkbox, Progress, Tooltip
} from 'antd';
import { PlusOutlined, ImportOutlined, BulbOutlined, RobotOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];
const TEXTBOOKS = ['人教版', '北师大版', '苏教版', '沪教版'];

export const QUESTION_TYPES = [
  { value: 'SINGLE_CHOICE', label: '单选题' },
  { value: 'MULTI_CHOICE', label: '多选题' },
  { value: 'TRUE_FALSE', label: '判断题' },
  { value: 'FILL_BLANK', label: '填空题' },
  { value: 'SHORT_ANSWER', label: '简答题' }
];

const TYPE_TAG = {
  SINGLE_CHOICE: 'blue', MULTI_CHOICE: 'geekblue', TRUE_FALSE: 'cyan',
  FILL_BLANK: 'orange', SHORT_ANSWER: 'purple'
};

const CHOICE_TYPES = ['SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE'];

/** 题型中文别名 → 枚举 */
const TYPE_ALIAS = {
  单选: 'SINGLE_CHOICE', 单选题: 'SINGLE_CHOICE', SINGLE_CHOICE: 'SINGLE_CHOICE',
  多选: 'MULTI_CHOICE', 多选题: 'MULTI_CHOICE', MULTI_CHOICE: 'MULTI_CHOICE',
  判断: 'TRUE_FALSE', 判断题: 'TRUE_FALSE', TRUE_FALSE: 'TRUE_FALSE',
  填空: 'FILL_BLANK', 填空题: 'FILL_BLANK', FILL_BLANK: 'FILL_BLANK',
  简答: 'SHORT_ANSWER', 简答题: 'SHORT_ANSWER', SHORT_ANSWER: 'SHORT_ANSWER'
};

const IMPORT_SAMPLE = `单选|3.6÷0.4 的结果是多少？|除数是小数的除法|9;0.9;90;0.09|9|被除数和除数同时扩大 10 倍|5|2
填空|0.5×0.8 = ____|小数乘小数||0.4|先按整数相乘再点小数点|4|2
判断|两个小数相乘，积一定小于其中任何一个因数。|||错|反例：1.2×1.5=1.8|3|3`;

/**
 * 解析批量导入文本。每行一道题，用 | 分列（列顺序固定，末尾列可省略）：
 * 题型 | 题干 | 知识点(多个用 ; 分隔) | 选项(多个用 ; 分隔) | 答案 | 解析 | 分值 | 难度
 */
function parseQuestionLines(text) {
  const rows = [];
  for (const line of String(text || '').split(/\r?\n/)) {
    if (!line.trim()) continue;
    const p = line.split(/[|｜]/).map(s => s.trim());
    const type = TYPE_ALIAS[p[0]] || p[0];
    rows.push({
      questionType: type,
      stem: p[1] || '',
      nodeNames: (p[2] || '').split(/[;；,，、]/).map(s => s.trim()).filter(Boolean),
      options: p[3] ? p[3].split(/[;；]/).map(s => s.trim()).filter(Boolean) : null,
      answer: p[4] || '',
      analysis: p[5] || undefined,
      score: p[6] ? Number(p[6]) : 5,
      difficulty: p[7] ? Number(p[7]) : 3
    });
  }
  return rows;
}

export default function QuestionList() {
  const [rows, setRows] = useState([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [editingId, setEditingId] = useState(null);
  const [form] = Form.useForm();
  const [graphs, setGraphs] = useState([]);
  const [graphNodes, setGraphNodes] = useState([]);
  const [filters, setFilters] = useState({});
  const [impOpen, setImpOpen] = useState(false);
  const [impForm] = Form.useForm();
  const [impText, setImpText] = useState('');
  const [impResult, setImpResult] = useState(null);

  // ---------- 知识点自动识别 ----------
  const [mapOpen, setMapOpen] = useState(false);
  const [mapLoading, setMapLoading] = useState(false);
  const [mapData, setMapData] = useState(null);
  const [mapPicked, setMapPicked] = useState([]);
  const [mapGraphId, setMapGraphId] = useState(null);
  const [mapReplace, setMapReplace] = useState(false);
  const [mapSaving, setMapSaving] = useState(false);
  // 批量：给「还没有映射」的题目一键识别
  const [autoOpen, setAutoOpen] = useState(false);
  const [autoForm] = Form.useForm();
  const [autoResult, setAutoResult] = useState(null);
  const [autoBusy, setAutoBusy] = useState(false);

  const load = async (p = page, f = filters) => {
    setLoading(true);
    try {
      const r = await api.listQuestions({ page: p, size: 10, ...f });
      const d = unwrap(r) || {};
      setRows(d.records || []);
      setTotal(d.total || 0);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    api.listGraphs().then(r => setGraphs(unwrap(r) || [])).catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 编辑时加载题目详情 + 图谱节点
  const openEdit = async (row) => {
    setEditingId(row.id);
    const r = await api.questionDetail(row.id);
    const q = unwrap(r);
    form.setFieldsValue({
      subject: q.subject, textbook: q.textbook, questionType: q.questionType,
      stem: q.stem, options: q.options, answer: q.answer, analysis: q.analysis,
      score: q.score, difficulty: q.difficulty
    });
    if (q.nodes?.length) {
      const g = q.nodes[0];
      form.setFieldsValue({ graphId: g.graphId });
      await loadGraphNodes(g.graphId);
      form.setFieldsValue({ nodeIds: q.nodeIds });
    }
    setOpen(true);
  };

  const loadGraphNodes = async (graphId) => {
    if (!graphId) { setGraphNodes([]); return; }
    const r = await api.graphDetail(graphId);
    setGraphNodes((unwrap(r)?.nodes) || []);
  };

  const submit = async () => {
    const values = await form.validateFields();
    const payload = {
      subject: values.subject, textbook: values.textbook,
      questionType: values.questionType, stem: values.stem,
      options: CHOICE_TYPES.includes(values.questionType) ? values.options : null,
      answer: values.answer, analysis: values.analysis,
      score: values.score, difficulty: values.difficulty,
      nodeIds: values.nodeIds
    };
    try {
      if (editingId) {
        await api.updateQuestion(editingId, payload);
        message.success('题目已更新');
      } else {
        await api.createQuestion(payload);
        message.success('题目已录入');
      }
      setOpen(false);
      setEditingId(null);
      form.resetFields();
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 批量导入 ----------

  const openImport = () => {
    setImpText('');
    setImpResult(null);
    impForm.setFieldsValue({ subject: '数学', textbook: '人教版', graphId: graphs[0]?.id });
    setImpOpen(true);
  };

  const impItems = parseQuestionLines(impText);

  const submitImport = async () => {
    if (!impItems.length) {
      message.warning('请先粘贴题目内容');
      return;
    }
    const values = await impForm.validateFields();
    const payload = {
      graphId: values.graphId,
      items: impItems.map(it => ({
        ...it,
        subject: values.subject,
        textbook: values.textbook,
        options: CHOICE_TYPES.includes(it.questionType) ? it.options : null
      }))
    };
    try {
      const r = await api.batchQuestions(payload);
      const d = unwrap(r) || {};
      if (d.errors?.length) {
        setImpResult(d);
        message.warning(`有 ${d.errors.length} 行有问题，整批未导入`);
        return;
      }
      setImpResult(d);
      message.success(`已导入 ${d.created} 道题`);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 知识点自动识别 ----------

  const openMapping = async (row, graphId) => {
    setMapOpen(true);
    setMapData(null);
    setMapPicked([]);
    const gid = graphId ?? row.nodes?.[0]?.graphId ?? graphs[0]?.id ?? null;
    setMapGraphId(gid);
    setMapReplace(false);
    await runSuggest(row.id, gid);
  };

  const runSuggest = async (questionId, graphId) => {
    setMapLoading(true);
    try {
      const d = unwrap(await api.suggestMapping({ questionId, graphId, topK: 5 }));
      setMapData(d);
      // 默认勾上「当前已关联」+ 置信度达标的建议
      const init = new Set(d.currentNodeIds || []);
      (d.matches || []).forEach((m) => {
        if ((m.confidence ?? 0) >= 0.6) init.add(m.nodeId);
      });
      setMapPicked(Array.from(init));
    } catch (e) {
      message.error(e.message);
      setMapOpen(false);
    } finally {
      setMapLoading(false);
    }
  };

  const applyMapping = async () => {
    if (!mapData) return;
    setMapSaving(true);
    try {
      await api.applyMapping({ questionId: mapData.questionId, nodeIds: mapPicked, replace: mapReplace });
      message.success(`已写入 ${mapPicked.length} 个知识点关联`);
      setMapOpen(false);
      load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setMapSaving(false);
    }
  };

  const runAuto = async (dryRun) => {
    const v = await autoForm.validateFields();
    setAutoBusy(true);
    try {
      const d = unwrap(await api.autoMapping({
        graphId: v.graphId,
        minConfidence: v.minConfidence,
        maxQuestions: v.maxQuestions,
        dryRun
      }));
      setAutoResult({ ...d, dryRun });
      message.success(dryRun
        ? `预演完成：可自动映射 ${d.mapped} 道题（未落库）`
        : `已为 ${d.mapped} 道题写入知识点映射`);
      if (!dryRun) load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setAutoBusy(false);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '题型', dataIndex: 'questionType', width: 90, render: t => {
        const meta = QUESTION_TYPES.find(x => x.value === t);
        return <Tag color={TYPE_TAG[t]}>{meta?.label || t}</Tag>;
      }
    },
    { title: '题干', dataIndex: 'stem', ellipsis: true },
    { title: '科目', dataIndex: 'subject', width: 70 },
    { title: '教材', dataIndex: 'textbook', width: 85 },
    { title: '分值', dataIndex: 'score', width: 65 },
    {
      title: '难度', dataIndex: 'difficulty', width: 100,
      render: v => '★'.repeat(v) + '☆'.repeat(5 - v)
    },
    {
      title: '关联知识点', dataIndex: 'nodes', width: 240, render: (nodes, row) => (
        <Space wrap size={4}>
          {(nodes || []).map(n => <Tag key={n.id} color="processing">{n.name}</Tag>)}
          {(nodes || []).length === 0 && (
            <Tooltip title="还没有映射知识点，用大模型 / 语义识别一下">
              <Button size="small" type="dashed" icon={<BulbOutlined />}
                onClick={() => openMapping(row)}>识别</Button>
            </Tooltip>
          )}
        </Space>
      )
    },
    {
      title: '操作', width: 200, render: (_, row) => (
        <Space>
          <Button type="link" size="small" icon={<BulbOutlined />} onClick={() => openMapping(row)}>
            识别知识点
          </Button>
          <Button type="link" size="small" onClick={() => openEdit(row)}>编辑</Button>
          <Popconfirm title="确认删除该题？" onConfirm={async () => {
            await api.deleteQuestion(row.id);
            message.success('已删除');
            load();
          }}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  const watchType = Form.useWatch('questionType', form);
  const watchGraph = Form.useWatch('graphId', form);

  return (
    <Card>
      <Space style={{ marginBottom: 16 }} wrap>
        <Select allowClear placeholder="科目" style={{ width: 100 }} options={SUBJECTS.map(v => ({ value: v }))}
          onChange={v => { const f = { ...filters, subject: v }; setFilters(f); setPage(1); load(1, f); }} />
        <Select allowClear placeholder="题型" style={{ width: 110 }} options={QUESTION_TYPES}
          onChange={v => { const f = { ...filters, questionType: v }; setFilters(f); setPage(1); load(1, f); }} />
        <Input.Search placeholder="题干关键词" allowClear style={{ width: 220 }}
          onSearch={v => { const f = { ...filters, keyword: v }; setFilters(f); setPage(1); load(1, f); }} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingId(null); setOpen(true); }}>
          录入题目
        </Button>
        <Button icon={<ImportOutlined />} onClick={openImport}>批量导入</Button>
        <Tooltip title="对还没有映射知识点的题目批量识别并写入">
          <Button icon={<RobotOutlined />} onClick={() => {
            setAutoResult(null);
            autoForm.setFieldsValue({
              graphId: graphs[0]?.id, maxQuestions: 50
            });
            setAutoOpen(true);
          }}>一键映射知识点</Button>
        </Tooltip>
      </Space>

      <Table rowKey="id" loading={loading} dataSource={rows} columns={columns}
        pagination={{ current: page, pageSize: 10, total, onChange: p => { setPage(p); load(p); } }} />

      <Modal title={editingId ? '编辑题目' : '录入题目'} open={open} onOk={submit}
        onCancel={() => { setOpen(false); setEditingId(null); form.resetFields(); }}
        width={720} destroyOnClose>
        <Form form={form} layout="vertical"
          initialValues={{ subject: '数学', textbook: '人教版', questionType: 'SINGLE_CHOICE', score: 5, difficulty: 3, options: ['', '', '', ''] }}>
          <Space wrap>
            <Form.Item name="subject" label="科目" rules={[{ required: true }]}>
              <Select style={{ width: 110 }} options={SUBJECTS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="textbook" label="教材" rules={[{ required: true }]}>
              <Select style={{ width: 110 }} options={TEXTBOOKS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="questionType" label="题型" rules={[{ required: true }]}>
              <Select style={{ width: 110 }} options={QUESTION_TYPES} />
            </Form.Item>
            <Form.Item name="score" label="分值" rules={[{ required: true }]}>
              <InputNumber min={0.5} max={100} step={0.5} />
            </Form.Item>
            <Form.Item name="difficulty" label="难度（1-5）" rules={[{ required: true }]}>
              <InputNumber min={1} max={5} />
            </Form.Item>
          </Space>

          <Form.Item name="stem" label="题干" rules={[{ required: true }]}>
            <Input.TextArea rows={3} />
          </Form.Item>

          {CHOICE_TYPES.includes(watchType) && (
            <Form.Item label="选项（TRUE_FALSE 可保持默认 对/错）">
              <Form.List name="options">
                {(fields, { add, remove }) => (
                  <>
                    {fields.map(field => (
                      <Space key={field.key} style={{ display: 'flex', marginBottom: 4 }}>
                        <Form.Item name={field.name} noStyle>
                          <Input placeholder={`选项 ${field.name + 1}`} style={{ width: 400 }} />
                        </Form.Item>
                        {fields.length > 2 && (
                          <Button type="link" danger size="small" onClick={() => remove(field.name)}>删除</Button>
                        )}
                      </Space>
                    ))}
                    {watchType !== 'TRUE_FALSE' && (
                      <Button type="dashed" size="small" onClick={() => add('')}
                        style={{ marginTop: 4 }}>添加选项</Button>
                    )}
                  </>
                )}
              </Form.List>
            </Form.Item>
          )}

          <Form.Item name="answer" label="答案" rules={[{ required: true }]}
            extra={watchType === 'MULTI_CHOICE' ? '多个答案用逗号分隔，如：选项1,选项3' :
              CHOICE_TYPES.includes(watchType) ? '填写选项的完整文本，如：选项2 或 对' : null}>
            <Input.TextArea rows={2} />
          </Form.Item>

          <Form.Item name="analysis" label="解析">
            <Input.TextArea rows={2} />
          </Form.Item>

          <Form.Item name="graphId" label="所属图谱（用于选择关联知识点）" rules={[{ required: true }]}>
            <Select placeholder="选择图谱" options={graphs.map(g => ({
              value: g.id, label: `${g.name}（${g.grade}${g.term}）`
            }))} onChange={loadGraphNodes} />
          </Form.Item>

          <Form.Item name="nodeIds" label="关联知识点（多选）" rules={[{ required: true, message: '至少关联一个知识点' }]}>
            <Select mode="multiple" placeholder="选择该题考察的知识点"
              options={graphNodes.map(n => ({
                value: n.id, label: `${n.name}（权重 ${n.weight}）`
              }))} />
          </Form.Item>
          {watchGraph && graphNodes.length === 0 && (
            <Typography.Text type="warning">该图谱暂无节点，请先在图谱管理中添加</Typography.Text>
          )}
        </Form>
      </Modal>

      <Modal title="批量导入题目" open={impOpen} width={860} destroyOnHidden
        onCancel={() => { setImpOpen(false); setImpResult(null); }}
        footer={[
          <Button key="sample" onClick={() => setImpText(IMPORT_SAMPLE)}>填入示例</Button>,
          <Button key="close" onClick={() => { setImpOpen(false); setImpResult(null); }}>关闭</Button>,
          <Button key="ok" type="primary" onClick={submitImport} disabled={!impItems.length}>
            导入 {impItems.length || ''} 道题
          </Button>
        ]}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="从 Excel 复制粘贴即可，每行一道题，用 | 分列"
          description={<>列顺序：<code>题型 | 题干 | 知识点 | 选项 | 答案 | 解析 | 分值 | 难度</code>。
            题型可写「单选/多选/判断/填空/简答」；知识点与选项多个用 <code>;</code> 分隔；分值默认 5、难度默认 3。
            知识点<b>可以留空</b>——导完之后点「一键映射知识点」，系统会用语义检索 + 大模型自动识别；
            填了名称的话会按<b>名称</b>在该图谱内匹配，匹配不到会报错并整批不导入。</>} />

        <Form form={impForm} layout="vertical">
          <Space wrap>
            <Form.Item name="subject" label="科目" rules={[{ required: true }]} style={{ marginBottom: 12 }}>
              <Select style={{ width: 120 }} options={SUBJECTS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="textbook" label="教材" rules={[{ required: true }]} style={{ marginBottom: 12 }}>
              <Select style={{ width: 120 }} options={TEXTBOOKS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="graphId" label="知识点所在图谱" rules={[{ required: true }]}
              style={{ marginBottom: 12 }}>
              <Select style={{ width: 300 }} placeholder="用于按名称匹配知识点"
                options={graphs.map(g => ({ value: g.id, label: `${g.name}（${g.grade}${g.term}）` }))} />
            </Form.Item>
          </Space>
        </Form>

        <Input.TextArea rows={9} value={impText} onChange={e => setImpText(e.target.value)}
          placeholder={IMPORT_SAMPLE} style={{ fontFamily: 'Consolas, monospace', fontSize: 12 }} />

        {impItems.length > 0 && !impResult?.errors?.length && (
          <>
            <Divider style={{ margin: '12px 0 8px' }}>解析预览（共 {impItems.length} 行）</Divider>
            <Table size="small" rowKey={(r, i) => `${r.stem}-${i}`} pagination={{ pageSize: 5 }}
              dataSource={impItems}
              columns={[
                {
                  title: '题型', dataIndex: 'questionType', width: 100,
                  render: ts => <Tag color={TYPE_TAG[ts]}>{QUESTION_TYPES.find(x => x.value === ts)?.label || ts}</Tag>
                },
                { title: '题干', dataIndex: 'stem', ellipsis: true },
                {
                  title: '知识点', dataIndex: 'nodeNames', width: 180,
                  render: ns => <Space wrap size={2}>{(ns || []).map(n => <Tag key={n}>{n}</Tag>)}</Space>
                },
                { title: '分值', dataIndex: 'score', width: 60 },
                { title: '难度', dataIndex: 'difficulty', width: 60 }
              ]} />
          </>
        )}

        {impResult?.errors?.length > 0 && (
          <>
            <Divider style={{ margin: '12px 0 8px' }}>导入失败（整批未落库）</Divider>
            <Alert type="error" showIcon message={`第 ${impResult.errors.length} 行有问题，请修正后重试`}
              description={
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {impResult.errors.map((e, i) => (
                    <li key={i}>第 {e.row} 行「{e.stem}」：{e.reasons.join('；')}</li>
                  ))}
                </ul>
              } />
          </>
        )}

        {impResult && !impResult.errors?.length && (
          <>
            <Divider style={{ margin: '12px 0 8px' }}>导入结果</Divider>
            <Alert type="success" showIcon message={`成功导入 ${impResult.created} 道题`}
              description={impResult.unmappedCount > 0 && (
                <Space direction="vertical" size={6}>
                  <span>其中 <b>{impResult.unmappedCount}</b> 道题还没有关联知识点。</span>
                  <Button size="small" type="primary" ghost icon={<RobotOutlined />}
                    onClick={() => {
                      setImpOpen(false);
                      setAutoResult(null);
                      autoForm.setFieldsValue({
                        graphId: impForm.getFieldValue('graphId') ?? graphs[0]?.id,
                        maxQuestions: 50
                      });
                      setAutoOpen(true);
                    }}>
                    去一键映射知识点
                  </Button>
                </Space>
              )} />
          </>
        )}
      </Modal>

      {/* 单题：知识点自动识别 */}
      <Modal title="知识点识别" open={mapOpen} width={860}
        onCancel={() => setMapOpen(false)} destroyOnClose
        footer={[
          <Button key="re" onClick={() => runSuggest(mapData?.questionId, mapGraphId)} loading={mapLoading}>
            重新识别
          </Button>,
          <Button key="cancel" onClick={() => setMapOpen(false)}>取消</Button>,
          <Button key="ok" type="primary" loading={mapSaving} onClick={applyMapping}
            disabled={!mapData || mapPicked.length === 0}>
            写入 {mapPicked.length || 0} 个关联
          </Button>
        ]}>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Space wrap>
            <span>限定图谱：</span>
            <Select style={{ width: 300 }} value={mapGraphId} placeholder="不限定（全机构检索）" allowClear
              onChange={(v) => { setMapGraphId(v); if (mapData) runSuggest(mapData.questionId, v); }}
              options={graphs.map(g => ({ value: g.id, label: `${g.name}（${g.grade}${g.term}）` }))} />
            <Checkbox checked={mapReplace} onChange={(e) => setMapReplace(e.target.checked)}>
              覆盖原有关联
            </Checkbox>
          </Space>

          {mapLoading && <Progress percent={100} status="active" showInfo={false} />}

          {mapData && (
            <>
              <Alert type={mapData.llmAvailable ? 'success' : 'warning'} showIcon
                message={`识别模式：${mapData.mode === 'LLM' ? '大模型判定' : '相似度阈值'}${mapData.semanticAvailable ? '（语义检索可用）' : ''}`}
                description={mapData.notice} />
              <div>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>题干</Typography.Text>
                <Typography.Paragraph style={{ marginBottom: 6 }}>{mapData.stem}</Typography.Paragraph>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  当前已关联：{(mapData.currentNodeIds || []).join('、') || '（无）'}
                </Typography.Text>
              </div>
              <Divider style={{ margin: '4px 0' }}>建议关联的知识点（勾选后写入）</Divider>
              <Table size="small" rowKey="nodeId" pagination={false}
                dataSource={mapData.matches?.length ? mapData.matches : []}
                locale={{ emptyText: '没有达到阈值的候选知识点，可换一张图谱或先重建向量索引' }}
                rowSelection={{
                  selectedRowKeys: mapPicked,
                  onChange: (keys) => setMapPicked(keys)
                }}
                columns={[
                  { title: '知识点', dataIndex: 'nodeName' },
                  {
                    title: '置信度', dataIndex: 'confidence', width: 150,
                    render: (v, r) => (
                      <Space size={6}>
                        <Progress size="small" style={{ width: 70, marginBottom: 0 }}
                          percent={Math.round((r.source === 'LLM' ? v : r.score ?? v) * 100)} />
                        <Typography.Text style={{ fontSize: 12 }}>
                          {Math.round((r.source === 'LLM' ? v : r.score ?? v) * 100)}%
                        </Typography.Text>
                      </Space>
                    )
                  },
                  {
                    title: '来源', dataIndex: 'source', width: 80,
                    render: (s) => <Tag color={s === 'LLM' ? 'blue' : s === 'VECTOR' ? 'geekblue' : 'default'}>{s}</Tag>
                  },
                  { title: '依据', dataIndex: 'reason', ellipsis: true }
                ]} />

              <Divider style={{ margin: '8px 0 4px' }}>候选池（召回结果）</Divider>
              <Space wrap size={4}>
                {(mapData.candidates || []).map(c => (
                  <Tag key={c.nodeId} color={mapPicked.includes(c.nodeId) ? 'blue' : 'default'}
                    style={{ cursor: 'pointer' }}
                    onClick={() => setMapPicked(prev => prev.includes(c.nodeId)
                      ? prev.filter(x => x !== c.nodeId)
                      : [...prev, c.nodeId])}>
                    {c.nodeName} · {Math.round(c.score * 100)}%
                  </Tag>
                ))}
              </Space>
            </>
          )}
        </Space>
      </Modal>

      {/* 批量：一键映射 */}
      <Modal title={<Space><ThunderboltOutlined />一键映射知识点</Space>} open={autoOpen} width={780}
        onCancel={() => setAutoOpen(false)} destroyOnClose
        footer={[
          <Button key="close" onClick={() => setAutoOpen(false)}>关闭</Button>,
          <Button key="dry" onClick={() => runAuto(true)} loading={autoBusy}>先预演（不落库）</Button>,
          <Button key="ok" type="primary" onClick={() => runAuto(false)} loading={autoBusy}>
            识别并写入
          </Button>
        ]}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="只处理「当前还没有任何知识点映射」的题目"
          description="建议先预演看一遍效果，确认阈值合适再真正写入。置信度低于阈值的题目会被跳过，留待人工处理；阈值留空则按识别模式自动取（大模型 0.6 / 纯向量 0.45）。" />
        <Form form={autoForm} layout="inline" initialValues={{ maxQuestions: 50 }}>
          <Form.Item name="graphId" label="映射到图谱" rules={[{ required: true, message: '请选择图谱' }]}>
            <Select style={{ width: 260 }} options={graphs.map(g => ({
              value: g.id, label: `${g.name}（${g.grade}${g.term}）`
            }))} />
          </Form.Item>
          <Form.Item name="minConfidence" label="置信度阈值"
            tooltip="大模型模式给的是模型的确定程度；纯向量模式给的是相似度，短中文题干通常在 0.35-0.5 之间">
            <InputNumber min={0} max={1} step={0.05} placeholder="自动" style={{ width: 90 }} />
          </Form.Item>
          <Form.Item name="maxQuestions" label="最多处理题数">
            <InputNumber min={1} max={500} />
          </Form.Item>
        </Form>
        {autoResult && (
          <>
            <Divider style={{ margin: '14px 0 8px' }}>
              {autoResult.dryRun ? '预演结果（未落库）' : '执行结果'}
            </Divider>
            <Alert type={autoResult.mapped > 0 ? 'success' : 'warning'} showIcon
              message={`扫描 ${autoResult.scanned} 题，可映射 ${autoResult.mapped} 题（跳过已有映射 ${autoResult.skippedMapped} 题）`}
              description={`识别模式：${autoResult.mode === 'LLM' ? '大模型判定' : '相似度阈值'}；阈值 ${autoResult.threshold}`} />
            {autoResult.items?.length > 0 && (
              <Table style={{ marginTop: 10 }} size="small" rowKey="questionId"
                dataSource={autoResult.items} pagination={{ pageSize: 5 }}
                columns={[
                  { title: '题干', dataIndex: 'stem', ellipsis: true },
                  {
                    title: '识别到的知识点', dataIndex: 'nodeIds',
                    render: (ids, row) => (
                      <Space wrap size={2}>
                        {ids.map(id => {
                          const hit = (row.matches || []).find(m => m.nodeId === id);
                          return <Tag key={id} color="processing">{hit?.nodeName || id}</Tag>;
                        })}
                      </Space>
                    )
                  }
                ]} />
            )}
          </>
        )}
      </Modal>
    </Card>
  );
}
