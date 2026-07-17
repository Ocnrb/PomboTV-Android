// Viewer baseline: abre o live-poc-v3.html headless, clica Watch e mede:
//  - t(connected), t(Watch click), t(primeiro sinal), t(primeiro frame decodificado)
//  - depois amostras a cada 5s: taxas, buffers, underruns, target, canvas
const puppeteer = require('puppeteer');
const path = require('path');
const POC = 'file:///' + path.resolve('c:/Users/v-f-r/Desktop/STABLE VERSIONS/p2p video - WORKING - EN/live-poc-v3.html').replace(/\\/g, '/');
const DURATION_S = parseInt(process.argv[2] || '90', 10);

function ts() { return new Date().toISOString().slice(11, 23); }
function log(s) { console.log(ts() + ' ' + s); }

(async () => {
  const t0 = Date.now();
  const browser = await puppeteer.launch({ headless: 'new', args: ['--autoplay-policy=no-user-gesture-required', '--no-sandbox'] });
  const page = await browser.newPage();
  page.on('console', m => { const t = m.text(); if (/watchdog|proxy|falhou|promotor|wedged|rebuild/.test(t)) log('PAGE: ' + t.slice(0, 140)); });
  await page.goto(POC);
  await page.waitForFunction(() => document.getElementById('status').textContent.startsWith('connected'), { timeout: 60000 });
  log('VIEWER connected +' + ((Date.now() - t0) / 1000).toFixed(1) + 's');
  const tWatch = Date.now();
  await page.click('#goWatch');
  log('WATCH clicked');

  // primeiro sinal (vFrames>0) e primeiro frame decodificado (vDecoded>0)
  let sawSignal = false, sawDecode = false, sawPlaying = false;
  const fast = setInterval(async () => {
    try {
      const s = await page.evaluate(() => document.getElementById('stats').textContent);
      const st = await page.evaluate(() => document.getElementById('wStatus').textContent);
      const vf = (s.match(/vFrames=(\d+)/) || [])[1] | 0;
      const vd = (s.match(/vDecoded=(\d+)/) || [])[1] | 0;
      if (!sawSignal && vf > 0) { sawSignal = true; log('FIRST-SIGNAL +' + ((Date.now() - tWatch) / 1000).toFixed(2) + 's (vFrames>0)'); }
      if (!sawDecode && vd > 0) { sawDecode = true; log('FIRST-DECODE +' + ((Date.now() - tWatch) / 1000).toFixed(2) + 's (vDecoded>0)'); }
      if (!sawPlaying && /playing=true/.test(s)) { sawPlaying = true; log('PLAYING +' + ((Date.now() - tWatch) / 1000).toFixed(2) + 's'); clearInterval(fast); }
    } catch (e) {}
  }, 150);

  let prev = null;
  const parse = (s) => {
    const g = (re) => { const m = s.match(re); return m ? parseInt(m[1], 10) : -1; };
    return { v: g(/vFrames=(\d+)/), a: g(/aFrames=(\d+)/), vd: g(/vDecoded=(\d+)/), vb: g(/vBuf=\d+\((\d+)ms\)/), ab: g(/aBuf=\d+\((\d+)ms\)/), under: g(/under=(\d+)/), target: g(/target=(\d+)/) };
  };
  setInterval(async () => {
    try {
      const s = await page.evaluate(() => document.getElementById('stats').textContent);
      const net = (s.match(/net: .*/) || [''])[0];
      const cv = await page.evaluate(() => { const c = document.getElementById('canvas'); return c.width + 'x' + c.height; });
      const st = await page.evaluate(() => document.getElementById('wStatus').textContent);
      const p = parse(s);
      const now = Date.now();
      if (!prev) { prev = { ...p, now }; return; }
      const dt = (now - prev.now) / 1000;
      log('SAMPLE t=' + Math.round((now - tWatch) / 1000) + 's aRate=' + ((p.a - prev.a) / dt).toFixed(1) + '/s vRate=' + ((p.v - prev.v) / dt).toFixed(1) +
        '/s decRate=' + ((p.vd - prev.vd) / dt).toFixed(1) + '/s aBuf=' + p.ab + 'ms vBuf=' + p.vb + 'ms under=' + p.under + ' target=' + p.target + ' canvas=' + cv + ' [' + st + '] ' + net);
      prev = { ...p, now };
      if (now - tWatch > DURATION_S * 1000) { log('DONE'); process.exit(0); }
    } catch (e) {}
  }, 5000);
})().catch(e => { console.error('ERRO: ' + e.message); process.exit(1); });
