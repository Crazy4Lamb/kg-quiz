import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Card, Table, Button, Space, Tag, Typography, message, Empty, Progress, Row, Col,
  Statistic, List, Modal, Tooltip, Divider, Alert, Form, Select, Input, DatePicker, Popconfirm
} from 'antd';
import {
  PlayCircleOutlined, CheckOutlined, ReloadOutlined, RocketOutlined, ReadOutlined,
  ExperimentOutlined, AimOutlined, ApartmentOutlined, FileDoneOutlined, HistoryOutlined,
  PlusOutlined, DeleteOutlined
} from '@ant-design/icons';
import dayjs from 'dayjs';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

/** 任务类型元信息：诊 → 学 → 练 → 复习 */
const TASK_META = {
  DIAGNOSIS: { label: '诊断', color: 'red', icon: <AimOutlined /> },
  LEARN: { label: '学', color: 'blue', icon: <ReadOutlined /> },
  PRACTICE: { label: '练', color: 'green', icon: <ExperimentOutlined /> },
  REVIEW: { label: '复习', color: 'gold', icon: <HistoryOutlined /> }
};

const masteryColor = (v) => (v >= 0.7 ? 'var(--color-success)' : v >= 0.4 ? 'var(--color-warning)' : 'var(--color-danger)');
const pct = (v) => `${Math.round((Number(v) || 0) * 100)}%`;

/**
 * 学习首页：把「诊 → 学 → 练 → 复习」这条流走成一天一张清单。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>学生自己：路由 /my，打开时向后端要「今日清单」，没有未完成任务就按推荐规则现生成一批；</li>
 *   <li>教师 / 管理员：路由 /students/:id/tasks，只读查看某个学生的清单，并可重排或手工布置任务。</li>
 * </ul>
 */
