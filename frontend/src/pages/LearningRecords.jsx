import { useEffect, useRef, useState } from 'react';
import {
  Card, Row, Col, Space, Tag, Typography, Select, Empty, List, Drawer, Statistic,
  Progress, Timeline, Alert, Segmented, message, Spin, Divider
} from 'antd';
import {
  LineChartOutlined, ReadOutlined, EditOutlined, AimOutlined, ReloadOutlined,
  CheckCircleOutlined, StarOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

const pct = (v) => (v == null ? '—' : `${Math.round((Number(v) || 0) * 100)}%`);
const fmtDur = (sec) => {
  const s = Number(sec) || 0;
  if (s < 60) return `${s}秒`;
  const m = Math.floor(s / 60);
  return m >= 60 ? `${Math.floor(m / 60)}时${m % 60}分` : `${m}分${s % 60}秒`;
};
const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '');

const ACTION_META = {
  VIEW: { label: '看讲解', color: 'blue', icon: <ReadOutlined /> },
  PRACTICE: { label: '做题', color: 'green', icon: <EditOutlined /> },
  DIAGNOSIS: { label: '诊断', color: 'red', icon: <AimOutlined /> },
  REVIEW: { label: '复习', color: 'gold', icon: <ReloadOutlined /> },
  TASK_DONE: { label: '完成任务', color: 'purple', icon: <CheckCircleOutlined /> },
  ADJUST: { label: '教师改分', color: 'orange', icon: <StarOutlined /> }
};
const actionMeta = (a) => ACTION_META[a] || { label: a, color: 'default', icon: null };

