import { useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { Form, Input, Button, Typography, Space, Tag, message, Alert, Divider } from 'antd';
import { UserOutlined, LockOutlined, ApartmentOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { saveSession, homePathOf, ROLE_LABEL } from '../auth.js';

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
    <div style={{
      minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'linear-gradient(135deg, #e6f4ff 0%, #f7f9fc 45%, #e6fffb 100%)', padding: 24
    }}>
      <div style={{
        width: 900, display: 'flex', background: '#fff', borderRadius: 14, overflow: 'hidden',
        boxShadow: '0 16px 48px rgba(0, 0, 0, 0.12)'
      }}>
        {/* 左：品牌与角色差异说明 */}
        <div style={{ flex: 1.05, padding: '40px 34px', background: 'linear-gradient(165deg, #1677ff, #0958d9)', color: '#fff' }}>
          <Space align="center" size={10}>
            <ApartmentOutlined style={{ fontSize: 26 }} />
            <Typography.Title level={3} style={{ color: '#fff', margin: 0 }}>知识图谱 · 题库</Typography.Title>
          </Space>
          <Typography.Paragraph style={{ color: 'rgba(255,255,255,.88)', marginTop: 16, marginBottom: 20 }}>
            一套系统三种视角。登录后能看到的菜单、能调用的接口，都由角色决定并由后端强制校验。
          </Typography.Paragraph>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            {DEMO_ACCOUNTS.map((acc) => (
              <div key={acc.role} style={{
                background: 'rgba(255,255,255,.14)', borderRadius: 8, padding: '10px 14px',
                border: '1px solid rgba(255,255,255,.22)'
              }}>
                <Space>
                  <Tag color={acc.color} style={{ marginRight: 0 }}>{ROLE_LABEL[acc.role]}</Tag>
                  <span style={{ fontWeight: 600 }}>{acc.username}</span>
                  <span style={{ color: 'rgba(255,255,255,.7)', fontSize: 12 }}>{acc.desc}</span>
                </Space>
              </div>
            ))}
          </div>
        </div>

        {/* 右：登录表单 */}
        <div style={{ flex: 1, padding: '44px 38px' }}>
          <Typography.Title level={4} style={{ marginTop: 0 }}>账号登录</Typography.Title>
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
              登录
            </Button>
          </Form>

          <Divider plain style={{ color: '#bfbfbf', fontSize: 12, marginTop: 26 }}>快速体验</Divider>
          <Space wrap>
            {DEMO_ACCOUNTS.map((acc) => (
              <Button key={acc.role} size="small" onClick={() => fill(acc)}>填入{ROLE_LABEL[acc.role]}账号</Button>
            ))}
          </Space>
        </div>
      </div>
    </div>
  );
}
