// Minimal bun giriş noktası: stdin'den HTML oku → htmlToUdf → stdout'a .udf (zip) yaz.
// Yalnız html2udf yolunu import eder (auth/sign YOK) → pkcs11 gibi native bağımlılıklar
// bundle'a girmez, `bun build --compile` temiz çalışır.
// Build sırasında udf-cli reposunun KÖKÜNE kopyalanır (./src/... çözülür).
import { htmlToUdf } from './src/converters/html-to-udf.js';

async function readStdin(): Promise<string> {
  const chunks: Uint8Array[] = [];
  for await (const c of process.stdin) chunks.push(c as Uint8Array);
  return Buffer.concat(chunks).toString('utf-8');
}

const html = await readStdin();
const udf = await htmlToUdf(html); // Buffer (.udf zip: content.xml)
process.stdout.write(udf);
