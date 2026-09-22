import http from 'node:http';
import { pathToFileURL } from 'node:url';

const ttlMs = 15 * 60 * 1000;
const sessions = new Map();
const attempts = new Map();
const idPattern = /^[A-Za-z0-9_-]{22}$/;
const host = process.env.HOST || '127.0.0.1';
const port = Number(process.env.PORT || 8787);

setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions) if (session.expires <= now) sessions.delete(id);
  for (const [ip, recent] of attempts) {
    const active = recent.filter(time => now - time < 60_000);
    if (active.length) attempts.set(ip, active);
    else attempts.delete(ip);
  }
}, 60_000).unref();

function reply(res, status, value) {
  const body = JSON.stringify(value);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

async function readJson(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > 32_768) throw new Error('Request too large');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function allowed(ip) {
  const now = Date.now();
  const recent = (attempts.get(ip) || []).filter(time => now - time < 60_000);
  if (recent.length >= 60) return false;
  recent.push(now);
  attempts.set(ip, recent);
  return true;
}

export const server = http.createServer(async (req, res) => {
  const now = Date.now();
  for (const [id, session] of sessions) if (session.expires <= now) sessions.delete(id);
  if (!allowed(req.socket.remoteAddress || 'unknown')) return reply(res, 429, { error: 'Too many requests' });
  const url = new URL(req.url || '/', 'http://localhost');
  if (url.pathname === '/health' && req.method === 'GET') return reply(res, 200, { ok: true });
  if (url.pathname === '/v1/sessions' && req.method === 'POST') {
    if (sessions.size >= 1000) return reply(res, 503, { error: 'Relay is full' });
    try {
      const { id } = await readJson(req);
      if (typeof id !== 'string' || !idPattern.test(id)) return reply(res, 400, { error: 'Invalid session' });
      if (sessions.has(id)) return reply(res, 409, { error: 'Session exists' });
      sessions.set(id, { expires: now + ttlMs, package: null });
      return reply(res, 201, { status: 'pending' });
    } catch { return reply(res, 400, { error: 'Invalid request' }); }
  }
  const match = /^\/v1\/sessions\/([A-Za-z0-9_-]{22})$/.exec(url.pathname);
  if (!match) return reply(res, 404, { error: 'Not found' });
  const session = sessions.get(match[1]);
  if (!session) return reply(res, 404, { error: 'Session expired' });
  if (req.method === 'GET') return reply(res, 200,
    session.package ? { status: 'ready', package: session.package } : { status: 'pending' });
  if (req.method === 'DELETE') {
    sessions.delete(match[1]);
    return reply(res, 200, { status: 'deleted' });
  }
  if (req.method === 'PUT') {
    if (session.package) return reply(res, 409, { error: 'Already approved' });
    try {
      const { package: encrypted } = await readJson(req);
      if (typeof encrypted !== 'string' || encrypted.length > 24_000) {
        return reply(res, 400, { error: 'Invalid package' });
      }
      session.package = encrypted;
      return reply(res, 200, { status: 'ready' });
    } catch { return reply(res, 400, { error: 'Invalid request' }); }
  }
  return reply(res, 405, { error: 'Method not allowed' });
});

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  server.listen(port, host, () => process.stdout.write(`NikTV pairing relay listening on ${host}:${port}\n`));
}
