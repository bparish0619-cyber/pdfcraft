import {build} from 'esbuild';
import {mkdir,writeFile} from 'node:fs/promises';
const dest = 'android/app/src/debug/assets/web';
await mkdir(dest,{recursive:true});
await build({entryPoints:['android/tests/engine-smoke.js'],bundle:true,format:'esm',platform:'browser',target:'firefox128',
  outfile:dest+'/android-smoke.js',tsconfig:'tsconfig.json',define:{'process.env.NEXT_PUBLIC_BASE_PATH':'""'},
  external:['canvas','fs','path','crypto'],logLevel:'info'});
await writeFile(dest+'/android-smoke.html','<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>RUNNING</title></head><body><h1>PDFCraft Android engine tests</h1><script type="module" src="/android-smoke.js"></script></body></html>');