export default function LearningRecords() {
  const session = readSession();
  const role = session?.user?.role;
  const isStudent = role === ROLE.STUDENT;
  const [students, setStudents] = useState([]);
  const [studentId, setStudentId] = useState(isStudent ? session?.user?.studentId : null);
  const [days, setDays] = useState(7);
  const [overview, setOverview] = useState(null);
  const [stats, setStats] = useState(null);
  const [timeline, setTimeline] = useState([]);
  const [loading, setLoading] = useState(false);
  const [drawer, setDrawer] = useState(null); // { nodeId, nodeName }
  const trajectoryVersion = useRef(0);

  const loadStudents = async () => {
    if (isStudent) return;
    try {
      setStudents(unwrap(await api.listStudents()) || []);
    } catch { /* 督导范围外可能为空 */ }
  };

  useEffect(() => { loadStudents(); }, []);
  useEffect(() => {
    let active = true;
    trajectoryVersion.current += 1;
    setDrawer(null);
    setOverview(null);
    setStats(null);
    setTimeline([]);
    if (!studentId) return () => { active = false; };
    setLoading(true);
    const requests = isStudent ? [
      api.myMastery(), api.myRecordStats({ days }), api.myRecords({ limit: 80, days })
    ] : [
      api.studentMastery(studentId), api.studentRecordStats(studentId, { days }),
      api.studentRecords(studentId, { limit: 80, days })
    ];
    Promise.all(requests).then(([ov, st, tl]) => {
      if (!active) return;
      setOverview(unwrap(ov)); setStats(unwrap(st)); setTimeline(unwrap(tl) || []);
    }).catch((e) => { if (active) message.error(e.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [studentId, days, isStudent]);

  const openNode = async (nodeId, nodeName) => {
    if (!nodeId) return;
    const version = ++trajectoryVersion.current;
    setDrawer({ nodeId, nodeName, loading: true, data: null });
    try {
      const r = unwrap(await api.nodeRecordTimeline(studentId, nodeId));
      if (version === trajectoryVersion.current) setDrawer((d) => d && ({ ...d, loading: false, data: r }));
    } catch (e) {
      if (version === trajectoryVersion.current) setDrawer((d) => d && ({ ...d, loading: false, error: e.message }));
    }
  };

  const levelBars = (overview?.levelDetail || []).map((lv) => ({
    ...lv,
    count: overview?.byLevel?.[lv.level] || 0
  }));

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small" title={<Space><LineChartOutlined />学习记录与掌握程度</Space>}
        extra={
          <Space wrap>
            {!isStudent && (
              <Select style={{ width: 220 }} value={studentId} placeholder="选择学生"
                onChange={setStudentId} showSearch optionFilterProp="label"
                options={students.map((s) => ({ value: s.id, label: `${s.name}（${s.id}）` }))} />
            )}
            <Segmented value={days} onChange={setDays} options={[
              { label: '近7天', value: 7 }, { label: '近30天', value: 30 }, { label: '近一年', value: 365 }
            ]} />
          </Space>
        }>
        {!studentId && !isStudent
          ? <Empty description="请选择一名学生查看其学习记录" />
          : <Spin spinning={loading}>
            {/* 统计卡片 */}
            <Row gutter={12} style={{ marginBottom: 12 }}>
              <Col xs={12} md={8} xl={4}><Card size="small"><Statistic title="学习事件" value={stats?.eventCount || 0} /></Card></Col>
              <Col xs={12} md={8} xl={4}><Card size="small"><Statistic title="涉及知识点" value={stats?.nodeCount || 0} /></Card></Col>
              <Col xs={12} md={8} xl={4}><Card size="small"><Statistic title="记录时长" value={fmtDur(stats?.totalDurationSec)} /></Card></Col>
              <Col xs={12} md={8} xl={4}><Card size="small"><Statistic title="做题量" value={stats?.questionCount || 0} /></Card></Col>
              <Col xs={12} md={8} xl={4}><Card size="small"><Statistic title="平均得分率"
                value={stats?.avgScoreRate == null ? '—' : pct(stats.avgScoreRate)} /></Card></Col>
              <Col xs={12} md={8} xl={4}><Card size="small"><Statistic title="图谱覆盖率"
                value={pct(overview?.coverage)} suffix={`/ ${overview?.totalNodes || 0}`} /></Card></Col>
            </Row>

            <Row gutter={12}>
              {/* 掌握程度分级 */}
              <Col xs={24} xl={10}>
                <Card size="small" title="掌握程度分级" style={{ height: '100%' }}>
                  {(levelBars || []).map((lv) => (
                    <div key={lv.level} style={{ marginBottom: 6 }}>
                      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                        <Tag color={lv.color}>{lv.label}</Tag>
                        <Typography.Text type="secondary">{lv.count} 个</Typography.Text>
                      </Space>
                      <Progress percent={overview?.totalNodes ? Math.round(lv.count / overview.totalNodes * 100) : 0}
                        showInfo={false} strokeColor={lv.color === 'grey' ? '#bfbfbf' : undefined} size="small" />
                    </div>
                  ))}
                  <Divider style={{ margin: '8px 0' }} />
                  <Space direction="vertical" size={4} style={{ width: '100%' }}>
                    <div>平均掌握度：<b>{pct(overview?.avgMastery)}</b>
                      （考虑遗忘后 <b>{pct(overview?.avgEffectiveMastery)}</b>）</div>
                    {overview?.weakNodes?.length > 0 && (
                      <div>薄弱：{(overview.weakNodes).map((n) => (
                        <Tag key={n.nodeId} color="red" style={{ cursor: 'pointer' }}
                          onClick={() => openNode(n.nodeId, n.nodeName)}>{n.nodeName}</Tag>))}</div>
                    )}
                    {overview?.lowConfidenceNodes?.length > 0 && (
                      <div>低置信度（练得少）：{(overview.lowConfidenceNodes).map((n) => (
                        <Tag key={n.nodeId} color="orange" style={{ cursor: 'pointer' }}
                          onClick={() => openNode(n.nodeId, n.nodeName)}>{n.nodeName}</Tag>))}</div>
                    )}
                  </Space>
                </Card>
              </Col>

              {/* 每日活跃趋势 */}
              <Col xs={24} xl={14}>
                <Card size="small" title="每日学习活跃度" style={{ height: '100%' }}>
                  <DailyBars trend={stats?.dailyTrend || []} />
                  <Divider style={{ margin: '8px 0' }} />
                  <Space wrap>
                    {Object.entries(stats?.byAction || {}).map(([a, c]) => {
                      const m = actionMeta(a);
                      return <Tag key={a} color={m.color} icon={m.icon}>{m.label} ×{c}</Tag>;
                    })}
                  </Space>
                </Card>
              </Col>
            </Row>

            {/* 时间线 */}
            <Card size="small" title="学习记录时间线" style={{ marginTop: 12 }}>
              {timeline.length === 0
                ? <Empty description="暂无学习记录，先做一份练习或诊断卷吧" />
                : (
                  <List dataSource={timeline} pagination={{ pageSize: 12, hideOnSinglePage: true }} size="small"
                    renderItem={(r) => {
                      const m = actionMeta(r.action);
                      return (
                        <List.Item>
                          <Space wrap size={8} align="start" style={{ width: '100%' }}>
                            <Tag color={m.color} icon={m.icon}>{m.label}</Tag>
                            <span style={{ fontWeight: 600, cursor: 'pointer', color: r.nodeId ? 'var(--color-primary)' : undefined }}
                              onClick={() => openNode(r.nodeId, r.nodeName)}>
                              {r.nodeName || '整卷'}</span>
                            {r.scoreRate != null && <Tag>得分率 {pct(r.scoreRate)}</Tag>}
                            {r.masteryBefore != null && r.masteryAfter != null && (
                              <Typography.Text type="secondary">
                                {pct(r.masteryBefore)} → <b>{pct(r.masteryAfter)}</b>
                              </Typography.Text>
                            )}
                            {r.questionCount > 0 && <Typography.Text type="secondary">{r.questionCount} 题</Typography.Text>}
                            {r.durationSec > 0 && <Typography.Text type="secondary">{fmtDur(r.durationSec)}</Typography.Text>}
                            <Typography.Text type="secondary" style={{ marginLeft: 'auto' }}>
                              {fmtTime(r.createdAt)}
                            </Typography.Text>
                          </Space>
                        </List.Item>
                      );
                    }} />
                )}
            </Card>
          </Spin>
        }
      </Card>

      <Drawer title={drawer?.nodeName ? `知识点轨迹：${drawer.nodeName}` : '知识点轨迹'}
        width={520} open={!!drawer} onClose={() => { trajectoryVersion.current += 1; setDrawer(null); }}>
        {drawer?.loading && <Empty description="加载中…" />}
        {drawer?.error && <Alert type="error" message={drawer.error} />}
        {drawer?.data && <NodeTrajectory data={drawer.data} />}
      </Drawer>
    </Space>
  );
}

/** 每日活跃度柱状图（内联 SVG，不引图表库） */
function DailyBars({ trend }) {
  if (!trend || trend.length === 0) return <Empty description="该时间段内无学习活动" />;
  const max = Math.max(1, ...trend.map((t) => t.count));
  const w = 720;
  const h = 120;
  const bw = w / trend.length;
  return (
    <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h}>
      {trend.map((t, i) => {
        const bh = (t.count / max) * (h - 20);
        return (
          <g key={t.date}>
            <rect x={i * bw + bw * 0.15} y={h - bh - 16} width={bw * 0.7} height={bh}
              fill={t.count > 0 ? 'var(--color-primary)' : '#eee'} rx={2} />
            {(i % Math.ceil(trend.length / 10) === 0) && (
              <text x={i * bw + bw / 2} y={h - 4} fontSize="9" fill="var(--color-muted)" textAnchor="middle">
                {t.date.slice(5)}
              </text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

/** 单知识点轨迹抽屉 */
function NodeTrajectory({ data }) {
  const recs = data.records || [];
  const series = recs.filter((r) => r.masteryAfter != null);
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {data.nodeDescription && <Typography.Paragraph type="secondary">{data.nodeDescription}</Typography.Paragraph>}
      <Alert showIcon type="info" message={`共 ${data.eventCount || 0} 次学习事件`} />
      {series.length >= 2 && <MasterySparkline series={series} />}
      <Timeline items={recs.map((r) => {
        const m = actionMeta(r.action);
        return {
          color: ({ blue: 'blue', green: 'green', red: 'red', gold: 'gold', purple: 'purple', orange: 'orange' })[m.color] || 'gray',
          children: (
            <Space direction="vertical" size={2}>
              <Space size={6}>
                <Tag color={m.color} icon={m.icon}>{m.label}</Tag>
                <Typography.Text type="secondary">{fmtTime(r.createdAt)}</Typography.Text>
              </Space>
              {r.scoreRate != null && <span>得分率 {pct(r.scoreRate)}</span>}
              {r.masteryBefore != null && r.masteryAfter != null && (
                <span>掌握度 {pct(r.masteryBefore)} → <b>{pct(r.masteryAfter)}</b></span>
              )}
              {r.note && <Typography.Text type="secondary" style={{ fontSize: 12 }}>{r.note}</Typography.Text>}
            </Space>
          )
        };
      })} />
    </Space>
  );
}

/** 掌握度随时间变化的折线（内联 SVG） */
function MasterySparkline({ series }) {
  const w = 460, h = 140, pad = 24;
  const n = series.length;
  const x = (i) => pad + (i / Math.max(1, n - 1)) * (w - pad * 2);
  const y = (v) => h - pad - (Number(v) || 0) * (h - pad * 2);
  const pts = series.map((r, i) => `${x(i)},${y(r.masteryAfter)}`).join(' ');
  return (
    <Card size="small" title="掌握度变化">
      <svg viewBox={`0 0 ${w} ${h}`} width="100%" height={h}>
        <line x1={pad} y1={h - pad} x2={w - pad} y2={h - pad} stroke="var(--color-border)" />
        <line x1={pad} y1={pad} x2={pad} y2={h - pad} stroke="var(--color-border)" />
        <polyline points={pts} fill="none" stroke="var(--color-primary)" strokeWidth="2" />
        {series.map((r, i) => (
          <circle key={i} cx={x(i)} cy={y(r.masteryAfter)} r="3" fill="var(--color-primary)" />
        ))}
        <text x={pad} y={h - 4} fontSize="9" fill="var(--color-muted)">0%</text>
        <text x={pad} y={pad + 2} fontSize="9" fill="var(--color-muted)">100%</text>
      </svg>
    </Card>
  );
}
