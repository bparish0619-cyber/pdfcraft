import {cp, mkdir, readFile, writeFile, stat, rm, readdir} from 'node:fs/promises';
import {resolve, join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
const root = resolve(fileURLToPath(new URL('../..', import.meta.url)));
const out = join(root, 'out');
const dest = join(root, 'android/app/src/main/assets/web');
// A direct next build deliberately avoids the web-only Cloudflare chunking postbuild.
const required = ['index.html', 'en/index.html', 'qpdf.wasm', 'qpdf.js',
  'pymupdf-wasm/pyodide.js', 'pymupdf-wasm/pyodide.asm.wasm',
  'pymupdf-wasm/pyodide-lock.json', 'pymupdf-wasm/python_stdlib.zip',
  'libreoffice-wasm/soffice.wasm.bin', 'libreoffice-wasm/soffice.data.bin',
  'libreoffice-wasm/soffice.wasm.bin.gz', 'libreoffice-wasm/soffice.data.bin.gz',
  'pdfjs-viewer/pdf.js', 'workers/pdf.worker.min.mjs'];
for (const asset of required) {
  if (!(await stat(join(out, asset))).isFile()) throw new Error(`Missing Android asset: ${asset}`);
}
await rm(dest, {recursive:true, force:true});
await mkdir(dest, {recursive:true});
await cp(out, dest, {recursive:true});
// APKs already contain immutable assets; avoid an old web cache surviving an APK update.
const sw = join(dest, 'sw.js');
await writeFile(sw, "self.addEventListener('install',()=>self.skipWaiting());self.addEventListener('activate',e=>e.waitUntil(caches.keys().then(k=>Promise.all(k.map(n=>caches.delete(n)))).then(()=>clients.claim())));\n");
const manifest = {sourceCommit: execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),
  appVersion: JSON.parse(await readFile(join(root,'package.json'),'utf8')).version, requiredAssets:required};
await writeFile(join(dest,'android-build.json'),JSON.stringify(manifest,null,2));
console.log(`Android assets ready: ${dest}`);
