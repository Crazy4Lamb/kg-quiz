import { Tabs } from 'antd';
import AdaptivePanel from './AdaptivePanel.jsx';
import PaperPanel from './PaperPanel.jsx';

/**
 * 出题中心（教师 / 管理员）：
 *  - 自适应出题：按学生掌握度生成个性化练习
 *  - 组卷与批改：手工挑题组卷、定向发给学生、收卷后自动批改 + 人工复核
 */
export default function ExamCenter() {
  return (
    <Tabs
      defaultActiveKey="adaptive"
      destroyOnHidden={false}
      items={[
        { key: 'adaptive', label: '自适应出题', children: <AdaptivePanel /> },
        { key: 'papers', label: '组卷与批改', children: <PaperPanel /> }
      ]}
    />
  );
}
