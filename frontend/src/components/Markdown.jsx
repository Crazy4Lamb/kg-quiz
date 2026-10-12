import { Typography } from 'antd';

/**
 * 极简 Markdown 渲染（标题 / 列表 / 引用 / 加粗 / 行内代码 / 分隔线）。
 *
 * <p>教学内容由大模型生成，输出是 Markdown。这里不引第三方渲染器（避免引入
 * 新的构建依赖与 XSS 面），只支持教研材料会用到的那几种语法，并用 React 元素
 * 组装而不是 dangerouslySetInnerHTML。</p>
 */

/** 行内语法：**加粗**、`代码`、*斜体* */
function inline(text, keyPrefix) {
  const out = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`|\*[^*]+\*)/g;
  let last = 0;
  let m;
  let i = 0;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) {
      out.push(text.slice(last, m.index));
    }
    const token = m[0];
    const key = `${keyPrefix}-${i++}`;
    if (token.startsWith('**')) {
      out.push(<strong key={key}>{token.slice(2, -2)}</strong>);
    } else if (token.startsWith('`')) {
      out.push(
        <code key={key} style={{
          background: '#f5f5f5', padding: '1px 5px', borderRadius: 4,
          fontSize: 13, fontFamily: 'ui-monospace, Consolas, monospace'
        }}>{token.slice(1, -1)}</code>
      );
    } else {
      out.push(<em key={key}>{token.slice(1, -1)}</em>);
    }
    last = m.index + token.length;
  }
  if (last < text.length) {
    out.push(text.slice(last));
  }
  return out;
}

const HEADING_SIZE = { 1: 22, 2: 19, 3: 17, 4: 15, 5: 14, 6: 14 };

export default function Markdown({ text, compact = false }) {
  const source = text || '';
  const lines = source.replace(/\r\n/g, '\n').split('\n');
  const blocks = [];
  let list = null; // { ordered: boolean, items: [] }
  let para = [];
  let key = 0;

  const flushPara = () => {
    if (para.length) {
      blocks.push(
        <Typography.Paragraph key={`p${key++}`} style={{ marginBottom: compact ? 6 : 10, lineHeight: 1.85 }}>
          {inline(para.join(' '), `p${key}`)}
        </Typography.Paragraph>
      );
      para = [];
    }
  };
  const flushList = () => {
    if (list) {
      const Tag = list.ordered ? 'ol' : 'ul';
      blocks.push(
        <Tag key={`l${key++}`} style={{
          margin: compact ? '2px 0 6px' : '4px 0 10px',
          paddingLeft: 22, lineHeight: 1.85
        }}>
          {list.items.map((it, idx) => <li key={idx}>{inline(it, `li${key}-${idx}`)}</li>)}
        </Tag>
      );
      list = null;
    }
  };

  for (const raw of lines) {
    const line = raw.trimEnd();
    const trimmed = line.trim();

    if (!trimmed) {
      flushPara();
      flushList();
      continue;
    }
    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed);
    if (heading) {
      flushPara();
      flushList();
      const level = heading[1].length;
      blocks.push(
        <div key={`h${key++}`} style={{
          fontSize: HEADING_SIZE[level],
          fontWeight: level <= 2 ? 700 : 600,
          margin: level <= 2 ? '18px 0 8px' : '14px 0 6px',
          paddingBottom: level <= 2 ? 6 : 0,
          borderBottom: level <= 2 ? '1px solid #f0f0f0' : 'none',
          color: '#141414'
        }}>{inline(heading[2], `h${key}`)}</div>
      );
      continue;
    }
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      flushPara();
      flushList();
      blocks.push(<div key={`hr${key++}`} style={{ borderTop: '1px solid #f0f0f0', margin: '16px 0' }} />);
      continue;
    }
    const quote = /^>\s?(.*)$/.exec(trimmed);
    if (quote) {
      flushPara();
      flushList();
      blocks.push(
        <div key={`q${key++}`} style={{
          borderLeft: '3px solid #d9d9d9', background: '#fafafa',
          padding: '6px 12px', margin: '10px 0', color: '#595959', fontSize: 13
        }}>{inline(quote[1], `q${key}`)}</div>
      );
      continue;
    }
    const ul = /^[-*+]\s+(.*)$/.exec(trimmed);
    if (ul) {
      flushPara();
      if (!list || list.ordered) {
        flushList();
        list = { ordered: false, items: [] };
      }
      list.items.push(ul[1]);
      continue;
    }
    const ol = /^\d+[.)]\s+(.*)$/.exec(trimmed);
    if (ol) {
      flushPara();
      if (!list || !list.ordered) {
        flushList();
        list = { ordered: true, items: [] };
      }
      list.items.push(ol[1]);
      continue;
    }
    flushList();
    para.push(trimmed);
  }
  flushPara();
  flushList();

  if (!blocks.length) {
    return <Typography.Text type="secondary">（暂无内容）</Typography.Text>;
  }
  return <div>{blocks}</div>;
}
