import { useEffect, useState } from 'react';
import {
  Card, Select, InputNumber, Button, Space, Table, Tag, Typography, message,
  Collapse, Progress, Descriptions, Empty, Modal
} from 'antd';
import { ThunderboltOutlined, CheckOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const STATUS_TAG = { PENDING: 'gold', CONFIRMED: 'blue' };

/**
 * 自适应出题：按学生当前掌握度生成个性化练习。
 * 确认后练习会自动指派给该学生，学生端「我的作业」里就能看到。
 */
export default function AdaptivePanel() {
  const [students, setStudents] = useState([]);
  const [studentId, setStudentId] = useState(null);
  const [total, setTotal] = useState(10);
  const [perNode, setPerNode] = useState(3);
  const [plan, setPlan] = useState(null);   // {examId, plan}
  const [exams, setExams] = useState([]);
  const [loading, setLoading] = useState(false);

  const loadStudents = () => {
    api.listStudents()
      .then(r => setStudents(unwrap(r) || []))
      .catch(e => message.error(e.message));
  };

  const loadExams = () => api.listExams()
    .then(r => setExams(unwrap(r) || [])).catch(() => {});

  useEffect(() => {
    loadStudents();
    loadExams();
  }, []);

  const generate = async () => {
    if (!studentId) {
      message.warning('请先选择学生');
      return;
    }
    setLoading(true);
    try {
      const r = await api.generatePlan({ studentId, totalQuestions: total, perNodeLimit: perNode });
      setPlan(unwrap(r));
      message.success('出题方案已生成');
      loadExams();
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const confirm = async (examId) => {
    try {
      await api.confirmPlan(examId);
      message.success('方案已确认，练习已指派给该学生');
      setPlan(null);
      loadExams();
    } catch (e) {
      message.error(e.message);
    }
  };

  const nameOf = (sid) => students.find(x => x.id === sid)?.name || `学生 #${sid}`;

  return (
    <div>
      <Card size="small" title="生成出题方案" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Select placeholder="选择学生" style={{ width: 200 }} showSearch optionFilterProp="label"
            value={studentId} onChange={setStudentId}
            options={students.map(s => ({ value: s.id, label: `${s.name}（${s.subject || '未复制'}/${s.grade || '-'}）` }))} />
          <InputNumber min={1} max={50} value={total} onChange={setTotal}
            addonBefore="题数" style={{ width: 140 }} />
          <InputNumber min={1} max={10} value={perNode} onChange={setPerNode}
            addonBefore="每知识点上限" style={{ width: 170 }} />
          <Button type="primary" icon={<ThunderboltOutlined />} loading={loading} onClick={generate}>
            生成方案
          </Button>
        </Space>
        <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 8, fontSize: 12 }}>
          策略：薄弱知识点优先补强（结合权重与前置关系），并保留约 20% 巩固题防遗忘；难度随掌握度自适应（薄弱出易题、巩固出难题）。
          确认后会同时把这份练习指派给该学生，他在「我的作业」里直接作答，系统自动批改。
        </Typography.Paragraph>
      </Card>

      {plan && (
        <Card size="small" title={`方案预览（练习 #${plan.examId}）`} style={{ marginBottom: 12 }}
          extra={
            <Button type="primary" icon={<CheckOutlined />} onClick={() => confirm(plan.examId)}>
              确认并指派给学生
            </Button>
          }>
          <Descriptions size="small" bordered column={3} style={{ marginBottom: 12 }}>
            <Descriptions.Item label="学生">{plan.plan?.studentName}</Descriptions.Item>
            <Descriptions.Item label="总题数">{plan.plan?.totalQuestions}</Descriptions.Item>
            <Descriptions.Item label="生成时间">{(plan.plan?.generatedAt || '').replace('T', ' ').slice(0, 19)}</Descriptions.Item>
          </Descriptions>
          <Collapse
            items={(plan.plan?.items || []).map((item, i) => ({
              key: String(i),
              label: (
                <Space>
                  <Tag color="processing">{item.nodeName}</Tag>
                  <Progress percent={Math.round((item.mastery || 0) * 100)} size="small"
                    strokeColor={(item.mastery || 0) < 0.4 ? 'var(--color-danger)' : (item.mastery || 0) >= 0.7 ? 'var(--color-success)' : 'var(--color-warning)'}
                    style={{ width: 120, marginBottom: 0 }} format={p => `掌握 ${p}%`} />
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>{item.reason}</Typography.Text>
                </Space>
              ),
              children: (
                <ul style={{ margin: 0, paddingLeft: 18 }}>
                  {(item.questions || []).map(q => (
                    <li key={q.questionId} style={{ fontSize: 13, marginBottom: 4 }}>
                      [#{q.questionId}] {q.stem?.slice(0, 60)}{q.stem?.length > 60 ? '…' : ''}
                      <Tag style={{ marginLeft: 8 }}>{q.questionType}</Tag>
                      <Tag color="orange">难度 {q.difficulty}</Tag>
                      <Tag color="blue">{q.score} 分</Tag>
                    </li>
                  ))}
                </ul>
              )
            }))}
          />
        </Card>
      )}

      <Card size="small" title="历史方案 / 练习">
        {exams.length === 0 ? <Empty description="暂无记录" /> : (
          <Table rowKey="examId" dataSource={exams} pagination={{ pageSize: 8 }} size="small"
            columns={[
              { title: '练习 ID', dataIndex: 'examId', width: 90 },
              {
                title: '学生', dataIndex: 'studentId', width: 120,
                render: v => nameOf(v)
              },
              {
                title: '状态', dataIndex: 'status', width: 110, render: s => (
                  <Tag color={STATUS_TAG[s]}>{s === 'PENDING' ? '待确认' : s === 'CONFIRMED' ? '已指派' : s}</Tag>
                )
              },
              { title: '创建时间', dataIndex: 'createdAt',
                render: v => (v || '').replace('T', ' ').slice(0, 19) },
              {
                title: '操作', width: 180, render: (_, row) => (
                  <Space>
                    {row.status === 'PENDING' && (
                      <Button size="small" type="primary" ghost onClick={() => confirm(row.examId)}>
                        确认方案
                      </Button>
                    )}
                    <Button size="small" type="link" onClick={() => {
                      Modal.info({
                        title: `${nameOf(row.studentId)} 掌握度`,
                        width: 520,
                        content: <MasteryInline studentId={row.studentId} />
                      });
                    }}>掌握度</Button>
                  </Space>
                )
              }
            ]} />
        )}
      </Card>
    </div>
  );
}

function MasteryInline({ studentId }) {
  const [data, setData] = useState(null);
  useEffect(() => {
    api.mastery(studentId).then(r => setData(unwrap(r))).catch(() => {});
  }, [studentId]);
  if (!data) return <span>加载中…</span>;
  return (
    <div style={{ maxHeight: 400, overflow: 'auto', marginTop: 8 }}>
      <p><b>平均掌握度：{(data.avgMastery * 100).toFixed(1)}%</b></p>
      {(data.nodes || []).map(n => (
        <div key={n.nodeId} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
          <span style={{ width: 130, textAlign: 'right' }}>{n.nodeName}</span>
          <Progress percent={Math.round((n.mastery || 0) * 100)} size="small"
            strokeColor={(n.mastery || 0) < 0.4 ? 'var(--color-danger)' : (n.mastery || 0) >= 0.7 ? 'var(--color-success)' : 'var(--color-warning)'} />
        </div>
      ))}
    </div>
  );
}
