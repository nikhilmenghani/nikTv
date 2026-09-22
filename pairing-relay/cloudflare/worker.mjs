import { DurableObject } from 'cloudflare:workers';

const ttlMs = 15 * 60 * 1000;
const idPattern = /^[A-Za-z0-9_-]{22}$/;

function reply(status, data) {
  return Response.json(data, {
    status,
    headers: { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' },
  });
}

async function boundedJson(request) {
  if (Number(request.headers.get('content-length') || 0) > 32_768) throw new Error('Request too large');
  const reader = request.body?.getReader();
  if (!reader) throw new Error('Missing request body');
  const chunks = [];
  let size = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.length;
    if (size > 32_768) {
      await reader.cancel();
      throw new Error('Request too large');
    }
    chunks.push(value);
  }
  const data = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { data.set(chunk, offset); offset += chunk.length; }
  return JSON.parse(new TextDecoder().decode(data));
}

export class PairingSession extends DurableObject {
  async fetch(request) {
    const now = Date.now();
    const expires = await this.ctx.storage.get('expires');
    if (expires && expires <= now) await this.ctx.storage.deleteAll();
    const active = expires && expires > now;

    if (request.method === 'POST') {
      if (active) return reply(409, { error: 'Session exists' });
      await this.ctx.storage.put('expires', now + ttlMs);
      await this.ctx.storage.setAlarm(now + ttlMs);
      return reply(201, { status: 'pending' });
    }
    if (!active) return reply(404, { error: 'Session expired' });
    if (request.method === 'GET') {
      const encrypted = await this.ctx.storage.get('package');
      return reply(200, encrypted ? { status: 'ready', package: encrypted } : { status: 'pending' });
    }
    if (request.method === 'PUT') {
      if (await this.ctx.storage.get('package')) return reply(409, { error: 'Already approved' });
      try {
        const encrypted = (await boundedJson(request)).package;
        if (typeof encrypted !== 'string' || encrypted.length > 24_000) {
          return reply(400, { error: 'Invalid package' });
        }
        await this.ctx.storage.put('package', encrypted);
        return reply(200, { status: 'ready' });
      } catch { return reply(400, { error: 'Invalid request' }); }
    }
    if (request.method === 'DELETE') {
      await this.ctx.storage.deleteAll();
      return reply(200, { status: 'deleted' });
    }
    return reply(405, { error: 'Method not allowed' });
  }

  async alarm() {
    await this.ctx.storage.deleteAll();
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/health' && request.method === 'GET') return reply(200, { ok: true });
    let id;
    if (url.pathname === '/v1/sessions' && request.method === 'POST') {
      try { id = (await boundedJson(request)).id; }
      catch { return reply(400, { error: 'Invalid request' }); }
      if (typeof id !== 'string' || !idPattern.test(id)) return reply(400, { error: 'Invalid session' });
    } else {
      const match = /^\/v1\/sessions\/([A-Za-z0-9_-]{22})$/.exec(url.pathname);
      if (!match) return reply(404, { error: 'Not found' });
      id = match[1];
    }
    const objectId = env.SESSIONS.idFromName(id);
    const stub = env.SESSIONS.get(objectId);
    const forwarded = new Request(`https://relay.internal/v1/sessions/${id}`, {
      method: request.method,
      headers: request.headers,
      body: request.method === 'PUT' ? request.body : undefined,
    });
    return stub.fetch(forwarded);
  },
};
