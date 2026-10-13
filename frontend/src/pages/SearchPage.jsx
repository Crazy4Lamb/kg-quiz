import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Card, Input, Select, Button, Table, Tag, Space, Typography, message, Empty } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import PageToolbar from '../components/PageToolbar.jsx';

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
  const [searched, setSearched] = useState(false);
  const [semanticAvailable, setSemanticAvailable] = useState(true);

  useEffect(() => {
    api.listGraphs().then(r => setGraphs(unwrap(r) || [])).catch(() => {});
  }, []);

  const doSearch = async () => {
    if (!keyword.trim() && !nodeId.trim()) {
      message.warning('请输入关键词或节点 ID');
      return;
    }
    setLoading(true);
    try {
      const params = { mode };
      if (keyword.trim()) params.keyword = keyword.trim();
      if (nodeId.trim()) params.nodeId = nodeId.trim();
      if (graphId) params.graphId = graphId;
      const r = await api.searchNodes(params);
      const d = unwrap(r) || {};
      setRows(d.hits || []);
      setSearched(true);
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
      title: '知识点', width: 300, render: (_, r) => (
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
    { title: '所属图谱', dataIndex: 'graphName', width: 220,
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
      <PageToolbar filters={
        <Input placeholder="搜索知识点，例如分数、约分" className="search-keyword" aria-label="搜索知识点"
          value={keyword} onChange={e => setKeyword(e.target.value)}
          onPressEnter={doSearch} prefix={<SearchOutlined />} allowClear />
      } actions={<Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={doSearch}>搜索</Button>} />
      <div className="search-options">
        <span className="search-options-label">检索范围</span>
        <Select aria-label="限定图谱" placeholder="全部图谱" allowClear className="graph-filter"
          value={graphId || undefined}
          onChange={v => setGraphId(v || '')}
          options={graphs.map(g => ({ value: g.id, label: `${g.name}（#${g.id}）` }))} />
        <Select aria-label="检索方式" value={mode} onChange={setMode} style={{ width: 130 }} options={[
          { value: 'all', label: '全部来源' },
          { value: 'like', label: '仅字面' },
          { value: 'semantic', label: '仅语义' }
        ]} />
        <Input aria-label="节点 ID" placeholder="节点 ID（选填）" style={{ width: 140 }}
          value={nodeId} onChange={e => setNodeId(e.target.value)} onPressEnter={doSearch} allowClear />
      </div>
      <div className="list-summary">{searched ? <>找到 <strong>{rows.length}</strong> 个结果</> : '支持关键词、语义与节点 ID 检索'}
        {!semanticAvailable && (
          <Typography.Text type="warning" style={{ fontSize: 12 }}>
            当前使用关键词匹配
          </Typography.Text>
        )}
      </div>
      <Table rowKey={r => `${r.nodeId}-${r.source}`} loading={loading} dataSource={rows} columns={columns} scroll={{ x: 880 }}
        pagination={{ pageSize: 15, hideOnSinglePage: true }} locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={searched ? '没有找到相关知识点，试试其他关键词' : '从一个关键词开始探索知识'} /> }} />
    </Card>
  );
}
