import { createContext, useContext, useLayoutEffect, useMemo, useState } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';

const STORAGE_KEY = 'kg-quiz-theme';
export const THEMES = [
  { id: 'dopamine', name: '活力多巴胺', description: '葡萄紫 × 青柠绿 × 珊瑚粉', primary: '#7040db', accent: '#d5f66b', secondary: '#ff83a7', background: '#f6f3ff', surface: '#ffffff', soft: '#eee6ff', text: '#29213f', muted: '#716780', border: '#e7def3', onAccent: '#344209', dark: false },
  { id: 'peach', name: '蜜桃派对', description: '蜜桃橙 × 草莓粉 × 奶油黄', primary: '#bd4924', accent: '#ffda70', secondary: '#f5a3c1', background: '#fff6ee', surface: '#ffffff', soft: '#ffeadc', text: '#42281f', muted: '#886d61', border: '#f2dfd1', onAccent: '#573600', dark: false },
  { id: 'mint', name: '薄荷花园', description: '薄荷绿 × 柠檬黄 × 丁香紫', primary: '#087c68', accent: '#e9f47d', secondary: '#c0a1f3', background: '#eff9f4', surface: '#ffffff', soft: '#dcf4eb', text: '#193e35', muted: '#627e75', border: '#d3e8df', onAccent: '#3c440b', dark: false },
  { id: 'sky', name: '晴空蓝', description: '晴空蓝 × 杏子橙 × 棉花粉', primary: '#2464c5', accent: '#ffcc87', secondary: '#f9a6c6', background: '#eff6ff', surface: '#ffffff', soft: '#dfedff', text: '#20344f', muted: '#667d99', border: '#d8e5f5', onAccent: '#573707', dark: false },
  { id: 'midnight', name: '霓虹夜色', description: '电光紫 × 荧光绿 × 热粉红', primary: '#b89aff', accent: '#d2f879', secondary: '#ff8ebd', background: '#181522', surface: '#252032', soft: '#362b4b', text: '#f4efff', muted: '#b6acc9', border: '#443954', onAccent: '#30410a', dark: true }
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
  for (const key of ['primary', 'accent', 'secondary', 'background', 'surface', 'soft', 'text', 'muted', 'border', 'onAccent']) {
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
      borderRadius: 12, fontFamily: "'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
      controlHeight: 38, controlHeightSM: 28, controlHeightLG: 46
    },
    components: {
      Layout: { headerBg: palette.surface, siderBg: palette.surface, bodyBg: palette.background },
      Menu: { itemBg: 'transparent', itemColor: palette.muted, itemSelectedBg: palette.soft, itemSelectedColor: palette.primary, itemHoverBg: palette.soft, itemBorderRadius: 12, itemHeight: 46 },
      Table: { headerBg: palette.soft, headerColor: palette.text, rowHoverBg: palette.soft, cellPaddingBlock: 16 },
      Card: { headerFontSize: 16 },
      Tabs: { titleFontSize: 14 },
      Button: { primaryShadow: 'none', primaryColor: palette.dark ? palette.background : '#ffffff', fontWeight: 600 }
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
