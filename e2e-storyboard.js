/** 讲解 IR 逐段阅读页 + 内容管理页截图 */
const { chromium } = require('playwright');
const fs = require('fs');

const OUT = 'C:/Users/Mayn/WorkBuddy/2026-10-10-10-44-20/kg-quiz/screenshots-learn';
fs.mkdirSync(OUT, { recursive: true });
const EXE = 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe';

const problems = [];
const log = (m) => { problems.push(m); console.log('  ISSUE:', m); };

async function login(page, u, p) {
  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.fill('#username', u);
  await page.fill('#password', p);
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);
}

(async () => {
  const browser = await chromium.launch({ executablePath: EXE, headless: true });
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  const page = await ctx.newPage();
  page.on('console', (m) => {
    if (m.type() === 'error') {
      const t = m.text();
      if (t.includes('destroyOnClose') || t.includes('not connected')) return;
      log('console: ' + t.slice(0, 160));
    }
  });
  page.on('pageerror', (e) => log('pageerror: ' + String(e).slice(0, 160)));

  // ---- 学生端：逐段阅读 ----
  console.log('student...');
  await login(page, 'stu1', '123456');
  await page.goto('http://localhost:5173/my/learn/3', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2200);

  const hasBoard = await page.locator('text=讲解段落').count();
  if (!hasBoard) log('未看到「讲解段落」导航条');
  const segTags = await page.locator('.ant-tag').allTextContents();
  console.log('  tags:', segTags.filter(t => /导入|新授|例题|练习|小结/.test(t)).join(' | '));

  await page.screenshot({ path: `${OUT}/20-storyboard-step1.png`, fullPage: true });

  // 点第 3 段，看画面锚点是否渲染
  const navTags = page.locator('.ant-tag').filter({ hasText: /例题精讲|情境导入|概念新授/ });
  if (await navTags.count() > 2) {
    await navTags.nth(2).click();
    await page.waitForTimeout(900);
  }
  const anchor = await page.locator('text=/锚点|⏱/').count();
  if (!anchor) log('画面锚点提示未渲染');
  await page.screenshot({ path: `${OUT}/21-storyboard-anchor.png`, fullPage: true });

  // 下一段
  const nextBtn = page.locator('button').filter({ hasText: '下一段' });
  if (await nextBtn.count()) {
    await nextBtn.first().click();
    await page.waitForTimeout(700);
    await page.screenshot({ path: `${OUT}/22-storyboard-next.png`, fullPage: true });
  }

  // 通读模式
  const readBtn = page.locator('button').filter({ hasText: '切成通读' });
  if (await readBtn.count()) {
    await readBtn.first().click();
    await page.waitForTimeout(900);
    await page.screenshot({ path: `${OUT}/23-storyboard-readall.png`, fullPage: true });
  }

  // ---- 教师端：内容管理 ----
  console.log('teacher...');
  const p2 = await ctx.newPage();
  p2.on('console', (m) => {
    if (m.type() === 'error') {
      const t = m.text();
      if (t.includes('destroyOnClose') || t.includes('not connected')) return;
      log('console(teacher): ' + t.slice(0, 160));
    }
  });
  p2.on('pageerror', (e) => log('pageerror(teacher): ' + String(e).slice(0, 160)));
  await login(p2, 'teacher', 'teacher123');
  await p2.goto('http://localhost:5173/contents', { waitUntil: 'networkidle' });
  await p2.waitForTimeout(2000);
  await p2.screenshot({ path: `${OUT}/24-content-manage.png`, fullPage: true });

  // 打开「积的近似数」抽屉（它才有讲解 + 教案）
  const targetRow = p2.locator('tr').filter({ hasText: '积的近似数' }).first();
  const editBtns = targetRow.locator('button').filter({ hasText: '编辑内容' });
  if (await editBtns.count()) {
    await editBtns.first().click();
    await p2.waitForTimeout(2200);
    await p2.screenshot({ path: `${OUT}/25-content-drawer.png`, fullPage: true });

    // 展开所有折叠项，验证分镜预览 / 自检徽标 / 重渲染按钮
    const heads = p2.locator('.ant-collapse-header');
    const hc = await heads.count();
    console.log('  内容条数:', hc);
    for (let i = 0; i < hc; i++) {
      await heads.nth(i).click();
      await p2.waitForTimeout(400);
    }
    await p2.waitForTimeout(600);
    await p2.screenshot({ path: `${OUT}/26-content-drawer-expanded.png`, fullPage: true });

    const hasPreview = await p2.locator('text=讲解分镜 IR').count();
    if (!hasPreview) log('分镜预览卡片未出现');
    const hasRecheck = await p2.locator('button').filter({ hasText: '自检' }).count();
    if (!hasRecheck) log('「自检」按钮未出现');
    const hasRe = await p2.locator('button').filter({ hasText: '重渲染' }).count();
    if (!hasRe) log('「重渲染」按钮未出现');
    const selfCheck = await p2.locator('.ant-tag').filter({ hasText: /自检通过|待复核/ }).allTextContents();
    console.log('  自检徽标:', selfCheck.join(' | ') || '（无）');
  } else {
    log('没找到「积的近似数」这一行');
  }

  await browser.close();
  fs.writeFileSync(`${OUT}/storyboard-problems.txt`,
    problems.length ? problems.join('\n') : '无问题');
  console.log(problems.length ? `\n${problems.length} 个问题` : '\n无控制台报错');
})().catch((e) => { console.error('FATAL', e); process.exit(1); });