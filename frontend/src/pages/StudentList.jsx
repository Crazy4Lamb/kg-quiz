import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Modal, Form, Input, Select, Space, message, Tag, Typography
} from 'antd';
import { PlusOutlined, UserOutlined, SearchOutlined, KeyOutlined, DeleteOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import PageToolbar from '../components/PageToolbar.jsx';
import RowActions from '../components/RowActions.jsx';

const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];
const TEXTBOOKS = ['人教版', '北师大版', '苏教版', '沪教版'];
const GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三'];
const TERMS = ['上学期', '下学期'];

export default function StudentList() {
  const nav = useNavigate();
  const [rows, setRows] = useState([]);
  const [clazzes, setClazzes] = useState([]);
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [query, setQuery] = useState('');
  const [classFilter, setClassFilter] = useState();
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [pwdTarget, setPwdTarget] = useState(null);
  const [pwdForm] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setRows(unwrap(await api.listStudents()) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // 班级用于把学生落到组织树上；教师只会拿到自己带的班
    api.listClazzes({}).then(r => setClazzes(unwrap(r) || [])).catch(() => {});
  }, []);

  const onCreate = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      const d = unwrap(await api.createStudent(values));
      message.success(
        `已创建学生，复制 ${d?.copiedNodes ?? 0} 个知识点；登录账号 ${d?.username} / ${d?.initPassword}`,
        8
      );
      setOpen(false);
      form.resetFields();
      load();
    } catch (e) {
      message.error(e.message);
    } finally { setSaving(false); }
  };

  const resetPwd = async () => {
    const values = await pwdForm.validateFields();
    try {
      const d = unwrap(await api.resetStudentPassword(pwdTarget.id, { password: values.password }));
      message.success(`${d.username} 的密码已重置为 ${d.password}`, 8);
      setPwdTarget(null);
      pwdForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const columns = [
    { title: '学生', dataIndex: 'name', width: 180, render: (name, row) => (
      <div className="entity-cell"><span className="entity-icon"><UserOutlined /></span><div>
        <span className="row-title">{name}</span>{row.accountEnabled === 0 && <Tag color="red" style={{ marginLeft: 6 }}>已停用</Tag>}
        <span className="row-meta">{row.username || '未开户'} · #{row.id}</span>
      </div></div>
    ) },
    {
      title: '班级', dataIndex: 'clazzName', width: 130,
      render: (v) => v || <span style={{ color: 'var(--color-muted)' }}>未分班</span>
    },
    { title: '学习图谱', dataIndex: 'graphName', width: 320, render: (name, row) => (
      <div><span>{name || '未复制图谱'}</span><span className="row-meta">{[row.subject, row.textbook, row.grade, row.term].filter(Boolean).join(' · ')}</span></div>
    ) },
    {
      title: '操作', width: 130, render: (_, row) => (
        <RowActions label={row.name} primary={{ label: '查看学情', disabled: !row.graphId, onClick: () => nav(`/students/${row.id}`) }} items={[
          { key: 'password', label: '重置密码', icon: <KeyOutlined />, disabled: !row.username,
            onClick: () => { pwdForm.resetFields(); setPwdTarget(row); } },
          { key: 'delete', label: '删除学生', icon: <DeleteOutlined />, danger: true,
            confirm: { title: `删除学生「${row.name}」？`, content: '学生的个人图谱、掌握度数据与登录账号会一并删除。' },
            onClick: async () => {
              try { await api.deleteStudent(row.id); message.success('已删除'); load(); }
              catch (e) { message.error(e.message); throw e; }
            } }
        ]} />
      )
    }
  ];

  const normalizedQuery = query.trim().toLowerCase();
  const visibleRows = rows.filter(row => (!classFilter || row.clazzName === classFilter) &&
    (!normalizedQuery || [row.name, row.username, row.clazzName].some(value => String(value || '').toLowerCase().includes(normalizedQuery))));
  const classOptions = [...new Set(rows.map(row => row.clazzName).filter(Boolean))].map(value => ({ value, label: value }));

  return (
    <Card>
      <PageToolbar filters={<>
        <Input className="toolbar-search" prefix={<SearchOutlined />} placeholder="搜索姓名、账号或班级" aria-label="搜索学生" allowClear value={query} onChange={e => setQuery(e.target.value)} />
        <Select aria-label="筛选班级" placeholder="全部班级" allowClear options={classOptions} value={classFilter} onChange={setClassFilter} />
      </>} actions={
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          新建学生
        </Button>
      } />
      <div className="list-summary">共 <strong>{visibleRows.length}</strong> 位学生{(normalizedQuery || classFilter) && ' · 已筛选'}</div>
      <Table rowKey="id" loading={loading} dataSource={visibleRows} columns={columns} scroll={{ x: 760 }} pagination={{ pageSize: 10, hideOnSinglePage: true }} />

      <Modal title="新建学生" open={open} onOk={onCreate} confirmLoading={saving} okText="创建学生" onCancel={() => setOpen(false)} destroyOnHidden>
        <Form form={form} layout="vertical"
          initialValues={{ subject: '数学', textbook: '人教版', grade: '五年级', term: '上学期' }}>
          <Form.Item name="name" label="学生姓名" rules={[{ required: true }]}>
            <Input placeholder="如：小明" />
          </Form.Item>
          <Form.Item label="选择教材（复制对应知识图谱）" required style={{ marginBottom: 4 }}>
            <Space wrap>
              <Form.Item name="subject" noStyle rules={[{ required: true }]}>
                <Select style={{ width: 110 }} options={SUBJECTS.map(v => ({ value: v }))} />
              </Form.Item>
              <Form.Item name="textbook" noStyle rules={[{ required: true }]}>
                <Select style={{ width: 110 }} options={TEXTBOOKS.map(v => ({ value: v }))} />
              </Form.Item>
              <Form.Item name="grade" noStyle rules={[{ required: true }]}>
                <Select style={{ width: 110 }} options={GRADES.map(v => ({ value: v }))} />
              </Form.Item>
              <Form.Item name="term" noStyle rules={[{ required: true }]}>
                <Select style={{ width: 110 }} options={TERMS.map(v => ({ value: v }))} />
              </Form.Item>
            </Space>
          </Form.Item>
          <div style={{ color: 'var(--color-muted)', fontSize: 12, marginBottom: 16 }}>
            需先存在对应维度的图谱，新建后将复制一份作为该生的个人图谱（含节点权重，掌握度初始为 0）
          </div>

          <Form.Item name="clazzId" label="所属班级"
            extra="在机构管理平台里维护；选中后学生与账号会一并落到该班级">
            <Select allowClear showSearch optionFilterProp="label" placeholder="可留空，稍后在机构管理里转班"
              options={clazzes.map(c => ({
                value: c.id,
                label: `${c.campusName || ''} ${c.name}`.trim()
              }))} />
          </Form.Item>

          <Typography.Text strong style={{ fontSize: 13 }}>登录账号（选填）</Typography.Text>
          <div style={{ color: 'var(--color-muted)', fontSize: 12, margin: '4px 0 12px' }}>
            不填则自动生成 <code>stu{'{学号}'}</code>，初始密码 123456。学生用该账号登录后只能看到自己的图谱、练习与错题本。
          </div>
          <Form.Item name="username" label="登录名"
            rules={[{ min: 3, message: '至少 3 位' }, { pattern: /^[A-Za-z0-9_]*$/, message: '仅支持字母、数字与下划线' }]}>
            <Input placeholder="留空自动生成，如 xiaoming" />
          </Form.Item>
          <Form.Item name="password" label="初始密码"
            rules={[{ min: 6, message: '至少 6 位' }]}>
            <Input placeholder="留空则为 123456" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`重置密码 · ${pwdTarget?.name || ''}（${pwdTarget?.username || ''}）`}
        open={!!pwdTarget} onOk={resetPwd} onCancel={() => setPwdTarget(null)} destroyOnClose okText="确认重置">
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="password" label="新密码" initialValue="123456"
            rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="6-64 位" />
          </Form.Item>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            重置后该学生所有已登录设备会立即掉线。
          </Typography.Text>
        </Form>
      </Modal>
    </Card>
  );
}
