import { BrowserRouter, Routes, Route, Navigate, useNavigate, useLocation } from 'react-router-dom';
import {
  Layout, Menu, Typography, Space, Tag, Dropdown, Modal, Form, Input, message, Button, Card
} from 'antd';
import {
  ApartmentOutlined, SearchOutlined, TeamOutlined, FileTextOutlined, RocketOutlined,
  ReadOutlined, SettingOutlined, LogoutOutlined, UserOutlined, KeyOutlined, DownOutlined,
  BankOutlined, BulbOutlined, SlidersOutlined, AimOutlined, MenuFoldOutlined, MenuUnfoldOutlined,
  RobotOutlined
} from '@ant-design/icons';
import { lazy, Suspense, useEffect, useState } from 'react';
import { api, unwrap } from './api.js';
import { readSession, clearSession, subscribeSession, ROLE, ROLE_LABEL, homePathOf } from './auth.js';
import Login from './pages/Login.jsx';
import AdminConsole from './pages/AdminConsole.jsx';
import OrgConsole from './pages/OrgConsole.jsx';
import GraphList from './pages/GraphList.jsx';
import SearchPage from './pages/SearchPage.jsx';
import StudentList from './pages/StudentList.jsx';
import QuestionList from './pages/QuestionList.jsx';
import ExamCenter from './pages/ExamCenter.jsx';
import PaperTake from './pages/PaperTake.jsx';
import GradingDesk from './pages/GradingDesk.jsx';
import MyAssignments from './pages/MyAssignments.jsx';
import LearnHome from './pages/LearnHome.jsx';
import DiagnosisReport from './pages/DiagnosisReport.jsx';
import NodeContent from './pages/NodeContent.jsx';
import ContentManage from './pages/ContentManage.jsx';
import RecommendRules from './pages/RecommendRules.jsx';
import Agents from './pages/Agents.jsx';
import ThemePicker from './components/ThemePicker.jsx';

const GraphEditor = lazy(() => import('./pages/GraphEditor.jsx'));
const StudentGraphView = lazy(() => import('./pages/StudentGraphView.jsx'));

const { Sider, Header, Content } = Layout;

/**
 * 菜单与角色的对应关系。学生看不到图谱编辑、题库、学生名册；
 * 系统运维只给管理员。后端 @RequireRole 是同一条边界的真实拦截。
 */
