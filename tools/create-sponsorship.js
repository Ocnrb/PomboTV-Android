// Cria e financia uma Sponsorship de TESTE para o stream de vídeo do POC.
// Uso: node create-sponsorship.js <caminho-para-.env-com-PK=...>
// A chave NUNCA vive neste ficheiro — vem de um .env local descartável.
const { _operatorContractUtils } = require('@streamr/sdk');
const { ethers } = require('ethers');
const fs = require('fs');

// argv: <envPath> [stream] [fundDATA]
const STREAM = process.argv[3] || '0x75fc31876b8cd9af59a0e882d87dd8468c2d0e35/video';
const RATE_PER_DAY = 1000n * 10n ** 18n;          // 1000 DATA/dia (atrai operadores)
const EARNINGS_PER_SECOND = RATE_PER_DAY / 86400n; // wei/s
const FUND = BigInt(process.argv[4] || '500') * 10n ** 18n;

(async () => {
  const envPath = process.argv[2] || '.env';
  const pk = '0x' + fs.readFileSync(envPath, 'utf8').trim().split('=')[1];
  const provider = new ethers.JsonRpcProvider('https://polygon-bor-rpc.publicnode.com');
  const deployer = new ethers.Wallet(pk, provider);
  console.log('deployer:', deployer.address);
  console.log('stream:', STREAM);
  console.log('taxa: 1000 DATA/dia · pote: ' + (FUND / 10n ** 18n) + ' DATA');
  const sponsorship = await _operatorContractUtils.deploySponsorshipContract({
    streamId: STREAM,
    deployer,
    earningsPerSecond: EARNINGS_PER_SECOND,
    minOperatorCount: 1,
    minStakeDuration: 0, // teste: operadores podem sair imediatamente
    environmentId: 'polygon',
    sponsorAmount: FUND,
  });
  const addr = await sponsorship.getAddress();
  console.log('SPONSORSHIP:', addr);
  console.log('Hub: https://streamr.network/hub/network/sponsorships/' + addr.toLowerCase());
})().catch(e => { console.error('ERRO:', e.reason || e.message); process.exit(1); });
