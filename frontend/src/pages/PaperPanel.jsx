import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Space, Tag, Typography, Modal, Form, Input, Select,
  message, Popconfirm, Empty, Tooltip, Badge, Divider, Alert
} from 'antd';
import {
  PlusOutlined, ReloadOutlined, SendOutlined, DeleteOutlined, EditOutlined,
  SearchOutlined, TeamOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';
import { QTYPE_LABEL, QTYPE_COLOR, ASSIGN_STATUS } from '../examMeta.js';
import PageToolbar from '../components/PageToolbar.jsx';
import RowActions from '../components/RowActions.jsx';

const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];
const TEXTBOOKS = ['人教版', '北师大版', '苏教版', '沪教版'];

/**
 * 组卷与批改：教师从题库挑题组卷 → 勾选学生定向发送 → 学生作答后进批改台。
 * 管理员可以看全部教师的卷子（并且能代管）。
 */
export default function PaperPanel() {
  const nav = useNavigate();
  const me = readSession()?.user;
  const isAdmin = me?.role === ROLE.ADMIN;

  const [papers, setPapers] = useState([]);
  const [students, setStudents] = useState([]);
  const [teachers, setTeachers] = useState([]);
  const [filterTeacher, setFilterTeacher] = useState(null);
  const [loading, setLoading] = useState(false);

  // 组卷
  const [createOpen, setCreateOpen] = useState(false);
  const [createForm] = Form.useForm();
  const [pool, setPool] = useState([]);
  const [poolLoading, setPoolLoading] = useState(false);
  const [picked, setPicked] = useState([]);       // 已选题目 id
  const [kw, setKw] = useState('');

  // 指派
  const [assignTarget, setAssignTarget] = useState(null);
  const [assignDetail, setAssignDetail] = useState(null);
  const [assignIds, setAssignIds] = useState([]);

  const load = async () => {
    setLoading(true);
    try {
      setPapers(unwrap(await api.listPapers(filterTeacher ? { teacherId: filterTeacher } : {})) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [filterTeacher]);

  useEffect(() => {
    api.listStudents().then(r => setStudents(unwrap(r) || [])).catch(() => {});
    if (isAdmin) {
      api.listAccounts()
        .then(r => setTeachers((unwrap(r) || []).filter(x => x.role === ROLE.TEACHER)))
        .catch(() => {});
    }
  }, [isAdmin]);

  // ---------- 组卷 ----------

  const openCreate = async () => {
    createForm.resetFields();
    setPicked([]);
    setKw('');
    setCreateOpen(true);
    if (pool.length === 0) {
      setPoolLoading(true);
      try {
        const r = unwrap(await api.listQuestions({ size: 500 }));
        setPool(r?.records || []);
      } catch (e) {
        message.error(e.message);
      } finally {
        setPoolLoading(false);
      }
    }
  };

  const filteredPool = useMemo(() => {
    const k = kw.trim();
    if (!k) return pool;
    return pool.filter(q => (q.stem || '').includes(k) || (q.nodes || []).some(n => (n.name || n) === k));
  }, [pool, kw]);

  const submitCreate = async () => {
    const values = await createForm.validateFields();
    if (picked.length === 0) {
      message.warning('请至少选择一道题');
      return;
    }
    try {
      await api.createPaper({ ...values, questionIds: picked });
      message.success(`卷子「${values.name}」已创建，接着选学生发出去吧`);
      setCreateOpen(false);
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------- 指派 ----------

  const openAssign = async (paper) => {
    setAssignTarget(paper);
    setAssignIds([]);
    try {
      setAssignDetail(unwrap(await api.paperDetail(paper.examId)));
    } catch (e) {
      message.error(e.message);
    }
  };

  const doAssign = async () => {
    if (assignIds.length === 0) {
      message.warning('请选择要发送的学生');
      return;
    }
    try {
      const r = unwrap(await api.assignPaper(assignTarget.examId, assignIds));
      message.success(`已发送给 ${r.assigned} 名学生${r.skipped ? `（${r.skipped} 名之前已发送过）` : ''}`);
      setAssignIds([]);
      setAssignDetail(unwrap(await api.paperDetail(assignTarget.examId)));
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const doUnassign = async (studentId) => {
    try {
      await api.unassignPaper(assignTarget.examId, studentId);
      message.success('已撤销发送');
      setAssignDetail(unwrap(await api.paperDetail(assignTarget.examId)));
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const remove = async (paper) => {
    try {
      await api.deletePaper(paper.examId);
      message.success('卷子已删除');
      load();
    } catch (e) {
      message.error(e.message);
      throw e;
    }
  };

  const columns = [
    { title: '试卷名称', dataIndex: 'name', width: 320,
      render: (v, row) => (
        <Space direction="vertical" size={0}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            #{row.examId} · {row.subject || '—'} · {row.textbook || '—'} · {row.questionCount} 题 · {row.totalScore} 分
          </Typography.Text>
        </Space>
      ) },
    ...(isAdmin ? [{ title: '出卷教师', dataIndex: 'creatorName', width: 110 }] : []),
    {
      title: '发送情况', width: 210,
      render: (_, row) => (
        <Space size={4} wrap>
          <Tag icon={<TeamOutlined />}>{row.assignedCount} 人</Tag>
          {row.submittedCount - row.gradedCount > 0 && (
            <Badge count={row.submittedCount - row.gradedCount} size="small">
              <Tag color="orange">已交 {row.submittedCount}</Tag>
            </Badge>
          )}
          {row.submittedCount - row.gradedCount <= 0 && (
            <Tag color="blue">已交 {row.submittedCount}</Tag>
          )}
          <Tag color="green">已批 {row.gradedCount}</Tag>
        </Space>
      )
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 165,
      render: v => (v || '').replace('T', ' ').slice(0, 19) },
    {
      title: '操作', width: 110, render: (_, row) => (
        <RowActions label={row.name} primary={{ label: '批改', onClick: () => nav(`/exams/grading/${row.examId}`) }} items={[
          { key: 'send', label: '发送给学生', icon: <SendOutlined />, onClick: () => openAssign(row) },
          { key: 'delete', label: '删除试卷', icon: <DeleteOutlined />, danger: true,
            confirm: { title: `删除试卷「${row.name}」？` }, onClick: () => remove(row) }
        ]} />
      )
    }
  ];

  const assignedIds = (assignDetail?.assignments || []).map(a => a.studentId);
  const assignable = students.filter(s => !assignedIds.includes(s.id));

  return (
    <div>
      <Card>
        <PageToolbar filters={<>
          <span className="list-summary" style={{ marginBottom: 0 }}>共 <strong>{papers.length}</strong> 份试卷</span>
          {isAdmin && (
            <Select allowClear placeholder="按出卷教师筛选" style={{ width: 200 }}
              value={filterTeacher} onChange={setFilterTeacher}
              options={teachers.map(t => ({ value: t.id, label: t.displayName }))} />
          )}
        </>} actions={<><Button icon={<ReloadOutlined />} onClick={load}>刷新</Button><Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>创建试卷</Button></>} />
        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 18 }}>从题库选题，发送给指定学生；交卷后可在这里复核评分。</Typography.Paragraph>
        <Table rowKey="examId" loading={loading} dataSource={papers} scroll={{ x: isAdmin ? 920 : 810 }}
          columns={columns} pagination={{ pageSize: 8, hideOnSinglePage: true }} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有试卷，点击「创建试卷」开始" /> }} />
      </Card>

      {/* 组卷弹窗 */}
      <Modal title="组卷" open={createOpen} onOk={submitCreate} width={900}
        onCancel={() => setCreateOpen(false)} okText="创建卷子" destroyOnClose>
        <Form form={createForm} layout="vertical" initialValues={{ subject: '数学', textbook: '人教版' }}>
          <Space style={{ display: 'flex' }} align="start" wrap>
            <Form.Item name="name" label="卷子名称" rules={[{ required: true, message: '请输入卷子名称' }]}
              style={{ width: 300 }}>
              <Input placeholder="如 小数乘法 · 单元测验" />
            </Form.Item>
            <Form.Item name="subject" label="科目" style={{ width: 130 }}>
              <Select options={SUBJECTS.map(s => ({ value: s, label: s }))} />
            </Form.Item>
            <Form.Item name="textbook" label="教材" style={{ width: 150 }}>
              <Select options={TEXTBOOKS.map(s => ({ value: s, label: s }))} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="说明（学生可见）">
            <Input.TextArea rows={2} placeholder="如：限时 20 分钟，做完检查一遍" />
          </Form.Item>
        </Form>

        <Divider style={{ margin: '4px 0 12px' }}>
          从题库选题（已选 {picked.length} 道）
        </Divider>
        <Input prefix={<SearchOutlined />} placeholder="按题干关键词筛选" allowClear
          value={kw} onChange={e => setKw(e.target.value)} style={{ marginBottom: 8, width: 320 }} />
        <Table
          rowKey="id" size="small" loading={poolLoading}
          dataSource={filteredPool}
          pagination={{ pageSize: 6, showSizeChanger: false }}
          scroll={{ x: 760, y: 240 }}
          rowSelection={{
            selectedRowKeys: picked,
            onChange: (keys) => setPicked(keys),
            preserveSelectedRowKeys: true
          }}
          onRow={(record) => ({
            onClick: () => setPicked(prev => prev.includes(record.id)
              ? prev.filter(x => x !== record.id) : [...prev, record.id])
          })}
          columns={[
            { title: 'ID', dataIndex: 'id', width: 60 },
            { title: '题干', dataIndex: 'stem', ellipsis: true },
            { title: '题型', dataIndex: 'questionType', width: 80,
              render: t => <Tag color={QTYPE_COLOR[t]}>{QTYPE_LABEL[t] || t}</Tag> },
            { title: '分值', dataIndex: 'score', width: 70 },
            { title: '难度', dataIndex: 'difficulty', width: 70 }
          ]}
        />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          点行即可勾选/取消；跨页选择会保留。
        </Typography.Text>
      </Modal>

      {/* 指派弹窗 */}
      <Modal title={`发送给学生 · ${assignTarget?.name || ''}`} open={!!assignTarget}
        onOk={doAssign} okText="发送" onCancel={() => setAssignTarget(null)}
        width={680} destroyOnClose>
        <Select mode="multiple" style={{ width: '100%' }} placeholder="选择学生（可多选）"
          value={assignIds} onChange={setAssignIds}
          optionFilterProp="label" showSearch
          options={assignable.map(s => ({
            value: s.id,
            label: `${s.name}（${s.subject || '未复制图谱'}/${s.grade || '-'}）`
          }))}
          notFoundContent="没有可发送的学生了" />
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          已发送过的学生不会重复出现在这里。
        </Typography.Text>

        <Divider style={{ margin: '16px 0 8px' }}>已发送（{assignedIds.length} 人）</Divider>
        {(assignDetail?.assignments || []).length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有发送给任何学生" />
        ) : (
          <Table rowKey="studentId" size="small" pagination={false}
            dataSource={assignDetail.assignments}
            columns={[
              { title: '学生', dataIndex: 'studentName' },
              {
                title: '状态', dataIndex: 'status', width: 120,
                render: s => <Tag color={ASSIGN_STATUS[s]?.color}>{ASSIGN_STATUS[s]?.label || s}</Tag>
              },
              {
                title: '得分', width: 100,
                render: (_, r) => r.totalScore == null ? '—' : `${r.totalScore} / ${r.fullScore}`
              },
              {
                title: '操作', width: 100, render: (_, r) => (
                  <Popconfirm title={`撤销发给「${r.studentName}」？`}
                    onConfirm={() => doUnassign(r.studentId)}
                    disabled={r.status !== 'ASSIGNED'}>
                    <Tooltip title={r.status !== 'ASSIGNED' ? '已提交答卷，不能撤销' : ''}>
                      <Button type="link" size="small" danger
                        disabled={r.status !== 'ASSIGNED'}>撤销</Button>
                    </Tooltip>
                  </Popconfirm>
                )
              }
            ]} />
        )}
      </Modal>
    </div>
  );
}
