/**
 * Bundles the prototype into a single self-contained HTML file.
 *
 * Published artifacts run under a strict CSP with no external requests, so everything — logic,
 * styles, markup — has to land in one file. `zephyr-core.js` and `app.js` stay separate on disk so
 * the core can be imported by `parity.test.mjs` as a real ES module; this step flattens them into
 * one classic script, rewriting the module boundary into a plain namespace object.
 *
 * Run: node prototype/build.mjs
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const read = f => readFileSync(join(here, f), 'utf8');

const coreSrc = read('zephyr-core.js');
const appSrc = read('app.js');
const css = read('styles.css');

// Collect every exported binding so the namespace object can be rebuilt by hand.
const exportNames = [...coreSrc.matchAll(/^export\s+(?:const|let|var|function)\s+([A-Za-z0-9_$]+)/gm)]
  .map(m => m[1]);

if (exportNames.length < 30) {
  throw new Error(`Only found ${exportNames.length} exports in zephyr-core.js — the export scan is probably broken.`);
}

const core = coreSrc.replace(/^export\s+/gm, '');
const appBody = appSrc.replace(/^import\s+\*\s+as\s+Z\s+from\s+'\.\/zephyr-core\.js';\s*$/m, '');

const bundle = `(function () {
'use strict';

// ===== zephyr-core.js ======================================================
${core}

const Z = { ${exportNames.join(', ')} };

// ===== app.js ==============================================================
${appBody}
})();
`;

const html = `<title>Zephyr</title>
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0B0F14">
<style>
${css}
</style>
<div id="app"></div>
<script>
${bundle}
</script>
`;

mkdirSync(join(here, 'dist'), { recursive: true });
writeFileSync(join(here, 'dist', 'bundle.js'), bundle);
writeFileSync(join(here, 'dist', 'zephyr.html'), html);

console.log(`✓ built dist/zephyr.html — ${(html.length / 1024).toFixed(1)} kB, ${exportNames.length} core exports inlined`);
