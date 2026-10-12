import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Form, Input, Button, Typography, Space, Tag, message, Alert, Divider } from 'antd';
import { UserOutlined, LockOutlined, ApartmentOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { saveSession, homePathOf, ROLE_LABEL } from '../auth.js';
import ThemePicker from '../components/ThemePicker.jsx';

/** 演示账号，点一下直接填表，方便快速切换角色看差异 */
const DEMO_ACCOUNTS = [
  { role: 'ADMIN', username: 'admin', password: 'admin123', color: 'red', desc: '全部功能，含账号管理与系统运维' },
  { role: 'TEACHER', username: 'teacher', password: 'teacher123', color: 'blue', desc: '图谱 / 题库 / 学生 / 出题' },
  { role: 'STUDENT', username: 'stu1', password: '123456', color: 'green', desc: '仅自己的图谱、练习与错题本' }
];

export default function Login() {
  const nav = useNavigate();
  const loc = useLocation();
  const [form] = Form.useForm();
  const [loading, setLoading] = useState(false);
  const expired = new URLSearchParams(loc.search).get('expired');

  const submit = async (values) => {
    setLoading(true);
    try {
      const d = unwrap(await api.login(values));
      saveSession(d.token, d.user);
      message.success(`欢迎回来，${d.user.displayName}（${ROLE_LABEL[d.user.role] || d.user.role}）`);
      nav(homePathOf(d.user), { replace: true });
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const fill = (acc) => {
    form.setFieldsValue({ username: acc.username, password: acc.password });
  };

  return (
    <div className="login-page">
      <header className="login-topbar"><div className="login-wordmark"><span className="brand-symbol"><ApartmentOutlined /></span><strong>知识图谱 · 题库</strong></div><ThemePicker /></header>
      <div className="login-card">
        {/* 左：品牌与角色差异说明 */}
        <div className="login-brand-panel">
          <span className="login-kicker">让好奇心自由生长 ✦</span>
          <h1>知识有连接，<br />成长有<span>颜色。</span></h1>
          <p className="login-intro">探索知识图谱，找到学习的方向。<br />把每一次练习，变成看得见的进步。</p>
          <div className="knowledge-art" aria-hidden="true">
            <svg viewBox="0 0 400 190"><path d="M80 60L200 100L330 45M200 100L115 155M200 100L325 155" /></svg>
            <span className="art-node art-node-one"><ApartmentOutlined />知识</span>
            <span className="art-node art-node-center">✦ 无限可能</span>
            <span className="art-node art-node-two">灵感 ↗</span>
            <span className="art-node art-node-three">练习 ✓</span>
            <span className="art-node art-node-four">成长 +</span>
          </div>
          <div className="login-roles">
            {DEMO_ACCOUNTS.map((acc) => (
              <div key={acc.role} className="login-role">
                  <Tag color={acc.color} style={{ marginRight: 0 }}>{ROLE_LABEL[acc.role]}</Tag>
                  <span>{acc.desc}</span>
              </div>
            ))}
          </div>
        </div>

        {/* 右：登录表单 */}
        <div className="login-form-panel">
          <span className="login-form-kicker">准备好新的收获了吗？</span>
          <Typography.Title level={2} style={{ marginTop: 8 }}>欢迎回来 <span className="login-wave">✦</span></Typography.Title>
          <Typography.Paragraph type="secondary" style={{ fontSize: 13 }}>
            请输入你的登录账号。学生账号由老师在新建学生时开户。
          </Typography.Paragraph>

          {expired && (
            <Alert type="warning" showIcon style={{ marginBottom: 14 }}
              message="登录状态已失效，请重新登录" />
          )}

          <Form form={form} layout="vertical" onFinish={submit} requiredMark={false}
            initialValues={{ username: '', password: '' }}>
            <Form.Item name="username" label="登录名" rules={[{ required: true, message: '请输入登录名' }]}>
              <Input size="large" prefix={<UserOutlined />} placeholder="如 admin / teacher / stu1"
                autoComplete="username" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password size="large" prefix={<LockOutlined />} placeholder="请输入密码"
                autoComplete="current-password" onPressEnter={() => form.submit()} />
            </Form.Item>
            <Button type="primary" size="large" block htmlType="submit" loading={loading}
              style={{ marginTop: 4 }}>
              登录，开启探索 <ArrowRightOutlined />
            </Button>
          </Form>

          <Divider plain style={{ color: 'var(--color-muted)', fontSize: 12, marginTop: 26 }}>快速体验</Divider>
          <Space wrap>
            {DEMO_ACCOUNTS.map((acc) => (
              <Button key={acc.role} size="small" onClick={() => fill(acc)}>填入{ROLE_LABEL[acc.role]}账号</Button>
            ))}
          </Space>
        </div>
      </div>
      <p className="login-footer">连接知识，点亮每一次成长。</p>
    </div>
  );
}
