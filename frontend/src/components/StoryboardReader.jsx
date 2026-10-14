import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Card, Tag, Typography, Button, Space, Progress, Tooltip, Empty, Alert, Collapse, Divider
} from 'antd';
import {
  PlayCircleOutlined, StepForwardOutlined, StepBackwardOutlined, EyeOutlined,
  ThunderboltOutlined, QuestionCircleOutlined, BulbOutlined
} from '@ant-design/icons';

const KIND_META = {
  HOOK: { label: '情境导入', color: 'cyan', icon: <BulbOutlined /> },
  TEACH: { label: '概念新授', color: 'blue', icon: <ThunderboltOutlined /> },
  DEMO: { label: '推导演示', color: 'geekblue', icon: <StepForwardOutlined /> },
  EXAMPLE: { label: '例题精讲', color: 'purple', icon: <BulbOutlined /> },
  PRACTICE: { label: '随堂练习', color: 'orange', icon: <QuestionCircleOutlined /> },
  RECAP: { label: '课堂小结', color: 'green', icon: <StepBackwardOutlined /> }
};
const KIND_FALLBACK = { label: '讲解', color: 'default', icon: <EyeOutlined /> };

const VISUAL_META = {
  TEXT: { label: '要点', color: 'default' },
  FORMULA: { label: '公式', color: 'blue' },
  QUESTION: { label: '题目', color: 'purple' },
  DIAGRAM: { label: '图示', color: 'cyan' }
};

const kindMeta = (k) => KIND_META[k] || KIND_FALLBACK;

/**
 * 逐段阅读器：把讲解分镜 IR 渲染成"可一步步走"的形态。
 *
 * <p>为什么不能只给 Markdown：分镜 IR 的价值在于<b>视觉动作锚定在段落相对位置上</b>
 * （anchorLine = 本段旁白第几行，anchorRatio = 该行内比例），而不是绝对秒数。
 * 这样进度是相对的，倍速/跳读都不会让画面与旁白错位。</p>
 *
 * <p>「逐步呈现」模式下，只有当前段的视觉高亮；「通读」模式按段落顺序平铺，
 * 适合快速浏览。两种模式读的是同一份 IR。</p>
 */
export default function StoryboardReader({ data, practiceOnNode }) {
  const sb = data?.storyboard;
  const segments = useMemo(() => sb?.segments || [], [sb]);
  const [step, setStep] = useState(0);
  const [mode, setMode] = useState('step');
  const bodyRef = useRef(null);

  useEffect(() => { setStep(0); }, [data?.contentId]);

  if (!sb || !segments.length) {
    return <Empty description="该知识点还没有讲解分镜" />;
  }

  const total = segments.length;
  const cur = segments[Math.min(step, total - 1)];
  const curMeta = kindMeta(cur.kind);
  const totalSec = sb.totalSeconds || 0;
  const elapsedSec = segments.slice(0, step).reduce((a, s) => a + (s.seconds || 0), 0);

  const jump = (i) => {
    const n = Math.max(0, Math.min(total - 1, i));
    setStep(n);
    bodyRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      {sb.objective && (
        <Alert type="info" showIcon message="本讲要解决" description={sb.objective} />
      )}

      {/* 段落导航：既是进度条也是跳读入口 */}
      <Card size="small" styles={{ body: { padding: 12 } }}>
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          <Space wrap size={6}>
            <Typography.Text strong style={{ fontSize: 13 }}>讲解段落</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              共 {total} 段{totalSec > 0 ? ` · 约 ${formatTime(totalSec)}` : ''}
            </Typography.Text>
            <Tag color={mode === 'step' ? 'blue' : 'default'}>
              {mode === 'step' ? '逐步呈现' : '通读'}
            </Tag>
          </Space>

          <Progress
            percent={Math.round(((step + 1) / total) * 100)}
            size="small"
            format={() => `${step + 1} / ${total}`}
          />

          <Space wrap size={6}>
            {segments.map((s, i) => {
              const meta = kindMeta(s.kind);
              const active = i === step;
              return (
                <Tag
                  key={s.index ?? i}
                  color={active ? meta.color : 'default'}
                  style={{ cursor: 'pointer', padding: '2px 8px', opacity: active ? 1 : 0.75 }}
                  onClick={() => { setStep(i); setMode('step'); }}
                >
                  <span style={{ fontSize: 12 }}>{s.index ?? i + 1}. {meta.label}</span>
                  {mode === 'step' && i < step && <span style={{ fontSize: 11 }}> ✓</span>}
                </Tag>
              );
            })}
          </Space>

          <Space wrap>
            <Button
              size="small"
              icon={<StepBackwardOutlined />}
              disabled={step === 0}
              onClick={() => setStep((i) => Math.max(0, i - 1))}
            >
              上一段
            </Button>
            <Button
              size="small"
              type="primary"
              icon={<StepForwardOutlined />}
              disabled={step >= total - 1}
              onClick={() => setStep((i) => Math.min(total - 1, i + 1))}
            >
              下一段
            </Button>
            <Button
              size="small"
              icon={<EyeOutlined />}
              onClick={() => setMode((m) => (m === 'step' ? 'read' : 'step'))}
            >
              {mode === 'step' ? '切成通读' : '切成逐步'}
            </Button>
            {practiceOnNode && (
              <Button size="small" type="primary" ghost onClick={practiceOnNode}>
                学完练一手
              </Button>
            )}
          </Space>
        </Space>
      </Card>

      {mode === 'step' ? (
        <SegmentCard seg={cur} meta={curMeta} highlight />
      ) : (
        <Space direction="vertical" size={10} style={{ width: '100%' }}>
          {segments.map((s, i) => (
            <SegmentCard key={s.index ?? i} seg={s} meta={kindMeta(s.kind)} onJump={() => jump(i)} />
          ))}
        </Space>
      )}

      <Divider style={{ margin: 0 }} />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        讲解按段落推进而不是按固定秒数对齐，所以可以任意跳读、倍速播放，画面不会与旁白错位。
      </Typography.Text>
    </Space>
  );
}

