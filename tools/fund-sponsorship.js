// Reforça (top-up) uma Sponsorship existente com DATA — sem criar contrato novo.
// Uso: node fund-sponsorship.js <caminho-.env-com-PK=...> [sponsorship] [quantiaDATA]
// Default: a sponsorship ATIVA do /video (0x6950…) e 500 DATA.
// A 1000 DATA/dia, 500 DATA ≈ 12h de vida. Estado atual no Hub:
// https://streamr.network/hub/network/sponsorships/<endereço>
const { ethers } = require('ethers');
const fs = require('fs');

const SPONSORSHIP = process.argv[3] || '0x69506ad5cb05321ee3aa1adaa459dd88b74c8134';
const AMOUNT = BigInt(process.argv[4] || '500') * 10n ** 18n;
const DATA_TOKEN = '0x3a9A81d576d83FF21f26f325066054540720fC34'; // DATA (Polygon)

(async () => {
  const envPath = process.argv[2] || '.env';
  const pk = '0x' + fs.readFileSync(envPath, 'utf8').trim().split('=')[1];
  const provider = new ethers.JsonRpcProvider('https://polygon-bor-rpc.publicnode.com');
  const wallet = new ethers.Wallet(pk, provider);
  const token = new ethers.Contract(DATA_TOKEN, [
    'function transferAndCall(address to, uint256 value, bytes data) returns (bool)',
    'function balanceOf(address) view returns (uint256)',
  ], wallet);
  const bal = await token.balanceOf(wallet.address);
  console.log('carteira:', wallet.address, '· saldo:', ethers.formatEther(bal), 'DATA');
  console.log('sponsorship:', SPONSORSHIP, '· reforço:', ethers.formatEther(AMOUNT), 'DATA');
  if (bal < AMOUNT) { console.error('ERRO: saldo insuficiente'); process.exit(1); }
  // ERC-677: o transferAndCall entrega os DATA e o contrato credita o pote
  const tx = await token.transferAndCall(SPONSORSHIP, AMOUNT, '0x');
  console.log('tx:', tx.hash);
  await tx.wait();
  console.log('CONFIRMADO — pote reforçado.');
})().catch(e => { console.error('ERRO:', e.reason || e.message); process.exit(1); });
