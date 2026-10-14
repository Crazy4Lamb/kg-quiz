/**
 * 组织管理层级端到端：校区管理员分配 / 班级分层 / 班级下钻 / 批量导入。
 *
 * 用最新的 dev 实例（默认 5175），截图输出到 screenshots-org/。
 */
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

const BASE = process.env.KG_FRONTEND || 'http://localhost:5175';
const OUT = path.join(__dirname, 'screenshots-org');
fs.mkdirSync(OUT, { recursive: true });

const log = (m) => console.log('  ' + m);

async function shot(page, name) {
  await page.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
  log(`📷 ${name}.png`);
}

async function login(page, username, password) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' });
  await page.getByPlaceholder(/admin \/ teacher/).first().fill(username);
  await page.getByPlaceholder('请输入密码').first().fill(password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 });
  await page.waitForTimeout(1200);
}

(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe'
  });
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });

  const errors = [];
  page.on('console', (m) => {
    const t = m.text();
    if (m.type() === 'error' && !/findDOMNode|antd v5|Support for defaultProps/i.test(t)) {
      errors.push(t);
    }
  });
  page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));

  console.log('\n=== 组织管理层级 E2E (' + BASE + ') ===\n');

  // ---------- 平台管理员视角 ----------
  console.log('[1] 平台管理员');
  await login(page, 'admin', 'admin123');
  await page.goto(BASE + '/org', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);
  await shot(page, '01-org-overview');

  // 校区 Tab —— 应看到「校区管理员」列
  await page.getByRole('tab', { name: /校\s*区/ }).first().click();
  await page.waitForTimeout(1200);
  await shot(page, '02-campus-admins-column');
  const hasAdminCol = await page.locator('th', { hasText: '校区管理员' }).count();
  log(hasAdminCol > 0 ? '✓ 校区列表有「校区管理员」列' : '✗ 缺「校区管理员」列');

  const unassignedTag = await page.locator('text=未分配').count();
  log(unassignedTag > 0 ? `✓ ${unassignedTag} 个校区标为未分配管理员（预期提示）` : '· 所有校区都已配管理员');

  // 校区管理员 Tab
  await page.getByRole('tab', { name: /校区管理员/ }).click();
  await page.waitForTimeout(1200);
  await shot(page, '03-campus-admin-tab');
  const adminTabText = await page.locator('.ant-tabs-tabpane-active').innerText();
  log(adminTabText.includes('校区管理员 = 一个校区的负责人')
    ? '✓ 管理员 Tab 有职责说明' : '✗ 管理员 Tab 缺职责说明');

  // 分配管理员弹窗
  await page.getByRole('tab', { name: /校\s*区/ }).first().click();
  await page.waitForTimeout(800);
  const assignBtn = page.getByRole('button', { name: /配\s*管\s*理\s*员/ }).first();
  if (await assignBtn.count()) {
    await assignBtn.click();
    await page.waitForTimeout(1000);
    await shot(page, '04-assign-campus-admin');
    const modalText = await page.locator('.ant-modal-content:visible').last().innerText();
    log(modalText.includes('权限边界就是下面选的校区')
      ? '✓ 弹窗说明了权限边界' : '✗ 弹窗缺权限边界说明');
    await page.getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(500);
  } else {
    log('✗ 找不到「配管理员」按钮');
  }

  // 班级 Tab
  await page.getByRole('tab', { name: /班\s*级/ }).first().click();
  await page.waitForTimeout(1200);
  await shot(page, '05-clazz-list');
  const drillBtn = page.getByRole('button', { name: /下\s*钻/ }).first();
  const importBtn = page.getByRole('button', { name: /导入学生/ }).first();
  log((await drillBtn.count()) > 0 ? '✓ 班级行有「下钻」入口' : '✗ 班级行缺「下钻」入口');
  log((await importBtn.count()) > 0 ? '✓ 班级行有「导入学生」入口' : '✗ 班级行缺「导入学生」入口');

  // 班级编辑弹窗 —— 应有「上级班级」
  const editBtn = page.getByRole('button', { name: /^编\s*辑$/ }).first();
  if (await editBtn.count()) {
    await editBtn.click();
    await page.waitForTimeout(1000);
    await shot(page, '06-clazz-editor-parent');
    const hasParent = await page.locator('.ant-modal-content:visible .ant-form-item', { hasText: '上级班级' }).count();
    log(hasParent > 0 ? '✓ 班级编辑有「上级班级」选择' : '✗ 班级编辑缺「上级班级」选择');
    await page.getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(500);
  }

  // 班级下钻抽屉
  if (await drillBtn.count()) {
    await drillBtn.click();
    await page.waitForTimeout(2000);
    await shot(page, '07-clazz-detail-drawer');
    const drawer = await page.locator('.ant-drawer-content').innerText().catch(() => '');
    log(drawer.includes('任课教师') ? '✓ 抽屉含任课教师区' : '✗ 抽屉缺任课教师区');
    log(drawer.includes('学生名单') ? '✓ 抽屉含学生名单区' : '✗ 抽屉缺学生名单区');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(600);
  }

  // 批量导入弹窗
  if (await importBtn.count()) {
    await importBtn.click();
    await page.waitForTimeout(1000);
    await shot(page, '08-import-students');
    const imp = await page.locator('.ant-modal-content:visible').last().innerText();
    log(imp.includes('每行一个学生姓名') ? '✓ 导入弹窗有格式说明' : '✗ 导入弹窗缺格式说明');
    await page.getByRole('button', { name: /取\s*消/ }).click();
    await page.waitForTimeout(500);
  }

  // ---------- 校区管理员视角 ----------
  console.log('\n[2] 校区管理员视角');
  // 先造一个管理员账号
  const created = await page.evaluate(async () => {
    const login = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'admin123' })
    }).then((r) => r.json());
    const t = login.data.token;
    const camps = await fetch('/api/orgs/2/campuses', { headers: { Authorization: 'Bearer ' + t } })
      .then((r) => r.json());
    const campus = camps.data[0];
    const res = await fetch('/api/orgs/campus-admins', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + t },
      body: JSON.stringify({
        username: 'campus_e2e_admin', password: 'campus123',
        displayName: 'E2E校区负责人', campusId: campus.id
      })
    }).then((r) => r.json());
    return { campusId: campus.id, code: res.code, userId: res.data && res.data.userId };
  });
  log(created.code === 0 || created.code === 200
    ? `✓ 创建校区管理员（校区 ${created.campusId}）` : `· 创建返回 code=${created.code}（可能已存在）`);

  if (created.userId) {
    await page.evaluate(() => localStorage.clear());
    await login(page, 'campus_e2e_admin', 'campus123');
    log('✓ 校区管理员登录成功，落地页 ' + new URL(page.url()).pathname);
    await page.goto(BASE + '/org', { waitUntil: 'networkidle' });
    await page.waitForTimeout(2000);
    await shot(page, '09-campus-admin-view');

    const adminTab = page.getByRole('tab', { name: /校区管理员/ });
    log((await adminTab.count()) === 0
      ? '✓ 校区管理员看不到「分配管理员」Tab（层级正确）'
      : '✗ 校区管理员仍能看到管理员分配 Tab');

    const campusRows = await page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row').count();
    log(campusRows === 1 ? '✓ 只看到自己那一个校区' : `✗ 看到 ${campusRows} 个校区`);

    // 清理
    await page.evaluate(async (uid) => {
      const l = await fetch('/api/auth/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: 'admin', password: 'admin123' })
      }).then((r) => r.json());
      await fetch('/api/orgs/campus-admins/' + uid, {
        method: 'DELETE', headers: { Authorization: 'Bearer ' + l.data.token }
      });
    }, created.userId);
    log('✓ 已清理 E2E 管理员账号');
  }

  console.log('\n--- 业务控制台错误 ---');
  if (errors.length) {
    errors.slice(0, 8).forEach((e) => console.log('  ✗ ' + e.slice(0, 180)));
  } else {
    console.log('  无 ✅');
  }

  await browser.close();
})();