export default function LearnHome() {
  const nav = useNavigate();
  const { id } = useParams();
  const session = readSession();
  const isStaff = session?.user?.role !== ROLE.STUDENT;
  const targetStudentId = isStaff ? Number(id) : null;

  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [recOpen, setRecOpen] = useState(false);
  const [recs, setRecs] = useState([]);
  // 教师布置任务
  const [assignOpen, setAssignOpen] = useState(false);
  const [assignForm] = Form.useForm();
  const [graphs, setGraphs] = useState([]);
  const [nodes, setNodes] = useState([]);

  const load = async () => {
    setLoading(true);
    try {
      const d = unwrap(isStaff ? await api.studentTasks(targetStudentId) : await api.myTasksToday());
      setData(d);
      if (d?.generated && d?.notice) {
        message.info(d.notice);
      }
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [targetStudentId]);

  const startTask = async (task) => {
    setBusy(true);
    try {
      const r = unwrap(await api.startTask(task.id));
      if (r.examId) {
        nav(`/exams/take/${r.examId}`);
      } else if (r.nodeId) {
        nav(`/my/learn/${r.nodeId}`);
      } else {
        message.info('这个任务暂时没有可执行的内容');
      }
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const finishTask = async (task) => {
    setBusy(true);
    try {
      await api.completeTask(task.id);
      message.success('已标记完成');
      await load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const reopenTask = async (task) => {
    try {
      await api.reopenTask(task.id);
      await load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const regenerate = async () => {
    setBusy(true);
    try {
      const r = unwrap(isStaff
        ? await api.regenerateStudentTasks(targetStudentId)
        : await api.regenerateMyTasks());
      message.success(r?.notice || '已重新生成学习任务');
      await load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const startDiagnosis = async () => {
    setBusy(true);
    try {
      const r = unwrap(await api.startMyDiagnosis({ perNode: 1, maxQuestions: 20 }));
      message.success(`已生成诊断卷：${r.questionCount} 题，覆盖 ${r.coveredNodes}/${r.totalNodes} 个知识点`);
      nav(`/exams/take/${r.examId}`);
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const openRecommend = async () => {
    setRecOpen(true);
    try {
      setRecs(unwrap(await api.myRecommend({ limit: 10 })) || []);
    } catch (e) {
      message.error(e.message);
    }
  };

  const removeTask = async (task) => {
    try {
      await api.deleteTask(task.id);
      message.success('已删除任务');
      await load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const openAssign = async () => {
    assignForm.resetFields();
    assignForm.setFieldsValue({ type: 'LEARN' });
    setNodes([]);
    setAssignOpen(true);
    if (!graphs.length) {
      try {
        setGraphs(unwrap(await api.listGraphs()) || []);
      } catch { /* 忽略 */ }
    }
  };

  const loadNodes = async (graphId) => {
    if (!graphId) { setNodes([]); return; }
    try {
      setNodes(unwrap(await api.graphDetail(graphId))?.nodes || []);
    } catch { setNodes([]); }
  };

  const submitAssign = async () => {
    const v = await assignForm.validateFields();
    setBusy(true);
    try {
      await api.assignTeacherTask({
        studentId: targetStudentId,
        nodeId: v.nodeId ?? null,
        type: v.type,
        title: v.title,
        dueAt: v.dueAt ? v.dueAt.format('YYYY-MM-DDTHH:mm') : null
      });
      message.success('已布置任务');
      setAssignOpen(false);
      await load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const progress = data?.progress || {};
  const tasks = data?.tasks || [];
  const pending = tasks.filter((t) => t.status === 'PENDING');
  const done = tasks.filter((t) => t.status !== 'PENDING');
  const avg = progress.avgMastery || 0;

  const taskColumns = [
    {
      title: '任务', dataIndex: 'title',
      render: (v, row) => {
        const meta = TASK_META[row.type] || { label: row.type, color: 'default' };
        return (
          <Space direction="vertical" size={2}>
            <Space size={6}>
              <Tag color={meta.color} style={{ marginRight: 0 }}>{meta.label}</Tag>
              <span style={{ fontWeight: 600 }}>{v}</span>
              {row.source === 'TEACHER' && <Tag color="purple">老师布置</Tag>}
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{row.reason}</Typography.Text>
          </Space>
        );
      }
    },
    {
      title: '知识点', dataIndex: 'nodeName', width: 150,
      render: (v, row) => (v ? (
        <Space direction="vertical" size={0}>
          <span>{v}</span>
          {row.mastery != null && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              掌握度 <span style={{ color: masteryColor(row.mastery) }}>{pct(row.mastery)}</span>
            </Typography.Text>
          )}
        </Space>
      ) : <Typography.Text type="secondary">—</Typography.Text>)
    },
    {
      title: '操作', width: isStaff ? 150 : 190, render: (_, row) => (
        isStaff ? (
          <Popconfirm title="确认删除这条任务？" onConfirm={() => removeTask(row)}>
            <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
          </Popconfirm>
        ) : (
          <Space size={4}>
            <Button type="primary" size="small" icon={<PlayCircleOutlined />} disabled={busy}
              onClick={() => startTask(row)}>
              {row.type === 'LEARN' ? '开始学习' : row.type === 'DIAGNOSIS' ? '去做诊断' : '开始练习'}
            </Button>
            <Tooltip title="不想做时可直接标记完成">
              <Button size="small" aria-label="标记任务完成" icon={<CheckOutlined />} disabled={busy} onClick={() => finishTask(row)} />
            </Tooltip>
          </Space>
        )
      )
    }
  ];

  const doneColumns = [
    {
      title: '已完成', dataIndex: 'title',
      render: (v, row) => {
        const meta = TASK_META[row.type] || { label: row.type, color: 'default' };
        return (
          <Space size={6}>
            <Tag color={meta.color} style={{ marginRight: 0 }}>{meta.label}</Tag>
            <Typography.Text delete type="secondary">{v}</Typography.Text>
          </Space>
        );
      }
    },
    {
      title: '完成时间', dataIndex: 'completedAt', width: 175,
      render: (v) => <Typography.Text type="secondary">{(v || '').replace('T', ' ').slice(0, 19)}</Typography.Text>
    },
    ...(isStaff ? [] : [{
      title: '', width: 90, render: (_, row) => (
        <Button size="small" type="link" onClick={() => reopenTask(row)}>再做一遍</Button>
      )
    }])
  ];

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>

      {isStaff && data && (
        <Alert type="info" showIcon
          message={`正在查看「${data.studentName || `学生 #${targetStudentId}`}」的学习安排`}
          description="系统按该生当前掌握度自动排的任务，可重排、可删除，也可以手工插一条。" />
      )}

      {/* 学习进度总览 */}
      <Card size="small" loading={loading}>
        <Row gutter={[16, 16]} align="middle">
          <Col xs={12} md={5}>
            <Statistic title={isStaff ? '该生知识点' : '我的知识点'} value={progress.total || 0} suffix="个" />
          </Col>
          <Col xs={12} md={5}>
            <Statistic title="已掌握" value={progress.mastered || 0} suffix="个" valueStyle={{ color: 'var(--color-success)' }} />
          </Col>
          <Col xs={12} md={5}>
            <Statistic title="待补强" value={progress.weak || 0} suffix="个" valueStyle={{ color: 'var(--color-danger)' }} />
          </Col>
          <Col xs={12} md={5}>
            <Statistic title="平均掌握度" value={pct(avg)} valueStyle={{ color: masteryColor(avg) }} />
          </Col>
          <Col xs={24} md={4} style={{ textAlign: 'right' }}>
            <Space direction="vertical" size={6} style={{ width: '100%' }}>
              <Button size="small" icon={<ReloadOutlined />} loading={busy} onClick={regenerate} block>
                按最新掌握度重排
              </Button>
              {isStaff ? (
                <>
                  <Button size="small" type="primary" ghost icon={<PlusOutlined />}
                    onClick={openAssign} block>
                    布置学习任务
                  </Button>
                  <Button size="small" type="link" onClick={() => nav(`/students/${targetStudentId}/diagnosis`)} block>
                    查看诊断报告
                  </Button>
                </>
              ) : (
                <Button size="small" type="primary" ghost icon={<ApartmentOutlined />}
                  onClick={() => nav('/my/graph')} block>
                  我的知识图谱
                </Button>
              )}
            </Space>
          </Col>
        </Row>
        <Progress style={{ marginTop: 12, marginBottom: 0 }}
          percent={Math.round(avg * 100)}
          strokeColor={masteryColor(avg)}
          format={() => `整体掌握 ${pct(avg)}`} />
      </Card>

      {/* 诊断入口（学生自助；教师视角只看状态） */}
      {!isStaff && (
        <Card size="small" title={<Space size={6}><AimOutlined />入学诊断</Space>}
          extra={<Button size="small" type="link" onClick={() => nav('/my/diagnosis')}>查看诊断报告</Button>}>
          {data?.diagnosisCompleted ? (
            <Alert type="success" showIcon
              message="诊断已完成"
              description="你的学习起点已经确定，系统会据此安排每天该学什么。想重新摸底可以再做一次。"
              action={<Button size="small" loading={busy} onClick={startDiagnosis}>重新诊断</Button>} />
          ) : data?.hasDiagnosis ? (
            <Alert type="warning" showIcon
              message="诊断卷已就绪，但还没有交卷"
              description="交卷后系统才知道你的真实水平，才能给出准确的起点和推荐。"
              action={<Button size="small" type="primary" loading={busy} onClick={startDiagnosis}>开始诊断</Button>} />
          ) : (
            <Alert type="info" showIcon
              message="还没有做过诊断"
              description="先做一次覆盖全部知识点的摸底测验（大约 10-20 题），系统会按你的真实水平初始化掌握度，再从最该补的地方带你学。"
              action={<Button size="small" type="primary" loading={busy} onClick={startDiagnosis}>开始入学诊断</Button>} />
          )}
        </Card>
      )}

      {/* 今日学习清单 */}
      <Card size="small"
        title={<Space size={6}><RocketOutlined />学习清单（待完成 {pending.length} 条）</Space>}
        extra={<Space>
          {!isStaff && (
            <Button size="small" icon={<AimOutlined />} onClick={openRecommend}>推荐学习顺序</Button>
          )}
          {!isStaff && (
            <Button size="small" icon={<FileDoneOutlined />} onClick={() => nav('/exams')}>我的作业</Button>
          )}
          {isStaff && (
            <Button size="small" icon={<PlusOutlined />} onClick={openAssign}>布置任务</Button>
          )}
        </Space>}>
        {pending.length === 0 ? (
          <Empty description={loading ? '加载中…' : `${isStaff ? '该生' : '今天'}没有待完成任务`}>
            <Button type="primary" icon={<ReloadOutlined />} loading={busy} onClick={regenerate}>
              生成新任务
            </Button>
          </Empty>
        ) : (
          <Table rowKey="id" size="small" pagination={false} dataSource={pending} columns={taskColumns} />
        )}
        {done.length > 0 && (
          <>
            <Divider orientation="left" plain style={{ marginTop: 20 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>已完成（{done.length}）</Typography.Text>
            </Divider>
            <Table rowKey="id" size="small" pagination={false} dataSource={done} columns={doneColumns} />
          </>
        )}
      </Card>

      <Modal title="推荐学习顺序（按当前掌握度算的）" open={recOpen}
        onCancel={() => setRecOpen(false)} footer={null} width={720}>
        <Typography.Paragraph type="secondary" style={{ fontSize: 12 }}>
          分数越高越该先学：缺口越大、知识点越重要，分数越高。已掌握的知识点排在最后。
        </Typography.Paragraph>
        <List size="small" dataSource={recs}
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
                    掌握度 {pct(r.mastery)} · 优先级 {r.score}
                  </Typography.Text>
                </Space>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {(r.reasons || []).join('；')}
                </Typography.Text>
              </Space>
            </List.Item>
          )} />
      </Modal>

      {/* 教师布置任务 */}
      <Modal title="布置学习任务" open={assignOpen} onOk={submitAssign} confirmLoading={busy}
        onCancel={() => setAssignOpen(false)} okText="布置" destroyOnClose>
        <Form form={assignForm} layout="vertical" initialValues={{ type: 'LEARN' }}>
          <Form.Item name="type" label="任务类型" rules={[{ required: true }]}>
            <Select options={[
              { value: 'LEARN', label: '学 · 看知识点讲解' },
              { value: 'PRACTICE', label: '练 · 做专项练习' },
              { value: 'REVIEW', label: '复习 · 回顾巩固' }
            ]} />
          </Form.Item>
          <Form.Item label="知识点（可选）">
            <Space>
              <Select style={{ width: 200 }} placeholder="先选图谱" allowClear
                onChange={loadNodes}
                options={graphs.map(g => ({ value: g.id, label: g.name || `图谱 #${g.id}` }))} />
              <Form.Item name="nodeId" noStyle>
                <Select style={{ width: 240 }} placeholder="选择知识点" allowClear showSearch
                  optionFilterProp="label"
                  options={nodes.map(n => ({ value: n.id, label: n.name }))} />
              </Form.Item>
            </Space>
          </Form.Item>
          <Form.Item name="title" label="任务标题" rules={[{ required: true, message: '给任务起个名字' }]}>
            <Input placeholder="如：今晚把小数乘法练熟" />
          </Form.Item>
          <Form.Item name="dueAt" label="截止时间（可选）">
            <DatePicker showTime style={{ width: '100%' }} disabledDate={(d) => d && d < dayjs().startOf('day')} />
          </Form.Item>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            老师布置的任务不会被「按最新掌握度重排」清掉，会一直留在学生的清单里直到完成。
          </Typography.Text>
        </Form>
      </Modal>
    </Space>
  );
}
