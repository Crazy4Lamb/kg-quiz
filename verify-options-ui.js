// 定向复验：学生知识点内容页的选项渲染（A. / B. 列表，而非原始 JSON）
const { chromium } = require('playwright');

(async () => {
  const browser = await chromium.launch({
    executablePath: 'C:/Users/Mayn/AppData/Local/ms-playwright/chromium-1234/chrome-win64/chrome.exe',
  });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });

  await page.goto('http://localhost:5173/login', { waitUntil: 'networkidle' });
  await page.fill('#username', 'stu1');
  await page.fill('#password', '123456');
  await page.click('button[type="submit"]');
  await page.waitForTimeout(1500);

  // 直接进知识点内容页（积的近似数 = node 3）
  await page.goto('http://localhost:5173/my/learn/3', { waitUntil: 'networkidle' });
  await page.waitForTimeout(2000);

  const body = await page.locator('body').innerText();
  const hasRawJson = /\["\d/.test(body) || body.includes('["0.27"');
  const hasLetter = /A\.\s*0\.27/.test(body) || /- A\./.test(body) || body.includes('A. 0.27');

  console.log('正文片段：');
  console.log(body.split('\n').filter((l) => l.trim()).slice(0, 40).join('\n'));
  console.log('\n--- 断言 ---');
  console.log('原始 JSON 残留:', hasRawJson ? 'FAIL' : 'PASS');
  console.log('字母选项渲染:', hasLetter ? 'PASS' : 'FAIL');
  if (errs.length) console.log('控制台错误:', errs.slice(0, 3));

  await page.screenshot({ path: 'screenshots-learn/15-content-options-fixed.png', fullPage: true });
  console.log('截图已存 screenshots-learn/15-content-options-fixed.png');
  await browser.close();
})();
