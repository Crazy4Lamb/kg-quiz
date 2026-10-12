import { useRef, useState } from 'react';
import { Button, Popover } from 'antd';
import { BgColorsOutlined, CheckOutlined } from '@ant-design/icons';
import { THEMES, useTheme } from '../theme.jsx';

export default function ThemePicker() {
  const { themeId, setThemeId, palette } = useTheme();
  const [open, setOpen] = useState(false);
  const triggerRef = useRef(null);
  return (
    <Popover trigger="click" placement="bottomRight" open={open} onOpenChange={setOpen}
      content={
        <div className="theme-panel">
          <div className="theme-panel-heading">给今天换个心情 <span>5 款主题</span></div>
          <div className="theme-options">
            {THEMES.map((item) => (
              <button type="button" key={item.id} className="theme-option" aria-pressed={item.id === themeId}
                onClick={() => { setThemeId(item.id); setOpen(false); triggerRef.current?.focus(); }}>
                <span className="theme-preview" style={{ background: item.background }} aria-hidden="true">
                  <i style={{ background: item.primary }} /><i style={{ background: item.accent }} /><i style={{ background: item.secondary }} />
                </span>
                <span className="theme-option-copy"><strong>{item.name}</strong><small>{item.description}</small></span>
                {item.id === themeId && <CheckOutlined className="theme-check" />}
              </button>
            ))}
          </div>
          <p className="theme-hint">选择会自动保存，下次打开依然是你喜欢的颜色。</p>
        </div>
      }>
      <Button ref={triggerRef} className="theme-trigger" icon={<BgColorsOutlined />} aria-label={`切换主题，当前：${palette.name}`} aria-expanded={open}>
        <span className="theme-trigger-label">{palette.name}</span>
      </Button>
    </Popover>
  );
}
