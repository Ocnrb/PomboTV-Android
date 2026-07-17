// E2E da chamada 1:1 no v3: dois Chromium headless (host+guest) com media
// sintética; amostra cStats dos dois lados e verifica vídeo/áudio a fluir.
const puppeteer = require('puppeteer');
const path = require('path');
const POC = 'file:///' + path.resolve('c:/Users/v-f-r/Desktop/STABLE VERSIONS/p2p video - WORKING - EN/live-poc-v3.html').replace(/\\/g, '/');
const DUR_S = parseInt(process.argv[2] || '60', 10);
function ts() { return new Date().toISOString().slice(11, 23); }

async function mkPeer(label) {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream',
           '--autoplay-policy=no-user-gesture-required', '--no-sandbox'],
  });
  const page = await browser.newPage();
  page.on('console', m => { const t = m.text(); if (/call|proxy|falhou|erro|Error/i.test(t) && !t.includes('[object')) console.log(ts() + ' [' + label + '] ' + t.slice(0, 130)); });
  page.on('pageerror', e => console.log(ts() + ' [' + label + '] PAGEERROR ' + e.message.slice(0, 200)));
  await page.goto(POC);
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('connected'), { timeout: 60000 });
  console.log(ts() + ' [' + label + '] connected');
  return { browser, page };
}

(async () => {
  const host = await mkPeer('HOST');
  const guest = await mkPeer('GUEST');
  await host.page.click('#goCall'); await host.page.click('#callHost');
  console.log(ts() + ' HOST clicked Start call');
  await new Promise(r => setTimeout(r, 3000));
  await guest.page.click('#goCall'); await guest.page.click('#callGuest');
  console.log(ts() + ' GUEST clicked Join call');
  const t0 = Date.now();
  let hostOk = false, guestOk = false;
  const timer = setInterval(async () => {
    try {
      const hs = await host.page.evaluate(() => document.getElementById('cStats').textContent.replace(/\n/g, ' ¦ '));
      const gs = await guest.page.evaluate(() => document.getElementById('cStats').textContent.replace(/\n/g, ' ¦ '));
      const hst = await host.page.evaluate(() => document.getElementById('cStatus').textContent);
      const gst = await guest.page.evaluate(() => document.getElementById('cStatus').textContent);
      console.log(ts() + ' HOST  [' + hst + '] ' + hs);
      console.log(ts() + ' GUEST [' + gst + '] ' + gs);
      if (!hostOk && /vDec=[1-9]/.test(hs)) { hostOk = true; console.log(ts() + ' *** HOST a receber vídeo +' + ((Date.now() - t0) / 1000).toFixed(1) + 's'); }
      if (!guestOk && /vDec=[1-9]/.test(gs)) { guestOk = true; console.log(ts() + ' *** GUEST a receber vídeo +' + ((Date.now() - t0) / 1000).toFixed(1) + 's'); }
      if (Date.now() - t0 > DUR_S * 1000) {
        console.log('RESULTADO: host=' + (hostOk ? 'OK' : 'FALHOU') + ' guest=' + (guestOk ? 'OK' : 'FALHOU'));
        await host.page.screenshot({ path: '../call_host.png' });
        await guest.page.screenshot({ path: '../call_guest.png' });
        process.exit(hostOk && guestOk ? 0 : 1);
      }
    } catch (e) { console.log('sample err: ' + e.message.slice(0, 80)); }
  }, 5000);
})().catch(e => { console.error('ERRO: ' + (e.stack || e.message)); process.exit(1); });
