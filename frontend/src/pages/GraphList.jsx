import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Table, Button, Modal, Form, Input, Select, Space, Dropdown, message, Card, Alert, Empty, Tag } from 'antd';
import { PlusOutlined, ApartmentOutlined, CloudDownloadOutlined, SyncOutlined, CopyOutlined, DeleteOutlined, MoreOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';
import PageToolbar from '../components/PageToolbar.jsx';
import RowActions from '../components/RowActions.jsx';

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
  const [saving, setSaving] = useState(false);
  const [filters, setFilters] = useState({});
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [copySrc, setCopySrc] = useState(null);
  const [copyForm] = Form.useForm();
  const loadId = useRef(0);

  const load = async (params = filters) => {
    const requestId = ++loadId.current;
    setLoading(true);
    try {
      const r = await api.listGraphs(params);
      if (requestId === loadId.current) {
        setRows((unwrap(r) || []).filter(row => ['subject', 'textbook', 'grade', 'term'].every(key => !params[key] || row[key] === params[key])));
      }
    } catch (e) {
      if (requestId === loadId.current) message.error(e.message);
    } finally {
      if (requestId === loadId.current) setLoading(false);
    }
  };

  useEffect(() => { load(filters); }, [filters]);

  const onCreate = async () => {
    const values = await form.validateFields();
    setSaving(true);
    try {
      await api.createGraph(values);
      message.success('创建成功');
      setOpen(false);
      form.resetFields();
      load();
    } catch (e) { message.error(e.message); }
    finally { setSaving(false); }
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
    setSaving(true);
    try {
      const r = await api.copyGraph(copySrc.id, values);
      const d = unwrap(r) || {};
      message.success(`已复制：${d.nodeCount} 个知识点、${d.relationCount} 条关系`);
      setCopySrc(null);
      copyForm.resetFields();
      load();
    } catch (e) {
      message.error(e.message);
    } finally { setSaving(false); }
  };

  const maintenance = async ({ key }) => {
    try {
      if (key === 'seed') {
        const d = unwrap(await api.seedDemo()) || {};
        message.success(`示例数据导入成功：${d.nodes} 个知识点、${d.questions} 道题、${d.students} 个学生`);
        load();
      } else {
        const d = unwrap(await api.rebuildIndex()) || {};
        message.success(`向量索引重建完成：${d.indexedNodes} 个节点`);
      }
    } catch (e) { message.error(e.message); }
  };
  const hasFilters = Object.values(filters).some(Boolean);
  const changeFilter = (key, value) => setFilters((current) => ({ ...current, [key]: value }));

  const columns = [
    { title: '图谱名称', dataIndex: 'name', width: 340, render: (name, row) => (
      <div className="entity-cell"><span className="entity-icon"><ApartmentOutlined /></span><div>
        <span className="row-title">{name}</span><span className="row-meta">图谱 #{row.id}</span>
      </div></div>
    ) },
    { title: '学科', dataIndex: 'subject', width: 90, render: (value) => <Tag>{value}</Tag> },
    { title: '教材与学段', width: 220, render: (_, row) => (
      <div><span>{row.textbook} · {row.grade}</span><span className="row-meta">{row.term}</span></div>
    ) },
    {
      title: '操作', width: 110, render: (_, row) => (
        <RowActions label={row.name} primary={{ label: '编辑', onClick: () => nav(`/graphs/${row.id}`) }} items={[
          { key: 'copy', label: '复制图谱', icon: <CopyOutlined />, onClick: () => openCopy(row) },
          { key: 'delete', label: '删除图谱', icon: <DeleteOutlined />, danger: true,
            confirm: { title: '删除这张图谱？', content: '图谱中的知识点、关系和关联数据会一并删除。' },
            onClick: async () => {
              try { await api.deleteGraph(row.id); message.success('已删除'); load(); }
              catch (e) { message.error(e.message); throw e; }
            } }
        ]} />
      )
    }
  ];

  return (
    <Card>
      <PageToolbar filters={<>
        <Select aria-label="筛选学科" allowClear placeholder="全部学科" value={filters.subject} options={SUBJECTS.map(v => ({ value: v }))} onChange={v => changeFilter('subject', v)} />
        <Select aria-label="筛选教材" allowClear placeholder="全部教材" value={filters.textbook} options={TEXTBOOKS.map(v => ({ value: v }))} onChange={v => changeFilter('textbook', v)} />
        <Select aria-label="筛选年级" allowClear placeholder="全部年级" value={filters.grade} options={GRADES.map(v => ({ value: v }))} onChange={v => changeFilter('grade', v)} />
        <Select aria-label="筛选学期" allowClear placeholder="全部学期" value={filters.term} options={TERMS.map(v => ({ value: v }))} onChange={v => changeFilter('term', v)} />
        {hasFilters && <Button type="text" onClick={() => setFilters({})}>重置</Button>}
      </>} actions={<>
        {isAdmin && <Dropdown trigger={['click']} menu={{ items: [
          ...(!hasFilters && rows.length === 0 ? [{ key: 'seed', label: '导入示例数据', icon: <CloudDownloadOutlined /> }] : []),
          { key: 'index', label: '重建向量索引', icon: <SyncOutlined /> }
        ], onClick: maintenance }}><Button icon={<MoreOutlined />}>更多</Button></Dropdown>}
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建图谱</Button>
      </>} />
      <div className="list-summary">共 <strong>{rows.length}</strong> 张图谱{hasFilters && ' · 已筛选'}</div>
      {isAdmin && !hasFilters && rows.length === 0 && (
        <Alert type="info" showIcon style={{ marginBottom: 16 }}
          message="当前是空库"
          description="点击「导入示例数据」可一键载入小学数学五年级上（人教版）的完整示例：12 个知识点、16 条关系、27 道题、1 名学生（含初始掌握度），并自动建立向量索引。" />
      )}
      <Table rowKey="id" loading={loading} dataSource={rows} columns={columns} scroll={{ x: 760 }}
        pagination={{ pageSize: 10, hideOnSinglePage: true }} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={hasFilters ? '没有符合筛选条件的图谱' : '暂无图谱，点击「新建图谱」开始'} /> }} />
      <Modal title="新建图谱" open={open} onOk={onCreate} confirmLoading={saving} onCancel={() => setOpen(false)} destroyOnHidden okText="创建图谱">
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
        onOk={onCopy} confirmLoading={saving} onCancel={() => setCopySrc(null)} okText="复制" destroyOnHidden width={560}>
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
