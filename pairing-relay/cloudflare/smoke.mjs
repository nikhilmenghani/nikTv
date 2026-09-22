const root = process.argv[2];
if (!root) throw new Error('Pass the relay base URL');
const id = crypto.randomUUID().replaceAll('-', '').slice(0, 22);
const endpoint = `${root.replace(/\/$/, '')}/v1/sessions/${id}`;
const create = await fetch(`${root.replace(/\/$/, '')}/v1/sessions`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ id }),
});
if (create.status !== 201) throw new Error(`Create: ${create.status} ${await create.text()}`);
const pending = await (await fetch(endpoint)).json();
if (pending.status !== 'pending') throw new Error(`Pending: ${JSON.stringify(pending)}`);
const approve = await fetch(endpoint, {
  method: 'PUT', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ package: 'encrypted-smoke-test' }),
});
if (approve.status !== 200) throw new Error(`Approve: ${approve.status} ${await approve.text()}`);
const ready = await (await fetch(endpoint)).json();
if (ready.status !== 'ready' || ready.package !== 'encrypted-smoke-test') {
  throw new Error(`Ready: ${JSON.stringify(ready)}`);
}
const removed = await fetch(endpoint, { method: 'DELETE' });
if (removed.status !== 200 || (await fetch(endpoint)).status !== 404) {
  throw new Error('Session was not deleted');
}
process.stdout.write('Pairing relay create, approve, fetch, and delete passed.\n');