function SegmentCard({ seg, meta, highlight, onJump }) {
  const segSec = seg.seconds || 0;
  return (
    <Card
      size="small"
      title={
        <Space size={8}>
          {meta.icon}
          <span>{seg.index}. {seg.title || meta.label}</span>
          <Tag color={meta.color}>{meta.label}</Tag>
          {segSec > 0 && <Tag>约 {segSec} 秒</Tag>}
        </Space>
      }
      extra={onJump && (
        <Button size="small" type="link" onClick={onJump}>跳到这里</Button>
      )}
      style={highlight ? { borderLeft: '3px solid #1677ff' } : undefined}
    >
      <Typography.Paragraph style={{ marginBottom: 8, whiteSpace: 'pre-wrap' }}>
        {seg.narration}
      </Typography.Paragraph>

      {seg.boardLines?.length > 0 && (
        <div style={{ marginBottom: 8 }}>
          <Typography.Text strong style={{ fontSize: 13 }}>板书 / 要点</Typography.Text>
          <ul style={{ margin: '4px 0 0 18px', padding: 0 }}>
            {seg.boardLines.map((b, i) => <li key={i}><Typography.Text>{b}</Typography.Text></li>)}
          </ul>
        </div>
      )}

      {seg.visuals?.length > 0 && (
        <div style={{ marginBottom: 8 }}>
          <Typography.Text strong style={{ fontSize: 13 }}>画面</Typography.Text>
          <Space direction="vertical" size={6} style={{ width: '100%', marginTop: 4 }}>
            {seg.visuals.map((v, i) => {
              const vm = VISUAL_META[v.type] || VISUAL_META.TEXT;
              const anchor = v.anchorLine
                ? `第 ${v.anchorLine} 行${v.anchorRatio != null ? ` · ${Math.round(v.anchorRatio * 100)}%` : ''}`
                : '段首';
              return (
                <div
                  key={i}
                  style={{
                    padding: '6px 10px',
                    background: highlight ? '#f0f7ff' : '#fafafa',
                    borderRadius: 4,
                    borderLeft: `2px solid ${highlight ? '#1677ff' : '#d9d9d9'}`
                  }}
                >
                  <Space size={6} wrap>
                    <Tag color={vm.color}>{vm.label}</Tag>
                    <Tooltip title={`视觉锚点：${anchor}（相对本段旁白，非绝对秒数）`}>
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        ⏱ {anchor}
                      </Typography.Text>
                    </Tooltip>
                    {v.holdSeconds > 0 && (
                      <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                        停留 {v.holdSeconds}s
                      </Typography.Text>
                    )}
                  </Space>
                  <div style={{ marginTop: 4 }}>
                    {v.type === 'FORMULA' ? (
                      <Typography.Text code>{v.text}</Typography.Text>
                    ) : (
                      <Typography.Text>{v.text}</Typography.Text>
                    )}
                  </div>
                </div>
              );
            })}
          </Space>
        </div>
      )}

      {seg.questionIds?.length > 0 && (
        <div>
          <Typography.Text strong style={{ fontSize: 13 }}>配套题目</Typography.Text>
          <Space wrap size={6} style={{ marginTop: 4 }}>
            {seg.questionIds.map((qid) => (
              <Tag key={qid} icon={<QuestionCircleOutlined />} color="purple">
                题 #{qid}
              </Tag>
            ))}
          </Space>
        </div>
      )}
    </Card>
  );
}

function formatTime(sec) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `${m} 分 ${s} 秒` : `${s} 秒`;
}

/** 供内容管理页复用的分镜只读预览（折叠式，比逐段阅读器轻） */
export function StoryboardPreview({ storyboard }) {
  if (!storyboard?.segments?.length) {
    return <Typography.Text type="secondary" style={{ fontSize: 12 }}>无分镜 IR</Typography.Text>;
  }
  return (
    <Collapse size="small" ghost
      items={storyboard.segments.map((s, i) => {
        const meta = kindMeta(s.kind);
        return {
          key: String(s.index ?? i),
          label: (
            <Space size={6}>
              <Tag color={meta.color} style={{ marginInlineEnd: 0 }}>{meta.label}</Tag>
              <Typography.Text style={{ fontSize: 13 }}>{s.title || `第 ${i + 1} 段`}</Typography.Text>
              <Typography.Text type="secondary" style={{ fontSize: 11 }}>
                {s.visuals?.length || 0} 个画面
                {s.questionIds?.length ? ` · ${s.questionIds.length} 题` : ''}
              </Typography.Text>
            </Space>
          ),
          children: (
            <div style={{ paddingLeft: 4 }}>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 6 }}>
                {s.narration}
              </Typography.Paragraph>
              {!!s.boardLines?.length && (
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  板书：{s.boardLines.join(' / ')}
                </Typography.Text>
              )}
              {!!s.visuals?.length && (
                <div style={{ marginTop: 4 }}>
                  {s.visuals.map((v, k) => (
                    <div key={k} style={{ fontSize: 12, color: '#555' }}>
                      · [{VISUAL_META[v.type]?.label || v.type}] {v.text}
                      {v.anchorLine ? `（锚点 第${v.anchorLine}行）` : ''}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )
        };
      })}
    />
  );
}