import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Card, Table, Button, Modal, Form, Input, Select, Space, Popconfirm, message, Tag, Typography,
  Tabs, Row, Col, Statistic, Drawer, Descriptions, Divider, Empty, Alert, Switch, List
} from 'antd';
import {
  PlusOutlined, EditOutlined, KeyOutlined, DeleteOutlined, ReloadOutlined, BankOutlined,
  ClusterOutlined, TeamOutlined, UserOutlined, SwapOutlined, CrownOutlined, EyeOutlined,
  ImportOutlined, ApartmentOutlined, SafetyCertificateOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

const GRADES = ['一年级', '二年级', '三年级', '四年级', '五年级', '六年级', '初一', '初二', '初三'];
const SUBJECTS = ['数学', '语文', '英语', '物理', '化学', '生物'];

const statusTag = (s) => (s === 'SUSPENDED'
  ? <Tag color="orange">已停用</Tag>
  : <Tag color="green">正常</Tag>);

/**
 * 组织管理平台：机构 → 校区 → 班级 → 教师 / 学生。
 *
 * <p>每一层都有「谁负责」：</p>
 * <ul>
 *   <li><b>校区</b>分配校区管理员（CAMPUS_ADMIN）—— 管理员的权限被绑定的 campusId 限死，
 *       看不到也改不了本机构其他校区；</li>
 *   <li><b>班级</b>分配班主任与任课教师，并承载学生名册；</li>
 *   <li><b>班级</b>可挂一层上级班（平行班 / 实验班）。</li>
 * </ul>
 *
 * <p>前端只做体验优化（隐藏按钮、加提示），真实边界一律在后端
 * {@code UserContext.checkCampusScope} 兜底，越界 403。</p>
 */
export default function OrgConsole() {
  const { user } = readSession() || {};
  const isPlatformAdmin = user?.role === ROLE.ADMIN;
  const isCampusAdmin = user?.role === ROLE.CAMPUS_ADMIN;

  const [orgs, setOrgs] = useState([]);
  const [orgId, setOrgId] = useState(null);
  const [overview, setOverview] = useState(null);
  const [campuses, setCampuses] = useState([]);
  const [clazzes, setClazzes] = useState([]);
  const [teachers, setTeachers] = useState([]);
  const [students, setStudents] = useState([]);
  const [campusAdmins, setCampusAdmins] = useState([]);
  const [loading, setLoading] = useState(false);
  const [activeTab, setActiveTab] = useState(isPlatformAdmin ? 'org' : 'campus');

  // 弹窗
  const [orgModal, setOrgModal] = useState(null);       // {mode, row}
  const [campusModal, setCampusModal] = useState(null);
  const [clazzModal, setClazzModal] = useState(null);
  const [teacherModal, setTeacherModal] = useState(null);
  const [moveModal, setMoveModal] = useState(null);
  const [pwdTarget, setPwdTarget] = useState(null);
  const [adminModal, setAdminModal] = useState(null);     // {mode, row, campusId}
  const [adminPwdTarget, setAdminPwdTarget] = useState(null);
  const [importTarget, setImportTarget] = useState(null); // 批量导入的班级
  const [detail, setDetail] = useState(null);             // 班级下钻抽屉
  const [detailLoading, setDetailLoading] = useState(false);

  const [orgForm] = Form.useForm();
  const [campusForm] = Form.useForm();
  const [clazzForm] = Form.useForm();
  const [teacherForm] = Form.useForm();
  const [moveForm] = Form.useForm();
  const [pwdForm] = Form.useForm();
  const [adminForm] = Form.useForm();
  const [adminPwdForm] = Form.useForm();
  const [importForm] = Form.useForm();

  // 校区是响应式的：切换校区后上级班级候选要跟着重算
  const watchCampusId = Form.useWatch('campusId', clazzForm);

  // 校区管理员自己只有绑定的那个校区，下拉不该出现别的选项
  const myCampusId = user?.campusId ?? null;

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
      setOverview(null); setCampuses([]); setClazzes([]); setTeachers([]);
      setStudents([]); setCampusAdmins([]);
      return;
    }
    setLoading(true);
    try {
      const [ov, cp, cz, tc, st, ca] = await Promise.all([
        api.orgOverview(oid).then(unwrap).catch(() => null),
        api.listCampuses(oid).then(unwrap).catch(() => []),
        api.listClazzes({ orgId: oid }).then(unwrap).catch(() => []),
        api.listTeachers({ orgId: oid }).then(unwrap).catch(() => []),
        api.listOrgStudents(oid).then(unwrap).catch(() => []),
        api.listCampusAdmins({ orgId: oid }).then(unwrap).catch(() => [])
      ]);
      setOverview(ov);
      setCampuses(cp || []);
      setClazzes(cz || []);
      setTeachers(tc || []);
      setStudents(st || []);
      setCampusAdmins(ca || []);
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

  // ---------------- 校区管理员 ----------------
  const submitAdmin = async () => {
    const v = await adminForm.validateFields();
    try {
      if (adminModal.mode === 'create') {
        await api.createCampusAdmin(v);
        message.success('校区管理员已创建，权限已限定在该校区');
      } else {
        await api.updateCampusAdmin(adminModal.row.userId, v);
        message.success('校区管理员已更新');
      }
      setAdminModal(null);
      adminForm.resetFields();
      reload();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ---------------- 班级下钻 ----------------
  const openDetail = async (clazzId) => {
    setDetailLoading(true);
    setDetail({ id: clazzId });
    try {
      const d = unwrap(await api.clazzDetail(clazzId));
      setDetail(d);
    } catch (e) {
      message.error(e.message);
      setDetail(null);
    } finally {
      setDetailLoading(false);
    }
  };

  // ---------------- 学生批量导入 ----------------
  const submitImport = async () => {
    const v = await importForm.validateFields();
    try {
      const r = unwrap(await api.importClazzStudents(importTarget.id, { csvText: v.csvText }));
      if (r.duplicatedCount > 0) {
        Modal.info({
          title: '导入完成',
          content: (
            <div>
              <p>新增 <b>{r.createdCount}</b> 名学生到「{r.clazzName}」。</p>
              {r.duplicatedCount > 0 && (
                <p style={{ color: '#fa8c16' }}>
                  跳过 <b>{r.duplicatedCount}</b> 个同名（该班已存在）：
                  {r.duplicated.join('、')}
                </p>
              )}
              <p style={{ color: '#888' }}>初始密码统一为 {r.defaultPassword}，学生首次登录后请修改。</p>
            </div>
          )
        });
      } else {
        message.success(`已导入 ${r.createdCount} 名学生到「${r.clazzName}」`);
      }
      setImportTarget(null);
      importForm.resetFields();
      openDetail(importTarget.id);
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
      {
        // 校区这一层「谁负责」——没分配管理员是要紧的运维缺口，直接标红提示
        title: '校区管理员', dataIndex: 'admins', width: 160, render: (v, row) => (
          v && v.length
            ? <Space size={4} wrap>{v.map((a) => (
              <Tag key={a.userId} color={a.enabled === 1 ? 'cyan' : 'default'}>
                {a.displayName}{a.enabled === 1 ? '' : '（停用）'}
              </Tag>
            ))}</Space>
            : <Tag color="red" icon={<CrownOutlined />}>未分配</Tag>
        )
      },
      { title: '地址', dataIndex: 'address', render: (v) => v || '—' },
      { title: '班级数', dataIndex: 'clazzCount', width: 80 },
      { title: '学生数', dataIndex: 'studentCount', width: 80 },
      {
        title: '操作', width: 230, render: (_, row) => (
          <Space size={2}>
            {!isCampusAdmin && (
              <Button type="link" size="small" icon={<CrownOutlined />}
                onClick={() => {
                  adminForm.resetFields();
                  adminForm.setFieldsValue({ displayName: '', campusId: row.id });
                  setAdminModal({ mode: 'create', campusId: row.id });
                }}>
                配管理员
              </Button>
            )}
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
      {
        // 班级分层：子班缩进 + 标注挂靠哪个班，一眼看出平行班关系
        title: '班级', dataIndex: 'name', render: (v, row) => (
          <Space size={6}>
            {row.level === 1 && <Typography.Text type="secondary">└</Typography.Text>}
            <span>{v}</span>
            {row.level === 1 && row.parentClazzName && (
              <Tag color="gold" icon={<ApartmentOutlined />}>{row.parentClazzName}</Tag>
            )}
            {row.childCount > 0 && <Tag color="cyan">{row.childCount} 个下级班</Tag>}
          </Space>
        )
      },
      { title: '校区', dataIndex: 'campusName', width: 120, render: (v) => v || '—' },
      { title: '年级', dataIndex: 'grade', width: 80, render: (v) => v || '—' },
      { title: '科目', dataIndex: 'subject', width: 80, render: (v) => v || '—' },
      { title: '班主任', dataIndex: 'headTeacherName', width: 100, render: (v) => v || '—' },
      { title: '学生', dataIndex: 'studentCount', width: 60 },
      { title: '教师', dataIndex: 'teacherCount', width: 60 },
      {
        title: '操作', width: 230, render: (_, row) => (
          <Space size={2}>
            <Button type="link" size="small" icon={<EyeOutlined />}
              onClick={() => openDetail(row.id)}>
              下钻
            </Button>
            <Button type="link" size="small" icon={<ImportOutlined />}
              onClick={() => { importForm.resetFields(); setImportTarget(row); }}>
              导入学生
            </Button>
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

  const campusOptions = campuses
    .filter((c) => !isCampusAdmin || c.id === myCampusId)
    .map((c) => ({ value: c.id, label: c.name }));
  const clazzOptions = clazzes.map((c) => ({ value: c.id, label: `${c.campusName || ''} ${c.name}`.trim() }));
  const teacherOptions = teachers.map((t) => ({ value: t.userId, label: t.displayName }));

  /**
   * 上级班级候选：同校区、排除自己、且自己不能已有上级（后端也校验，前端先挡一道更好用）。
   * 子班挂在一层就够了，所以「已经有下级班的班」不再出现在候选里。
   *
   * <p>校区必须用 {@code Form.useWatch} 而不是 {@code getFieldValue}：
   * 后者不是响应式的，切换校区后候选列表不会重算。</p>
   */
  const parentClazzOptions = useMemo(() => {
    const selfId = clazzModal?.row?.id;
    return clazzes
      .filter((c) => (!watchCampusId || c.campusId === watchCampusId)
        && c.id !== selfId
        && !c.parentClazzId
        && c.id !== watchCampusId)
      .map((c) => ({ value: c.id, label: c.name }));
  }, [clazzes, watchCampusId, clazzModal]);

  const adminTab = (
    <Space direction="vertical" style={{ width: '100%' }} size={12}>
      <Alert type="info" showIcon
        message="校区管理员 = 一个校区的负责人"
        description={
          <div>
            他的权限被绑定的校区限死：能管该校区下的班级、教师、学生与教学资源，
            但<b>看不到也改不了本机构其他校区</b>。机构管理员仍然是全机构视角。
          </div>
        } />
      <Table rowKey="userId" loading={loading} dataSource={campusAdmins} columns={[
        { title: '登录名', dataIndex: 'username', width: 140 },
        { title: '姓名', dataIndex: 'displayName', width: 120 },
        { title: '负责校区', dataIndex: 'campusName', width: 160, render: (v) => v || '—' },
        { title: '该校区班级', dataIndex: 'managedClazzCount', width: 100 },
        {
          title: '状态', dataIndex: 'enabled', width: 80,
          render: (v) => (v === 1 ? <Tag color="green">启用</Tag> : <Tag color="red">停用</Tag>)
        },
        {
          title: '操作', width: 230, render: (_, row) => (
            <Space size={2}>
              <Button type="link" size="small" icon={<EditOutlined />}
                onClick={() => {
                  adminForm.resetFields();
                  adminForm.setFieldsValue({
                    displayName: row.displayName,
                    campusId: row.campusId
                  });
                  setAdminModal({ mode: 'edit', row });
                }}>
                编辑
              </Button>
              <Button type="link" size="small" icon={<KeyOutlined />}
                onClick={() => { adminPwdForm.resetFields(); setAdminPwdTarget(row); }}>
                重置密码
              </Button>
              <Popconfirm title={row.enabled === 1 ? '停用该管理员账号？' : '启用该管理员账号？'}
                onConfirm={async () => {
                  try {
                    await api.setCampusAdminEnabled(row.userId, row.enabled !== 1);
                    message.success('状态已更新');
                    reload();
                  } catch (e) { message.error(e.message); }
                }}>
                <Button type="link" size="small">{row.enabled === 1 ? '停用' : '启用'}</Button>
              </Popconfirm>
              <Popconfirm title="删除该管理员账号？" onConfirm={async () => {
                try { await api.deleteCampusAdmin(row.userId); message.success('已删除'); reload(); }
                catch (e) { message.error(e.message); }
              }}>
                <Button type="link" size="small" danger>删除</Button>
              </Popconfirm>
            </Space>
          )
        }
      ]} pagination={{ pageSize: 10 }} />
    </Space>
  );

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
      {!isCampusAdmin && (
        <Alert type="info" showIcon
          message="校区是这一层的责任主体"
          description="每个校区需要分配至少一名校区管理员 —— 他的权限被绑定在这个校区上，能管班级、教师、学生，但看不到本机构其他校区。" />
      )}
      <Button type="primary" icon={<PlusOutlined />} disabled={!orgId || isCampusAdmin}
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
    // 校区管理员不该看到「分配管理员」这一层 —— 那是他自己的上级
    ...(!isCampusAdmin
      ? [{
        key: 'admin', label: <span><CrownOutlined /> 校区管理员</span>, children: adminTab
      }]
      : []),
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
        open={!!orgModal} onOk={submitOrg} onCancel={() => setOrgModal(null)}
        destroyOnHidden forceRender>
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
        open={!!campusModal} onOk={submitCampus} onCancel={() => setCampusModal(null)}
        destroyOnHidden forceRender>
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
        open={!!clazzModal} onOk={submitClazz} onCancel={() => setClazzModal(null)}
        destroyOnHidden forceRender>
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
          <Form.Item name="parentClazzId" label="上级班级"
            extra="用于表达平行班 / 实验班这类分层。留空表示顶级班；只支持一层。">
            <Select allowClear showSearch optionFilterProp="label" options={parentClazzOptions}
              placeholder={parentClazzOptions.length ? '可选：挂靠到某个班' : '本校区暂无可作为上级的班级'} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 教师 */}
      <Modal title={teacherModal?.mode === 'create' ? '新建教师' : '编辑教师'}
        open={!!teacherModal} onOk={submitTeacher} onCancel={() => setTeacherModal(null)}
        destroyOnHidden forceRender>
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
      <Modal title={`转班：${moveModal?.name || ''}`} open={!!moveModal} destroyOnHidden forceRender
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
      <Modal title={`重置密码：${pwdTarget?.displayName || ''}`} open={!!pwdTarget} destroyOnHidden forceRender
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

      {/* 校区管理员 */}
      <Modal title={adminModal?.mode === 'create'
        ? `新建校区管理员：${campuses.find((c) => c.id === adminModal?.campusId)?.name || ''}`
        : `编辑校区管理员：${adminModal?.row?.displayName || ''}`}
        open={!!adminModal} onOk={submitAdmin} onCancel={() => setAdminModal(null)}
        okText="保存" destroyOnHidden forceRender>
        <Alert type="warning" showIcon style={{ marginBottom: 12 }}
          message="这个角色的权限边界就是下面选的校区"
          description="创建后该账号只能看到并管理所选校区下的班级、教师、学生与教学资源，其他校区一律 403。改绑校区等于改变他的管辖范围，请确认后再保存。" />
        <Form form={adminForm} layout="vertical">
          {adminModal?.mode === 'create' && (
            <Row gutter={12}>
              <Col span={12}>
                <Form.Item name="username" label="登录名" rules={[{ required: true, message: '请输入登录名' }]}>
                  <Input placeholder="例如：campus_a_admin" />
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
            <Input placeholder="例如：张校长" />
          </Form.Item>
          <Form.Item name="campusId" label="负责校区" rules={[{ required: true, message: '校区管理员必须绑定校区' }]}>
            <Select showSearch optionFilterProp="label" options={campusOptions}
              placeholder="选择他负责的校区" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 重置校区管理员密码 */}
      <Modal title={`重置密码：${adminPwdTarget?.displayName || ''}`} open={!!adminPwdTarget} destroyOnHidden forceRender
        onOk={async () => {
          const v = await adminPwdForm.validateFields();
          try {
            const d = unwrap(await api.resetCampusAdminPassword(adminPwdTarget.userId, { password: v.password }));
            message.success(`${d.username} 的密码已重置为 ${d.password}`, 8);
            setAdminPwdTarget(null);
            adminPwdForm.resetFields();
          } catch (e) { message.error(e.message); }
        }}
        onCancel={() => setAdminPwdTarget(null)}>
        <Form form={adminPwdForm} layout="vertical">
          <Form.Item name="password" label="新密码"
            rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="留空则重置为 campus123" />
          </Form.Item>
        </Form>
      </Modal>

      {/* 学生批量导入到班级 */}
      {/* forceRender 让 Form 立即挂载：否则 useForm 建的实例在 Modal 打开前无处附着，
          antd 会持续告警「Instance created by useForm is not connected」。 */}
      <Modal title={`批量导入学生：${importTarget?.name || ''}`} open={!!importTarget} forceRender
        onOk={submitImport} onCancel={() => setImportTarget(null)} okText="导入" width={560}>
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="每行一个学生姓名"
          description="可以直接从 Excel 里复制一列姓名粘进来。系统会为每个名字建学生档案并开通登录账号（初始密码 123456），同班同名会自动跳过。" />
        <Form form={importForm} layout="vertical">
          <Form.Item name="csvText" label="学生姓名" rules={[{ required: true, message: '请粘贴至少一个姓名' }]}>
            <Input.TextArea rows={10} placeholder={'张三\n李四\n王五'} />
          </Form.Item>
        </Form>
      </Modal>

      {/* 班级下钻抽屉：谁在教、有哪些学生、还有哪些下级班 */}
      <Drawer title={detailLoading ? '加载中…' : `班级详情：${detail?.name || ''}`}
        width={720} open={!!detail} onClose={() => setDetail(null)}>
        {detailLoading && !detail?.name && <Empty description="加载中" />}
        {detail && detail.name && (
          <Space direction="vertical" size={16} style={{ width: '100%' }}>
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="所属校区">{detail.campusName || '—'}</Descriptions.Item>
              <Descriptions.Item label="年级 / 科目">
                {[detail.grade, detail.subject].filter(Boolean).join(' · ') || '—'}
              </Descriptions.Item>
              <Descriptions.Item label="班主任">
                {detail.headTeacherName
                  ? <Tag color="blue">{detail.headTeacherName}</Tag>
                  : <Typography.Text type="warning">未指定</Typography.Text>}
              </Descriptions.Item>
              <Descriptions.Item label="学生数">{detail.studentCount}</Descriptions.Item>
              {detail.level === 1 && (
                <Descriptions.Item label="上级班级" span={2}>
                  <Tag color="gold">{detail.parentClazzName || '—'}</Tag>
                </Descriptions.Item>
              )}
            </Descriptions>

            {detail.children?.length > 0 && (
              <div>
                <Divider orientation="left" plain>下级班级</Divider>
                <Space size={8} wrap>
                  {detail.children.map((c) => (
                    <Tag
                      key={c.id}
                      color="cyan"
                      style={{ cursor: 'pointer' }}
                      onClick={() => openDetail(c.id)}
                    >
                      {c.name} · {c.studentCount} 人
                    </Tag>
                  ))}
                </Space>
              </div>
            )}

            <div>
              <Divider orientation="left" plain>
                任课教师（{detail.teachers?.length || 0}）
              </Divider>
              {detail.teachers?.length ? (
                <List size="small" bordered dataSource={detail.teachers}
                  renderItem={(t) => (
                    <List.Item
                      actions={[
                        <Button key="import" type="link" size="small" icon={<ImportOutlined />}
                          onClick={() => { importForm.resetFields(); setImportTarget({ id: detail.id, name: detail.name }); setDetail(null); }}>
                          导入学生
                        </Button>
                      ]}
                    >
                      <List.Item.Meta
                        avatar={<Tag color={t.isHeadTeacher ? 'blue' : 'default'}>
                          {t.isHeadTeacher ? '班主任' : (t.subject || '任课')}
                        </Tag>}
                        title={t.displayName}
                        description={<Typography.Text type="secondary" style={{ fontSize: 12 }}>
                          {t.username}{t.subject && !t.isHeadTeacher ? ` · ${t.subject}` : ''}
                        </Typography.Text>}
                      />
                    </List.Item>
                  )} />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该班尚未分配任课教师" />
              )}
            </div>

            <div>
              <Divider orientation="left" plain>
                学生名单（{detail.students?.length || 0}）
              </Divider>
              {detail.students?.length ? (
                <Table rowKey="id" size="small" dataSource={detail.students}
                  pagination={{ pageSize: 8 }} columns={[
                    { title: '姓名', dataIndex: 'name' },
                    { title: '登录名', dataIndex: 'username', render: (v) => v
                      || <Typography.Text type="warning">未开户</Typography.Text> },
                    {
                      title: '操作', width: 90, render: (_, s) => (
                        <Button type="link" size="small" icon={<SwapOutlined />}
                          onClick={() => { moveForm.resetFields(); moveForm.setFieldsValue({ clazzId: detail.id }); setMoveModal(s); }}>
                          转班
                        </Button>
                      )
                    }
                  ]} />
              ) : (
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该班还没有学生">
                  <Button type="primary" size="small" icon={<ImportOutlined />}
                    onClick={() => { importForm.resetFields(); setImportTarget({ id: detail.id, name: detail.name }); setDetail(null); }}>
                    批量导入
                  </Button>
                </Empty>
              )}
            </div>
          </Space>
        )}
      </Drawer>
    </Space>
  );
}
