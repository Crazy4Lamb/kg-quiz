/** 题目类型与作业状态的展示映射，多个页面共用 */

export const QTYPE_LABEL = {
  SINGLE_CHOICE: '单选',
  MULTI_CHOICE: '多选',
  TRUE_FALSE: '判断',
  FILL_BLANK: '填空',
  SHORT_ANSWER: '简答'
};

export const QTYPE_COLOR = {
  SINGLE_CHOICE: 'blue',
  MULTI_CHOICE: 'purple',
  TRUE_FALSE: 'cyan',
  FILL_BLANK: 'orange',
  SHORT_ANSWER: 'magenta'
};

/** 客观题（机器可精确判分） */
export const OBJECTIVE_TYPES = ['SINGLE_CHOICE', 'MULTI_CHOICE', 'TRUE_FALSE'];

/** 作业状态：待作答 / 已交待批改 / 已批改定稿 */
export const ASSIGN_STATUS = {
  ASSIGNED: { label: '待作答', color: 'default' },
  SUBMITTED: { label: '待老师批改', color: 'orange' },
  GRADED: { label: '已批改', color: 'green' }
};

export const scoreColor = (ratio) => {
  if (ratio >= 0.85) return 'var(--color-success)';
  if (ratio >= 0.6) return 'var(--color-warning)';
  return 'var(--color-danger)';
};
