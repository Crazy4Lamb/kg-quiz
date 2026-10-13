import { Button, Dropdown, Modal } from 'antd';
import { MoreOutlined } from '@ant-design/icons';

/** Keep the frequent action visible; group occasional actions in a labelled menu. */
export default function RowActions({ label, primary, items = [] }) {
  const run = ({ key }) => {
    const item = items.find((action) => action.key === key);
    if (!item || item.disabled) return;
    if (item.confirm) {
      Modal.confirm({
        ...item.confirm,
        okText: item.danger ? '确认删除' : '确认', cancelText: '取消',
        okButtonProps: { danger: item.danger }, onOk: item.onClick
      });
    } else item.onClick?.();
  };
  return (
    <div className="row-actions">
      {primary && <Button type="link" size="small" disabled={primary.disabled} onClick={primary.onClick}>{primary.label}</Button>}
      {items.length > 0 && (
        <Dropdown trigger={['click']} placement="bottomRight" menu={{
          items: items.map(({ key, label: text, icon, danger, disabled }) => ({ key, label: text, icon, danger, disabled })),
          onClick: run
        }}>
          <Button type="text" size="small" className="row-more" icon={<MoreOutlined />} aria-label={`更多操作：${label}`} />
        </Dropdown>
      )}
    </div>
  );
}
