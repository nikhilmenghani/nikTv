# NikTV pairing relay

This service is a temporary mailbox for **encrypted** NikTV pairing packages. It has no GitHub token, database, or NikTV credentials. A new device creates a random session ID and a separate 128-bit transfer secret. The relay receives only the ID and encrypted package; the transfer secret stays in the pairing link or QR code shared with the approving device. Sessions expire after 15 minutes, are single-approval, and are deleted after pickup. An in-memory relay restart cancels outstanding sessions.

## Free HTTPS address with Cloudflare Workers

Cloudflare's free `workers.dev` address is the simplest way to host this relay without buying a domain. The Worker implementation uses a SQLite-backed Durable Object so sessions survive Worker restarts and expire after 15 minutes. It keeps the same API as the Node relay.

1. Create a free Cloudflare account and choose your `workers.dev` subdomain in the Workers & Pages dashboard.
2. Open PowerShell in `pairing-relay/cloudflare` and run `npx wrangler@latest login`. Complete Cloudflare's browser sign-in.
3. Run `npx wrangler@latest deploy`. The command prints a URL such as `https://niktv-pairing-relay.<your-subdomain>.workers.dev`.
4. Open `<that URL>/health` and check for `{"ok":true}`. Enter the base URL (without `/health`) in **NikTV → Settings → Profiles → Pair devices → Remote → Pairing relay URL** on the receiving device.

The Android app rejects plain HTTP and does not embed a relay address in the APK. Run `npx wrangler@latest dev --local` and `node smoke.mjs http://127.0.0.1:8787` to test the Worker locally. The app itself requires HTTPS for remote pairing.

## Alternative self-hosted Node service

Run with Node 24 using `node server.mjs`, or build and run the included Dockerfile. By default the Node process binds to `127.0.0.1:8787`; set `HOST=0.0.0.0` behind a reverse proxy. Publish it through **HTTPS** and enter its base URL in NikTV on the receiving device.

The API is `POST /v1/sessions` with `{ "id": "22-character-random-id" }`, `PUT /v1/sessions/:id` with `{ "package": "encrypted-envelope" }`, `GET /v1/sessions/:id`, and `DELETE /v1/sessions/:id`. A health check is at `/health`. Request sizes, session count, and requests per source IP are bounded. Place normal TLS, access logging, and resource monitoring at the reverse proxy. Avoid logging request bodies.

Run `node --test server.test.mjs` before deployment. Pairing remains unavailable until a reachable HTTPS address is configured on the receiving device. The encrypted-file flow in the app needs no relay.
