// Teste de VIDEO-CHAMADA (1:1): dois clientes no mesmo processo, cada um
// publica "vídeo"+"áudio" sintéticos e subscreve os do outro — pub+sub
// SIMULTÂNEOS por cliente, como numa chamada real.
//   mode=part    → UM stream (/video), partições: A pub #2/#3, B pub #4/#5
//                  (evita #0/#1 usados pelo live) — uma só sponsorship
//   mode=streams → DOIS streams: A pub /video#2/#3, B pub /audio#2/#3
// Payload: [f64 wallMs][enchimento] — latência real por mensagem (mesmo relógio).
// Vídeo ~2 Mbps eq. (20KB × 12/s) + áudio (1KB × 8/s), 45s por modo.
const { StreamrClient } = require('@streamr/sdk');
const OWNER = '0x75fc31876b8cd9af59a0e882d87dd8468c2d0e35';
const MODE = process.argv[2] || 'part';
const DUR_S = parseInt(process.argv[3] || '45', 10);
const PROXIES = (process.argv[4] || 'proxy') === 'proxy'; // proxy|mesh

const defs = MODE === 'part'
  ? { aV: { streamId: OWNER + '/video', partition: 2 }, aA: { streamId: OWNER + '/video', partition: 3 },
      bV: { streamId: OWNER + '/video', partition: 4 }, bA: { streamId: OWNER + '/video', partition: 5 } }
  : { aV: { streamId: OWNER + '/video', partition: 2 }, aA: { streamId: OWNER + '/video', partition: 3 },
      bV: { streamId: OWNER + '/audio', partition: 2 }, bA: { streamId: OWNER + '/audio', partition: 3 } };

function mkPayload(bytes) {
  const u8 = new Uint8Array(bytes);
  new DataView(u8.buffer).setFloat64(0, Date.now());
  return u8;
}
function latOf(content) {
  let u8; try { u8 = content instanceof Uint8Array ? content : new Uint8Array(content); } catch (e) { return null; }
  if (u8.length < 8) return null;
  return Date.now() - new DataView(u8.buffer, u8.byteOffset).getFloat64(0);
}
function stats(arr) {
  if (!arr.length) return 'sem dados';
  const s = [...arr].sort((x, y) => x - y);
  const p = q => s[Math.min(s.length - 1, Math.floor(q * s.length))];
  return `n=${s.length} p50=${p(.5).toFixed(0)}ms p95=${p(.95).toFixed(0)}ms max=${s[s.length - 1].toFixed(0)}ms`;
}

async function mkClient(label) {
  const c = new StreamrClient({
    orderMessages: false, gapFill: false,
    network: { controlLayer: { webrtcAllowPrivateAddresses: true } },
  });
  await c.connect();
  console.log(label + ' connected');
  return c;
}

