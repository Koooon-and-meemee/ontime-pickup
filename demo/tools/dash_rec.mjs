// usage: node dash_rec.mjs <url> <seconds> <outdir>
import { chromium } from 'playwright-core';
const [url, secs, out] = process.argv.slice(2);
const browser = await chromium.launch({ channel: 'chrome', headless: true });
const ctx = await browser.newContext({ viewport: { width: 1280, height: 720 }, deviceScaleFactor: 1, colorScheme: 'light',
  recordVideo: { dir: out, size: { width: 1280, height: 720 } } });
const page = await ctx.newPage();
await page.goto(url);
console.log('REC_START', Date.now());
await page.waitForTimeout(Number(secs) * 1000);
await ctx.close(); await browser.close();
console.log('REC_END', Date.now());
