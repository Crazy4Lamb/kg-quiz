import { useCallback, useEffect, useState } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message, Tag, Typography,
  Tabs, Row, Col, Statistic
} from 'antd';
import {
  PlusOutlined, EditOutlined, KeyOutlined, DeleteOutlined, ReloadOutlined, BankOutlined,
  ClusterOutlined, TeamOutlined, UserOutlined, SwapOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

const GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三'];
const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];

const statusTag = (s) => (s === 'SUSPENDED'
  ? <Tag color="orange">已停用</Tag>
  : <Tag color="green">正常</Tag>);

/**
 * 机构管理平台：机构 → 校区 → 班级 → 教师 / 学生。
 *
 * <p>平台管理员可切换机构、可建机构；机构管理员锁定在自己机构，机构 Tab 变为只读概览。
 * 后端对所有写操作再次校验机构边界，前端的禁用只是体验优化。</p>
 */
export default function OrgConsole() {
  const { user } = readSession() || {};
  const isPlatformAdmin = user?.role === ROLE.ADMIN;

  const [orgs, setOrgs] = useState([]);
  const [orgId, setOrgId] = useState(null);
  const [overview, setOverview] = useState(null);
  const [campuses, setCampuses] = useState([]);
  const [clazzes, setClazzes] = useState([]);
  const [teachers, setTeachers] = useState([]);
  const [students, setStudents] = useState([]);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState(isPlatformAdmin ? 'org' : 'campus');

  // 弹窗
  const [orgModal, setOrgModal] = useState(null);       // {mode, row}
  const [campusModal, setCampusModal] = useState(null);
  const [clazzModal, setClazzModal] = useState(null);
  const [teacherModal, setTeacherModal] = useState(null);
  const [moveModal, setMoveModal] = useState(null);
  const [pwdTarget, setPwdTarget] = useState(null);

  const [orgForm] = Form.useForm();
  const [campusForm] = Form.useForm();
  const [clazzForm] = Form.useForm();
  const [teacherForm] = Form.useForm();
  const [moveForm] = Form.useForm();
  const [pwdForm] = Form.useForm();

  const loadOrgs = useCallback(async () => {
    const list = unwrap(await api.listOrgs()) || [];
    setOrgs(list);
    setOrgId((prev) => {
      if (prev && list.some((o) => o.id === prev)) return prev;
      return list.length ? list[0].id : null;
    });
  }, []);

  const loadDetail = useCallback(async (oid) => {
    if (!oid) {
      setOverview(null); setCampuses([]); setClazzes([]); setTeachers([]); setStudents([]);
      return;
    }
    setLoading(true);
    try {
      const [ov, cp, cz, tc, st] = await Promise.all([
        api.orgOverview(oid).then(unwrap).catch(() => null),
        api.listCampuses(oid).then(unwrap).catch(() => []),
        api.listClazzes({ orgId: oid }).then(unwrap).catch(() => []),
        api.listTeachers({ orgId: oid }).then(unwrap).catch(() => []),
        api.listOrgStudents(oid).then(unwrap).catch(() => [])
      ]);
      setOverview(ov);
      setCampuses(cp || []);
      setClazzes(cz || []);
      setTeachers(tc || []);
      setStudents(st || []);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadOrgs().catch((e) => message.error(e.message)); }, [loadOrgs]);
  useEffect(() => { loadDetail(orgId).catch((e) => message.error(e.message)); }, [orgId, loadDetail]);

  const reload = async () => {
    await loadOrgs();
    await loadDetail(orgId);
  };

  // ---------------- 机构 ----------------
  const submitOrg = async () => {
    const v = await orgForm.validateFields();
    try {
      if (orgModal.mode === 'create') {
        await api.createOrg(v);
        message.success('机构已创建');
      } else {
        await api.updateOrg(orgModal.row.id, v);
        message.success('机构已更新');
      }
      setOrgModal(null);
      orgForm.resetFields();
      reload();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------------- 校区 ----------------
  const submitCampus = async () => {
    const v = await campusForm.validateFields();
    try {
      if (campusModal.mode === 'create') {
        await api.createCampus({ ...v, orgId });
        message.success('校区已创建');
      } else {
        await api.updateCampus(campusModal.row.id, v);
        message.success('校区已更新');
      }
      setCampusModal(null);
      campusForm.resetFields();
      reload();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------------- 班级 ----------------
  const submitClazz = async () => {
    const v = await clazzForm.validateFields();
    try {
      if (clazzModal.mode === 'create') {
        await api.createClazz(v);
        message.success('班级已创建');
      } else {
        await api.updateClazz(clazzModal.row.id, v);
        message.success('班级已更新');
      }
      setClazzModal(null);
      clazzForm.resetFields();
      reload();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------------- 教师 ----------------
  const submitTeacher = async () => {
    const v = await teacherForm.validateFields();
    try {
      if (teacherModal.mode === 'create') {
        await api.createTeacher(v);
        message.success('教师已创建');
      } else {
        await api.updateTeacher(teacherModal.row.userId, v);
        message.success('教师已更新');
      }
      setTeacherModal(null);
      teacherForm.resetFields();
      reload();
    } catch (e) {
      message.error(e.message);
    }
  };

  const columns = {
    org: [
      { title: 'ID', dataIndex: 'id', width: 60 },
      { title: '机构名称', dataIndex: 'name' },
      { title: '编码', dataIndex: 'code', width: 110, render: (v) => v || '—' },
      { title: '联系人', dataIndex: 'contactName', width: 100, render: (v) => v || '—' },
      { title: '联系电话', dataIndex: 'contactPhone', width: 130, render: (v) => v || '—' },
      { title: '校区', dataIndex: 'campusCount', width: 70 },
      { title: '班级', dataIndex: 'clazzCount', width: 70 },
      { title: '教师', dataIndex: 'teacherCount', width: 70 },
      { title: '学生', dataIndex: 'studentCount', width: 70 },
      { title: '状态', dataIndex: 'status', width: 90, render: statusTag },
      {
        title: '操作', width: 190, render: (_, row) => (
          <Space size={2}>
            <Button type="link" size="small" onClick={() => setOrgId(row.id)}>进入</Button>
            <Button type="link" size="small" icon={<EditOutlined />}
              onClick={() => { orgForm.setFieldsValue(row); setOrgModal({ mode: 'edit', row }); }}>
              编辑
            </Button>
            <Popconfirm title={row.status === 'ACTIVE' ? '停用该机构？' : '恢复该机构？'}
              onConfirm={async () => {
                try {
                  await api.setOrgStatus(row.id, row.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE');
                  message.success('状态已更新');
                  reload();
                } catch (e) { message.error(e.message); }
              }}>
              <Button type="link" size="small" danger={row.status === 'ACTIVE'}>
                {row.status === 'ACTIVE' ? '停用' : '恢复'}
              </Button>
            </Popconfirm>
          </Space>
        )
      }
    ],
    campus: [
      { title: 'ID', dataIndex: 'id', width: 60 },
      { title: '校区名称', dataIndex: 'name' },
      { title: '编码', dataIndex: 'code', width: 110, render: (v) => v || '—' },
      { title: '地址', dataIndex: 'address', render: (v) => v || '—' },
      { title: '班级数', dataIndex: 'clazzCount', width: 80 },
      { title: '学生数', dataIndex: 'studentCount', width: 80 },
      {
        title: '操作', width: 150, render: (_, row) => (
          <Space size={2}>
            <Button type="link" size="small" icon={<EditOutlined />}
              onClick={() => { campusForm.setFieldsValue(row); setCampusModal({ mode: 'edit', row }); }}>
              编辑
            </Button>
            <Popconfirm title="删除该校区？" onConfirm={async () => {
              try { await api.deleteCampus(row.id); message.success('已删除'); reload(); }
              catch (e) { message.error(e.message); }
            }}>
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </Space>
        )
      }
    ],
    clazz: [
      { title: 'ID', dataIndex: 'id', width: 60 },
      { title: '班级', dataIndex: 'name' },
      { title: '校区', dataIndex: 'campusName', width: 120, render: (v) => v || '—' },
      { title: '年级', dataIndex: 'grade', width: 80, render: (v) => v || '—' },
      { title: '科目', dataIndex: 'subject', width: 80, render: (v) => v || '—' },
      { title: '班主任', dataIndex: 'headTeacherName', width: 100, render: (v) => v || '—' },
      { title: '学生', dataIndex: 'studentCount', width: 60 },
      { title: '教师', dataIndex: 'teacherCount', width: 60 },
      {
        title: '操作', width: 150, render: (_, row) => (
          <Space size={2}>
            <Button type="link" size="small" icon={<EditOutlined />}
              onClick={() => {
                clazzForm.setFieldsValue({ ...row, campusId: row.campusId });
                setClazzModal({ mode: 'edit', row });
              }}>
              编辑
            </Button>
            <Popconfirm title="删除该班级？" onConfirm={async () => {
              try { await api.deleteClazz(row.id); message.success('已删除'); reload(); }
              catch (e) { message.error(e.message); }
            }}>
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </Space>
        )
      }
    ],
    teacher: [
      { title: '登录名', dataIndex: 'username', width: 130 },
      { title: '姓名', dataIndex: 'displayName', width: 110 },
      { title: '校区', dataIndex: 'campusName', width: 130, render: (v) => v || '—' },
      {
        title: '带班', dataIndex: 'clazzes', render: (v) => (
          v && v.length
            ? v.map((c) => <Tag key={`${c.clazzId}-${c.subject}`}>{c.clazzName}{c.subject ? ` · ${c.subject}` : ''}</Tag>)
            : <Typography.Text type="secondary">未带班</Typography.Text>
        )
      },
      { title: '状态', dataIndex: 'enabled', width: 80, render: (v) => (v === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>) },
      {
        title: '操作', width: 240, render: (_, row) => (
          <Space size={2}>
            <Button type="link" size="small" icon={<EditOutlined />}
              onClick={() => {
                teacherForm.setFieldsValue({
                  displayName: row.displayName,
                  campusId: row.campusId,
                  subject: row.clazzes?.[0]?.subject || '',
                  clazzIds: row.clazzIds || []
                });
                setTeacherModal({ mode: 'edit', row });
              }}>
              编辑
            </Button>
            <Button type="link" size="small" icon={<KeyOutlined />}
              onClick={() => { pwdForm.resetFields(); setPwdTarget(row); }}>
              重置密码
            </Button>
            <Popconfirm title={row.enabled === 1 ? '停用该教师账号？' : '启用该教师账号？'}
              onConfirm={async () => {
                try {
                  await api.setTeacherEnabled(row.userId, row.enabled !== 1);
                  message.success('状态已更新');
                  reload();
                } catch (e) { message.error(e.message); }
              }}>
              <Button type="link" size="small">{row.enabled === 1 ? '停用' : '启用'}</Button>
            </Popconfirm>
            <Popconfirm title="删除该教师账号？（其带班关系一并解除）" onConfirm={async () => {
              try { await api.deleteTeacher(row.userId); message.success('已删除'); reload(); }
              catch (e) { message.error(e.message); }
            }}>
              <Button type="link" size="small" danger>删除</Button>
            </Popconfirm>
          </Space>
        )
      }
    ],
    student: [
      { title: 'ID', dataIndex: 'id', width: 60 },
      { title: '姓名', dataIndex: 'name', width: 110 },
      { title: '登录名', dataIndex: 'username', width: 120, render: (v) => v || <Typography.Text type="warning">未开户</Typography.Text> },
      { title: '班级', dataIndex: 'clazzName', width: 130, render: (v) => v || '—' },
      { title: '年级', dataIndex: 'grade', width: 80, render: (v) => v || '—' },
      { title: '状态', dataIndex: 'accountEnabled', width: 80, render: (v) => (v === 1 ? <Tag color="green">启用</Tag> : v === 0 ? <Tag color="red">停用</Tag> : '—') },
      {
        title: '操作', width: 120, render: (_, row) => (
          <Button type="link" size="small" icon={<SwapOutlined />}
            onClick={() => { moveForm.resetFields(); setMoveModal(row); }}>
            转班
          </Button>
        )
      }
    ]
  };

  const campusOptions = campuses.map((c) => ({ value: c.id, label: c.name }));
  const clazzOptions = clazzes.map((c) => ({ value: c.id, label: `${c.campusName || ''} ${c.name}`.trim() }));
  const teacherOptions = teachers.map((t) => ({ value: t.userId, label: t.displayName }));

  const orgTab = (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      {isPlatformAdmin && (
        <Button type="primary" icon={<PlusOutlined />}
          onClick={() => { orgForm.resetFields(); setOrgModal({ mode: 'create' }); }}>
          新建机构
        </Button>
      )}
      <Table rowKey="id" loading={loading} dataSource={orgs} columns={columns.org}
        pagination={{ pageSize: 10 }} />
    </Space>
  );

  const campusTab = (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Button type="primary" icon={<PlusOutlined />} disabled={!orgId}
        onClick={() => { campusForm.resetFields(); setCampusModal({ mode: 'create' }); }}>
        新建校区
      </Button>
      <Table rowKey="id" loading={loading} dataSource={campuses} columns={columns.campus}
        pagination={{ pageSize: 10 }} />
    </Space>
  );

  const clazzTab = (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Button type="primary" icon={<PlusOutlined />} disabled={!campusOptions.length}
        onClick={() => {
          clazzForm.resetFields();
          clazzForm.setFieldsValue({ campusId: campusOptions[0]?.value });
          setClazzModal({ mode: 'create' });
        }}>
        新建班级
      </Button>
      <Table rowKey="id" loading={loading} dataSource={clazzes} columns={columns.clazz}
        pagination={{ pageSize: 10 }} />
    </Space>
  );

  const teacherTab = (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Button type="primary" icon={<PlusOutlined />} disabled={!orgId}
        onClick={() => { teacherForm.resetFields(); setTeacherModal({ mode: 'create' }); }}>
        新建教师
      </Button>
      <Table rowKey="userId" loading={loading} dataSource={teachers} columns={columns.teacher}
        pagination={{ pageSize: 10 }} />
    </Space>
  );

  const studentTab = (
    <Table rowKey="id" loading={loading} dataSource={students} columns={columns.student}
      pagination={{ pageSize: 10 }} />
  );

  const tabs = [
    ...(isPlatformAdmin ? [{ key: 'org', label: <span><BankOutlined /> 机构</span>, children: orgTab }] : []),
    { key: 'campus', label: <span><ClusterOutlined /> 校区</span>, children: campusTab },
    { key: 'clazz', label: <span><TeamOutlined /> 班级</span>, children: clazzTab },
    { key: 'teacher', label: <span><UserOutlined /> 教师</span>, children: teacherTab },
    { key: 'student', label: <span><TeamOutlined /> 学生</span>, children: studentTab }
  ];

  return (
    <Space direction="vertical" style={{ width: '100%' }} size={16}>
      <Card size="small">
        <Row gutter={16} align="middle">
          <Col flex="auto">
            <Space size={12} wrap>
              <Typography.Text strong>当前机构</Typography.Text>
              {isPlatformAdmin ? (
                <Select
                  style={{ minWidth: 240 }}
                  value={orgId ?? undefined}
                  placeholder="选择要管理的机构"
                  options={orgs.map((o) => ({ value: o.id, label: `${o.name}（${o.code || '无编码'}）` }))}
                  onChange={setOrgId}
                />
              ) : (
                <Tag color="purple" style={{ fontSize: 13 }}>
                  {orgs[0]?.name || '未归属机构'}
                </Tag>
              )}
              <Button icon={<ReloadOutlined />} onClick={() => reload()}>刷新</Button>
            </Space>
          </Col>
          <Col>
            <Space size={28}>
              <Statistic title="校区" value={overview?.campusCount ?? 0} />
              <Statistic title="班级" value={overview?.clazzCount ?? 0} />
              <Statistic title="教师" value={overview?.teacherCount ?? 0} />
              <Statistic title="学生" value={overview?.studentCount ?? 0} />
            </Space>
          </Col>
        </Row>
      </Card>

      <Card>
        <Tabs activeKey={activeTab} onChange={setActiveTab} items={tabs} />
      </Card>

      {/* 机构 */}
      <Modal title={orgModal?.mode === 'create' ? '新建机构' : '编辑机构'}
        open={!!orgModal} onOk={submitOrg} onCancel={() => setOrgModal(null)} destroyOnClose>
        <Form form={orgForm} layout="vertical">
          <Form.Item name="name" label="机构名称" rules={[{ required: true, message: '请输入机构名称' }]}>
            <Input placeholder="例如：示例教育集团" />
          </Form.Item>
          <Form.Item name="code" label="机构编码">
            <Input placeholder="唯一编码，可留空" />
          </Form.Item>
          <Form.Item name="contactName" label="联系人"><Input /></Form.Item>
          <Form.Item name="contactPhone" label="联系电话"><Input /></Form.Item>
          <Form.Item name="remark" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Modal>

      {/* 校区 */}
      <Modal title={campusModal?.mode === 'create' ? '新建校区' : '编辑校区'}
        open={!!campusModal} onOk={submitCampus} onCancel={() => setCampusModal(null)} destroyOnClose>
        <Form form={campusForm} layout="vertical">
          <Form.Item name="name" label="校区名称" rules={[{ required: true, message: '请输入校区名称' }]}>
            <Input placeholder="例如：示例校区" />
          </Form.Item>
          <Form.Item name="code" label="校区编码"><Input /></Form.Item>
          <Form.Item name="address" label="地址"><Input /></Form.Item>
        </Form>
      </Modal>

      {/* 班级 */}
      <Modal title={clazzModal?.mode === 'create' ? '新建班级' : '编辑班级'}
        open={!!clazzModal} onOk={submitClazz} onCancel={() => setClazzModal(null)} destroyOnClose>
        <Form form={clazzForm} layout="vertical">
          <Form.Item name="campusId" label="所属校区" rules={[{ required: true, message: '请选择校区' }]}>
            <Select options={campusOptions} placeholder="选择校区" />
          </Form.Item>
          <Form.Item name="name" label="班级名称" rules={[{ required: true, message: '请输入班级名称' }]}>
            <Input placeholder="例如：五年级1班" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="grade" label="年级">
                <Select allowClear options={GRADES.map((g) => ({ value: g, label: g }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="subject" label="科目">
                <Select allowClear options={SUBJECTS.map((s) => ({ value: s, label: s }))} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="headTeacherId" label="班主任">
            <Select allowClear showSearch optionFilterProp="label" options={teacherOptions}
              placeholder="可留空" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 教师 */}
      <Modal title={teacherModal?.mode === 'create' ? '新建教师' : '编辑教师'}
        open={!!teacherModal} onOk={submitTeacher} onCancel={() => setTeacherModal(null)} destroyOnClose>
        <Form form={teacherForm} layout="vertical">
          {teacherModal?.mode === 'create' && (
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item name="username" label="登录名" rules={[{ required: true, message: '请输入登录名' }]}>
                  <Input placeholder="例如：teacher02" />
                </Form.Item>
              </Col>
              <Col span={12}>
                <Form.Item name="password" label="初始密码" rules={[{ required: true, message: '请输入初始密码' }, { min: 6, message: '至少 6 位' }]}>
                  <Input.Password placeholder="至少 6 位" />
                </Form.Item>
              </Col>
            </Row>
          )}
          <Form.Item name="displayName" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input placeholder="例如：李老师" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="campusId" label="归属校区">
                <Select allowClear options={campusOptions} placeholder="可留空" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="subject" label="授课科目">
                <Select allowClear options={SUBJECTS.map((s) => ({ value: s, label: s }))}
                  placeholder="空表示班主任/全科" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="clazzIds" label="带班（可多选）"
            extra="教师只在这里勾选的班级内开展教学与学生管理">
            <Select mode="multiple" allowClear options={clazzOptions} placeholder="选择班级" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 学生转班 */}
      <Modal title={`转班：${moveModal?.name || ''}`} open={!!moveModal} destroyOnClose
        onOk={async () => {
          const v = await moveForm.validateFields();
          try {
            const d = unwrap(await api.moveStudent(moveModal.id, v.clazzId));
            message.success(`已转入 ${d.clazzName}`);
            setMoveModal(null);
            moveForm.resetFields();
            reload();
          } catch (e) { message.error(e.message); }
        }}
        onCancel={() => setMoveModal(null)}>
        <Form form={moveForm} layout="vertical">
          <Form.Item name="clazzId" label="目标班级" rules={[{ required: true, message: '请选择班级' }]}>
            <Select options={clazzOptions} placeholder="选择班级" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 重置教师密码 */}
      <Modal title={`重置密码：${pwdTarget?.displayName || ''}`} open={!!pwdTarget} destroyOnClose
        onOk={async () => {
          const v = await pwdForm.validateFields();
          try {
            const d = unwrap(await api.resetTeacherPassword(pwdTarget.userId, { password: v.password }));
            message.success(`${d.username} 的密码已重置为 ${d.password}`, 8);
            setPwdTarget(null);
            pwdForm.resetFields();
          } catch (e) { message.error(e.message); }
        }}
        onCancel={() => setPwdTarget(null)}>
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="password" label="新密码"
            rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="留空则重置为 teacher123" />
          </Form.Item>
        </Form>
      </Modal>
    </Space>
  );
}
