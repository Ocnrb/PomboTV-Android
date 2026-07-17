// Testa os modos de rede do v3: node viewer-modes.js fast|proxyonly [dur]
// fast      → clique FRIO imediato; espera-se arranque rápido em malha + promoção
// proxyonly → net: tem de mostrar SEMPRE proxy (nunca mesh) e nunca reload
const puppeteer = require('puppeteer');
const path = require('path');
const POC = 'file:///' + path.resolve('c:/Users/v-f-r/Desktop/STABLE VERSIONS/p2p video - WORKING - EN/live-poc-v3.html').replace(/\\/g, '/');
const MODE = process.argv[2] || 'fast';
const DUR_S = parseInt(process.argv[3] || '50', 10);
function ts() { return new Date().toISOString().slice(11, 23); }
function log(s) { console.log(ts() + ' ' + s); }

(async () => {
  const browser = await puppeteer.launch({ headless: 'new', args: ['--autoplay-policy=no-user-gesture-required', '--no-sandbox'] });
  const page = await browser.newPage();
  await page.evaluateOnNewDocument((mode) => {
    localStorage.setItem('poc-optMeshStart', mode === 'fast' ? '1' : '0');
    localStorage.setItem('poc-optProxyOnly', mode === 'proxyonly' ? '1' : '0');
  }, MODE);
  page.on('console', m => { const t = m.text(); if (/fast start|proxy|watchdog|promovido|falhou/.test(t)) log('PAGE: ' + t.slice(0, 130)); });
  await page.goto(POC);
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('connected'), { timeout: 60000 });
  log('connected — clique FRIO imediato (mode=' + MODE + ')');
  const tW = Date.now();
  await page.click('#goWatch');
  let playing = false, meshSeen = false, proxySeen = false;
  setInterval(async () => {
    try {
      const s = await page.evaluate(() => document.getElementById('stats').textContent.replace(/\n/g, ' ¦ '));
      const st = await page.evaluate(() => document.getElementById('wStatus').textContent);
      if (!playing && /playing=true/.test(s)) { playing = true; log('PLAYING +' + ((Date.now() - tW) / 1000).toFixed(1) + 's'); }
      if (/net: .*mesh/.test(s)) meshSeen = true;
      if (/net: .*proxy/.test(s)) proxySeen = true;
      log('S [' + st + '] ' + s.slice(0, 170));
      if (Date.now() - tW > DUR_S * 1000) {
        log('RESULTADO mode=' + MODE + ' playing=' + playing + ' meshVisto=' + meshSeen + ' proxyVisto=' + proxySeen);
        if (MODE === 'proxyonly' && meshSeen) log('*** FALHA: proxy-only mostrou malha!');
        process.exit(0);
      }
    } catch (e) {}
  }, 5000);
})().catch(e => { console.error('ERRO: ' + e.message); process.exit(1); });
