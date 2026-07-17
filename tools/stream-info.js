// Consulta metadados dos streams: nº de partições e existência do /audio.
const { StreamrClient } = require('@streamr/sdk');
const OWNER = '0x75fc31876b8cd9af59a0e882d87dd8468c2d0e35';
(async () => {
  const client = new StreamrClient();
  for (const id of [OWNER + '/video', OWNER + '/audio']) {
    try {
      const s = await client.getStream(id);
      const md = await s.getMetadata();
      console.log(id, '→ partitions=' + (md.partitions || 1), JSON.stringify(md));
    } catch (e) { console.log(id, '→ ERRO:', e.message.slice(0, 120)); }
  }
  await client.destroy();
  process.exit(0);
})();
