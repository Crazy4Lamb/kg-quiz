const { chromium } = require('playwright');
const path = require('path');

const OUT = path.join(__dirname, 'screenshots-learn');
const BASE = 'http://localhost:5173';
const EXE = 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe';

(async () => {
  const browser = await chromium.launch({ executablePath: EXE });
  const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 } });
  const page = await ctx.newPage();
  const errors = [];
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

  const login = async (u, p) => {
    await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await page.fill('#username', u);
    await page.fill('#password', p);
    await page.click('button[type="submit"]');
    await page.waitForTimeout(2500);
  };

  const log = (s) => console.log('  ' + s);

  // 管理员：大模型接入页
  await login('admin', 'admin123');
  log('管理员已登录');

  await page.goto(BASE + '/llm-settings', { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);
  await page.screenshot({ path: `${OUT}/30-llm-settings.png`, fullPage: true });
  log('30-llm-settings.png');

  // 选预设：用键盘输入筛选，避免虚拟滚动列表项定位失败
  const pickPreset = async (keyword, shot, note) => {
    const picker = page.locator('.ant-select-selector').first();
    await picker.click();
    await page.waitForTimeout(500);
    const search = page.locator('.ant-select-dropdown .ant-select-search input').first();
    if (await search.count()) {
      await search.fill(keyword);
      await page.waitForTimeout(900);
    }
    const opt = page.locator('.ant-select-item-option').first();
    if (!(await opt.count())) {
      log('!! 下拉里没找到 ' + keyword);
      await page.keyboard.press('Escape');
      return;
    }
    await opt.click();
    await page.waitForTimeout(1400);
    await page.screenshot({ path: `${OUT}/${shot}`, fullPage: true });
    log(`${shot}（${note}）`);
  };

  await pickPreset('Ollama', '31-llm-preset-ollama.png', 'Ollama 预设自动填参');
  await pickPreset('Anthropic', '32-llm-preset-anthropic.png', 'Anthropic 无原生 JSON 模式提示');
  await pickPreset('Gemini', '35-llm-preset-gemini.png', 'Gemini 模型名嵌在路径里');

  // 试连通（当前无 Key，应给出清晰失败原因）
  const testBtn = page.locator('button').filter({ hasText: /试\s*连\s*通/ });
  if (await testBtn.count()) {
    await testBtn.first().click();
    await page.waitForTimeout(6000);
    await page.screenshot({ path: `${OUT}/33-llm-test-result.png`, fullPage: true });
    log('33-llm-test-result.png（试连通结果）');
  }

  // 教师：内容管理页状态条应显示真实协议/模型
  await login('teacher', 'teacher123');
  await page.goto(BASE + '/contents', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${OUT}/34-content-status-real.png`, fullPage: true });
  log('34-content-status-real.png（内容页状态条显示真实配置）');

  console.log('\n控制台错误数: ' + errors.length);
  errors.slice(0, 6).forEach((e) => console.log('  ! ' + e.slice(0, 180)));

  await browser.close();
})().catch((e) => { console.error('FAILED: ' + e.message); process.exit(1); });
