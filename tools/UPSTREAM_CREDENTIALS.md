# Upstream credentials

Integrated sources must not contain literal OAuth tokens or API keys. Run
`python tools/check-source-secrets.py` before committing or after importing upstream code.
The check prints only file names, line numbers and credential types.

Runtime keys are read from environment variables listed in
`tools/upstream-credential-names.json`. Environment values take precedence over
the matching entries in `.local-secrets/upstream-credentials.json`, which is
ignored by Git. This fallback supports Android Studio without putting values in
source. Missing values become empty strings; configure the keys before testing
YouTube playback. CI needs the same environment variables from Actions secrets.
These client-side keys are included in built APKs, so this prevents Git exposure,
not extraction from a distributed Android app.

Manual HTTP requests reference variables from the ignored
`http-client.private.env.json`; choose its `local` environment in the IDE HTTP
client. Manual Java authentication tests use `System.getenv`. OAuth request
bodies and refresh tokens are never packaged into the app.

The initial cleanup preserved the exact imported values only in ignored local
configuration. Those historical OAuth credentials must be considered compromised:
do not use them, and revoke/rotate them through the owning Google account.
Replacing Git history cannot revoke credentials or remove copies from clones,
forks, caches or existing GitHub alert locations. Never mark an alert revoked
until revocation is confirmed.
