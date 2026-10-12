/**
 * 诊 → 学 → 练 前端端到端走查 + 截图。
 *
 * 用真实 Chromium 逐页打开新做的页面，收集控制台错误，并把截图落到 screenshots-learn/。
 * 本机只有 chromium-1234 完整包，必须显式指定 executablePath。
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const EXEC = 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe';
const BASE = 'http://localhost:5173';
const OUT = path.resolve(__dirname, 'screenshots-learn');
fs.mkdirSync(OUT, { recursive: true });

const problems = [];

async function login(page, username, password) {
  await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
  await page.fill('#username', username);
  await page.fill('#password', password);
  await page.click('button[type="submit"]');
  await page.waitForURL((u) => !u.pathname.startsWith('/login'), { timeout: 15000 });
  await page.waitForLoadState('networkidle').catch(() => {});
}

async function shot(page, name, opts = {}) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(opts.wait ?? 700);
  await page.screenshot({ path: path.join(OUT, name), fullPage: opts.full !== false });
  console.log('  shot ->', name);
}

(async () => {
  const browser = await chromium.launch({ executablePath: EXEC, headless: true });

  // ---------------- 学生 ----------------
  const studentCtx = await browser.newContext({ viewport: { width: 1440, height: 950 } });
  const sp = await studentCtx.newPage();
  sp.on('console', (m) => {
    if (m.type() === 'error') problems.push('[student console] ' + m.text().slice(0, 200));
  });
  sp.on('pageerror', (e) => problems.push('[student pageerror] ' + String(e.message).slice(0, 200)));

  console.log('\n--- 学生端 ---');
  await login(sp, 'stu1', '123456');
  console.log('  landed:', new URL(sp.url()).pathname);
  await shot(sp, '01-student-learn-home.png');

  // 今日清单里的任务类型
  const taskTags = await sp.locator('.ant-table-tbody .ant-tag').allInnerTexts();
  console.log('  任务标签:', JSON.stringify(taskTags.slice(0, 14)));

  await sp.goto(BASE + '/my/diagnosis', { waitUntil: 'domcontentloaded' });
  await shot(sp, '02-student-diagnosis-report.png');
  const diagHead = await sp.locator('.ant-statistic').allInnerTexts();
  console.log('  诊断统计卡:', JSON.stringify(diagHead.slice(0, 4)));

  // 从首页点「开始学习」进内容页
  await sp.goto(BASE + '/my', { waitUntil: 'domcontentloaded' });
  await sp.waitForLoadState('networkidle').catch(() => {});
  const learnBtn = sp.locator('button:has-text("开始学习")').first();
  if (await learnBtn.count()) {
    await learnBtn.click();
    await sp.waitForTimeout(1500);
    console.log('  点开始学习后:', new URL(sp.url()).pathname);
    await shot(sp, '03-student-node-content.png');
    const md = await sp.locator('h2, h1').allInnerTexts().catch(() => []);
    console.log('  内容标题区:', JSON.stringify(md.slice(0, 4)));
  } else {
    problems.push('学生首页找不到「开始学习」按钮');
  }

  await sp.goto(BASE + '/my/graph', { waitUntil: 'domcontentloaded' });
  await shot(sp, '04-student-graph.png');

  // ---------------- 教师 ----------------
  const teacherCtx = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const tp = await teacherCtx.newPage();
  tp.on('console', (m) => {
    if (m.type() === 'error') problems.push('[teacher console] ' + m.text().slice(0, 200));
  });
  tp.on('pageerror', (e) => problems.push('[teacher pageerror] ' + String(e.message).slice(0, 200)));

  console.log('\n--- 教师端 ---');
  await login(tp, 'teacher', 'teacher123');
  await tp.goto(BASE + '/contents', { waitUntil: 'domcontentloaded' });
  await shot(tp, '05-teacher-content-manage.png');
  const cover = await tp.locator('.ant-card').nth(1).innerText().catch(() => '');
  console.log('  内容覆盖卡:', cover.replace(/\s+/g, ' ').slice(0, 110));

  // 打开有内容的那个知识点的抽屉
  const contentBtn = tp.locator('button:has-text("内容")').first();
  if (await contentBtn.count()) {
    await contentBtn.click();
    await tp.waitForTimeout(1200);
    await shot(tp, '06-teacher-content-drawer.png');
    const drawerTitle = await tp.locator('.ant-drawer-title').innerText().catch(() => '');
    console.log('  抽屉标题:', drawerTitle);
  } else {
    problems.push('内容管理页没有找到「内容」按钮');
  }
  await tp.keyboard.press('Escape');
  await tp.waitForTimeout(400);

  await tp.goto(BASE + '/recommend-rules', { waitUntil: 'domcontentloaded' });
  await shot(tp, '07-recommend-rules.png');
  const ruleRows = await tp.locator('.ant-table-tbody tr').count();
  console.log('  规则行数:', ruleRows);

  await tp.goto(BASE + '/questions', { waitUntil: 'domcontentloaded' });
  await shot(tp, '08-question-bank.png');
  const mapBtn = tp.locator('button:has-text("识别知识点")').first();
  if (await mapBtn.count()) {
    await mapBtn.click();
    await tp.waitForTimeout(2500);
    await shot(tp, '09-question-mapping-modal.png');
    const notice = await tp.locator('.ant-modal .ant-alert-message').first().innerText().catch(() => '');
    console.log('  识别模式提示:', notice);
    const matchRows = await tp.locator('.ant-modal .ant-table-tbody tr').count();
    console.log('  识别建议行数:', matchRows);
  } else {
    problems.push('题库页没有找到「识别知识点」按钮');
  }
  await tp.keyboard.press('Escape');
  await tp.waitForTimeout(400);

  // 一键映射弹窗
  const autoBtn = tp.locator('button:has-text("一键映射知识点")').first();
  if (await autoBtn.count()) {
    await autoBtn.click();
    await tp.waitForTimeout(800);
    await shot(tp, '10-question-auto-mapping.png');
    await tp.keyboard.press('Escape');
    await tp.waitForTimeout(300);
  } else {
    problems.push('题库页没有找到「一键映射知识点」按钮');
  }

  await tp.goto(BASE + '/students/1/tasks', { waitUntil: 'domcontentloaded' });
  await shot(tp, '11-teacher-student-tasks.png');
  const alert = await tp.locator('.ant-alert-message').first().innerText().catch(() => '');
  console.log('  教师视角提示:', alert);

  await tp.goto(BASE + '/students/1/diagnosis', { waitUntil: 'domcontentloaded' });
  await shot(tp, '12-teacher-student-diagnosis.png');

  await tp.goto(BASE + '/students/1', { waitUntil: 'domcontentloaded' });
  await shot(tp, '13-teacher-student-graph.png', { full: false });

  // ---------------- 机构管理员 ----------------
  console.log('\n--- 机构管理平台回归 ---');
  const orgCtx = await browser.newContext({ viewport: { width: 1500, height: 950 } });
  const op = await orgCtx.newPage();
  op.on('pageerror', (e) => problems.push('[org pageerror] ' + String(e.message).slice(0, 200)));
  await login(op, 'admin', 'admin123');
  await op.goto(BASE + '/org', { waitUntil: 'domcontentloaded' });
  await shot(op, '14-org-console.png', { full: false });

  await browser.close();

  console.log('\n--- 控制台问题 ---');
  if (!problems.length) {
    console.log('  无');
  } else {
    problems.forEach((p) => console.log('  ' + p));
  }
  fs.writeFileSync(path.join(OUT, 'e2e-problems.txt'), problems.join('\n'), 'utf8');
})().catch((e) => {
  console.error('E2E 失败:', e);
  process.exit(1);
});
