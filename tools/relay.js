// Nó relay no PC: junta-se aos overlays de vídeo+áudio (via WebRTC ao browser
// do broadcaster) e aceita ligações PROXY por WebSocket — o emulador liga-se
// por TCP determinístico (10.0.2.2), contornando o WebRTC frágil do NAT dele.
const { StreamrClient } = require('@streamr/sdk');

const OWNER = '0x75fc31876b8cd9af59a0e882d87dd8468c2d0e35';
// Chave fixa APENAS para o nó ter um nodeId estável em testes locais.
// É a conta de teste #1 pública e conhecida do Hardhat/Anvil (sem fundos
// reais associados) — não usar em produção nem para nada com valor.
const DEV_KEY = '0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d';

(async () => {
  const client = new StreamrClient({
    auth: { privateKey: DEV_KEY },
    orderMessages: false,
    gapFill: false,
    network: {
      node: { acceptProxyConnections: true },
      controlLayer: {
        websocketPortRange: { min: 32200, max: 32200 },
        websocketServerEnableTls: false,
      },
    },
  });
  let v = 0, a = 0;
  await client.subscribe({ streamId: OWNER + '/video', partition: 0 }, () => { v++; });
  await client.subscribe({ streamId: OWNER + '/audio', partition: 0 }, () => { a++; });
  const node = await client.getNode();
  const pd = await node.getPeerDescriptor();
  console.log('PEER_DESCRIPTOR=' + JSON.stringify(pd));
  setInterval(() => console.log(`relay rx: video=${v} audio=${a}`), 5000);
})().catch(e => { console.error('ERRO: ' + (e.stack || e.message)); process.exit(1); });
