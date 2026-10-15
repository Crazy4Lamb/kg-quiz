/**
 * 推荐配置中心 + 学习记录页 端到端走查 + 截图。
 * 前置：dev server 在 5173，后端在 8090（已用 verify-recommend.py 跑过诊断，stu1 有学习记录）。
 */
const { chromium } = require('playwright');
const fs = require('fs');

const BASE = process.env.KG_FRONTEND || 'http://localhost:5173';
const OUT = 'screenshots-recommend';

const log = (...a) => console.log(...a);

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe'
  });
  const page = await browser.newPage({ viewport: { width: 1500, height: 1100 } });

  const errors = [];
  page.on('console', (m) => {
    const t = m.text();
    if (m.type() === 'error' && !/findDOMNode|antd v5|Support for defaultProps|destroyOnClose/i.test(t)) {
      errors.push(t);
    }
  });
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
  page.on('response', (resp) => {
    if (resp.status() >= 400) {
      log(`  [HTTP ${resp.status()}] ${resp.url()}`);
    }
  });

  const fail = [];
  const assert = (cond, label) => { if (cond) log('✓', label); else { fail.push(label); log('✗', label); } };

  try {
    await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
    await page.getByPlaceholder(/admin \/ teacher/).first().fill('admin');
    await page.getByPlaceholder('请输入密码').first().fill('admin123');
    await page.getByRole('button', { name: /登\s*录/ }).click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 });
    log('✓ 登录成功');

    // ===== 推荐配置中心 =====
    await page.goto(BASE + '/recommend-rules', { waitUntil: 'networkidle' });
    await page.waitForSelector('text=推荐策略与参数', { timeout: 15000 });
    await page.waitForTimeout(1000);

    const stratCards = await page.locator('.ant-card:has-text("补弱优先"), .ant-card:has-text("顺序推进"), .ant-card:has-text("应试优先"), .ant-card:has-text("均衡")').count();
    assert(stratCards >= 4, `策略卡片出现（${stratCards} 张）`);
    const sliders = await page.locator('.ant-slider').count();
    assert(sliders >= 10, `因子滑块渲染（${sliders} 个）`);
    await page.screenshot({ path: `${OUT}/01-recommend-config.png`, fullPage: true });
    log('✓ 推荐配置中心截图');

    // 选有数据的学生（小明/1），否则可能选到尚未复制图谱的学生导致模拟 400
    const simSel = page.getByText('沙盒模拟对象：').locator('..').locator('.ant-select');
    await simSel.click();
    await page.waitForTimeout(400);
    await page.locator('.ant-select-item-option').filter({ hasText: '小明' }).first().click();
    await page.waitForTimeout(600);
    log('✓ 已选择模拟学生（小明）');

    // 点「沙盒模拟」
    await page.getByRole('button', { name: /沙盒模拟/ }).click();
    await page.waitForSelector('text=沙盒模拟结果', { timeout: 15000 });
    await page.waitForTimeout(1200);
    await page.screenshot({ path: `${OUT}/02-recommend-simulate.png`, fullPage: true });
    log('✓ 沙盒模拟结果截图');

    // ===== 学习记录页（管理员选学生）=====
    await page.goto(BASE + '/learning-records', { waitUntil: 'networkidle' });
    await page.waitForSelector('text=学习记录与掌握程度', { timeout: 15000 });
    await page.waitForTimeout(800);
    // 选学生
    const recSel = page.locator('.ant-select').first();
    await recSel.click();
    await page.waitForTimeout(500);
    await page.locator('.ant-select-item-option').filter({ hasText: '小明' }).first().click();
    await page.waitForTimeout(1500);
    await page.screenshot({ path: `${OUT}/03-learning-records.png`, fullPage: true });
    const levelTags = await page.locator('.ant-tag:has-text("薄弱"), .ant-tag:has-text("入门"), .ant-tag:has-text("掌握")').count();
    assert(levelTags > 0, `掌握度分级标签渲染（${levelTags}）`);
    const timeline = await page.locator('text=学习记录时间线').count();
    assert(timeline > 0, '时间线区块渲染');
    log('✓ 学习记录页截图');

    // 点开一个知识点轨迹抽屉
    const nodeTag = page.locator('.ant-tag').filter({ hasText: /薄弱|未接触/ }).first();
    if (await nodeTag.count() > 0) {
      await nodeTag.click();
      await page.waitForTimeout(800);
      await page.screenshot({ path: `${OUT}/04-node-trajectory.png`, fullPage: true });
      log('✓ 知识点轨迹抽屉截图');
    }

  } catch (e) {
    fail.push('EXCEPTION: ' + e.message);
    log('✗ 异常：', e.message);
  }

  if (errors.length) {
    log('\n[console/page errors]');
    errors.slice(0, 10).forEach((e) => log('  !', e));
  }
  await browser.close();
  log(`\n==== E2E 结果：断言失败 ${fail.length} 个，控制台错误 ${errors.length} 条 ====`);
  if (fail.length) { console.log('失败项：', fail); process.exit(1); }
})();