const MENUS = [
  { key: '/org', icon: <BankOutlined />, label: '机构管理', roles: [ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/graphs', icon: <ApartmentOutlined />, label: '图谱管理', roles: [ROLE.TEACHER, ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/search', icon: <SearchOutlined />, label: '图谱检索', roles: [ROLE.STUDENT, ROLE.TEACHER, ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/students', icon: <TeamOutlined />, label: '学生管理', roles: [ROLE.TEACHER, ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/questions', icon: <FileTextOutlined />, label: '题库管理', roles: [ROLE.TEACHER, ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/contents', icon: <BulbOutlined />, label: '教学内容', roles: [ROLE.TEACHER, ROLE.ORG_ADMIN, ROLE.ADMIN] },
  {
    key: '/exams', icon: <RocketOutlined />, label: '出题中心',
    labelByRole: { STUDENT: '我的作业' },
    roles: [ROLE.STUDENT, ROLE.TEACHER, ROLE.ORG_ADMIN, ROLE.ADMIN]
  },
  { key: '/my', icon: <ReadOutlined />, label: '我的学习', roles: [ROLE.STUDENT] },
  { key: '/my/diagnosis', icon: <AimOutlined />, label: '学习诊断', roles: [ROLE.STUDENT] },
  { key: '/recommend-rules', icon: <SlidersOutlined />, label: '推荐规则', roles: [ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/agents', icon: <RobotOutlined />, label: 'Agent 管理', roles: [ROLE.ORG_ADMIN, ROLE.ADMIN] },
  { key: '/admin', icon: <SettingOutlined />, label: '系统运维', roles: [ROLE.ADMIN] }
];

const TITLES = {
  '/': '知识图谱 · 题库管理系统',
  '/org': '机构管理平台',
  '/graphs': '图谱管理',
  '/search': '图谱检索',
  '/students': '学生管理',
  '/questions': '题库管理',
  '/contents': '教学内容',
  '/exams': '出题中心',
  '/my': '我的学习',
  '/my/diagnosis': '我的诊断报告',
  '/recommend-rules': '学习优先级推荐规则',
  '/agents': 'Agent 管理台',
  '/admin': '系统运维'
};

const DESCRIPTIONS = {
  '/org': '连接师生，让教学协作更轻松。',
  '/graphs': '把零散的知识，连成看得见的成长地图。',
  '/search': '从一个问题出发，发现知识之间的联系。',
  '/students': '看见每一位学生的进步，找到下一步的方向。',
  '/questions': '积累好题，让每一次练习都有收获。',
  '/contents': '让知识更好懂，让学习更有趣。',
  '/exams': '用恰到好处的练习，巩固每一个知识点。',
  '/my': '每天前进一点点，今天也有新的收获。',
  '/my/diagnosis': '了解你的学习起点，找到适合自己的节奏。',
  '/recommend-rules': '为不同的学习起点，安排合适的下一步。',
  '/agents': '把工具、编排与权限分开管，让 Agent 行为可控可查。',
  '/admin': '让每一次教与学，都顺畅发生。'
};

const sectionOf = (pathname) => {
  if (pathname.startsWith('/my/diagnosis')) return '/my/diagnosis';
  if (pathname.startsWith('/my/learn') || pathname === '/my/graph') return '/my';
  return pathname.split('/').slice(0, 2).join('/') || '/';
};

function Shell({ session, onLogout }) {
  const nav = useNavigate();
  const loc = useLocation();
  const { user } = session;
  const studentMode = user.role === ROLE.STUDENT;
  const selected = sectionOf(loc.pathname);

  const [semanticOk, setSemanticOk] = useState(null);
  const [pwdOpen, setPwdOpen] = useState(false);
  const [collapsed, setCollapsed] = useState(false);
  const [pwdForm] = Form.useForm();

  useEffect(() => { window.scrollTo(0, 0); }, [loc.pathname]);

  useEffect(() => {
    // 向量索引状态属管理员接口，其他角色不发请求（否则每次进出都产生一个必然的 403）
    if (user.role !== ROLE.ADMIN) return;
    api.indexStatus().then((r) => setSemanticOk(unwrap(r)?.available)).catch(() => setSemanticOk(null));
  }, [user.role]);

  const menus = MENUS
    .filter((m) => m.roles.includes(user.role))
    .map((m) => ({ key: m.key, icon: m.icon, label: m.labelByRole?.[user.role] || m.label }));

  const title = selected === '/exams' && studentMode
    ? '我的作业'
    : (TITLES[selected] || '知识图谱 · 题库管理系统');

  const changePassword = async () => {
    const values = await pwdForm.validateFields();
    try {
      await api.changePassword({ oldPassword: values.oldPassword, newPassword: values.newPassword });
      message.success('密码已修改，请用新密码重新登录');
      setPwdOpen(false);
      pwdForm.resetFields();
      onLogout();
    } catch (e) {
      message.error(e.message);
    }
  };

  return (
    <Layout className="app-shell">
      <Sider className="app-sidebar" theme="light" width={232} breakpoint="lg" collapsedWidth={0}
        collapsed={collapsed} trigger={null} onBreakpoint={setCollapsed}>
        <div className="sidebar-brand">
          <span className="brand-symbol"><ApartmentOutlined /></span>
          <div><strong>知识图谱</strong><span>让每一步学习都有方向</span></div>
        </div>
        <div className="sidebar-section-label">学习工作台</div>
        <Menu
          theme="light"
          mode="inline"
          inlineCollapsed={false}
          selectedKeys={[selected]}
          items={menus}
          onClick={({ key }) => {
            nav(key);
            if (window.matchMedia('(max-width: 767px)').matches) setCollapsed(true);
          }}
        />
        <div className="sidebar-footer">
          <div className="sidebar-note"><span>✦</span><div><strong>每天一点新收获</strong><p>连接知识，持续成长</p></div></div>
          <Space direction="vertical" size={4}>
            <Tag style={{ marginRight: 0 }}>
              {ROLE_LABEL[user.role]} · {user.displayName}
            </Tag>
            {user.role !== ROLE.STUDENT && semanticOk !== null && (
              <Tag color={semanticOk ? 'green' : 'orange'} style={{ marginRight: 0 }}>
                语义检索 {semanticOk ? '可用' : '不可用'}
              </Tag>
            )}
          </Space>
        </div>
      </Sider>

      {!collapsed && <button type="button" className="mobile-nav-backdrop" aria-label="关闭导航" onClick={() => setCollapsed(true)} />}

      <Layout>
        <Header className="app-header">
          <div className="header-heading">
            <Button type="text" aria-label={collapsed ? '展开导航' : '收起导航'} aria-expanded={!collapsed}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={() => setCollapsed(!collapsed)} />
            <span className="header-product">工作台</span><span className="header-divider">/</span><span>{title}</span>
          </div>
          <div className="header-actions">
          <ThemePicker />
          <Dropdown
            trigger={['click']}
            menu={{
              items: [
                { key: 'pwd', icon: <KeyOutlined />, label: '修改密码' },
                { type: 'divider' },
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', danger: true }
              ],
              onClick: ({ key }) => {
                if (key === 'pwd') {
                  pwdForm.resetFields();
                  setPwdOpen(true);
                } else if (key === 'logout') {
                  Modal.confirm({
                    title: '确认退出登录？',
                    onOk: onLogout
                  });
                }
              }
            }}
          >
            <Button type="text" className="user-menu" aria-label="账户菜单">
              <span className="user-avatar"><UserOutlined /></span>
              <span className="user-display-name">{user.displayName}</span>
              <Tag style={{ marginRight: 0 }}>{ROLE_LABEL[user.role]}</Tag>
              <DownOutlined style={{ fontSize: 10, color: 'var(--color-muted)' }} />
            </Button>
          </Dropdown>
          </div>
        </Header>

        <Content className="app-content">
          {!/^\/(graphs\/|exams\/(take|grading)\/|my\/learn\/)/.test(loc.pathname) && <section className="page-welcome">
            <div><h1>{title}</h1><p>{DESCRIPTIONS[selected] || '探索知识的连接，让每一步都更有收获。'}</p></div>
            <div className="welcome-art" aria-hidden="true"><i /><i /><i /><span>✦</span></div>
          </section>}
          <Suspense fallback={<Card loading aria-label="正在加载图谱" style={{ minHeight: 320 }} />}>
          <Routes>
            <Route path="/" element={<Navigate to={homePathOf(user)} replace />} />
            <Route path="/search" element={<SearchPage />} />
            <Route path="/exams/take/:examId" element={<PaperTake />} />
            <Route path="/exams" element={studentMode ? <MyAssignments /> : <ExamCenter />} />

            {studentMode ? (
              <>
                <Route path="/my" element={<LearnHome />} />
                <Route path="/my/graph" element={<StudentGraphView fixedStudentId={user.studentId} />} />
                <Route path="/my/diagnosis" element={<DiagnosisReport />} />
                <Route path="/my/learn/:nodeId" element={<NodeContent />} />
              </>
            ) : (
              <>
                <Route path="/exams/grading/:examId" element={<GradingDesk />} />
                <Route path="/graphs" element={<GraphList />} />
                <Route path="/graphs/:id" element={<GraphEditor />} />
                <Route path="/students" element={<StudentList />} />
                <Route path="/students/:id" element={<StudentGraphView />} />
                <Route path="/students/:id/diagnosis" element={<DiagnosisReport />} />
                <Route path="/students/:id/tasks" element={<LearnHome />} />
                <Route path="/questions" element={<QuestionList />} />
                <Route path="/contents" element={<ContentManage />} />
                <Route path="/recommend-rules" element={<RecommendRules />} />
                <Route path="/agents" element={<Agents />} />
              </>
            )}

            {(user.role === ROLE.ADMIN || user.role === ROLE.ORG_ADMIN) && (
              <Route path="/org" element={<OrgConsole />} />
            )}

            {user.role === ROLE.ADMIN && (
              <Route path="/admin" element={<AdminConsole />} />
            )}

            <Route path="*" element={<Navigate to={homePathOf(user)} replace />} />
          </Routes>
          </Suspense>
        </Content>
      </Layout>

      <Modal title="修改密码" open={pwdOpen} onOk={changePassword}
        onCancel={() => setPwdOpen(false)} destroyOnClose okText="确认修改">
        <Form form={pwdForm} layout="vertical">
          <Form.Item name="oldPassword" label="原密码" rules={[{ required: true, message: '请输入原密码' }]}>
            <Input.Password placeholder="请输入原密码" />
          </Form.Item>
          <Form.Item name="newPassword" label="新密码"
            rules={[{ required: true, message: '请输入新密码' }, { min: 6, message: '至少 6 位' }]}>
            <Input.Password placeholder="6-64 位" />
          </Form.Item>
          <Form.Item name="confirm" label="确认新密码" dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator: (_, v) => (!v || v === getFieldValue('newPassword'))
                  ? Promise.resolve() : Promise.reject(new Error('两次输入不一致'))
              })
            ]}>
            <Input.Password placeholder="再次输入新密码" />
          </Form.Item>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            修改成功后所有设备的登录状态都会失效，需要重新登录。
          </Typography.Text>
        </Form>
      </Modal>
    </Layout>
  );
}

function Router() {
  const nav = useNavigate();
  const [session, setSession] = useState(() => readSession());

  // 订阅会话变化：登录成功后自动切换到对应角色的外壳，登出/令牌失效后回到登录页
  useEffect(() => subscribeSession(setSession), []);

  const logout = async () => {
    try {
      await api.logout();
    } catch {
      // 令牌可能已失效，忽略
    }
    clearSession();
    nav('/login', { replace: true });
  };

  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/*" element={
        session
          ? <Shell session={session} onLogout={logout} />
          : <Navigate to="/login" replace />
      } />
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <Router />
    </BrowserRouter>
  );
}
