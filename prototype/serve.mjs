/**
 * Zephyr development server.
 *
 * Rebuilds the prototype whenever a source file changes and pushes a reload to every open tab, so
 * a phone propped next to you updates the moment something is edited.
 *
 *   npm start              # http://localhost:5173
 *   PORT=8080 npm start
 *
 * No dependencies — Node's own http, fs and os are enough, and a dev server is not worth an
 * install step or a lockfile in a repo whose real build is Gradle.
 */

import { createServer } from 'node:http';
import { readFileSync, existsSync, watch } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, extname, normalize } from 'node:path';
import { networkInterfaces } from 'node:os';
import { build } from './build.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const dist = join(here, 'dist');
const PORT = Number(process.env.PORT) || 5173;

const WATCHED = ['zephyr-core.js', 'app.js', 'styles.css'];
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

/** Open browser tabs waiting on a reload signal. */
const clients = new Set();

/**
 * Injected into the served page rather than written into dist/, so the built file stays a clean
 * artifact that can be opened straight off disk or handed to anyone.
 */
const LIVE_RELOAD = `
<script>
(function () {
  var source = new EventSource('/__reload');
  source.onmessage = function () { location.reload(); };
  // A dropped connection means the server went away; EventSource retries on its own.
  source.onerror = function () {};
})();
</script>`;

let building = false;
let pending = false;

function rebuild(reason) {
  if (building) { pending = true; return; }
  building = true;
  try {
    const { bytes } = build();
    console.log(`  rebuilt (${reason}) — ${(bytes / 1024).toFixed(1)} kB · ${new Date().toLocaleTimeString()}`);
    for (const res of clients) res.write('data: reload\n\n');
  } catch (error) {
    // Don't take the server down for a syntax error mid-edit; the next save will fix it.
    console.error(`  build failed: ${error.message}`);
  } finally {
    building = false;
    if (pending) { pending = false; rebuild('coalesced'); }
  }
}

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/__reload') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
    });
    res.write('retry: 1000\n\n');
    clients.add(res);
    req.on('close', () => clients.delete(res));
    return;
  }

  const requested = url.pathname === '/' ? '/zephyr.html' : url.pathname;
  // normalize() collapses any ../ before it can escape dist/.
  const file = join(dist, normalize(requested).replace(/^(\.\.[/\\])+/, ''));

  if (!file.startsWith(dist) || !existsSync(file)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
    return;
  }

  const type = MIME[extname(file)] ?? 'application/octet-stream';
  let body = readFileSync(file);
  if (extname(file) === '.html') body = body.toString() + LIVE_RELOAD;

  res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
  res.end(body);
});

/** The address a phone on the same Wi-Fi should use. */
function lanAddress() {
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family === 'IPv4' && !address.internal) return address.address;
    }
  }
  return null;
}

build();
for (const name of WATCHED) {
  watch(join(here, name), { persistent: true }, () => rebuild(name));
}

// 0.0.0.0 so a phone on the same network can reach it — testing a fitness app on a desktop only
// tells you half of what you need to know.
server.listen(PORT, '0.0.0.0', () => {
  const lan = lanAddress();
  console.log('\n  Zephyr — development server\n');
  console.log(`  local    http://localhost:${PORT}`);
  if (lan) console.log(`  network  http://${lan}:${PORT}   ← open this on your phone`);
  console.log(`\n  watching ${WATCHED.join(', ')} — saves rebuild and reload every open tab.`);
  console.log('  ctrl-c to stop\n');
});

process.on('SIGINT', () => {
  for (const res of clients) res.end();
  server.close(() => process.exit(0));
});
