import { useEffect, useMemo, useState } from 'react';
import {
  Card, Table, Button, Space, Tag, Typography, message, Modal, Form, Input, Select,
  Switch, Popconfirm, Alert, Row, Col, List, Empty, Tooltip, Tabs, Checkbox, Statistic, Spin, Cascader
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, ReloadOutlined, ExperimentOutlined,
  ApiOutlined, RobotOutlined, SafetyCertificateOutlined, LinkOutlined, BankOutlined,
  ClusterOutlined, TeamOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { readSession, ROLE } from '../auth.js';

const ROLE_LABEL = { STUDENT: '学生', TEACHER: '教师', ORG_ADMIN: '机构管理员', ADMIN: '平台管理员' };
const STATUS_META = {
  OK: { color: 'green', label: '成功' },
  SKIPPED: { color: 'default', label: '跳过' },
  ERROR: { color: 'red', label: '失败' }
};

/** 工具按分类分组的展示顺序 */
const CATEGORY_ORDER = ['诊断', '学习', '检索', '教研', '图谱'];

/** 生效范围四档：平台 / 机构 / 校区 / 班级 */
const SCOPE_META = {
  PLATFORM: { color: 'blue', label: '全平台' },
  ORG: { color: 'purple', label: '本机构' },
  CAMPUS: { color: 'cyan', label: '校区级' },
  CLAZZ: { color: 'gold', label: '班级级' }
};

/**
 * Agent 配置域管理台。
 *
 * <p>页面刻意按<b>三层边界</b>组织，因为它们性质不同、可配置程度也不同：</p>
 * <ul>
 *   <li><b>工具</b>是代码（AgentToolCatalog）—— 只读展示，管理台改不了；</li>
 *   <li><b>编排</b>是数据（agent_profile / agent_tool）—— 本页可配置；</li>
 *   <li><b>权限</b>是运行时算的（UserContext）—— 本页<b>刻意不提供</b>任何入口。</li>
 * </ul>
 *
 * <p>最后一条是关键：配置只能决定「看不看得到这个按钮」，
 * 决定不了「能不能拿到这份数据」。</p>
 *
 * <p><b>生效范围</b>（平台 / 机构 / 校区 / 班级）是编排层的第四维，
 * 表达「这个 Agent 在哪些组织单元启用」——它同样不放开任何数据访问。
 * 两个总览 Tab 用来回答「哪个校区、哪个班还没配」。</p>
 */
export default function Agents() {
  const { user } = readSession() || {};
  const isPlatformAdmin = user?.role === ROLE.ADMIN;

  const [agents, setAgents] = useState([]);
  const [tools, setTools] = useState([]);
  const [scopes, setScopes] = useState(null);
  const [campuses, setCampuses] = useState([]);
  const [clazzes, setClazzes] = useState([]);
  const [orgs, setOrgs] = useState([]);
  const [orgId, setOrgId] = useState(null);
  const [campusId, setCampusId] = useState(null);
  const [clazzId, setClazzId] = useState(null);
  const [loading, setLoading] = useState(true);

  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [form] = Form.useForm();

  const [bindTarget, setBindTarget] = useState(null);
  const [bindKeys, setBindKeys] = useState([]);
  const [bindBusy, setBindBusy] = useState(false);

  const [dryTarget, setDryTarget] = useState(null);
  const [students, setStudents] = useState([]);
  const [dryStudent, setDryStudent] = useState(null);
  const [dryResult, setDryResult] = useState(null);
  const [dryBusy, setDryBusy] = useState(false);

  /** 生效范围下拉的级联选项：机构 → 校区 → 班级 */
  const scopeOptions = useMemo(() => {
    const campusNodes = campuses.map((c) => {
      const kids = clazzes
        .filter((k) => k.campusId === c.id)
        .map((k) => ({ value: k.id, label: k.name }));
      // children 传空数组时 antd 会把它当叶子节点处理，hover 不展开二级菜单。
      // 校区下面暂时没有班级也得能选（那就只到校区级），所以给个空的占位层。
      return {
        value: c.id,
        label: c.name,
        // 带 orgId 是为了让平台管理员能把校区挂回正确的机构节点下
        orgId: c.orgId,
        children: kids.length ? kids : [{ value: `__empty_${c.id}`, label: '（暂无班级）', disabled: true }]
      };
    });
    // 机构管理员锁在自己机构，树顶只留一个机构节点
    if (!isPlatformAdmin) {
      const self = orgs[0];
      return self ? [{ value: self.id, label: self.name, children: campusNodes }] : [];
    }
    // 平台管理员按机构建树：校区必须挂在自己机构下面，不能串
    return orgs.map((o) => ({
      value: o.id,
      label: o.name,
      children: campusNodes.filter((c) => c.orgId === o.id)
    }));
  }, [campuses, clazzes, orgs, isPlatformAdmin]);

  const load = async () => {
    setLoading(true);
    try {
      const [a, t, sc] = await Promise.all([
        api.listAgents({ orgId, campusId, clazzId }),
        api.agentTools(),
        api.agentScopes().catch(() => null)
      ]);
      setAgents(unwrap(a) || []);
      setTools(unwrap(t) || []);
      setScopes(sc ? unwrap(sc) : null);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, [orgId, campusId, clazzId]);

  /**
   * 组织树一次加载完：机构 + 校区 + 班级。
   *
   * <p>刻意合成一个 effect —— 拆成「先取机构再取校区」会产生竞态：
   * 级联的 options 由 campuses 推导，而 Cascader 在 options 变化时不会重建已展开的菜单，
   * 结果就是「机构层有、校区层空」这种看着像没数据的半成品状态。</p>
   *
   * <p>平台管理员不选机构时（orgId 为 null）把所有机构的校区班级都拉下来；
   * 一旦选了机构就只拉那一个，避免筛选下拉里混进别的机构的班级。</p>
   */
  useEffect(() => {
    let alive = true;
    (async () => {
      const o = unwrap(await api.listOrgs()) || [];
      if (!alive) return;
      setOrgs(o);
      if (!isPlatformAdmin) {
        setOrgId((prev) => (prev ?? o[0]?.id ?? null));
      }
      const targets = orgId ? [orgId] : o.map((x) => x.id);
      if (!targets.length) {
        setCampuses([]);
        setClazzes([]);
        return;
      }
      const pairs = await Promise.all(targets.map(async (id) => ({
        c: unwrap(await api.listCampuses(id)) || [],
        k: unwrap(await api.listClazzes({ orgId: id })) || []
      })));
      if (!alive) return;
      setCampuses(pairs.flatMap((p) => p.c));
      setClazzes(pairs.flatMap((p) => p.k));
    })().catch(() => {
      // 组织数据缺失不该挡住 Agent 编排本身
    });
    return () => { alive = false; };
  }, [orgId, isPlatformAdmin]);

  useEffect(() => {
    api.listStudents().then((r) => {
      const list = unwrap(r) || [];
      setStudents(list);
      if (list.length && !dryStudent) setDryStudent(list[0].id);
    }).catch(() => { /* 教师督导范围外可能为空 */ });
  }, []);

  // 工具按分类分组，供绑定弹窗使用
  const grouped = useMemo(() => {
    const map = new Map();
    tools.forEach((t) => {
      if (!map.has(t.category)) map.set(t.category, []);
      map.get(t.category).push(t);
    });
    return CATEGORY_ORDER.filter((c) => map.has(c)).map((c) => ({ category: c, items: map.get(c) }));
  }, [tools]);

  // ==================== 编排配置 ====================

  const openEditor = (agent) => {
    form.resetFields();
    if (agent) {
      // 后端已保证「有 clazzId 必有 campusId」，这里直接还原级联路径
      const path = agent.clazzId
        ? [agent.orgId, agent.campusId, agent.clazzId]
        : (agent.campusId ? [agent.orgId, agent.campusId] : (agent.orgId ? [agent.orgId] : []));
      form.setFieldsValue({
        id: agent.id,
        code: agent.code,
        scopePath: path,
        name: agent.name,
        description: agent.description,
        targetRole: agent.targetRole,
        model: agent.model,
        temperature: agent.temperature,
        maxTokens: agent.maxTokens,
        enabled: agent.enabled === 1
      });
    } else {
      form.setFieldsValue({
        scopePath: isPlatformAdmin ? [] : (orgId ? [orgId] : []),
        targetRole: 'STUDENT', temperature: 0.3, maxTokens: 2048, enabled: true
      });
    }
    setOpen(true);
  };

  const save = async () => {
    const v = await form.validateFields();
    const path = v.scopePath || [];
    const last = path[path.length - 1];
    // 级联末级有两种含义：校区或班级。后端会再校验一遍归属，这里只是把语义拆开。
    // __empty_ 前缀是「该校区暂无班级」的占位节点，不能当 clazzId 提交。
    const isClazz = last != null && !String(last).startsWith('__empty_')
      && clazzes.some((k) => k.id === last);
    setBusy(true);
    try {
      await api.saveAgent({
        id: v.id ?? null,
        code: v.code,
        // 平台级 = 不选任何机构；选中机构但不选下级 = 本机构全部校区
        orgId: path.length ? path[0] : null,
        campusId: path.length >= 2 ? path[1] : null,
        clazzId: isClazz ? last : null,
        name: v.name,
        description: v.description || '',
        targetRole: v.targetRole,
        systemPrompt: null,
        model: v.model || '',
        temperature: v.temperature,
        maxTokens: v.maxTokens,
        enabled: v.enabled ? 1 : 0
      });
      message.success('已保存');
      setOpen(false);
      load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  const toggleEnabled = async (agent) => {
    try {
      await api.setAgentEnabled(agent.id, agent.enabled === 1 ? 0 : 1);
      message.success(agent.enabled === 1 ? '已停用' : '已启用');
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  const remove = async (agent) => {
    try {
      await api.deleteAgent(agent.id);
      message.success('已删除');
      load();
    } catch (e) {
      message.error(e.message);
    }
  };

  // ==================== 工具绑定 ====================

  const openBind = (agent) => {
    setDryResult(null);
    setBindTarget(agent);
    setBindKeys((agent.tools || []).map((t) => t.toolKey));
  };

  const saveBind = async () => {
    setBindBusy(true);
    try {
      await api.bindAgentTools(bindTarget.id, bindKeys);
      message.success('已保存工具绑定');
      setBindTarget(null);
      load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setBindBusy(false);
    }
  };

  // ==================== 上下文预演 ====================

  const openDry = (agent) => {
    setDryTarget(agent);
    setDryResult(null);
  };

  const runDry = async () => {
    if (!dryTarget || !dryStudent) return;
    setDryBusy(true);
    try {
      setDryResult(unwrap(await api.dryRunAgent(dryTarget.code, dryStudent)));
    } catch (e) {
      message.error(e.message);
      setDryResult(null);
    } finally {
      setDryBusy(false);
    }
  };

  // ==================== 表格列 ====================

  const agentColumns = [
    {
      title: 'Agent', dataIndex: 'name', render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Space size={6}>
            <Typography.Text strong>{r.name}</Typography.Text>
            {r.platformLevel
              ? <Tag color="blue">平台级</Tag>
              : <Tag color="green">本机构</Tag>}
          </Space>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            <code>{r.code}</code> · {r.description}
          </Typography.Text>
        </Space>
      )
    },
    {
      title: '生效范围', dataIndex: 'scopeLabel', width: 190,
      render: (_, r) => {
        const meta = SCOPE_META[r.scopeLevel] || SCOPE_META.PLATFORM;
        return (
          <Tooltip title={r.scopeLevel === 'CLAZZ'
            ? '只有该班级的师生会看到这个 Agent；同校区其他班不受影响'
            : (r.scopeLevel === 'CAMPUS'
              ? '该校区下所有班级的师生都会看到这个 Agent'
              : '覆盖范围内的全部人员都会看到')}>
            <Space size={4}>
              <Tag color={meta.color}>{meta.label}</Tag>
              {r.campusName && <Typography.Text style={{ fontSize: 12 }}>{r.campusName}</Typography.Text>}
              {r.clazzName && <Tag>{r.clazzName}</Tag>}
            </Space>
          </Tooltip>
        );
      }
    },
    {
      title: '面向角色', dataIndex: 'targetRole', width: 100,
      render: (v) => <Tag>{ROLE_LABEL[v] || v}</Tag>
    },
    {
      title: '工具', dataIndex: 'toolCount', width: 150,
      render: (_, r) => (
        <Space size={4} wrap>
          <Tag color="blue">{r.toolCount} 个</Tag>
          {r.writeToolCount > 0 && <Tag color="red">{r.writeToolCount} 个写入</Tag>}
          {r.writeToolCount === 0 && <Tag color="green">全只读</Tag>}
        </Space>
      )
    },
    {
      title: '模型', dataIndex: 'model', width: 130,
      render: (v, r) => (v
        ? <Typography.Text style={{ fontSize: 12 }}>{v}</Typography.Text>
        : <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            后端默认 · T={r.temperature}
          </Typography.Text>)
    },
    {
      title: '启用', dataIndex: 'enabled', width: 80,
      render: (v, r) => <Switch size="small" checked={v === 1} onChange={() => toggleEnabled(r)} />
    },
    {
      title: '操作', width: 250, render: (_, r) => (
        <Space size={4} wrap>
          <Tooltip title="以某个学生的身份把只读工具真跑一遍，看它到底能拿到什么">
            <Button size="small" icon={<ExperimentOutlined />} onClick={() => openDry(r)}>预演</Button>
          </Tooltip>
          <Button size="small" icon={<LinkOutlined />} onClick={() => openBind(r)}>工具</Button>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEditor(r)}>编辑</Button>
          <Popconfirm title="删除该 Agent？" description="其工具绑定会一并删除" onConfirm={() => remove(r)}
            okText="删除" cancelText="取消">
            <Button size="small" danger icon={<DeleteOutlined />} />
          </Popconfirm>
        </Space>
      )
    }
  ];

  // ==================== 生效范围总览 ====================

  const campusColumns = [
    { title: '校区', dataIndex: 'name', width: 160 },
    { title: '班级数', dataIndex: 'clazzCount', width: 80 },
    {
      title: '单独配置', dataIndex: 'directCount', width: 100,
      render: (v) => (v > 0
        ? <Tag color="cyan">{v} 个校区级</Tag>
        : <Typography.Text type="secondary" style={{ fontSize: 12 }}>未单独配置</Typography.Text>)
    },
    {
      title: '实际生效', dataIndex: 'effectiveCount', width: 150,
      render: (_, r) => (
        <Space size={4} wrap>
          <Tag color="blue">{r.effectiveCount} 个</Tag>
          {r.effectiveCount === 0 && <Tag color="orange">该校区没有任何可用 Agent</Tag>}
        </Space>
      )
    },
    {
      title: '校区级 Agent', dataIndex: 'directAgents', render: (v) => (
        v.length
          ? v.map((a) => (
            <Tag key={a.id} color={a.enabled === 1 ? 'cyan' : 'default'}>
              {a.name}{a.enabled !== 1 ? '（停用）' : ''}
            </Tag>
          ))
          : <Typography.Text type="secondary" style={{ fontSize: 12 }}>—</Typography.Text>
      )
    },
    {
      title: '', width: 90, render: (_, r) => (
        <Button type="link" size="small" onClick={() => { setCampusId(r.id); setClazzId(null); }}>
          查看
        </Button>
      )
    }
  ];

  const clazzColumns = [
    { title: '班级', dataIndex: 'name', width: 160 },
    { title: '年级', dataIndex: 'grade', width: 90, render: (v) => v || '—' },
    { title: '科目', dataIndex: 'subject', width: 80, render: (v) => v || '—' },
    {
      title: '所属校区', dataIndex: 'campusId', width: 130,
      render: (v) => campuses.find((c) => c.id === v)?.name || '—'
    },
    {
      title: '单独配置', dataIndex: 'directCount', width: 100,
      render: (v) => (v > 0
        ? <Tag color="gold">{v} 个班级级</Tag>
        : <Typography.Text type="secondary" style={{ fontSize: 12 }}>未单独配置</Typography.Text>)
    },
    {
      title: '实际生效', dataIndex: 'effectiveCount', width: 150,
      render: (_, r) => (
        <Space size={4} wrap>
          <Tag color="blue">{r.effectiveCount} 个</Tag>
          {r.effectiveCount === 0 && <Tag color="orange">该班级没有任何可用 Agent</Tag>}
        </Space>
      )
    },
    {
      title: '班级级 Agent', dataIndex: 'directAgents', render: (v) => (
        v.length
          ? v.map((a) => (
            <Tag key={a.id} color={a.enabled === 1 ? 'gold' : 'default'}>
              {a.name}{a.enabled !== 1 ? '（停用）' : ''}
            </Tag>
          ))
          : <Typography.Text type="secondary" style={{ fontSize: 12 }}>—</Typography.Text>
      )
    },
    {
      title: '', width: 90, render: (_, r) => (
        <Button type="link" size="small" onClick={() => { setClazzId(r.id); setCampusId(null); }}>
          查看
        </Button>
      )
    }
  ];

  const toolColumns = [
    {
      title: '工具', dataIndex: 'name', render: (_, t) => (
        <Space direction="vertical" size={0}>
          <Typography.Text strong>{t.name}</Typography.Text>
          <code style={{ fontSize: 12, color: 'var(--color-muted)' }}>{t.key}</code>
        </Space>
      )
    },
    { title: '分类', dataIndex: 'category', width: 80, render: (v) => <Tag>{v}</Tag> },
    {
      title: '读写', dataIndex: 'sideEffect', width: 90,
      render: (v) => (v === 'WRITE'
        ? <Tooltip title="有副作用：不参与上下文预演，真实调用需人工确认"><Tag color="red">写入</Tag></Tooltip>
        : <Tag color="green">只读</Tag>)
    },
    {
      title: '对应接口', dataIndex: 'endpoint', width: 260,
      render: (v) => <Typography.Text type="secondary" style={{ fontSize: 12 }}>{v}</Typography.Text>
    },
    {
      title: '被引用', dataIndex: 'usedByAgents', width: 190,
      render: (v, t) => (v.length
        ? <Space size={4} wrap>{v.map((c) => <Tag key={c} style={{ fontSize: 11 }}>{c}</Tag>)}</Space>
        : <Typography.Text type="secondary" style={{ fontSize: 12 }}>未被引用</Typography.Text>)
    },
    {
      title: '调用观测', dataIndex: 'callCount', width: 160,
      render: (_, t) => {
        if (!t.callCount) {
          return <Typography.Text type="secondary" style={{ fontSize: 12 }}>尚无记录</Typography.Text>;
        }
        return (
          <Typography.Text style={{ fontSize: 12 }}>
            {t.callCount} 次 · 成功率 {t.successRate}% · 均 {t.avgCostMs}ms
          </Typography.Text>
        );
      }
    }
  ];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Alert
        type="info"
        showIcon
        icon={<SafetyCertificateOutlined />}
        message="管理 Agent 可见范围与工具预演"
        description={
          <div style={{ fontSize: 13, lineHeight: 1.7 }}>
            <div><Tag color="default">工具</Tag>可绑定已有工具并查看调用记录；只读工具支持预演。</div>
            <div><Tag color="orange">配置</Tag>维护名称、目标角色、工具绑定和校区 / 班级可见范围。
              模型参数为后续 Agent 运行时预留；当前讲解生成使用「大模型接入」中的配置。</div>
            <div><Tag color="cyan">生效范围</Tag>平台 / 机构 / 校区 / 班级四档。
              它只决定「这个 Agent 在哪儿启用」——同样是编排层，<b>不放开任何数据访问</b>。
              越界的校区、班级 id 会被后端直接 403。</div>
            <div><Tag color="red">权限层</Tag>本页<b>刻意不提供任何入口</b>。
              Agent 能访问哪些数据，永远由后端按调用者身份实时算——
              配置只能决定「看不看得到这个按钮」，决定不了「能不能拿到这份数据」。</div>
          </div>
        }
      />

      <Row gutter={16}>
        <Col span={6}><Card size="small"><Statistic title="Agent 编排" value={agents.length}
          suffix="个" prefix={<RobotOutlined />} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="工具总数" value={tools.length}
          suffix="个" prefix={<ApiOutlined />} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="只读工具"
          value={tools.filter((t) => t.sideEffect === 'READ').length} suffix="个"
          prefix={<SafetyCertificateOutlined />} /></Card></Col>
        <Col span={6}><Card size="small"><Statistic title="写入工具"
          value={tools.filter((t) => t.sideEffect === 'WRITE').length} suffix="个"
          valueStyle={{ color: '#cf1322' }} prefix={<ApiOutlined />} /></Card></Col>
      </Row>

      <Card>
        <Tabs items={[
          {
            key: 'agents', label: 'Agent 编排',
            children: (
              <>
                <Space style={{ marginBottom: 12 }} wrap>
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => openEditor(null)}>
                    新建 Agent
                  </Button>
                  <Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>
                  <span style={{ width: 12 }} />
                  {isPlatformAdmin && (
                    <Select allowClear placeholder="按机构筛选" style={{ minWidth: 180 }}
                      value={orgId ?? undefined}
                      options={orgs.map((o) => ({ value: o.id, label: o.name }))}
                      onChange={(v) => { setOrgId(v ?? null); setCampusId(null); setClazzId(null); }}
                    />
                  )}
                  <Select allowClear placeholder="按校区筛选" style={{ minWidth: 170 }}
                    value={campusId ?? undefined}
                    options={campuses.map((c) => ({ value: c.id, label: c.name }))}
                    onChange={(v) => { setCampusId(v ?? null); setClazzId(null); }}
                  />
                  <Select allowClear placeholder="按班级筛选" style={{ minWidth: 170 }}
                    value={clazzId ?? undefined}
                    options={clazzes.map((k) => ({
                      value: k.id,
                      label: `${campuses.find((c) => c.id === k.campusId)?.name || ''} ${k.name}`.trim()
                    }))}
                    onChange={(v) => { setClazzId(v ?? null); if (v) setCampusId(null); }}
                  />
                  {(orgId || campusId || clazzId) && (
                    <Button type="link" size="small"
                      onClick={() => { setCampusId(null); setClazzId(null); setOrgId(isPlatformAdmin ? null : orgId); }}>
                      清除筛选
                    </Button>
                  )}
                </Space>
                {(campusId || clazzId) && (
                  <Alert type="info" showIcon style={{ marginBottom: 12 }}
                    message="当前按生效范围下钻"
                    description="列出的是「在这个校区 / 班级里实际生效」的全部 Agent —— 包括从机构级、平台级继承下来的，不只是精确挂在该单元上的。" />
                )}
                <Table rowKey="id" size="small" loading={loading} dataSource={agents}
                  columns={agentColumns} pagination={false} scroll={{ x: 1100 }} />
              </>
            )
          },
          {
            key: 'campus', label: <span><ClusterOutlined /> 校区（{scopes?.campuses?.length || 0}）</span>,
            children: (
              <>
                <Alert type="info" showIcon style={{ marginBottom: 12 }}
                  message="这张表回答的是「哪个校区漏配了」"
                  description={
                    <div style={{ fontSize: 13, lineHeight: 1.7 }}>
                      <b>单独配置</b> = 精确挂在该校区上的 Agent；
                      <b>实际生效</b> = 加上从机构级与平台级继承下来的。
                      两个数字分开，是为了避免「一个都没单独配过的校区」被误读成「没开 Agent」。
                    </div>
                  } />
                <Table rowKey="id" size="small" loading={loading}
                  dataSource={scopes?.campuses || []}
                  columns={campusColumns} pagination={false}
                  locale={{ emptyText: <Empty description="本机构下还没有校区，请先到「机构管理」创建" /> }}
                  scroll={{ x: 900 }} />
              </>
            )
          },
          {
            key: 'clazz', label: <span><TeamOutlined /> 班级（{scopes?.clazzes?.length || 0}）</span>,
            children: (
              <>
                <Alert type="info" showIcon style={{ marginBottom: 12 }}
                  message="这张表回答的是「哪个班漏配了」"
                  description="班级级 Agent 只对该班师生可见；同校区其他班不受影响。实际生效数包含校区级、机构级与平台级继承。" />
                <Table rowKey="id" size="small" loading={loading}
                  dataSource={scopes?.clazzes || []}
                  columns={clazzColumns} pagination={{ pageSize: 12 }}
                  locale={{ emptyText: <Empty description="本机构下还没有班级，请先到「机构管理」创建" /> }}
                  scroll={{ x: 1050 }} />
              </>
            )
          },
          {
            key: 'tools', label: `工具注册表（${tools.length}）`,
            children: (
              <>
                <Alert type="warning" showIcon style={{ marginBottom: 12 }}
                  message="这张表是只读的"
                  description="工具是代码，不入库。写入类工具不会参与上下文预演——预演必须零副作用；真实调用它们必须经过人工确认。" />
                <Table rowKey="key" size="small" loading={loading} dataSource={tools}
                  columns={toolColumns} pagination={false} scroll={{ x: 1000 }} />
              </>
            )
          }
        ]} />
      </Card>

      {/* ==================== 编辑 ==================== */}
      <Modal title={form.getFieldValue('id') ? '编辑 Agent' : '新建 Agent'} open={open}
        onCancel={() => setOpen(false)} onOk={save} confirmLoading={busy}
        okText="保存" cancelText="取消" destroyOnHidden width={620}>
        <Form form={form} layout="vertical">
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="code" label="编码" rules={[
                { required: true, message: '请输入编码' },
                { pattern: /^[a-z][a-z0-9-]*$/, message: '小写字母开头，只能含小写字母、数字与短横线' }
              ]}>
                <Input placeholder="student-planner" disabled={!!form.getFieldValue('id')} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
                <Input placeholder="学生学习规划师" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="一句话说明">
            <Input placeholder="诊断 → 学 → 练 → 复习，讲清楚下一步先做什么" />
          </Form.Item>
          <Form.Item name="scopePath" label="生效范围"
            extra="留空 = 全平台启用（仅平台管理员）。选到机构 = 该机构全部校区；选到班级 = 只有该班师生可见。">
            <Cascader options={scopeOptions} changeOnSelect allowClear
              placeholder="全平台 / 选择机构 · 校区 · 班级" style={{ width: '100%' }} />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="targetRole" label="面向角色" rules={[{ required: true }]}>
                <Select options={Object.entries(ROLE_LABEL).map(([v, l]) => ({ value: v, label: l }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="model" label="模型标识" extra="留空则用后端默认模型">
                <Input placeholder="gpt-4o-mini" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="temperature" label="采样温度">
                <Input type="number" step="0.1" min="0" max="2" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="maxTokens" label="响应上限">
                <Input type="number" step="256" min="256" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="enabled" label="启用" valuePropName="checked">
                <Switch />
              </Form.Item>
            </Col>
          </Row>
          <Alert type="info" style={{ fontSize: 12 }}
            message="提示词与工具参数覆盖将在下一期开放在线编辑；此处新建的 Agent 默认为只读编排骨架。" />
        </Form>
      </Modal>

      {/* ==================== 绑定工具 ==================== */}
      <Modal title={`绑定工具 · ${bindTarget?.name || ''}`} open={!!bindTarget}
        onCancel={() => setBindTarget(null)} onOk={saveBind} confirmLoading={bindBusy}
        okText="保存" cancelText="取消" destroyOnHidden width={760}>
        {bindTarget && (
          <>
            <Alert type="warning" showIcon style={{ marginBottom: 12 }}
              message={`当前已挂 ${bindTarget.writeToolCount} 个写入工具`}
              description="写入工具会真正改动数据（题库 / 图谱 / 任务）。它们不参与上下文预演，真实调用必须经过人工确认。请只挂必要的那几个。" />
            {grouped.map((g) => (
              <div key={g.category} style={{ marginBottom: 12 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>{g.category}</Typography.Text>
                <Checkbox.Group value={bindKeys} onChange={setBindKeys}
                  style={{ display: 'block', marginTop: 8 }}>
                  <Row gutter={[8, 8]}>
                    {g.items.map((t) => (
                      <Col span={12} key={t.key}>
                        <Card size="small" styles={{ body: { padding: '8px 12px' } }}
                          style={{ borderColor: bindKeys.includes(t.key) ? '#1677ff' : undefined }}>
                          <Space direction="vertical" size={0} style={{ width: '100%' }}>
                            <Space size={6}>
                              <Checkbox value={t.key} />
                              <Typography.Text style={{ fontSize: 13 }}>{t.name}</Typography.Text>
                              {t.sideEffect === 'WRITE' && <Tag color="red">写入</Tag>}
                            </Space>
                            <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                              {t.endpoint}
                            </Typography.Text>
                          </Space>
                        </Card>
                      </Col>
                    ))}
                  </Row>
                </Checkbox.Group>
              </div>
            ))}
          </>
        )}
      </Modal>

      {/* ==================== 上下文预演 ==================== */}
      <Modal title={`上下文预演 · ${dryTarget?.name || ''}`} open={!!dryTarget}
        onCancel={() => setDryTarget(null)} footer={[
          <Button key="cancel" onClick={() => setDryTarget(null)}>关闭</Button>,
          <Button key="run" type="primary" loading={dryBusy} onClick={runDry}
            disabled={!dryStudent}>开始预演</Button>
        ]} width={860} destroyOnHidden>
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert type="warning" showIcon
            message="预演不调用大模型、不写任何业务数据"
            description="它回答的是「这个 Agent 以某个人的身份，到底能拿到哪些数据」，而不是「它会怎么回答」。参数按上下文推导：需要知识点的用推荐列表首个，需要关键词的用该知识点名称。" />
          <Space>
            <Typography.Text>选择学习上下文的学生：</Typography.Text>
            <Select style={{ minWidth: 220 }} value={dryStudent} onChange={setDryStudent}
              placeholder="选择学生"
              options={students.map((s) => ({ value: s.id, label: `${s.name}（id=${s.id}）` }))} />
          </Space>

          {dryBusy && <div style={{ textAlign: 'center', padding: 24 }}><Spin tip="正在以该学生身份逐个调用只读工具" /></div>}

          {dryResult && (
            <>
              <Row gutter={12}>
                <Col span={6}><Card size="small"><Statistic title="工具总数" value={dryResult.total} /></Card></Col>
                <Col span={6}><Card size="small"><Statistic title="成功" value={dryResult.okCount}
                  valueStyle={{ color: '#389e0d' }} /></Card></Col>
                <Col span={6}><Card size="small"><Statistic title="跳过" value={dryResult.skippedCount} /></Card></Col>
                <Col span={6}><Card size="small"><Statistic title="失败" value={dryResult.errorCount}
                  valueStyle={{ color: dryResult.errorCount ? '#cf1322' : undefined }} /></Card></Col>
              </Row>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                {dryResult.identityNote}
              </Typography.Text>
              <List size="small" bordered
                dataSource={dryResult.steps}
                locale={{ emptyText: <Empty description="该 Agent 尚未绑定任何工具" /> }}
                renderItem={(s) => {
                  const meta = STATUS_META[s.status] || STATUS_META.SKIPPED;
                  return (
                    <List.Item key={s.toolKey}>
                      <List.Item.Meta
                        avatar={<Tag color={meta.color}>{meta.label}</Tag>}
                        title={
                          <Space size={6}>
                            <Typography.Text strong>{s.toolName}</Typography.Text>
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {s.toolKey}
                            </Typography.Text>
                            {s.sideEffect === 'WRITE' && <Tag color="red">写入</Tag>}
                            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                              {s.costMs}ms
                            </Typography.Text>
                          </Space>
                        }
                        description={
                          <Space direction="vertical" size={0}>
                            {s.brief?.summary && <Typography.Text style={{ fontSize: 12 }}>
                              {s.brief.summary}
                            </Typography.Text>}
                            {Object.entries(s.brief || {})
                              .filter(([k]) => k !== 'summary')
                              .map(([k, v]) => (
                                <Typography.Text key={k} type="secondary" style={{ fontSize: 12 }}>
                                  {k} = {String(v)}
                                </Typography.Text>
                              ))}
                            {s.error && <Typography.Text type="danger" style={{ fontSize: 12 }}>
                              {s.error}
                            </Typography.Text>}
                          </Space>
                        }
                      />
                    </List.Item>
                  );
                }}
              />
            </>
          )}
        </Space>
      </Modal>
    </Space>
  );
}
