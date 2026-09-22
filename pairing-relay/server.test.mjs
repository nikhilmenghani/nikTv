import test from 'node:test';
import assert from 'node:assert/strict';
import { server } from './server.mjs';

test('a remote pairing request is approved once, fetched, then removed', async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const root = `http://127.0.0.1:${server.address().port}`;
  const id = 'abcdefghijklmnopqrstuv';
  try {
    const create = await fetch(`${root}/v1/sessions`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    assert.equal(create.status, 201);
    assert.deepEqual(await (await fetch(`${root}/v1/sessions/${id}`)).json(), { status: 'pending' });
    const approve = await fetch(`${root}/v1/sessions/${id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ package: 'encrypted-envelope' }),
    });
    assert.equal(approve.status, 200);
    assert.equal((await (await fetch(`${root}/v1/sessions/${id}`)).json()).package, 'encrypted-envelope');
    assert.equal((await fetch(`${root}/v1/sessions/${id}`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ package: 'replacement' }),
    })).status, 409);
    assert.equal((await fetch(`${root}/v1/sessions/${id}`, { method: 'DELETE' })).status, 200);
    assert.equal((await fetch(`${root}/v1/sessions/${id}`)).status, 404);
  } finally {
    await new Promise(resolve => server.close(resolve));
  }
});
