import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Card, Table, Button, Space, Tag, Typography, message, Empty, Row, Col, Statistic,
  Progress, Alert, List, Divider, Result
} from 'antd';
import { AimOutlined, ReloadOutlined, PlayCircleOutlined, CheckCircleOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const masteryColor = (v) => (v >= 0.7 ? 'var(--color-success)' : v >= 0.4 ? 'var(--color-warning)' : 'var(--color-danger)');
const pct = (v) => `${Math.round((Number(v) || 0) * 1000) / 10}%`;
const LEVEL = {
  WEAK: { label: '待补强', color: 'red' },
  MID: { label: '提升中', color: 'orange' },
  SOLID: { label: '已掌握', color: 'green' }
};

/**
 * 诊断报告。
 *
 * <p>学生看自己（走 /my/report），教师 / 管理员看指定学生（走 /{studentId}/report）。
 * 诊断是「覆盖式初始化掌握度」的唯一入口，所以这份报告就是学生的起点快照。</p>
 */
export default function DiagnosisReport() {
  const nav = useNavigate();
  const { id } = useParams();
  const studentId = id ? Number(id) : null;
  const isSelf = !studentId;

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const r = isSelf ? await api.myDiagnosisReport() : await api.diagnosisReport(studentId);
      setData(unwrap(r));
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [studentId]);

  const startDiagnosis = async () => {
    setBusy(true);
    try {
      const r = unwrap(await api.startMyDiagnosis({ perNode: 1, maxQuestions: 20 }));
      message.success(`已生成诊断卷：${r.questionCount} 题`);
      nav(`/exams/take/${r.examId}`);
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return <Card size="small" loading />;
  }
  if (!data) {
    return <Card size="small"><Empty description="没有拿到诊断数据" /></Card>;
  }

  // 还没做诊断
  if (!data.hasDiagnosis) {
    return (
      <Card size="small" title={<Space><AimOutlined />入学诊断</Space>}>
        <Result status="info" title="还没有做过诊断"
          subTitle={data.message || '先完成一次入学诊断，系统才能给出学习起点'}
          extra={isSelf && (
            <Button type="primary" icon={<PlayCircleOutlined />} loading={busy} onClick={startDiagnosis}>
              开始入学诊断
            </Button>
          )} />
      </Card>
    );
  }

  // 有诊断卷但还没交
  if (!data.completed) {
    return (
      <Card size="small" title={<Space><AimOutlined />入学诊断</Space>}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>}>
        <Alert type="warning" showIcon
          message={`诊断卷已就绪（共 ${data.questionCount} 题），还没有交卷`}
          description="交卷后系统才能按真实水平初始化掌握度，并据此安排学习任务。"
          action={isSelf && (
            <Button size="small" type="primary" loading={busy} onClick={startDiagnosis}>去做诊断</Button>
          )} />
      </Card>
    );
  }

  const items = data.items || [];
  const weak = data.weakNodes || [];
  const solid = data.solidNodes || [];
  const avgRate = data.avgRate || 0;
  const rate = data.fullScore ? (data.totalScore / data.fullScore) : 0;

  const columns = [
    {
      title: '知识点', dataIndex: 'nodeName',
      render: (v, row) => (
        <Space size={6}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          <Tag color={LEVEL[row.level]?.color} style={{ marginRight: 0 }}>
            {LEVEL[row.level]?.label || row.level}
          </Tag>
          {row.weight != null && Number(row.weight) >= 1.5 && <Tag color="blue">重点</Tag>}
        </Space>
      )
    },
    {
      title: '诊断正确率', dataIndex: 'rate', width: 210,
      sorter: (a, b) => a.rate - b.rate,
      render: (v) => (
        <Space>
          <Progress size="small" style={{ width: 90, marginBottom: 0 }}
            percent={Math.round(v * 100)} strokeColor={masteryColor(v)} />
          <Typography.Text style={{ width: 48 }}>{pct(v)}</Typography.Text>
        </Space>
      )
    },
    {
      title: '答对 / 题数', width: 110,
      render: (_, row) => <span>{row.correctCount} / {row.questionCount}</span>
    },
    {
      title: '掌握度起点', dataIndex: 'mastery', width: 130,
      render: (v) => (
        <Typography.Text style={{ color: masteryColor(Number(v) || 0), fontWeight: 600 }}>
          {pct(v)}
        </Typography.Text>
      )
    }
  ];

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small" title={<Space><AimOutlined />诊断报告 · {data.name}</Space>}
        extra={<Button size="small" icon={<ReloadOutlined />} onClick={load}>刷新</Button>}>
        <Row gutter={16}>
          <Col span={6}><Statistic title="诊断卷得分" value={data.totalScore ?? 0} suffix={`/ ${data.fullScore ?? 0}`} /></Col>
          <Col span={6}><Statistic title="整卷正确率" value={pct(rate)} valueStyle={{ color: masteryColor(rate) }} /></Col>
          <Col span={6}>
            <Statistic title="覆盖知识点" value={items.length}
              suffix={data.uncoveredNodes ? `（${data.uncoveredNodes} 个无题目未覆盖）` : '个'} />
          </Col>
          <Col span={6}>
            <Statistic title="交卷时间" value={(data.submittedAt || '').replace('T', ' ').slice(0, 16)}
              valueStyle={{ fontSize: 16 }} />
          </Col>
        </Row>
        <Alert style={{ marginTop: 12 }} type="info" showIcon
          message="掌握度起点已按诊断结果直接写入（不是 EMA 增量）"
          description={`低于 ${pct(data.thresholds?.weak ?? 0.4)} 判为待补强，高于 ${pct(data.thresholds?.solid ?? 0.7)} 判为已掌握。之后每次练习走 EMA 增量更新。`} />
      </Card>

      <Row gutter={12}>
        <Col span={12}>
          <Card size="small" title={<Space><Tag color="red">待补强</Tag>{weak.length} 个知识点</Space>}>
            {weak.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有特别薄弱的知识点" />
              : (
                <List size="small" dataSource={weak}
                  renderItem={(r) => (
                    <List.Item extra={
                      isSelf && (
                        <Space size={4}>
                          <Button size="small" type="link" onClick={() => nav(`/my/learn/${r.nodeId}`)}>去学</Button>
                          <Button size="small" type="link" onClick={async () => {
                            try {
                              const ex = unwrap(await api.practiceNow({ nodeId: r.nodeId, count: 5 }));
                              nav(`/exams/take/${ex.examId}`);
                            } catch (e) { message.error(e.message); }
                          }}>去练</Button>
                        </Space>
                      )
                    }>
                      <Space size={6}>
                        <span>{r.nodeName}</span>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{pct(r.rate)}</Typography.Text>
                      </Space>
                    </List.Item>
                  )} />
              )}
          </Card>
        </Col>
        <Col span={12}>
          <Card size="small" title={<Space><Tag color="green">已掌握</Tag>{solid.length} 个知识点</Space>}>
            {solid.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂时还没有已掌握的知识点" />
              : (
                <List size="small" dataSource={solid}
                  renderItem={(r) => (
                    <List.Item>
                      <Space size={6}>
                        <CheckCircleOutlined style={{ color: 'var(--color-success)' }} />
                        <span>{r.nodeName}</span>
                        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{pct(r.rate)}</Typography.Text>
                      </Space>
                    </List.Item>
                  )} />
              )}
          </Card>
        </Col>
      </Row>

      <Card size="small" title="逐知识点诊断明细">
        <Table rowKey="nodeId" size="small" dataSource={items} columns={columns}
          pagination={{ pageSize: 12, hideOnSinglePage: true }} />
      </Card>

      {(data.recommendations || []).length > 0 && (
        <>
          <Divider orientation="left" plain style={{ margin: '4px 0' }}>按这次诊断结果，建议从这里开始学</Divider>
          <Card size="small">
            <List size="small" dataSource={(data.recommendations || []).slice(0, 5)}
              renderItem={(r, i) => (
                <List.Item extra={isSelf && (
                  <Button size="small" type="link" onClick={() => nav(`/my/learn/${r.nodeId}`)}>开始学习</Button>
                )}>
                  <Space direction="vertical" size={2}>
                    <Space size={8}>
                      <Tag color="blue">{i + 1}</Tag>
                      <span style={{ fontWeight: 600 }}>{r.nodeName}</span>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        掌握度 {pct(r.mastery)} · 优先级 {r.score}
                      </Typography.Text>
                    </Space>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {(r.reasons || []).join('；')}
                    </Typography.Text>
                  </Space>
                </List.Item>
              )} />
          </Card>
        </>
      )}
    </Space>
  );
}
