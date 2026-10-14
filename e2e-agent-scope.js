/**
 * Agent 生效范围（校区 / 班级）端到端走查 + 截图。
 *
 * 关注点：管理台能不能把「在哪儿启用」这件事讲清楚——
 * 生效范围列、校区/班级筛选、两个总览 Tab、编辑弹窗的级联选择。
 */
const { chromium } = require('playwright');
const fs = require('fs');

const BASE = process.env.KG_FRONTEND || 'http://localhost:5173';
const OUT = 'screenshots-scope';

const log = (...a) => console.log(...a);

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe'
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });

  const errors = [];
  page.on('console', (m) => {
    const t = m.text();
    // 只抓业务错误。antd 的 React 17 兼容告警与 destroyOnClose 弃用提示是全项目既有问题，
    // 不属于本次改动范围，不计入。
    if (m.type() === 'error'
      && !/findDOMNode|antd v5|Support for defaultProps|destroyOnClose/i.test(t)) {
      errors.push(t);
    }
  });
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));

  try {
    // ---- 登录 ----
    await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await page.getByPlaceholder(/admin \/ teacher/).first().fill('admin');
    await page.getByPlaceholder('请输入密码').first().fill('admin123');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 });
    log('✓ 登录成功');

    // ---- 进 Agent 管理台 ----
    await page.goto(BASE + '/agents', { waitUntil: 'networkidle' });
    await page.waitForSelector('text=Agent 编排', { timeout: 15000 });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${OUT}/01-agents-list.png`, fullPage: true });
    log('✓ Agent 编排列表已截图');

    // ---- 生效范围列 ----
    const scopeHeader = await page.locator('th', { hasText: '生效范围' }).count();
    log(scopeHeader > 0 ? '✓ 列表有「生效范围」列' : '✗ 缺「生效范围」列');

    // ---- 校区 Tab ----
    await page.getByRole('tab', { name: /校区/ }).click();
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${OUT}/02-campus-overview.png`, fullPage: true });
    log('✓ 校区总览已截图');

    // ---- 班级 Tab ----
    await page.getByRole('tab', { name: /班级/ }).click();
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${OUT}/03-clazz-overview.png`, fullPage: true });
    log('✓ 班级总览已截图');

    // ---- 工具注册表 ----
    await page.getByRole('tab', { name: /工具注册表/ }).click();
    await page.waitForTimeout(1000);
    await page.screenshot({ path: `${OUT}/04-tool-registry.png`, fullPage: true });
    log('✓ 工具注册表已截图');

    // ---- 回编排页，验证级联选择器存在 ----
    await page.getByRole('tab', { name: /Agent 编排/ }).click();
    await page.waitForTimeout(800);

    const campusFilter = await page.locator('.ant-select').filter({ hasText: '按校区筛选' }).count();
    log(campusFilter > 0 ? '✓ 有「按校区筛选」下拉' : '✗ 缺校区筛选');

    const clazzFilter = await page.locator('.ant-select').filter({ hasText: '按班级筛选' }).count();
    log(clazzFilter > 0 ? '✓ 有「按班级筛选」下拉' : '✗ 缺班级筛选');

    // ---- 打开编辑弹窗看级联 ----
    await page.getByRole('button', { name: '新建 Agent' }).click();
    await page.waitForTimeout(1000);
    // antd Cascader 的根节点类名是 .ant-cascader（不是 .ant-cascader-picker）
    const cascader = await page.locator('.ant-cascader').count();
    log(cascader > 0 ? '✓ 编辑弹窗有生效范围级联选择器' : '✗ 编辑弹窗缺级联选择器');
    await page.screenshot({ path: `${OUT}/05-editor-scope.png`, fullPage: true });

    // 展开级联看是否装出机构→校区→班级
    if (cascader > 0) {
      await page.locator('.ant-cascader').first().click();
      await page.waitForTimeout(900);
      await page.screenshot({ path: `${OUT}/06-cascader-open.png`, fullPage: true });      // 逐层展开验证：antd Cascader 的 hover 展开在 headless 下定位不稳，
      // 改用键盘方向键（Ant Design 原生支持，也是真实用户会用的操作）
      const menus0 = await page.locator('.ant-cascader-menu').allInnerTexts();
      log(menus0.length > 0
        ? '✓ 级联第 1 层装出机构：' + (menus0[0] || '').trim().replace(/\n/g, ' / ')
        : '✗ 级联第 1 层为空');

      await page.keyboard.press('ArrowDown');
      await page.waitForTimeout(300);
      await page.keyboard.press('ArrowRight');   // 展开当前机构 → 校区层
      await page.waitForTimeout(1200);
      const menus = await page.locator('.ant-cascader-menu').allInnerTexts();
      log(menus.length > 1
        ? '✓ 级联第 2 层装出校区：' + (menus[1] || '').trim().replace(/\n/g, ' / ')
        : '✗ 级联第 2 层为空（只装出机构层）');
      await page.screenshot({ path: `${OUT}/06-cascader-campus.png`, fullPage: true });

      if (menus.length > 1 && (menus[1] || '').trim()) {
        await page.keyboard.press('ArrowDown');
        await page.waitForTimeout(300);
        await page.keyboard.press('ArrowRight'); // 展开当前校区 → 班级层
        await page.waitForTimeout(1200);
        const menus2 = await page.locator('.ant-cascader-menu').allInnerTexts();
        log(menus2.length > 2 && (menus2[2] || '').trim()
          ? '✓ 级联第 3 层装出班级：' + (menus2[2] || '').trim().replace(/\n/g, ' / ')
          : '✗ 级联第 3 层为空（该校区下无班级）');
        await page.screenshot({ path: `${OUT}/07-cascader-clazz.png`, fullPage: true });
      }
    }
    await page.keyboard.press('Escape');
    await page.waitForTimeout(600);

    // ---- 机构管理页确认校区/班级 CRUD 仍在 ----
    await page.goto(BASE + '/org', { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${OUT}/07-org-console.png`, fullPage: true });
    const orgTabs = await page.locator('.ant-tabs-tab').allInnerTexts();
    log('✓ 机构管理 Tab: ' + orgTabs.join(' / '));

    log('\n' + (errors.length ? '✗ 控制台业务错误 ' + errors.length + ' 条：' : '✓ 无业务控制台错误'));
    errors.slice(0, 5).forEach((e) => log('   ' + e));
  } catch (e) {
    log('✗ 走查异常：' + e.message);
    await page.screenshot({ path: `${OUT}/99-error.png`, fullPage: true }).catch(() => { });
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
