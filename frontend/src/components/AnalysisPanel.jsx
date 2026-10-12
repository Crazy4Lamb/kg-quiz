import { useState } from 'react';
import {
  Card, Select, Button, Space, Typography, Tag, Table, Steps, Empty, Alert, Switch, Row, Col
} from 'antd';
import { NodeIndexOutlined, BranchesOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';

const statusColor = (s) => s.startsWith('薄弱') ? 'red' : s.startsWith('掌握中') ? 'orange' : 'green';

/**
 * 学生维度的图分析面板：
 * - 学习路径推荐（前置闭包 + 拓扑分层）
 * - 前置缺口分析（递归找未掌握前置）
 */
export default function AnalysisPanel({ studentId, nodes }) {
  const [target, setTarget] = useState(null);
  const [path, setPath] = useState(null);
  const [gap, setGap] = useState(null);
  const [loading, setLoading] = useState('');

  const runPath = async () => {
    if (!target) return;
    setLoading('path');
    try {
      const r = await api.recommendPath(studentId, target);
      setPath(unwrap(r));
      setGap(null);
    } finally {
      setLoading('');
    }
  };

  const runGap = async () => {
    if (!target) return;
    setLoading('gap');
    try {
      const r = await api.gapAnalysis(studentId, target);
      setGap(unwrap(r));
      setPath(null);
    } finally {
      setLoading('');
    }
  };

  const options = (nodes || []).map(n => ({
    value: n.id,
    label: `${n.name}（掌握 ${Math.round((Number(n.mastery) || 0) * 100)}%）`
  }));

  return (
    <Card size="small" title="学习分析" style={{ marginBottom: 12 }}
      extra={
        <Space>
          <Select placeholder="选择目标知识点" style={{ width: 260 }} showSearch optionFilterProp="label"
            value={target} onChange={setTarget} options={options} />
          <Button type="primary" icon={<NodeIndexOutlined />} loading={loading === 'path'}
            onClick={runPath} disabled={!target}>推荐学习路径</Button>
          <Button icon={<BranchesOutlined />} loading={loading === 'gap'}
            onClick={runGap} disabled={!target}>前置缺口分析</Button>
        </Space>
      }>

      {!path && !gap && (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          选择一个目标知识点，推荐"从当前水平到掌握它"的最优学习顺序，或分析它缺失的前置基础。
        </Typography.Text>
      )}

      {path && (
        <div>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={`目标：${path.targetNodeName} ｜ 前置闭包 ${path.totalClosure} 个知识点，其中需学习 ${path.steps.length} 个，可跳过 ${path.skippable.length} 个`}
            description={path.note} />
          {path.steps.length === 0 ? (
            <Empty description="该知识点的全部前置均已掌握，可直接学习目标内容" />
          ) : (
            <Steps direction="vertical" size="small" current={-1}
              items={path.steps.map(s => ({
                title: (
                  <Space>
                    <span>{s.order}. {s.name}</span>
                    <Tag color={statusColor(s.status)}>{s.status}</Tag>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      掌握 {(s.mastery * 100).toFixed(0)}% · 权重 {s.weight} · 层级 {s.layer}
                      {s.isTarget ? ' · 目标' : ''}
                    </Typography.Text>
                  </Space>
                )
              }))} />
          )}
          {path.skippable.length > 0 && (
            <div style={{ marginTop: 8 }}>
              <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                已掌握可跳过：{path.skippable.map(s => s.name).join('、')}
              </Typography.Text>
            </div>
          )}
        </div>
      )}

      {gap && (
        <div>
          <Alert type={gap.gapCount > 0 ? 'warning' : 'success'} showIcon style={{ marginBottom: 12 }}
            message={`知识点「${gap.nodeName}」的前置链：共 ${gap.closureSize} 个节点，发现 ${gap.gapCount} 个未掌握缺口`}
            description={gap.note} />
          {gap.layers.length === 0 ? <Empty description="无前置缺口" /> : (
            <Row gutter={12}>
              {gap.layers.map(layer => (
                <Col span={8} key={layer.layer} style={{ marginBottom: 12 }}>
                  <Card size="small" title={`第 ${layer.layer} 层（${layer.nodeCount} 个）`}>
                    {layer.nodes.map(n => (
                      <div key={n.nodeId} style={{ marginBottom: 6 }}>
                        <Space>
                          <Tag color={n.gapLevel === '完全未掌握' ? 'red' : 'orange'}>{n.gapLevel}</Tag>
                          <span>{n.name}</span>
                          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                            掌握 {(n.mastery * 100).toFixed(0)}%
                          </Typography.Text>
                        </Space>
                      </div>
                    ))}
                  </Card>
                </Col>
              ))}
            </Row>
          )}
        </div>
      )}
    </Card>
  );
}
