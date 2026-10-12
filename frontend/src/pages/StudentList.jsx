import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message, Tag, Typography
} from 'antd';
import { PlusOutlined, RadarChartOutlined, KeyOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];
const TEXTBOOKS = ['人教版', '北师大版', '苏教版', '沪教版'];
const GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三'];
const TERMS = ['上学期', '下学期'];

export default function StudentList() {
  const nav = useNavigate();
  const [rows, setRows] = useState([]);
  const [clazzes, setClazzes] = useState([]);
  const [loading, setLoading] = useState(false);
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
    }
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
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '姓名', dataIndex: 'name', width: 110 },
    {
      title: '班级', dataIndex: 'clazzName', width: 120,
      render: (v) => v || <span style={{ color: '#999' }}>未分班</span>
    },
    {
      title: '登录名', dataIndex: 'username', width: 140,
      render: (v, row) => v ? (
        <Space size={6}>
          <span>{v}</span>
          {row.accountEnabled === 0 && <Tag color="red">已停用</Tag>}
        </Space>
      ) : <Typography.Text type="warning">未开户</Typography.Text>
    },
    { title: '学科', dataIndex: 'subject', width: 80 },
    { title: '教材', dataIndex: 'textbook', width: 90 },
    { title: '年级', dataIndex: 'grade', width: 90 },
    { title: '学期', dataIndex: 'term', width: 90 },
    { title: '图谱', dataIndex: 'graphName', render: v => v || <span style={{ color: '#999' }}>未复制</span> },
    {
      title: '操作', width: 260, render: (_, row) => (
        <Space size={2}>
          <Button type="link" size="small" icon={<RadarChartOutlined />}
            onClick={() => nav(`/students/${row.id}`)} disabled={!row.graphId}>
            掌握度视图
          </Button>
          <Button type="link" size="small" icon={<KeyOutlined />} disabled={!row.username}
            onClick={() => { pwdForm.resetFields(); setPwdTarget(row); }}>
            重置密码
          </Button>
          <Popconfirm title="删除学生及其图谱副本、掌握度数据与登录账号？" onConfirm={async () => {
            try {
              await api.deleteStudent(row.id);
              message.success('已删除');
              load();
            } catch (e) {
              message.error(e.message);
            }
          }}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <Card>
      <Space style={{ marginBottom: 16 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
          新建学生（复制图谱 + 开户）
        </Button>
      </Space>
      <Table rowKey="id" loading={loading} dataSource={rows} columns={columns} pagination={{ pageSize: 10 }} />

      <Modal title="新建学生" open={open} onOk={onCreate} onCancel={() => setOpen(false)} destroyOnClose>
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
          <div style={{ color: '#999', fontSize: 12, marginBottom: 16 }}>
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
          <div style={{ color: '#999', fontSize: 12, margin: '4px 0 12px' }}>
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
