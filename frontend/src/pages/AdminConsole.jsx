import { useEffect, useState } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, Tabs, Modal, Form, Input, Select,
  Popconfirm, message, Switch, Statistic, Row, Col, Alert, Divider, Badge
} from 'antd';
import { PlusOutlined, ReloadOutlined, DatabaseOutlined, ThunderboltOutlined, KeyOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE, ROLE_LABEL, ROLE_COLOR } from '../auth.js';

const ROLE_OPTIONS = [
  { value: ROLE.ADMIN, label: '平台管理员' },
  { value: ROLE.ORG_ADMIN, label: '机构管理员' },
  { value: ROLE.CAMPUS_ADMIN, label: '校区管理员' },
  { value: ROLE.TEACHER, label: '教师' },
  { value: ROLE.STUDENT, label: '学生' }
];

/** 账号管理：建号、改密、启停、删除 */
function Accounts() {
  const me = readSession()?.user;
  const [rows, setRows] = useState([]);
  const [students, setStudents] = useState([]);
  const [orgs, setOrgs] = useState([]);
  const [campuses, setCampuses] = useState([]);
  const [pickOrgId, setPickOrgId] = useState(null);
  const [loading, setLoading] = useState(false);
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const [pwdTarget, setPwdTarget] = useState(null);
  const [pwdForm] = Form.useForm();
  const roleValue = Form.useWatch('role', form);

  const load = async () => {
    setLoading(true);
    try {
      setRows(unwrap(await api.listAccounts()) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    api.listStudents().then(r => setStudents(unwrap(r) || [])).catch(() => {});
    api.listOrgs().then(r => setOrgs(unwrap(r) || [])).catch(() => {});
  }, []);

  // 建号时需要选机构 → 校区（平台管理员才有机构下拉，其余落自己机构）
  useEffect(() => {
    if (!pickOrgId) { setCampuses([]); return; }
    api.listCampuses(pickOrgId).then(r => setCampuses(unwrap(r) || [])).catch(() => setCampuses([]));
  }, [pickOrgId]);

  const create = async () => {
    const values = await form.validateFields();
    try {
      await api.createAccount(values);
      message.success('账号已创建');
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
      await api.resetAccountPassword(pwdTarget.id, { newPassword: values.newPassword });
      message.success(`已重置 ${pwdTarget.username} 的密码，该账号需重新登录`);
      setPwdTarget(null);
      pwdForm.resetFields();
    } catch (e) {
      message.error(e.message);
    }
  };

  const toggle = async (row, enabled) => {
    try {
      await api.setAccountEnabled(row.id, enabled);
      message.success(enabled ? '已启用' : '已停用');
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const remove = async (row) => {
    try {
      await api.deleteAccount(row.id);
      message.success('账号已删除');
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const columns = [
    { title: 'ID', dataIndex: 'id', width: 60 },
    {
      title: '登录名', dataIndex: 'username', width: 150,
      render: (v, row) => (
        <Space size={6}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          {row.id === me?.id && <Tag color="geekblue">当前登录</Tag>}
        </Space>
      )
    },
    { title: '显示名', dataIndex: 'displayName', width: 130 },
    {
      title: '角色', dataIndex: 'role', width: 100,
      render: r => <Tag color={ROLE_COLOR[r]}>{ROLE_LABEL[r] || r}</Tag>
    },
    {
      title: '绑定学生', dataIndex: 'studentId', width: 140,
      render: (v) => {
        if (v == null) return <Typography.Text type="secondary">—</Typography.Text>;
        const s = students.find(x => x.id === v);
        return s ? `${s.name}（#${v}）` : `#${v}`;
      }
    },
    {
      title: '状态', dataIndex: 'enabled', width: 100,
      render: (v, row) => {
        const self = row.id === me?.id;
        return (
          <Switch size="small" checked={v === 1} disabled={self}
            checkedChildren="启用" unCheckedChildren="停用"
            onChange={(checked) => toggle(row, checked)} />
        );
      }
    },
    {
      title: '最后登录', dataIndex: 'lastLoginAt', width: 165,
      render: v => v ? String(v).replace('T', ' ').slice(0, 19)
        : <Typography.Text type="secondary">从未登录</Typography.Text>
    },
    {
      title: '操作', width: 150, render: (_, row) => (
        <Space size={2}>
          <Button type="link" size="small" icon={<KeyOutlined />}
            onClick={() => { pwdForm.resetFields(); setPwdTarget(row); }}>重置密码</Button>
          <Popconfirm title={`删除账号「${row.username}」？`} onConfirm={() => remove(row)}
            disabled={row.id === me?.id}>
            <Button type="link" size="small" danger disabled={row.id === me?.id}>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ];

  return (
    <Card size="small">
      <Space style={{ marginBottom: 12 }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { form.resetFields(); setOpen(true); }}>
          新建账号
        </Button>
        <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
      </Space>
      <Alert type="info" showIcon style={{ marginBottom: 12 }}
        message="学生账号建议在「学生管理 → 新建学生」时一并开户，那样会自动绑定学生档案；这里适合补建教师或管理员账号。" />
      <Table rowKey="id" size="small" loading={loading} dataSource={rows} columns={columns}
        pagination={{ pageSize: 10 }} />

      <Modal title="新建账号" open={open} onOk={create} onCancel={() => setOpen(false)} destroyOnClose>
        <Form form={form} layout="vertical" initialValues={{ role: ROLE.TEACHER }}>
          <Form.Item name="username" label="登录名"
            rules={[{ required: true, message: '请输入登录名' }, { min: 3, message: '至少 3 位' }]}>
            <Input placeholder="如 teacher2" />
          </Form.Item>
          <Form.Item name="password" label="初始密码"
            rules={[{ required: true, message: '请输入密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="6-64 位" />
          </Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true }]}>
            <Select options={ROLE_OPTIONS} />
          </Form.Item>
          <Form.Item name="displayName" label="显示名" rules={[{ required: true, message: '请输入显示名' }]}>
            <Input placeholder="如 王老师" />
          </Form.Item>
          {roleValue !== ROLE.ADMIN && (
            <>
              <Form.Item name="orgId" label="归属机构"
                extra="平台管理员跨机构，不需要归属；其余角色留空则落在操作者所属机构">
                <Select allowClear showSearch optionFilterProp="label" placeholder="选择机构"
                  options={orgs.map(o => ({ value: o.id, label: o.name }))}
                  onChange={(v) => setPickOrgId(v)} />
              </Form.Item>
              <Form.Item name="campusId" label="归属校区">
                <Select allowClear placeholder={pickOrgId ? '选择校区' : '请先选机构'}
                  disabled={!pickOrgId}
                  options={campuses.map(c => ({ value: c.id, label: c.name }))} />
              </Form.Item>
            </>
          )}
          {roleValue === ROLE.STUDENT && (
            <Form.Item name="studentId" label="绑定学生" rules={[{ required: true, message: '请选择要绑定的学生' }]}>
              <Select showSearch optionFilterProp="label" placeholder="选择学生档案"
                options={students.map(s => ({ value: s.id, label: `${s.name}（#${s.id}）` }))} />
            </Form.Item>
          )}
        </Form>
      </Modal>

      <Modal title={`重置密码 · ${pwdTarget?.username || ''}`} open={!!pwdTarget}
        onOk={resetPwd} onCancel={() => setPwdTarget(null)} destroyOnClose okText="确认重置">
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="newPassword" label="新密码"
            rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="6-64 位" />
          </Form.Item>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            重置后该账号所有已登录设备会立即掉线。
          </Typography.Text>
        </Form>
      </Modal>
    </Card>
  );
}

/** 系统运维：示例数据与向量索引 */
function Ops() {
  const [status, setStatus] = useState(null);
  const [busy, setBusy] = useState('');

  const checkStatus = async () => {
    try {
      setStatus(unwrap(await api.indexStatus()));
    } catch (e) {
      message.error(e.message);
    }
  };

  useEffect(() => { checkStatus(); }, []);

  const run = async (key, fn, okText) => {
    setBusy(key);
    try {
      const r = unwrap(await fn());
      message.success(okText);
      Modal.info({
        title: '执行结果',
        width: 460,
        content: <pre style={{ maxHeight: 320, overflow: 'auto', fontSize: 12 }}>
          {JSON.stringify(r, null, 2)}
        </pre>
      });
      checkStatus();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy('');
    }
  };

  return (
    <Card size="small">
      <Row gutter={16}>
        <Col span={8}>
          <Card size="small" title="向量索引状态">
            <Statistic title="语义检索" value={status?.available ? '可用' : '不可用'}
              valueStyle={{ color: status?.available ? 'var(--color-success)' : 'var(--color-warning)', fontSize: 22 }} />
            <Divider style={{ margin: '12px 0' }} />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              向量库不可用时系统自动降级为 SQL LIKE 检索，不影响启动。
            </Typography.Text>
          </Card>
        </Col>
        <Col span={16}>
          <Card size="small" title="运维操作">
            <Space direction="vertical" style={{ width: '100%' }} size={12}>
              <Space>
                <Button icon={<DatabaseOutlined />} loading={busy === 'seed'}
                  onClick={() => Modal.confirm({
                    title: '导入示例数据？',
                    content: '仅在空库可用：会写入小学数学五年级上图谱、题库与示例学生。',
                    onOk: () => run('seed', api.seedDemo, '示例数据已导入')
                  })}>
                  导入示例数据
                </Button>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  空库时使用；已有数据会被跳过
                </Typography.Text>
              </Space>
              <Space>
                <Button icon={<ThunderboltOutlined />} loading={busy === 'rebuild'}
                  onClick={() => run('rebuild', api.rebuildIndex, '向量索引已重建')}>
                  重建向量索引
                </Button>
                <Button icon={<ReloadOutlined />} onClick={checkStatus}>刷新状态</Button>
              </Space>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                重建会把全部知识点重新写入 Qdrant，知识点数量很大时耗时较长（当前为同步接口）。
              </Typography.Text>
            </Space>
          </Card>
        </Col>
      </Row>
    </Card>
  );
}

/** 教师管理：管理员视角看清每个老师出了多少卷、积压多少待批改，并管理其账号 */
function Teachers() {
  const me = readSession()?.user;
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [pwdTarget, setPwdTarget] = useState(null);
  const [pwdForm] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      setRows(unwrap(await api.teacherWorkload()) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const resetPwd = async () => {
    const values = await pwdForm.validateFields();
    try {
      await api.resetAccountPassword(pwdTarget.teacherId, { newPassword: values.newPassword });
      message.success(`已重置 ${pwdTarget.username} 的密码，该账号需重新登录`);
      setPwdTarget(null);
      pwdForm.resetFields();
    } catch (e) {
      message.error(e.message);
    }
  };

  const toggle = async (row, enabled) => {
    try {
      await api.setAccountEnabled(row.teacherId, enabled);
      message.success(enabled ? '已启用' : '已停用');
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const totals = rows.reduce((acc, r) => ({
    papers: acc.papers + r.paperCount,
    assigned: acc.assigned + r.assignedCount,
    pending: acc.pending + r.pendingCount,
    graded: acc.graded + r.gradedCount
  }), { papers: 0, assigned: 0, pending: 0, graded: 0 });

  const columns = [
    {
      title: '教师', dataIndex: 'displayName', width: 150,
      render: (v, row) => (
        <Space size={6}>
          <span style={{ fontWeight: 600 }}>{v}</span>
          {row.teacherId === me?.id && <Tag color="geekblue">当前登录</Tag>}
        </Space>
      )
    },
    { title: '登录名', dataIndex: 'username', width: 130 },
    { title: '出卷', dataIndex: 'paperCount', width: 80,
      render: v => <Tag color="blue">{v} 份</Tag> },
    { title: '已发送', dataIndex: 'assignedCount', width: 90,
      render: v => `${v} 人次` },
    {
      title: '待批改', dataIndex: 'pendingCount', width: 100,
      render: v => v > 0
        ? <Badge count={v} size="small"><span style={{ color: 'var(--color-warning)' }}>积压</span></Badge>
        : <Typography.Text type="secondary">无</Typography.Text>
    },
    { title: '已批改', dataIndex: 'gradedCount', width: 90,
      render: v => <span style={{ color: 'var(--color-success)' }}>{v} 人次</span> },
    {
      title: '状态', dataIndex: 'enabled', width: 100,
      render: (v, row) => (
        <Switch size="small" checked={v === 1} disabled={row.teacherId === me?.id}
          checkedChildren="启用" unCheckedChildren="停用"
          onChange={(checked) => toggle(row, checked)} />
      )
    },
    { title: '最后登录', dataIndex: 'lastLoginAt', width: 165,
      render: v => v ? String(v).replace('T', ' ').slice(0, 19)
        : <Typography.Text type="secondary">从未登录</Typography.Text> },
    {
      title: '操作', width: 110, render: (_, row) => (
        <Button type="link" size="small" icon={<KeyOutlined />}
          onClick={() => { pwdForm.resetFields(); setPwdTarget(row); }}>重置密码</Button>
      )
    }
  ];

  return (
    <div>
      <Row gutter={16} style={{ marginBottom: 12 }}>
        <Col span={6}><Card size="small"><Statistic title="教师数" value={rows.length} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="卷子总数" value={totals.papers} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="已发送人次" value={totals.assigned} /></Card></Col>
        <Col span={6}>
          <Card size="small">
            <Statistic title="待批改积压" value={totals.pending}
              valueStyle={{ color: totals.pending > 0 ? 'var(--color-warning)' : 'var(--color-success)' }} />
          </Card>
        </Col>
      </Row>
      <Card size="small" title="各教师工作量与批改进度"
        extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="管理员可以查看并管理全部教师；教师本人只能看自己出的卷子。这里是跨教师的汇总视角。" />
        <Table rowKey="teacherId" size="small" loading={loading} dataSource={rows}
          columns={columns} pagination={{ pageSize: 10 }} />
      </Card>

      <Modal title={`重置密码 · ${pwdTarget?.username || ''}`} open={!!pwdTarget}
        onOk={resetPwd} onCancel={() => setPwdTarget(null)} destroyOnClose okText="确认重置">
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="newPassword" label="新密码"
            rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="6-64 位" />
          </Form.Item>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            重置后该账号所有已登录设备会立即掉线。
          </Typography.Text>
        </Form>
      </Modal>
    </div>
  );
}

export default function AdminConsole() {
  return (
    <Tabs
      defaultActiveKey="accounts"
      items={[
        { key: 'accounts', label: '账号管理', children: <Accounts /> },
        { key: 'teachers', label: '教师管理', children: <Teachers /> },
        { key: 'ops', label: '系统运维', children: <Ops /> }
      ]}
    />
  );
}
