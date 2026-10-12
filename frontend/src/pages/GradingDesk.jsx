import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Button, Space, Tag, Typography, message, Table, Empty, InputNumber,
  Input, Divider, Alert, Popconfirm, Tooltip, Badge, Row, Col, Statistic
} from 'antd';
import {
  ArrowLeftOutlined, CheckOutlined, ReloadOutlined, RobotOutlined,
  WarningOutlined, RollbackOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { QTYPE_LABEL, QTYPE_COLOR, ASSIGN_STATUS } from '../examMeta.js';

const isObjective = (t) => ['SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE'].includes(t);

/**
 * 批改台：一份卷子、所有学生的答卷。
 * 机器已经把客观题判完了，主观题也给了参考分并标红；
 * 老师只需要核对/改写，点「提交批改」定稿，掌握度随最终分数更新。
 */
export default function GradingDesk() {
  const { examId } = useParams();
  const nav = useNavigate();
  const [subs, setSubs] = useState([]);
  const [detail, setDetail] = useState(null);
  const [draft, setDraft] = useState({});   // questionId -> { score, comment }
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  const loadSubs = async () => {
    setLoading(true);
    try {
      setSubs(unwrap(await api.paperSubmissions(examId)) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadSubs(); }, [examId]);

  const openStudent = async (studentId) => {
    try {
      const d = unwrap(await api.submissionDetail(examId, studentId));
      setDetail(d);
      const init = {};
      (d.questions || []).forEach(q => {
        init[q.questionId] = { score: q.score ?? 0, comment: q.comment || '' };
      });
      setDraft(init);
    } catch (e) {
      message.error(e.message);
    }
  };

  const adoptAuto = () => {
    if (!detail) return;
    const next = {};
    (detail.questions || []).forEach(q => {
      next[q.questionId] = { score: q.autoScore ?? 0, comment: q.comment || '' };
    });
    setDraft(next);
    message.success('已全部采用机器判分，可再逐题修改');
  };

  const patch = (qid, field, value) => {
    setDraft(d => ({ ...d, [qid]: { ...d[qid], [field]: value } }));
  };

  const submitGrade = async () => {
    if (!detail) return;
    setSaving(true);
    try {
      const items = (detail.questions || []).map(q => ({
        questionId: q.questionId,
        score: Number(draft[q.questionId]?.score ?? 0),
        comment: draft[q.questionId]?.comment || ''
      }));
      const r = unwrap(await api.gradeSubmission(examId, detail.studentId, items));
      message.success(`批改完成：${r.totalScore} / ${r.fullScore} 分，掌握度已按修正结果更新`);
      await loadSubs();
      await openStudent(detail.studentId);
    } catch (e) {
      message.error(e.message);
    } finally {
      setSaving(false);
    }
  };

  const doReset = async () => {
    try {
      await api.resetSubmission(examId, detail.studentId);
      message.success('已退回重做，该学生会重新看到「待作答」');
      setDetail(null);
      loadSubs();
    } catch (e) {
      message.error(e.message);
    }
  };

  const pendingTotal = subs.reduce((n, s) => n + (s.pendingCount || 0), 0);
  const submittedCount = subs.filter(s => s.status !== 'ASSIGNED').length;
  const draftTotal = detail
    ? (detail.questions || []).reduce((sum, q) => sum + Number(draft[q.questionId]?.score ?? 0), 0)
    : 0;

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/exams')}>返回出题中心</Button>
          <Typography.Title level={5} style={{ margin: 0 }}>
            {subs.length ? '批改台' : '批改台'} · 卷子 #{examId}
          </Typography.Title>
          <Button icon={<ReloadOutlined />} onClick={loadSubs}>刷新</Button>
        </Space>
      </Card>

      <Row gutter={12} style={{ marginBottom: 12 }}>
        <Col span={8}>
          <Card size="small"><Statistic title="已交卷" value={submittedCount} suffix={`/ ${subs.length} 人`} /></Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="待人工批改题数" value={pendingTotal}
              valueStyle={{ color: pendingTotal > 0 ? 'var(--color-warning)' : 'var(--color-success)' }} />
          </Card>
        </Col>
        <Col span={8}>
          <Card size="small">
            <Statistic title="已批改定稿" value={subs.filter(s => s.status === 'GRADED').length}
              suffix={`/ ${subs.length} 人`} valueStyle={{ color: 'var(--color-success)' }} />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="交卷情况" style={{ marginBottom: 12 }}>
        {subs.length === 0 ? <Empty description="这份卷子还没发给学生" /> : (
          <Table rowKey="studentId" size="small" loading={loading} dataSource={subs}
            pagination={false}
            rowClassName={(r) => (detail?.studentId === r.studentId ? 'ant-table-row-selected' : '')}
            columns={[
              { title: '学生', dataIndex: 'studentName', width: 140 },
              {
                title: '状态', dataIndex: 'status', width: 130,
                render: (s, r) => (
                  <Space size={4}>
                    <Tag color={ASSIGN_STATUS[s]?.color}>{ASSIGN_STATUS[s]?.label || s}</Tag>
                    {r.pendingCount > 0 && <Badge count={r.pendingCount} size="small" />}
                  </Space>
                )
              },
              {
                title: '得分', width: 130,
                render: (_, r) => r.totalScore == null
                  ? <Typography.Text type="secondary">未交卷</Typography.Text>
                  : <span style={{ fontWeight: 600 }}>{r.totalScore} / {r.fullScore}</span>
              },
              { title: '交卷时间', dataIndex: 'submittedAt', width: 170,
                render: v => v ? String(v).replace('T', ' ').slice(0, 19) : '—' },
              {
                title: '操作', render: (_, r) => (
                  <Button type="link" size="small" disabled={r.status === 'ASSIGNED'}
                    onClick={() => openStudent(r.studentId)}>
                    {r.status === 'GRADED' ? '查看 / 修改' : '批改'}
                  </Button>
                )
              }
            ]} />
        )}
      </Card>

      {detail && (
        <Card size="small"
          title={
            <Space wrap>
              <span>{detail.studentName} 的答卷</span>
              <Tag color={ASSIGN_STATUS[detail.status]?.color}>
                {ASSIGN_STATUS[detail.status]?.label || detail.status}
              </Tag>
              <Tag color="blue">当前合计 {draftTotal.toFixed(1)} / {detail.fullScore} 分</Tag>
            </Space>
          }
          extra={
            <Space>
              <Button icon={<RobotOutlined />} onClick={adoptAuto}>全部采用机器分</Button>
              <Popconfirm title={`把「${detail.studentName}」退回重做？答卷会被清空`}
                onConfirm={doReset}>
                <Button icon={<RollbackOutlined />}>退回重做</Button>
              </Popconfirm>
              <Button type="primary" icon={<CheckOutlined />} loading={saving} onClick={submitGrade}>
                提交批改并定稿
              </Button>
            </Space>
          }>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message="客观题机器已判分；简答题给的是要点覆盖率参考分（标红表示疑似未答到要点），请核对后改分。定稿后学生会看到分数、评语与参考答案。" />

          {(detail.questions || []).map((q, idx) => {
            const d = draft[q.questionId] || {};
            const full = Number(q.fullScore) || 0;
            const objective = isObjective(q.questionType);
            return (
              <Card key={q.questionId} size="small" style={{ marginBottom: 10 }}
                title={
                  <Space wrap>
                    <span>第 {idx + 1} 题</span>
                    <Tag color={QTYPE_COLOR[q.questionType]}>{QTYPE_LABEL[q.questionType] || q.questionType}</Tag>
                    <Tag color="blue">{full} 分</Tag>
                    {q.nodeName && <Tag color="geekblue">{q.nodeName}</Tag>}
                    {objective
                      ? <Tag color={Number(q.autoScore) === full ? 'green' : 'red'}>
                          机器判分 {q.autoScore}
                        </Tag>
                      : <Tag color="orange" icon={<WarningOutlined />}>
                          机器参考分 {q.autoScore} · 待复核
                        </Tag>}
                  </Space>
                }>
                <Typography.Paragraph style={{ fontSize: 14, whiteSpace: 'pre-wrap', marginBottom: 8 }}>
                  {q.stem}
                </Typography.Paragraph>

                {(q.options || []).length > 0 && (
                  <div style={{ marginBottom: 8 }}>
                    {(q.options || []).map((opt, i) => {
                      const isRef = String(q.referenceAnswer || '').split(/[,，]/).map(s => s.trim()).includes(String(opt).trim());
                      const isMine = String(q.studentAnswer || '').split(/[,，]/).map(s => s.trim()).includes(String(opt).trim());
                      return (
                        <div key={i} style={{
                          padding: '2px 8px', borderRadius: 4,
                          background: isRef ? 'var(--color-success-soft)' : isMine ? 'var(--color-warning-soft)' : 'transparent'
                        }}>
                          {isRef && <Tag color="green">答案</Tag>}
                          {isMine && !isRef && <Tag color="orange">学生选</Tag>}
                          {isMine && isRef && <Tag color="orange">学生选对</Tag>}
                          {opt}
                        </div>
                      );
                    })}
                  </div>
                )}

                <Row gutter={12}>
                  <Col span={12}>
                    <Card size="small" type="inner" title="学生作答">
                      <div style={{ whiteSpace: 'pre-wrap', minHeight: 42 }}>
                        {q.studentAnswer
                          ? q.studentAnswer
                          : <Typography.Text type="secondary">（未作答）</Typography.Text>}
                      </div>
                    </Card>
                  </Col>
                  <Col span={12}>
                    <Card size="small" type="inner" title="参考答案 / 解析">
                      <div style={{ whiteSpace: 'pre-wrap' }}>
                        <b>{q.referenceAnswer}</b>
                        {q.analysis && (
                          <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 6, marginBottom: 0 }}>
                            {q.analysis}
                          </Typography.Paragraph>
                        )}
                      </div>
                    </Card>
                  </Col>
                </Row>

                <Divider style={{ margin: '10px 0' }} />

                <Space wrap align="baseline">
                  <span>给分：</span>
                  <InputNumber min={0} max={full} step={0.5}
                    value={d.score} onChange={v => patch(q.questionId, 'score', v)}
                    style={{ width: 90 }} />
                  <span style={{ color: 'var(--color-muted)' }}>/ {full}</span>
                  <span style={{ marginLeft: 12 }}>评语：</span>
                  <Input value={d.comment} onChange={e => patch(q.questionId, 'comment', e.target.value)}
                    placeholder="选填，学生会看到" style={{ width: 380 }} />
                  {q.gradedBy === 'MANUAL' && <Tag color="purple">已人工改过</Tag>}
                </Space>
              </Card>
            );
          })}

          <Space style={{ marginTop: 8 }}>
            <Button type="primary" size="large" icon={<CheckOutlined />}
              loading={saving} onClick={submitGrade}>提交批改并定稿</Button>
            <Tooltip title="定稿后学生即可看到分数、评语与参考答案">
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                当前合计 {draftTotal.toFixed(1)} / {detail.fullScore} 分
              </Typography.Text>
            </Tooltip>
          </Space>
        </Card>
      )}
    </div>
  );
}
