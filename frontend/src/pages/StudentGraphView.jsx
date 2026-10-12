import { useEffect, useMemo, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ReactFlow, Background, Controls, MiniMap, Handle, Position
} from '@xyflow/react';
import {
  Card, Button, Space, Tag, Typography, Statistic, Row, Col, message, Table,
  Drawer, Collapse, Empty, Divider, InputNumber, Popconfirm, Alert, Progress, Modal
} from 'antd';
import {
  ArrowLeftOutlined, FileTextOutlined, WarningOutlined, ReloadOutlined, EditOutlined,
  RocketOutlined, AimOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import AnalysisPanel from '../components/AnalysisPanel.jsx';

const RELATION_LABEL = { CONTAINS: '包含', SIMILAR: '相近', PREREQUISITE: '前置' };

const LEVEL_COLOR = { 已掌握: 'green', 掌握中: 'orange', 薄弱: 'red', 未练习: 'default' };

function masteryColor(m) {
  const v = Number(m) || 0;
  if (v < 0.4) return 'var(--color-danger)';
  if (v < 0.7) return 'var(--color-warning)';
  return 'var(--color-success)';
}

function MasteryNode({ data }) {
  const color = masteryColor(data.mastery);
  return (
    <div style={{
      padding: '8px 12px', borderRadius: 8, minWidth: 90, textAlign: 'center',
      background: 'var(--color-surface)', border: `2px solid ${color}`, boxShadow: '0 1px 4px rgba(0,0,0,0.08)',
      fontSize: 12
    }}>
      <Handle type="target" position={Position.Top} style={{ opacity: 0 }} />
      <div style={{ fontWeight: 600 }}>{data.label}</div>
      <div style={{ color, fontWeight: 700 }}>
        掌握 {((Number(data.mastery) || 0) * 100).toFixed(0)}%
      </div>
      <Handle type="source" position={Position.Bottom} style={{ opacity: 0 }} />
    </div>
  );
}

const nodeTypes = { mastery: MasteryNode };

/** 无依赖的迷你折线图：历次练习得分率趋势 */
function TrendChart({ data = [] }) {
  const w = 760, h = 150, padX = 48, padY = 26;
  if (data.length === 0) {
    return <Empty description="还没有练习记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />;
  }
  const step = data.length > 1 ? (w - padX * 2) / (data.length - 1) : 0;
  const pts = data.map((d, i) => {
    const x = padX + i * step;
    const y = h - padY - (Number(d.accuracy) || 0) * (h - padY * 2);
    return [x, y];
  });
  const line = pts.map(p => p.join(',')).join(' ');
  return (
    <div style={{ overflowX: 'auto' }}>
      <svg width={w} height={h} style={{ minWidth: w }}>
        {[0, 0.25, 0.5, 0.75, 1].map(v => {
          const y = h - padY - v * (h - padY * 2);
          return (
            <g key={v}>
              <line x1={padX} y1={y} x2={w - padX} y2={y} stroke="var(--color-border)" />
              <text x={4} y={y + 4} fontSize={10} fill="var(--color-muted)">{v * 100}%</text>
            </g>
          );
        })}
        <polyline points={line} fill="none" stroke="var(--color-primary)" strokeWidth={2} />
        {pts.map(([x, y], i) => (
          <g key={i}>
            <circle cx={x} cy={y} r={4} fill="var(--color-surface)" stroke="var(--color-primary)" strokeWidth={2} />
            <text x={x} y={y - 10} fontSize={10} fill="var(--color-primary)" textAnchor="middle">
              {Math.round((data[i].accuracy || 0) * 100)}%
            </text>
            <text x={x} y={h - 8} fontSize={10} fill="var(--color-muted)" textAnchor="middle">
              {String(data[i].createdAt || '').slice(5, 16).replace('T', ' ')}
            </text>
          </g>
        ))}
      </svg>
    </div>
  );
}

export default function StudentGraphView({ fixedStudentId }) {
  // 学生登录后从 /my 进入，没有路由参数，直接用会话里绑定的 studentId；
  // 教师从「学生管理」点进来时走 /students/:id
  const params = useParams();
  const id = fixedStudentId ?? params.id;
  const mine = fixedStudentId != null;
  const nav = useNavigate();
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [reportOpen, setReportOpen] = useState(false);
  const [report, setReport] = useState(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [wrongOpen, setWrongOpen] = useState(false);
  const [wrong, setWrong] = useState(null);
  const [wrongLoading, setWrongLoading] = useState(false);
  const [editMastery, setEditMastery] = useState(null);

  const loadGraph = () => {
    setLoading(true);
    api.studentGraph(id)
      .then(r => setData(unwrap(r)))
      .catch(e => message.error(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(loadGraph, [id]);

  const openReport = async () => {
    setReportOpen(true);
    setReportLoading(true);
    try {
      setReport(unwrap(await api.studentReport(id)));
    } catch (e) {
      message.error(e.message);
    } finally {
      setReportLoading(false);
    }
  };

  const openWrong = async () => {
    setWrongOpen(true);
    setWrongLoading(true);
    try {
      setWrong(unwrap(await api.wrongQuestions(id)));
    } catch (e) {
      message.error(e.message);
    } finally {
      setWrongLoading(false);
    }
  };

  const saveMastery = async () => {
    try {
      const r = unwrap(await api.adjustMastery(id, { nodeId: editMastery.nodeId, mastery: editMastery.value }));
      message.success(`「${editMastery.name}」掌握度已调整为 ${(Number(r.masteryAfter) * 100).toFixed(0)}%`);
      setEditMastery(null);
      openReport();
      loadGraph();
    } catch (e) {
      message.error(e.message);
    }
  };

  const nodes = useMemo(() => (data?.nodes || []).map(n => ({
    id: String(n.id),
    type: 'mastery',
    position: { x: n.posX || 0, y: n.posY || 0 },
    data: { label: n.name, mastery: n.mastery }
  })), [data]);

  const edges = useMemo(() => (data?.relations || []).map(rel => ({
    id: String(rel.id),
    source: String(rel.fromNodeId),
    target: String(rel.toNodeId),
    label: RELATION_LABEL[rel.relationType] || rel.relationType,
    style: { stroke: 'var(--color-muted)' }
  })), [data]);

  if (loading) return <Card loading style={{ height: '70vh' }} />;
  if (!data) return <Card>未找到数据</Card>;

  const stats = (data.nodes || []);
  const avg = stats.length ? stats.reduce((s, n) => s + (Number(n.mastery) || 0), 0) / stats.length : 0;
  const weak = stats.filter(n => (Number(n.mastery) || 0) < 0.4).length;
  const solid = stats.filter(n => (Number(n.mastery) || 0) >= 0.7).length;

  const ov = report?.overview;

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          {!mine && <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/students')}>返回</Button>}
          <Typography.Title level={5} style={{ margin: 0 }}>
            {data.student?.name} 的图谱
          </Typography.Title>
          {data.graph && (
            <Tag>{data.graph.name}（{data.graph.subject} · {data.graph.textbook} · {data.graph.grade} · {data.graph.term}）</Tag>
          )}
          <Tag color="red">薄弱（&lt;40%）：{weak}</Tag>
          <Tag color="orange">掌握中（40%-70%）：{stats.length - weak - solid}</Tag>
          <Tag color="green">已掌握（≥70%）：{solid}</Tag>
          {!mine && (
            <>
              <Button icon={<RocketOutlined />}
                onClick={() => nav(`/students/${data.student.id}/tasks`)}>学习任务</Button>
              <Button icon={<AimOutlined />}
                onClick={() => nav(`/students/${data.student.id}/diagnosis`)}>诊断报告</Button>
            </>
          )}
          <Button type="primary" icon={<FileTextOutlined />} onClick={openReport}>学情报告</Button>
          <Button danger icon={<WarningOutlined />} onClick={openWrong}>错题本</Button>
          <Button icon={<ReloadOutlined />} onClick={loadGraph}>刷新</Button>
        </Space>
      </Card>

      <Row gutter={12} style={{ marginBottom: 12 }}>
        <Col span={8}><Card size="small"><Statistic title="平均掌握度" value={(avg * 100).toFixed(1)} suffix="%" /></Card></Col>
        <Col span={16}><Card size="small" title="薄弱知识点（掌握度升序）">
          <Table
            size="small"
            pagination={false}
            scroll={{ y: 140 }}
            dataSource={stats.filter(n => (Number(n.mastery) || 0) < 0.4)
              .sort((a, b) => (Number(a.mastery) || 0) - (Number(b.mastery) || 0))}
            rowKey="id"
            columns={[
              { title: '知识点', dataIndex: 'name' },
              { title: '掌握度', dataIndex: 'mastery', width: 90,
                render: v => <span style={{ color: masteryColor(v), fontWeight: 600 }}>
                  {((Number(v) || 0) * 100).toFixed(0)}%
                </span> },
              { title: '权重', dataIndex: 'weight', width: 70 }
            ]}
          />
        </Card></Col>
      </Row>

      <AnalysisPanel studentId={id} nodes={data.nodes} />

      <div style={{
        height: 'calc(100vh - 420px)', minHeight: 320, background: 'var(--color-surface)',
        borderRadius: 8, border: '1px solid var(--color-border)'
      }}>
        <ReactFlow nodes={nodes} edges={edges} nodeTypes={nodeTypes} fitView
          nodesConnectable={false} elementsSelectable={true}>
          <Background />
          <Controls />
          <MiniMap />
        </ReactFlow>
      </div>

      {/* 学情报告 */}
      <Drawer title={`学情报告 —— ${data.student?.name ?? ''}`} width={960} open={reportOpen}
        onClose={() => setReportOpen(false)}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={openReport}>刷新</Button>}>
        {reportLoading && <Card loading />}
        {!reportLoading && report && (
          <>
            <Row gutter={12}>
              <Col span={4}><Statistic title="平均掌握度" value={(ov.avgMastery * 100).toFixed(1)} suffix="%" /></Col>
              <Col span={4}><Statistic title="加权掌握度" value={(ov.weightedMastery * 100).toFixed(1)} suffix="%" /></Col>
              <Col span={4}><Statistic title="答题正确率" value={(ov.accuracy * 100).toFixed(1)} suffix="%" /></Col>
              <Col span={4}><Statistic title="已掌握 / 总" value={`${ov.mastered}/${ov.nodeTotal}`} styles={{ content: { color: 'var(--color-success)' } }} /></Col>
              <Col span={4}><Statistic title="薄弱" value={ov.weak} styles={{ content: { color: 'var(--color-danger)' } }} /></Col>
              <Col span={4}><Statistic title="错题" value={report.wrongTotal} styles={{ content: { color: 'var(--color-danger)' } }} /></Col>
            </Row>

            <Divider style={{ margin: '16px 0 8px' }}>历次练习得分率</Divider>
            <TrendChart data={report.trend} />

            <Divider style={{ margin: '16px 0 8px' }}>下一步补强建议</Divider>
            {report.advice?.length ? (
              <Space direction="vertical" style={{ width: '100%' }} size={8}>
                {report.advice.map(a => (
                  <Alert key={a.nodeId} type={a.level === '未练习' ? 'info' : 'warning'} showIcon
                    message={<Space>
                      <span>{a.nodeName}</span>
                      <Tag color={LEVEL_COLOR[a.level]}>{a.level}</Tag>
                      <span style={{ color: 'var(--color-muted)', fontSize: 12 }}>
                        掌握度 {(Number(a.mastery) * 100).toFixed(0)}%
                      </span>
                    </Space>}
                    description={a.suggestion} />
                ))}
              </Space>
            ) : <Empty description="暂无建议：知识点都已掌握" image={Empty.PRESENTED_IMAGE_SIMPLE} />}

            <Divider style={{ margin: '16px 0 8px' }}>知识点明细（可人工修正掌握度）</Divider>
            <Table
              size="small"
              rowKey="nodeId"
              dataSource={report.nodeStats}
              pagination={{ pageSize: 12 }}
              columns={[
                { title: '知识点', dataIndex: 'nodeName' },
                { title: '权重', dataIndex: 'weight', width: 60 },
                {
                  title: '掌握度', dataIndex: 'mastery', width: 150,
                  render: v => (
                    <Space>
                      <span style={{ color: masteryColor(v), fontWeight: 600, width: 42, display: 'inline-block' }}>
                        {((Number(v) || 0) * 100).toFixed(0)}%
                      </span>
                      <Progress percent={Math.round((Number(v) || 0) * 100)} size="small"
                        strokeColor={masteryColor(v)} showInfo={false} style={{ width: 60 }} />
                    </Space>
                  )
                },
                {
                  title: '状态', dataIndex: 'level', width: 80,
                  render: l => <Tag color={LEVEL_COLOR[l]}>{l}</Tag>
                },
                { title: '答题', width: 90, render: (_, r) => `${r.correctCount}/${r.answerCount}` },
                ...(mine ? [] : [{
                  title: '操作', width: 150, render: (_, r) => (
                    <Space size={4}>
                      <Button type="link" size="small" icon={<EditOutlined />}
                        onClick={() => setEditMastery({ nodeId: r.nodeId, name: r.nodeName, value: Number(r.mastery) })}>
                        修正
                      </Button>
                      <Popconfirm title="重置该知识点的掌握度与练习状态？"
                        onConfirm={async () => {
                          await api.adjustMastery(id, { nodeId: r.nodeId, mastery: 0 });
                          message.success('已重置');
                          openReport();
                          loadGraph();
                        }}>
                        <Button type="link" size="small" danger>重置</Button>
                      </Popconfirm>
                    </Space>
                  )
                }])
              ]}
            />
          </>
        )}
      </Drawer>

      {/* 错题本 */}
      <Drawer title={`错题本 —— ${data.student?.name ?? ''}`} width={820} open={wrongOpen}
        onClose={() => setWrongOpen(false)}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={openWrong}>刷新</Button>}>
        {wrongLoading && <Card loading />}
        {!wrongLoading && wrong && (
          <>
            <Alert type={wrong.questionTotal ? 'warning' : 'success'} showIcon style={{ marginBottom: 12 }}
              message={wrong.questionTotal
                ? `共 ${wrong.questionTotal} 道错题（累计答错 ${wrong.wrongTotal} 次）`
                : '暂无错题'} />
            {wrong.items?.length > 0 && (
              <Collapse accordion items={wrong.items.map((w, i) => ({
                key: String(w.questionId),
                label: (
                  <Space wrap>
                    <Tag color="red">错 {w.wrongCount} 次</Tag>
                    <Tag>{w.nodeName}</Tag>
                    <span style={{ fontWeight: 600 }}>{w.stem?.slice(0, 40)}{w.stem?.length > 40 ? '…' : ''}</span>
                  </Space>
                ),
                children: (
                  <div style={{ fontSize: 13 }}>
                    <div style={{ fontWeight: 600, marginBottom: 6 }}>{w.stem}</div>
                    {w.options?.length > 0 && (
                      <ol style={{ paddingLeft: 20, margin: '0 0 8px' }}>
                        {w.options.map((o, oi) => (
                          <li key={oi} style={{ color: o === w.answer ? 'var(--color-success)' : undefined }}>{o}</li>
                        ))}
                      </ol>
                    )}
                    <div>正确答案：<Tag color="green">{w.answer}</Tag></div>
                    <div style={{ marginTop: 6 }}>
                      最近一次得分：{Number(w.lastScore)} / {Number(w.fullScore)}
                      （共作答 {w.attemptCount} 次，答错 {w.wrongCount} 次）
                    </div>
                    {w.analysis && (
                      <div style={{ marginTop: 8, color: 'var(--color-muted)' }}>
                        解析：{w.analysis}
                      </div>
                    )}
                  </div>
                )
              }))} />
            )}
          </>
        )}
      </Drawer>

      {/* 人工修正掌握度 */}
      <Modal title={`修正掌握度 —— ${editMastery?.name ?? ''}`} open={!!editMastery}
        onOk={saveMastery} onCancel={() => setEditMastery(null)} destroyOnHidden okText="保存">
        <Space direction="vertical" style={{ width: '100%' }}>
          <Space>
            <span>掌握度：</span>
            <InputNumber min={0} max={1} step={0.05} style={{ width: 140 }}
              value={editMastery?.value}
              onChange={v => setEditMastery(m => ({ ...m, value: v }))} />
            <Tag>{Math.round((Number(editMastery?.value) || 0) * 100)}%</Tag>
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            0-1 之间。&lt;0.4 记为薄弱，≥0.7 记为已掌握；填 0 相当于重置。
            人工修正只改掌握度，不会删除历史答题记录。
          </Typography.Text>
        </Space>
      </Modal>
    </div>
  );
}
