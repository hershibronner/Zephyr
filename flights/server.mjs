/**
 * Skylark — the flight search server.
 *
 *   node flights/server.mjs          # http://localhost:5174
 *   PORT=8080 node flights/server.mjs
 *
 * No dependencies: Node's own http, fs and fetch are enough, and a search UI is not worth an
 * install step in a repo whose real build is Gradle.
 *
 * Configuration comes from the environment, or from a .env file next to this one:
 *
 *   ANTHROPIC_API_KEY=        # AI search. Without it, a rule-based parser handles common phrasing.
 *   AMADEUS_CLIENT_ID=        # Real fares. Without it, a labelled synthetic provider is used.
 *   AMADEUS_CLIENT_SECRET=
 *   AMADEUS_ENV=test          # or "production"
 */

import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, extname, normalize } from 'node:path';
import { networkInterfaces } from 'node:os';

import { createSearcher, AmadeusClient } from './lib/search.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const publicDir = join(here, 'public');

const PORT = Number(process.env.PORT) || 5174;
const PORT_ATTEMPTS = 10;
/** A search request is a sentence, not a payload. Anything larger is a mistake or an attack. */
const MAX_BODY_BYTES = 8 * 1024;

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
};

/** Load a .env file if one is sitting next to the server, so keys don't have to be exported. */
function loadEnvFile() {
  for (const candidate of [join(here, '.env'), join(here, '..', '.env')]) {
    if (!existsSync(candidate)) continue;
    for (const line of readFileSync(candidate, 'utf8').split('\n')) {
      const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/.exec(line);
      if (!match) continue;
      const value = match[2].replace(/^["']|["']$/g, '');
      // Never let a file silently override something the operator exported deliberately.
      if (process.env[match[1]] === undefined) process.env[match[1]] = value;
    }
    return candidate;
  }
  return null;
}

const envFile = loadEnvFile();

const amadeus = new AmadeusClient({
  clientId: process.env.AMADEUS_CLIENT_ID,
  clientSecret: process.env.AMADEUS_CLIENT_SECRET,
  env: process.env.AMADEUS_ENV ?? 'test',
});

const searcher = createSearcher({
  amadeus,
  anthropicApiKey: process.env.ANTHROPIC_API_KEY,
});

function sendJson(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    let tooLarge = false;
    const chunks = [];

    req.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        // Stop buffering, but keep draining so the response below actually reaches the client —
        // destroying the socket here would leave curl and fetch with a dead connection instead of
        // an explanation.
        tooLarge = true;
        chunks.length = 0;
        return;
      }
      chunks.push(chunk);
    });

    req.on('end', () => {
      if (tooLarge) {
        const error = new Error(`Search text must be under ${MAX_BODY_BYTES} bytes.`);
        error.status = 413;
        reject(error);
        return;
      }
      resolve(Buffer.concat(chunks).toString('utf8'));
    });
    req.on('error', reject);
  });
}

async function handleSearch(req, res) {
  let payload;
  try {
    payload = JSON.parse(await readBody(req) || '{}');
  } catch (error) {
    sendJson(res, error.status ?? 400, { error: error.status ? error.message : `Could not read the request: ${error.message}` });
    return;
  }

  const text = typeof payload.query === 'string' ? payload.query.trim() : '';
  if (!text) {
    sendJson(res, 400, { error: 'Tell me where you want to go.' });
    return;
  }

  try {
    const result = await searcher.search(text);
    console.log(`  search "${text.slice(0, 60)}" -> ${result.results.length} results in ${result.elapsedMs ?? 0}ms`);
    sendJson(res, 200, result);
  } catch (error) {
    console.error(`  search failed: ${error.stack}`);
    sendJson(res, 502, { error: error.message });
  }
}

async function handleLocations(url, res) {
  const keyword = url.searchParams.get('q')?.trim();
  if (!keyword) {
    sendJson(res, 400, { error: 'Pass ?q=' });
    return;
  }
  if (!amadeus.configured) {
    sendJson(res, 503, { error: 'Location lookup needs Amadeus credentials.' });
    return;
  }
  try {
    sendJson(res, 200, { locations: await amadeus.findLocations(keyword) });
  } catch (error) {
    sendJson(res, 502, { error: error.message });
  }
}

function serveStatic(pathname, res) {
  const requested = pathname === '/' ? '/index.html' : pathname;
  // normalize() collapses any ../ before it can escape publicDir.
  const file = join(publicDir, normalize(requested).replace(/^(\.\.[/\\])+/, ''));

  if (!file.startsWith(publicDir) || !existsSync(file)) {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('Not found');
    return;
  }

  res.writeHead(200, {
    'Content-Type': MIME[extname(file)] ?? 'application/octet-stream',
    'Cache-Control': 'no-store',
  });
  res.end(readFileSync(file));
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/api/search' && req.method === 'POST') return handleSearch(req, res);
  if (url.pathname === '/api/locations' && req.method === 'GET') return handleLocations(url, res);

  if (url.pathname === '/api/health') {
    return sendJson(res, 200, {
      ok: true,
      ai: Boolean(process.env.ANTHROPIC_API_KEY),
      fares: amadeus.configured ? `amadeus (${process.env.AMADEUS_ENV ?? 'test'})` : 'mock',
    });
  }

  if (req.method !== 'GET') {
    res.writeHead(405, { 'Content-Type': 'text/plain', Allow: 'GET' });
    res.end('Method not allowed');
    return;
  }

  serveStatic(url.pathname, res);
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

function start(port, attemptsLeft) {
  server.listen(port, '0.0.0.0');

  server.once('error', (error) => {
    if (error.code === 'EADDRINUSE' && attemptsLeft > 0) {
      console.log(`  port ${port} is busy, trying ${port + 1}…`);
      server.removeAllListeners('listening');
      start(port + 1, attemptsLeft - 1);
      return;
    }
    console.error(`\n  Could not start the server: ${error.message}\n`);
    process.exit(1);
  });

  server.once('listening', () => {
    const lan = lanAddress();
    console.log('\n  Skylark — flight search\n');
    console.log(`  local    http://localhost:${port}`);
    if (lan) console.log(`  network  http://${lan}:${port}`);
    if (envFile) console.log(`\n  config   ${envFile}`);
    console.log(`  search   ${process.env.ANTHROPIC_API_KEY ? 'Claude (claude-opus-5)' : 'rule-based — set ANTHROPIC_API_KEY for AI search'}`);
    console.log(`  fares    ${amadeus.configured ? `Amadeus ${process.env.AMADEUS_ENV ?? 'test'}` : 'synthetic demo data — set AMADEUS_CLIENT_ID/SECRET for real fares'}`);
    console.log('\n  Ctrl-C to stop.\n');
  });
}

start(PORT, PORT_ATTEMPTS);

process.on('SIGINT', () => server.close(() => process.exit(0)));
