import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message, Card, Alert } from 'antd';
import { PlusOutlined, EditOutlined, CloudDownloadOutlined, SyncOutlined, CopyOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];
const TEXTBOOKS = ['人教版', '北师大版', '苏教版', '沪教版'];
const GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三'];
const TERMS = ['上学期', '下学期'];

export default function GraphList() {
  const nav = useNavigate();
  // 导入示例数据 / 重建向量索引是管理员接口，教师看不到入口（后端仍会拦）
  const isAdmin = readSession()?.user?.role === ROLE.ADMIN;
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [copySrc, setCopySrc] = useState(null);
  const [copyForm] = Form.useForm();

  const load = async (params = {}) => {
    setLoading(true);
    try {
      const r = await api.listGraphs(params);
      setRows(unwrap(r) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const onCreate = async () => {
    const values = await form.validateFields();
    await api.createGraph(values);
    message.success('创建成功');
    setOpen(false);
    form.resetFields();
    load();
  };

  const openCopy = (row) => {
    setCopySrc(row);
    copyForm.setFieldsValue({
      name: `${row.name}（副本）`,
      subject: row.subject, textbook: row.textbook, grade: row.grade, term: row.term,
      description: row.description
    });
  };

  const onCopy = async () => {
    const values = await copyForm.validateFields();
    try {
      const r = await api.copyGraph(copySrc.id, values);
      const d = unwrap(r) || {};
      message.success(`已复制：${d.nodeCount} 个知识点、${d.relationCount} 条关系`);
      setCopySrc(null);
      copyForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    { title: '图谱名称', dataIndex: 'name' },
    { title: '学科', dataIndex: 'subject', width: 80 },
    { title: '教材', dataIndex: 'textbook', width: 90 },
    { title: '年级', dataIndex: 'grade', width: 90 },
    { title: '学期', dataIndex: 'term', width: 90 },
    {
      title: '操作', width: 280, render: (_, row) => (
        <Space>
          <Button type="link" icon={<EditOutlined />} onClick={() => nav(`/graphs/${row.id}`)}>
            编辑图谱
          </Button>
          <Button type="link" icon={<CopyOutlined />} onClick={() => openCopy(row)}>
            复制
          </Button>
          <Popconfirm title="删除图谱会级联删除节点、关系及关联，确认？" onConfirm={async () => {
            await api.deleteGraph(row.id);
            message.success('已删除');
            load();
          }}>
            <Button type="link" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <Card>
      <Space style={{ marginBottom: 16 }} wrap>
        <Select allowClear placeholder="学科" style={{ width: 110 }} options={SUBJECTS.map(v => ({ value: v }))}
          onChange={v => load(v !== undefined ? { subject: v } : {})} />
        <Select allowClear placeholder="教材" style={{ width: 110 }} options={TEXTBOOKS.map(v => ({ value: v }))}
          onChange={v => load(v !== undefined ? { textbook: v } : {})} />
        <Select allowClear placeholder="年级" style={{ width: 110 }} options={GRADES.map(v => ({ value: v }))}
          onChange={v => load(v !== undefined ? { grade: v } : {})} />
        <Select allowClear placeholder="学期" style={{ width: 110 }} options={TERMS.map(v => ({ value: v }))}
          onChange={v => load(v !== undefined ? { term: v } : {})} />
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建图谱</Button>
        {isAdmin && rows.length === 0 && (
          <Button icon={<CloudDownloadOutlined />} onClick={async () => {
            try {
              const r = await api.seedDemo();
              const d = unwrap(r) || {};
              message.success(`示例数据导入成功：${d.nodes} 个知识点、${d.questions} 道题、${d.students} 个学生`);
              load();
            } catch (e) {
              message.error(e.message);
            }
          }}>导入示例数据</Button>
        )}
        {isAdmin && (
          <Button icon={<SyncOutlined />} onClick={async () => {
            try {
              const r = await api.rebuildIndex();
              const d = unwrap(r) || {};
              message.success(`向量索引重建完成：${d.indexedNodes} 个节点`);
            } catch (e) {
              message.error(e.message);
            }
          }}>重建向量索引</Button>
        )}
      </Space>
      {isAdmin && rows.length === 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }}
          message="当前是空库"
          description="点击「导入示例数据」可一键载入小学数学五年级上（人教版）的完整示例：12 个知识点、16 条关系、27 道题、1 名学生（含初始掌握度），并自动建立向量索引。" />
      )}
      <Table rowKey="id" loading={loading} dataSource={rows} columns={columns} pagination={{ pageSize: 10 }} />
      <Modal title="新建图谱" open={open} onOk={onCreate} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" initialValues={{ subject: '数学', textbook: '人教版', grade: '五年级', term: '上学期' }}>
          <Form.Item name="name" label="图谱名称" rules={[{ required: true }]}>
            <Input placeholder="如：小学数学五年级上（人教版）" />
          </Form.Item>
          <Space wrap>
            <Form.Item name="subject" label="学科" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={SUBJECTS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="textbook" label="教材" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={TEXTBOOKS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="grade" label="年级" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={GRADES.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="term" label="学期" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={TERMS.map(v => ({ value: v }))} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal title={`复制图谱 —— 基于「${copySrc?.name ?? ''}」`} open={!!copySrc}
        onOk={onCopy} onCancel={() => setCopySrc(null)} okText="复制" destroyOnHidden width={560}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="节点、坐标、权重、编码与全部关系都会原样复制到新的维度下；题目按「学科+教材」归属，不随图谱复制。" />
        <Form form={copyForm} layout="vertical">
          <Form.Item name="name" label="新图谱名称" rules={[{ required: true }]}>
            <Input placeholder="如：小学数学五年级下（人教版）" />
          </Form.Item>
          <Space wrap>
            <Form.Item name="subject" label="学科" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={SUBJECTS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="textbook" label="教材" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={TEXTBOOKS.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="grade" label="年级" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={GRADES.map(v => ({ value: v }))} />
            </Form.Item>
            <Form.Item name="term" label="学期" rules={[{ required: true }]}>
              <Select style={{ width: 120 }} options={TERMS.map(v => ({ value: v }))} />
            </Form.Item>
          </Space>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  );
}
