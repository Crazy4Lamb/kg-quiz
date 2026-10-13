import { createContext, useContext, useLayoutEffect, useMemo, useState } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';

const STORAGE_KEY = 'kg-quiz-theme';
export const THEMES = [
  { id: 'dopamine', name: '活力多巴胺', description: '葡萄紫 × 青柠绿 × 珊瑚粉', primary: '#7153cf', accent: '#d7ee91', secondary: '#eda1bb', background: '#f7f7fb', surface: '#ffffff', surfaceAlt: '#f8f7fc', soft: '#f0ecfa', text: '#292536', muted: '#777181', border: '#eae7f0', onAccent: '#344209', dark: false },
  { id: 'peach', name: '蜜桃派对', description: '蜜桃橙 × 草莓粉 × 奶油黄', primary: '#b95338', accent: '#f7db95', secondary: '#ebb2c8', background: '#faf8f5', surface: '#ffffff', surfaceAlt: '#fcf9f6', soft: '#fbefe8', text: '#3c2c27', muted: '#82726b', border: '#eee6e0', onAccent: '#573600', dark: false },
  { id: 'mint', name: '薄荷花园', description: '薄荷绿 × 柠檬黄 × 丁香紫', primary: '#217e69', accent: '#e5ecac', secondary: '#bfa9e5', background: '#f5f8f6', surface: '#ffffff', surfaceAlt: '#f6faf8', soft: '#e8f3ee', text: '#273b34', muted: '#6b7c74', border: '#e0eae4', onAccent: '#3c440b', dark: false },
  { id: 'sky', name: '晴空蓝', description: '晴空蓝 × 杏子橙 × 棉花粉', primary: '#386bbf', accent: '#f5ce9b', secondary: '#eab3cc', background: '#f5f7fa', surface: '#ffffff', surfaceAlt: '#f7f9fc', soft: '#eaf0fb', text: '#29384d', muted: '#6f7d90', border: '#e3e9f1', onAccent: '#573707', dark: false },
  { id: 'midnight', name: '霓虹夜色', description: '电光紫 × 荧光绿 × 热粉红', primary: '#b59ce9', accent: '#cce696', secondary: '#eaa3c3', background: '#19181f', surface: '#24232d', surfaceAlt: '#2a2935', soft: '#332e43', text: '#ece9f4', muted: '#ada7bc', border: '#3b3748', onAccent: '#30410a', dark: true }
];

export function readThemeId() {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    return THEMES.some((item) => item.id === saved) ? saved : 'dopamine';
  } catch { return 'dopamine'; }
}

export function applyTheme(id) {
  const palette = THEMES.find((item) => item.id === id) || THEMES[0];
  document.documentElement.dataset.theme = palette.id;
  document.documentElement.style.colorScheme = palette.dark ? 'dark' : 'light';
  for (const key of ['primary', 'accent', 'secondary', 'background', 'surface', 'surfaceAlt', 'soft', 'text', 'muted', 'border', 'onAccent']) {
    document.documentElement.style.setProperty(`--color-${key}`, palette[key]);
  }
  return palette;
}

const ThemeContext = createContext(null);

export function ThemeProvider({ children }) {
  const [themeId, setThemeId] = useState(readThemeId);
  const palette = THEMES.find((item) => item.id === themeId) || THEMES[0];
  const config = useMemo(() => ({
    algorithm: palette.dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
    token: {
      colorPrimary: palette.primary, colorInfo: palette.primary,
      colorSuccess: palette.dark ? '#80d7ac' : '#23865a', colorWarning: palette.dark ? '#ffd477' : '#ad6909',
      colorError: palette.dark ? '#ff8f9e' : '#d43b57',
      colorBgBase: palette.surface, colorBgLayout: palette.background, colorBgContainer: palette.surface,
      colorBgElevated: palette.surface, colorText: palette.text, colorTextSecondary: palette.muted,
      colorTextPlaceholder: palette.muted, colorTextLightSolid: palette.dark ? palette.background : '#ffffff',
      colorBorder: palette.border, colorBorderSecondary: palette.border,
      borderRadius: 8, fontFamily: "'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
      controlHeight: 36, controlHeightSM: 28, controlHeightLG: 44
    },
    components: {
      Layout: { headerBg: palette.surface, siderBg: palette.surface, bodyBg: palette.background },
      Menu: { itemBg: 'transparent', itemColor: palette.muted, itemSelectedBg: palette.soft, itemSelectedColor: palette.primary, itemHoverBg: palette.surfaceAlt, itemBorderRadius: 9, itemHeight: 42 },
      Table: { headerBg: palette.surfaceAlt, headerColor: palette.muted, rowHoverBg: palette.surfaceAlt, cellPaddingBlock: 16, cellPaddingInline: 16 },
      Card: { headerFontSize: 15, paddingLG: 24 },
      Tabs: { titleFontSize: 14 },
      Button: { primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none', primaryColor: palette.dark ? palette.background : '#ffffff', fontWeight: 500 }
    }
  }), [palette]);

  useLayoutEffect(() => {
    applyTheme(themeId);
    try { localStorage.setItem(STORAGE_KEY, themeId); } catch { /* Theme works even when storage is disabled. */ }
    // Ant Design's static messages and confirmation dialogs render outside the React tree.
    ConfigProvider.config({ holderRender: (node) => <ConfigProvider locale={zhCN} theme={config}>{node}</ConfigProvider> });
  }, [themeId, config]);

  useLayoutEffect(() => {
    const sync = (event) => {
      if (event.key === STORAGE_KEY) setThemeId(THEMES.some((item) => item.id === event.newValue) ? event.newValue : 'dopamine');
    };
    window.addEventListener('storage', sync);
    return () => window.removeEventListener('storage', sync);
  }, []);

  return (
    <ThemeContext.Provider value={{ themeId, setThemeId, palette }}>
      <ConfigProvider locale={zhCN} theme={config}>{children}</ConfigProvider>
    </ThemeContext.Provider>
  );
}

export const useTheme = () => useContext(ThemeContext);
