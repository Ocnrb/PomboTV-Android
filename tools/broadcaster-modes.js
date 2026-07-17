// Broadcaster automatizado v3: Chromium (Puppeteer) com câmara/micro sintéticos
// a correr o live-poc-v3.html. Arg "part" → ativa o teste áudio-como-partição.
const puppeteer = require('puppeteer');
const path = require('path');

const POC = 'file:///' + path.resolve('c:/Users/v-f-r/Desktop/STABLE VERSIONS/p2p video - WORKING - EN/live-poc-v3.html').replace(/\\/g, '/');
const PART = process.argv.includes('part');

(async () => {
  const browser = await puppeteer.launch({
    headless: 'new',
    args: [
      '--use-fake-ui-for-media-stream',
      '--use-fake-device-for-media-stream',
      '--autoplay-policy=no-user-gesture-required',
      '--no-sandbox',
    ],
  });
  const page = await browser.newPage();
  page.on('console', m => { const t = m.text(); if (!t.includes('[object')) console.log('PAGE:', t.slice(0, 160)); });
  const MODE = process.argv[2] || 'normal';
  await page.evaluateOnNewDocument((m) => {
    localStorage.setItem('poc-onePart', (m === 'onepart' || m === 'mux') ? '1' : '0');
    localStorage.setItem('poc-muxAV', m === 'mux' ? '1' : '0');
  }, MODE);
  console.log('BROADCASTER MODE=' + MODE);
  await page.goto(POC);
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('connected'), { timeout: 60000 });
  console.log('BROADCASTER: connected (v3, audioPart=' + PART + ')');
  await page.click('#goLive');
  await new Promise(r => setTimeout(r, 3000)); // requestDevices (fake)
  // teste de partição: marcar a checkbox (onchange grava localStorage + rediscovery)
  // (audioPart removido no v3)
  
  await page.click('#startBtn');
  console.log('BROADCASTER: startBroadcast clicked');
  setInterval(async () => {
    try {
      const s = await page.evaluate(() => document.getElementById('bStats').textContent.replace(/\n/g, ' ¦ ') + ' | ' + document.getElementById('bStatus').textContent);
      console.log('BSTATS:', s);
    } catch (e) {}
  }, 5000);
})().catch(e => { console.error('ERRO: ' + (e.stack || e.message)); process.exit(1); });
