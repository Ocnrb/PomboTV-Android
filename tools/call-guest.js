// Guest de chamada no browser (o Host é a app Android): entra na call como
// guest e reporta cStats; grava screenshot no fim.
const puppeteer = require('puppeteer');
const path = require('path');
const POC = 'file:///' + path.resolve('c:/Users/v-f-r/Desktop/STABLE VERSIONS/p2p video - WORKING - EN/live-poc-v3.html').replace(/\\/g, '/');
const DUR_S = parseInt(process.argv[2] || '60', 10);
function ts() { return new Date().toISOString().slice(11, 23); }

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
           '--autoplay-policy=no-user-gesture-required', '--no-sandbox'],
  });
  const page = await browser.newPage();
  page.on('console', m => { const t = m.text(); if (/call|proxy|falhou/i.test(t) && !t.includes('[object')) console.log(ts() + ' PAGE ' + t.slice(0, 120)); });
  page.on('pageerror', e => console.log(ts() + ' PAGEERROR ' + e.message.slice(0, 200)));
  await page.goto(POC);
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('connected'), { timeout: 60000 });
  console.log(ts() + ' GUEST connected');
  await page.click('#goCall'); await page.click('#callGuest');
  console.log(ts() + ' GUEST joined');
  const t0 = Date.now();
  let ok = false;
  setInterval(async () => {
    try {
      const s = await page.evaluate(() => document.getElementById('cStats').textContent.replace(/\n/g, ' ¦ '));
      const st = await page.evaluate(() => document.getElementById('cStatus').textContent);
      console.log(ts() + ' [' + st + '] ' + s);
      if (!ok && /vDec=[1-9]/.test(s)) { ok = true; console.log(ts() + ' *** GUEST a receber vídeo da app +' + ((Date.now() - t0) / 1000).toFixed(1) + 's'); }
      if (Date.now() - t0 > DUR_S * 1000) {
        await page.screenshot({ path: '../call_app_guest.png' });
        console.log('RESULTADO guest=' + (ok ? 'OK' : 'FALHOU'));
        process.exit(ok ? 0 : 1);
      }
    } catch (e) {}
  }, 5000);
})().catch(e => { console.error('ERRO: ' + e.message); process.exit(1); });