(async () => {
  const t0 = Date.now();
  console.log(`=== CALL TEST mode=${MODE} dur=${DUR_S}s proxies=${PROXIES} ===`);
  const A = await mkClient('A'), B = await mkClient('B');

  // proxies (se pedidos): pub nos defs próprios, sub nos do outro
  if (PROXIES) {
    const { ProxyDirection } = require('@streamr/sdk');
    async function trySetProxies(client, def, dir, label) {
      try {
        const nodes = await client.findProxyNodes(def, 2);
        await client.setProxies(def, nodes, dir, nodes.length);
        console.log('proxy ' + label + ' ok (' + nodes.length + ')');
      } catch (e) { console.log('proxy ' + label + ' falhou → malha (' + e.message.slice(0, 60) + ')'); }
    }
    await Promise.all([
      trySetProxies(A, defs.aV, ProxyDirection.PUBLISH, 'A-pubV'),
      trySetProxies(A, defs.aA, ProxyDirection.PUBLISH, 'A-pubA'),
      trySetProxies(B, defs.bV, ProxyDirection.PUBLISH, 'B-pubV'),
      trySetProxies(B, defs.bA, ProxyDirection.PUBLISH, 'B-pubA'),
      trySetProxies(A, defs.bV, ProxyDirection.SUBSCRIBE, 'A-subV'),
      trySetProxies(A, defs.bA, ProxyDirection.SUBSCRIBE, 'A-subA'),
      trySetProxies(B, defs.aV, ProxyDirection.SUBSCRIBE, 'B-subV'),
      trySetProxies(B, defs.aA, ProxyDirection.SUBSCRIBE, 'B-subA'),
    ]);
  }

  // subscrições cruzadas
  const lats = { AfromB_v: [], AfromB_a: [], BfromA_v: [], BfromA_a: [] };
  let firstAB = 0, firstBA = 0;
  await Promise.all([
    A.subscribe(defs.bV, c => { const l = latOf(c); if (l !== null) { lats.AfromB_v.push(l); if (!firstBA) firstBA = Date.now(); } }),
    A.subscribe(defs.bA, c => { const l = latOf(c); if (l !== null) lats.AfromB_a.push(l); }),
    B.subscribe(defs.aV, c => { const l = latOf(c); if (l !== null) { lats.BfromA_v.push(l); if (!firstAB) firstAB = Date.now(); } }),
    B.subscribe(defs.aA, c => { const l = latOf(c); if (l !== null) lats.BfromA_a.push(l); }),
  ]);
  console.log('subs prontos +' + ((Date.now() - t0) / 1000).toFixed(1) + 's — a publicar ' + DUR_S + 's…');

  // publicação bidirecional: vídeo 20KB×12/s, áudio 1KB×8/s
  const tPub = Date.now();
  let pubErr = { A: 0, B: 0 }, pubOk = { A: 0, B: 0 };
  const timers = [];
  function pump(client, def, bytes, hz, who) {
    timers.push(setInterval(() => {
      client.publish(def, mkPayload(bytes), { contentType: 'binary' })
        .then(() => pubOk[who]++).catch(() => pubErr[who]++);
    }, 1000 / hz));
  }
  pump(A, defs.aV, 20 * 1024, 12, 'A'); pump(A, defs.aA, 1024, 8, 'A');
  pump(B, defs.bV, 20 * 1024, 12, 'B'); pump(B, defs.bA, 1024, 8, 'B');

  await new Promise(r => setTimeout(r, DUR_S * 1000));
  timers.forEach(clearInterval);
  await new Promise(r => setTimeout(r, 1500)); // últimos em voo

  const durReal = (Date.now() - tPub) / 1000;
  console.log('--- RESULTADOS mode=' + MODE + ' ---');
  console.log('pub A ok=' + pubOk.A + ' err=' + pubErr.A + ' | pub B ok=' + pubOk.B + ' err=' + pubErr.B);
  console.log('A→B vídeo: rx=' + lats.BfromA_v.length + ' (' + (lats.BfromA_v.length / durReal).toFixed(1) + '/s) lat ' + stats(lats.BfromA_v));
  console.log('A→B áudio: rx=' + lats.BfromA_a.length + ' (' + (lats.BfromA_a.length / durReal).toFixed(1) + '/s) lat ' + stats(lats.BfromA_a));
  console.log('B→A vídeo: rx=' + lats.AfromB_v.length + ' (' + (lats.AfromB_v.length / durReal).toFixed(1) + '/s) lat ' + stats(lats.AfromB_v));
  console.log('B→A áudio: rx=' + lats.AfromB_a.length + ' (' + (lats.AfromB_a.length / durReal).toFixed(1) + '/s) lat ' + stats(lats.AfromB_a));
  const expV = Math.round(durReal * 12), expA = Math.round(durReal * 8);
  console.log('entrega vídeo: A→B ' + (100 * lats.BfromA_v.length / expV).toFixed(1) + '% · B→A ' + (100 * lats.AfromB_v.length / expV).toFixed(1) + '% (esperados ~' + expV + ')');
  await A.destroy(); await B.destroy();
  process.exit(0);
})().catch(e => { console.error('ERRO: ' + (e.stack || e.message)); process.exit(1); });
