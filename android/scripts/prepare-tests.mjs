import {build} from 'esbuild';
import {mkdir,writeFile} from 'node:fs/promises';
const dest = 'android/app/src/debug/assets/web';
await mkdir(dest,{recursive:true});
await build({entryPoints:['android/tests/engine-smoke.js'],bundle:true,format:'esm',platform:'browser',target:'firefox128',
  outfile:dest+'/android-smoke.js',tsconfig:'tsconfig.json',define:{'process.env.NEXT_PUBLIC_BASE_PATH':'""'},
  external:['canvas','fs','path','crypto'],logLevel:'info'});
// The instrumented test can only observe document.title. Without these guards a
// module that fails to load, a syntax error, or an unhandled rejection leaves the
// title at RUNNING, which the test can only report as a bare 12 minute timeout.
const guard = `
(function(){
  var alive = 0;
  window.__smokeAlive = function(){ alive = Date.now(); };
  function fail(message){
    if (String(document.title).indexOf('PASS:') === 0) return;
    document.title = 'FAIL: ' + message;
  }
  window.addEventListener('error', function(event){
    fail('uncaught error: ' + ((event && event.message) || 'unknown'));
  });
  window.addEventListener('unhandledrejection', function(event){
    var reason = event && event.reason;
    fail('unhandled rejection: ' + ((reason && reason.stack) || reason));
  });
  setTimeout(function(){ if (!alive) fail('harness module never executed'); }, 120000);
})();
`;
await writeFile(dest+'/android-smoke.html','<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>RUNNING</title><script>'+guard+'</script></head><body><h1>PDFCraft Android engine tests</h1><script type="module" src="/android-smoke.js" onerror="document.title=\'FAIL: harness module failed to load\'"></script></body></html>');
