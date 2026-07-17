// Cria um stream com 10 partições e permissões NATIVAS Streamr (on-chain):
// por omissão fica PRIVADO (só o dono publica/subscreve) e concede grants aos
// endereços passados. Serve para testar o modelo de streams dinâmicos +
// permissões nativas com as identidades persistentes da app e do WebView
// (Network → Identity & stream mostra o address de cada cliente).
//
// Uso:
//   node create-private-stream.js <caminho-.env-com-PK=...> <nome> [addr1,addr2,...] [--public]
//
//   <nome>       → o stream fica <address-do-deployer>/<nome>  (ex.: pombotv-priv)
//   [addrs]      → endereços com grant de publish+subscribe (as identidades dos clientes)
//   [--public]   → em vez de privado: subscribe+publish públicos (modo password-only)
//
// A chave NUNCA vive neste ficheiro — vem de um .env local descartável.
// Precisa de POL no deployer (1-2 transações: createStream + grants).
const { StreamrClient, StreamPermission } = require('@streamr/sdk');
const fs = require('fs');

(async () => {
  const envPath = process.argv[2] || '.env';
  const name = process.argv[3];
  if (!name) { console.error('uso: node create-private-stream.js <.env> <nome> [addr1,addr2] [--public]'); process.exit(1); }
  const addrs = (process.argv[4] && !process.argv[4].startsWith('--')) ? process.argv[4].split(',') : [];
  const isPublic = process.argv.includes('--public');
  const pk = '0x' + fs.readFileSync(envPath, 'utf8').trim().split('=')[1];

  const client = new StreamrClient({ auth: { privateKey: pk } });
  const me = await client.getAddress();
  const streamId = me.toLowerCase() + '/' + name;
  console.log('deployer:', me);
  console.log('stream:', streamId, '(10 partições)');

  const stream = await client.getOrCreateStream({ id: streamId, partitions: 10 });
  console.log('criado/obtido:', stream.id);

  if (isPublic) {
    await stream.grantPermissions({ public: true, permissions: [StreamPermission.SUBSCRIBE, StreamPermission.PUBLISH] });
    console.log('permissões: PÚBLICAS (subscribe+publish) — controlo de acesso = password');
  } else if (addrs.length) {
    await stream.grantPermissions(...addrs.map(a => ({
      userId: a, permissions: [StreamPermission.SUBSCRIBE, StreamPermission.PUBLISH],
    })));
    console.log('permissões: PRIVADO — grants publish+subscribe a:', addrs.join(', '));
  } else {
    console.log('permissões: PRIVADO — só o dono. (Passa endereços para dar grants.)');
  }
  console.log('\nCola este Stream ID nos clientes (Network → Identity & stream):');
  console.log('  ' + stream.id);
  await client.destroy();
})().catch(e => { console.error('ERRO:', e.reason || e.message); process.exit(1); });
