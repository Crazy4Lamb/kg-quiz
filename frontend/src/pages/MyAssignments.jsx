import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Card, Table, Button, Space, Tag, Typography, message, Empty, Badge, Progress
} from 'antd';
import { EditOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { ASSIGN_STATUS, scoreColor } from '../examMeta.js';

/** 学生：老师发给我的作业。没发给我的，这里不会出现，接口也取不到。 */
export default function MyAssignments() {
  const nav = useNavigate();
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    try {
      setRows(unwrap(await api.myAssignments()) || []);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);

  const todo = rows.filter(r => r.status === 'ASSIGNED').length;

  return (
    <Card size="small"
      title={`我的作业（${rows.length}）`}
      extra={<Button icon={<ReloadOutlined />} onClick={load}>刷新</Button>}>
      {todo > 0 && (
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          有 <b style={{ color: '#fa8c16' }}>{todo}</b> 份作业还没做，点「去作答」开始。
        </Typography.Paragraph>
      )}
      {rows.length === 0 ? (
        <Empty description="老师还没有给你发作业" />
      ) : (
        <Table rowKey="examId" size="small" loading={loading} dataSource={rows}
          pagination={{ pageSize: 8 }}
          columns={[
            {
              title: '作业', dataIndex: 'name',
              render: (v, row) => (
                <Space direction="vertical" size={0}>
                  <span style={{ fontWeight: 600 }}>{v}</span>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                    {row.source === 'TEACHER' ? `老师布置 · ${row.teacherName || ''}` : '系统按你的掌握度生成'}
                    {row.description ? ` · ${row.description}` : ''}
                  </Typography.Text>
                </Space>
              )
            },
            { title: '题数', dataIndex: 'questionCount', width: 80 },
            {
              title: '状态', dataIndex: 'status', width: 140,
              render: (s, row) => (
                <Space size={4}>
                  <Tag color={ASSIGN_STATUS[s]?.color}>{ASSIGN_STATUS[s]?.label || s}</Tag>
                  {row.pendingCount > 0 && (
                    <Badge count={row.pendingCount} size="small" title="待老师批改的题数" />
                  )}
                </Space>
              )
            },
            {
              title: '得分', width: 180,
              render: (_, row) => row.totalScore == null
                ? <Typography.Text type="secondary">—</Typography.Text>
                : (
                  <Space>
                    <span style={{ fontWeight: 600 }}>
                      {row.totalScore} / {row.fullScore}
                    </span>
                    <Progress size="small" style={{ width: 80, marginBottom: 0 }}
                      percent={Math.round((row.totalScore / row.fullScore) * 100)}
                      strokeColor={scoreColor(row.totalScore / row.fullScore)} />
                  </Space>
                )
            },
            { title: '布置时间', dataIndex: 'assignedAt', width: 165,
              render: v => (v || '').replace('T', ' ').slice(0, 19) },
            {
              title: '操作', width: 130, render: (_, row) => (
                row.status === 'ASSIGNED'
                  ? <Button type="primary" size="small" icon={<EditOutlined />}
                      onClick={() => nav(`/exams/take/${row.examId}`)}>去作答</Button>
                  : <Button size="small" icon={<EyeOutlined />}
                      onClick={() => nav(`/exams/take/${row.examId}`)}>查看答卷</Button>
              )
            }
          ]} />
      )}
    </Card>
  );
}
