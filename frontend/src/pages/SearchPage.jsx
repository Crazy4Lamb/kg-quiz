import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Input, Select, Button, Table, Tag, Space, Typography, message } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const SOURCE_TAG = {
  id: { color: 'blue', text: 'ID 精确' },
  like: { color: 'green', text: '字面匹配' },
  semantic: { color: 'purple', text: '语义召回' }
};

export default function SearchPage() {
  const nav = useNavigate();
  const [keyword, setKeyword] = useState('');
  const [nodeId, setNodeId] = useState('');
  const [graphId, setGraphId] = useState('');
  const [mode, setMode] = useState('all');
  const [graphs, setGraphs] = useState([]);
  const [rows, setRows] = useState([]);
  const [loading, setLoading] = useState(false);
  const [semanticAvailable, setSemanticAvailable] = useState(true);

  useState(() => {
    api.listGraphs().then(r => setGraphs(unwrap(r) || [])).catch(() => {});
  });

  const doSearch = async () => {
    if (!keyword && !nodeId) {
      message.warning('请输入关键词或节点 ID');
      return;
    }
    setLoading(true);
    try {
      const params = { mode };
      if (keyword) params.keyword = keyword;
      if (nodeId) params.nodeId = nodeId;
      if (graphId) params.graphId = graphId;
      const r = await api.searchNodes(params);
      const d = unwrap(r) || {};
      setRows(d.hits || []);
      setSemanticAvailable(d.semanticAvailable !== false);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  const columns = [
    { title: '节点 ID', dataIndex: 'nodeId', width: 90 },
    {
      title: '知识点', render: (_, r) => (
        <Space direction="vertical" size={2}>
          <Typography.Text strong>{r.name}</Typography.Text>
          {r.description && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }} ellipsis>
              {r.description}
            </Typography.Text>
          )}
        </Space>
      )
    },
    { title: '所属图谱', dataIndex: 'graphName', width: 180,
      render: (_, r) => `${r.subject} · ${r.textbook} · ${r.grade}${r.term ? ' · ' + r.term : ''}` },
    { title: '权重', dataIndex: 'weight', width: 70 },
    {
      title: '来源', dataIndex: 'source', width: 100, render: (s) => {
        const meta = SOURCE_TAG[s] || {};
        return <Tag color={meta.color}>{meta.text || s}</Tag>;
      }
    },
    {
      title: '相似度', dataIndex: 'score', width: 90,
      render: (v, r) => r.source === 'semantic' ? Number(v).toFixed(3) : '-'
    },
    {
      title: '操作', width: 90, render: (_, r) => (
        <Button type="link" size="small" onClick={() => nav(`/graphs/${r.graphId}`)}>
          打开图谱
        </Button>
      )
    }
  ];

  return (
    <Card>
      <Space style={{ marginBottom: 16 }} wrap>
        <Input placeholder="关键词（支持字面 + 语义模糊搜索）" style={{ width: 320 }}
          value={keyword} onChange={e => setKeyword(e.target.value)}
          onPressEnter={doSearch} prefix={<SearchOutlined />} allowClear />
        <Input placeholder="节点 ID（精确）" style={{ width: 140 }}
          value={nodeId} onChange={e => setNodeId(e.target.value)} allowClear />
        <Select placeholder="限定图谱" allowClear style={{ width: 240 }}
          value={graphId || undefined}
          onChange={v => setGraphId(v || '')}
          options={graphs.map(g => ({ value: g.id, label: `${g.name}（#${g.id}）` }))} />
        <Select value={mode} onChange={setMode} style={{ width: 130 }} options={[
          { value: 'all', label: '全部来源' },
          { value: 'like', label: '仅字面' },
          { value: 'semantic', label: '仅语义' }
        ]} />
        <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={doSearch}>
          搜索
        </Button>
        {!semanticAvailable && (
          <Typography.Text type="warning" style={{ fontSize: 12 }}>
            语义检索不可用（Qdrant 或 embedding 模型未就绪），当前仅字面匹配
          </Typography.Text>
        )}
      </Space>
      <Table rowKey={r => `${r.nodeId}-${r.source}`} dataSource={rows} columns={columns}
        pagination={{ pageSize: 15 }} locale={{ emptyText: '输入关键词检索知识点，如「分数」「约分」' }} />
    </Card>
  );
}
