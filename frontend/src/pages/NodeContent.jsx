import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Card, Space, Tag, Typography, message, Empty, Button, Tabs, Descriptions, Alert, Divider
} from 'antd';
import {
  ArrowLeftOutlined, ExperimentOutlined, ReadOutlined, BulbOutlined, FileTextOutlined
} from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import Markdown from '../components/Markdown.jsx';

const TYPE_ICON = {
  LECTURE: <ReadOutlined />,
  EXAMPLE: <BulbOutlined />,
  MICRO_LESSON: <FileTextOutlined />,
  CARD: <FileTextOutlined />
};
const BY_LABEL = {
  LLM: { text: '大模型生成', color: 'blue' },
  TEACHER: { text: '教师编辑', color: 'green' },
  TEMPLATE: { text: '规则模板', color: 'orange' }
};

/**
 * 知识点学习内容页（学生视角，只展示已发布内容）。
 *
 * <p>支持从学习任务直接进入（/my/learn/:nodeId），也可以带 examId 返回答题页。</p>
 */
export default function NodeContent() {
  const nav = useNavigate();
  const { nodeId } = useParams();
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);
  const [active, setActive] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = async () => {
    setLoading(true);
    try {
      const list = unwrap(await api.publishedContents(Number(nodeId))) || [];
      setItems(list);
      setActive(list[0]?.id ?? null);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [nodeId]);

  const practice = async () => {
    setBusy(true);
    try {
      const ex = unwrap(await api.practiceNow({ nodeId: Number(nodeId), count: 5 }));
      message.success(`已生成 ${ex.questions?.length || 0} 题的专项练习`);
      nav(`/exams/take/${ex.examId}`);
    } catch (e) {
      message.error(e.message);
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return <Card size="small" loading />;
  }
  if (!items.length) {
    const first = null;
    return (
      <Card size="small">
        <Empty description="这个知识点还没有发布学习内容">
          <Space direction="vertical" size={8}>
            <Typography.Text type="secondary">
              老师还没有为它生成 / 发布讲义，你可以直接做题，或者稍后再来。
            </Typography.Text>
            <Space>
              <Button onClick={() => nav(-1)} icon={<ArrowLeftOutlined />}>返回</Button>
              <Button type="primary" icon={<ExperimentOutlined />} loading={busy} onClick={practice}>
                直接做专项练习
              </Button>
            </Space>
          </Space>
        </Empty>
        {first}
      </Card>
    );
  }

  const current = items.find((i) => i.id === active) || items[0];
  const by = BY_LABEL[current.generatedBy] || { text: current.generatedBy, color: 'default' };

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small"
        title={
          <Space size={8}>
            <ReadOutlined />
            <span>{current.title || '学习内容'}</span>
            <Tag color={by.color}>{by.text}</Tag>
            {current.version > 1 && <Tag>v{current.version}</Tag>}
          </Space>
        }
        extra={
          <Space>
            <Button size="small" icon={<ArrowLeftOutlined />} onClick={() => nav('/my')}>返回学习首页</Button>
            <Button size="small" type="primary" icon={<ExperimentOutlined />} loading={busy} onClick={practice}>
              学完练一手
            </Button>
          </Space>
        }>
        <Descriptions size="small" column={3} style={{ marginBottom: 4 }}>
          <Descriptions.Item label="内容类型">{current.typeLabel || current.type}</Descriptions.Item>
          <Descriptions.Item label="来源">{by.text}{current.model ? `（${current.model}）` : ''}</Descriptions.Item>
          <Descriptions.Item label="更新时间">{(current.updatedAt || '').replace('T', ' ').slice(0, 19)}</Descriptions.Item>
        </Descriptions>
        {current.summary && (
          <Alert type="info" showIcon style={{ marginTop: 8 }} message={current.summary} />
        )}
      </Card>

      {items.length > 1 ? (
        <Card size="small">
          <Tabs activeKey={String(current.id)} onChange={(k) => setActive(Number(k))}
            items={items.map((i) => ({
              key: String(i.id),
              label: <Space size={4}>{TYPE_ICON[i.type]}{i.typeLabel || i.type}</Space>,
              children: (
                <div style={{ maxWidth: 900 }}>
                  <Markdown text={i.body} />
                </div>
              )
            }))} />
        </Card>
      ) : (
        <Card size="small">
          <div style={{ maxWidth: 900 }}>
            <Markdown text={current.body} />
          </div>
        </Card>
      )}

      <Divider style={{ margin: 0 }} />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        看完内容记得去练一手——掌握度是按做题得分率更新的，光看不练不涨分。
      </Typography.Text>
    </Space>
  );
}
