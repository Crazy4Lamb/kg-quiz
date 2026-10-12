import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Card, Button, Space, Tag, Typography, message, Radio, Checkbox, Input,
  Result, Alert, Modal, Divider, Descriptions
} from 'antd';
import { ArrowLeftOutlined, SendOutlined } from '@ant-design/icons';
import { api, unwrap } from '../api.js';
import { QTYPE_LABEL, QTYPE_COLOR, ASSIGN_STATUS, scoreColor } from '../examMeta.js';

const { TextArea } = Input;

/**
 * 学生作答 / 回顾。
 * 作答时只填答案，判分交给后端；老师没批改完之前看不到答案，
 * 批改定稿后才展示参考答案与评语。
 */
export default function PaperTake() {
  const { examId } = useParams();
  const nav = useNavigate();
  const [paper, setPaper] = useState(null);
  const [answers, setAnswers] = useState({});
  const [loading, setLoading] = useState(true);
  const [submitting, setSubmitting] = useState(false);
  const [result, setResult] = useState(null);

  const load = async () => {
    setLoading(true);
    try {
      const d = unwrap(await api.myPaper(examId));
      setPaper(d);
      const init = {};
      (d.questions || []).forEach(q => {
        if (q.studentAnswer == null) {
          init[q.questionId] = q.questionType === 'MULTI_CHOICE' ? [] : '';
        } else {
          init[q.questionId] = q.questionType === 'MULTI_CHOICE'
            ? String(q.studentAnswer).split(/[,，]/).map(s => s.trim()).filter(Boolean)
            : q.studentAnswer;
        }
      });
      setAnswers(init);
    } catch (e) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, [examId]);

  if (loading) return <Card loading />;
  if (!paper) return <Card>作业不存在或没有发给你</Card>;

  const editing = paper.status === 'ASSIGNED' && !result;
  const questions = paper.questions || [];

  const setAnswer = (qid, value) => setAnswers(a => ({ ...a, [qid]: value }));

  const doSubmit = async () => {
    setSubmitting(true);
    try {
      const items = questions.map(q => {
        const v = answers[q.questionId];
        const answer = Array.isArray(v) ? v.join(',') : (v || '');
        return { questionId: q.questionId, answer };
      });
      const r = unwrap(await api.submitMyPaper(examId, items));
      setResult(r);
      if (r.status === 'GRADED') {
        message.success(`交卷完成，机器已批改：${r.totalScore} / ${r.fullScore} 分`);
      } else {
        message.success('交卷完成，客观题已自动判分，简答题等老师复核');
      }
      await load();
    } catch (e) {
      message.error(e.message);
    } finally {
      setSubmitting(false);
    }
  };

  const submit = () => {
    const blank = questions.filter(q => {
      const v = answers[q.questionId];
      return Array.isArray(v) ? v.length === 0 : !String(v || '').trim();
    });
    if (blank.length > 0) {
      Modal.confirm({
        title: `还有 ${blank.length} 题没作答`,
        content: '没作答的题会按 0 分计。确认现在交卷？',
        okText: '确认交卷',
        onOk: doSubmit
      });
      return;
    }
    Modal.confirm({
      title: '确认交卷？',
      content: '交卷后不能修改，需要重做请找老师退回。',
      okText: '交卷',
      onOk: doSubmit
    });
  };

  const showResult = result || (paper.status !== 'ASSIGNED');
  const ratio = paper.fullScore ? (paper.totalScore || 0) / paper.fullScore : 0;

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}>
        <Space wrap>
          <Button icon={<ArrowLeftOutlined />} onClick={() => nav('/exams')}>返回我的作业</Button>
          <Typography.Title level={5} style={{ margin: 0 }}>{paper.name}</Typography.Title>
          <Tag color="blue">{questions.length} 题</Tag>
          {paper.teacherName && <Tag>{paper.teacherName} 布置</Tag>}
          <Tag color={ASSIGN_STATUS[paper.status]?.color}>
            {ASSIGN_STATUS[paper.status]?.label || paper.status}
          </Tag>
        </Space>
        {paper.description && (
          <Typography.Paragraph type="secondary" style={{ marginTop: 8, marginBottom: 0, fontSize: 12 }}>
            {paper.description}
          </Typography.Paragraph>
        )}
      </Card>

      {showResult && (
        <Card size="small" style={{ marginBottom: 12 }}>
          <Result
            status={paper.pendingCount > 0 ? 'warning' : 'success'}
            title={paper.pendingCount > 0
              ? '已交卷，简答题等老师复核'
              : `已批改完成：${paper.totalScore} / ${paper.fullScore} 分`}
            subTitle={paper.pendingCount > 0
              ? `客观题已自动判分，当前合计 ${paper.totalScore} / ${paper.fullScore} 分；老师定稿后分数与评语会更新到这里`
              : `得分率 ${(ratio * 100).toFixed(0)}%，掌握度已按结果更新`}
            extra={<Typography.Text type="secondary">批改老师：{paper.teacherName || '—'}</Typography.Text>}
          />
        </Card>
      )}

      {questions.map((q, idx) => {
        const v = answers[q.questionId];
        const multi = q.questionType === 'MULTI_CHOICE';
        return (
          <Card key={q.questionId} size="small" style={{ marginBottom: 12 }}
            title={
              <Space wrap>
                <span>第 {idx + 1} 题</span>
                <Tag color={QTYPE_COLOR[q.questionType]}>{QTYPE_LABEL[q.questionType] || q.questionType}</Tag>
                <Tag color="blue">{q.fullScore} 分</Tag>
                {q.nodeName && <Tag color="geekblue">{q.nodeName}</Tag>}
                {!editing && q.score != null && (
                  <Tag color={Number(q.score) >= Number(q.fullScore) ? 'green'
                    : Number(q.score) > 0 ? 'orange' : 'red'}>
                    得分 {q.score} / {q.fullScore}
                  </Tag>
                )}
              </Space>
            }>
            <Typography.Paragraph style={{ fontSize: 15, whiteSpace: 'pre-wrap' }}>
              {q.stem}
            </Typography.Paragraph>

            {editing ? (
              <>
                {(q.options || []).length > 0 && (
                  multi
                    ? <Checkbox.Group value={Array.isArray(v) ? v : []}
                        onChange={nv => setAnswer(q.questionId, nv)}>
                        <Space direction="vertical">
                          {(q.options || []).map((opt, i) => (
                            <Checkbox key={i} value={opt}>{opt}</Checkbox>
                          ))}
                        </Space>
                      </Checkbox.Group>
                    : <Radio.Group value={v} onChange={e => setAnswer(q.questionId, e.target.value)}>
                        <Space direction="vertical">
                          {(q.options || []).map((opt, i) => (
                            <Radio key={i} value={opt}>{opt}</Radio>
                          ))}
                        </Space>
                      </Radio.Group>
                )}
                {q.questionType === 'FILL_BLANK' && (
                  <Input value={v || ''} onChange={e => setAnswer(q.questionId, e.target.value)}
                    placeholder="填写答案" style={{ maxWidth: 320 }} />
                )}
                {q.questionType === 'SHORT_ANSWER' && (
                  <TextArea rows={4} value={v || ''} onChange={e => setAnswer(q.questionId, e.target.value)}
                    placeholder="写下你的解答过程" />
                )}
              </>
            ) : (
              <>
                {(q.options || []).length > 0 && (
                  <div>
                    {(q.options || []).map((opt, i) => {
                      const isRef = String(q.answer || '').split(/[,，]/).map(s => s.trim()).includes(String(opt).trim());
                      const isMine = String(q.studentAnswer || '').split(/[,，]/).map(s => s.trim()).includes(String(opt).trim());
                      return (
                        <div key={i} style={{
                          padding: '2px 8px', borderRadius: 4,
                          background: isRef ? 'var(--color-success-soft)' : isMine ? 'var(--color-warning-soft)' : 'transparent'
                        }}>
                          {isRef && <Tag color="green">正确答案</Tag>}
                          {isMine && !isRef && <Tag color="red">你选的</Tag>}
                          {isMine && isRef && <Tag color="blue">选对了</Tag>}
                          {opt}
                        </div>
                      );
                    })}
                  </div>
                )}
                <Descriptions size="small" column={1} style={{ marginTop: 8 }} bordered>
                  <Descriptions.Item label="我的作答">
                    <span style={{ whiteSpace: 'pre-wrap' }}>
                      {q.studentAnswer || <Typography.Text type="secondary">未作答</Typography.Text>}
                    </span>
                  </Descriptions.Item>
                  {q.score != null && (
                    <Descriptions.Item label="得分">
                      <b style={{ color: scoreColor(Number(q.score) / Number(q.fullScore || 1)) }}>
                        {q.score} / {q.fullScore}
                      </b>
                    </Descriptions.Item>
                  )}
                  {q.comment && (
                    <Descriptions.Item label="老师评语">{q.comment}</Descriptions.Item>
                  )}
                  {paper.answerRevealed && (
                    <Descriptions.Item label="参考答案">
                      <b>{q.answer}</b>
                      {q.analysis && (
                        <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginTop: 4, marginBottom: 0 }}>
                          解析：{q.analysis}
                        </Typography.Paragraph>
                      )}
                    </Descriptions.Item>
                  )}
                </Descriptions>
                {!paper.answerRevealed && (
                  <Alert type="info" showIcon style={{ marginTop: 8 }}
                    message="老师批改定稿后，这里会显示参考答案与评语" />
                )}
              </>
            )}
          </Card>
        );
      })}

      {editing && (
        <Button type="primary" size="large" block icon={<SendOutlined />}
          loading={submitting} onClick={submit}>
          交卷（系统自动批改客观题）
        </Button>
      )}
    </div>
  );
}
