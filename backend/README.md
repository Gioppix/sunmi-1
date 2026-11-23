# Backend

The WebSocket endpoint the Sunmi app connects to. With `--repo`, it prints a receipt for every PR merged into that GitHub repo. Pass `--repo` more than once, or set `GITHUB_REPO` to a comma- or space-separated list, to watch several repos at once.

```sh
export SUNMI_SECRET=$(python3 -c "import secrets; print(secrets.token_urlsafe(32))")
export GITHUB_TOKEN=github_pat_...
uv run python main.py --repo owner/name --repo owner/other \
  --cert /etc/letsencrypt/live/example.com/fullchain.pem \
  --key /etc/letsencrypt/live/example.com/privkey.pem
```

Set the app's URL to `wss://example.com:8765/<SUNMI_SECRET>`. Connections with any other path get HTTP 403. The app redacts the URL path in its debug logs, and the backend never logs the secret.

The certificate must come from a CA that the device's Android 7.1 trusts, because the app uses the system trust store. A self-signed certificate will not work. The certificate is loaded at startup, so restart the backend after it is renewed.

To terminate TLS in a reverse proxy such as Caddy or nginx instead, run with `--host 127.0.0.1` and no `--cert`, and forward the proxy to that port. The backend refuses plain `ws://` on any non-loopback address.

`GITHUB_TOKEN` is ideally a fine-grained personal access token limited to the watched repos, with the read-only "Pull requests" permission. A fine-grained token for an organization's repo needs an organization admin's approval. A classic token with the `repo` scope works without approval, but it can read and write every private repo its owner can reach. A public repo also works without a token, but GitHub then allows only 60 requests per hour per IP address, and checking every 60 seconds uses all of them.

- `--since 2026-11-01` also prints PRs merged after that date. By default only merges after startup are printed. It looks at the 100 most recently updated closed PRs.
- `--interval 60` sets the seconds between GitHub checks.
- `--dry-run` makes the device validate jobs without using paper.

Open `https://<host>/<SUNMI_SECRET>` in a browser for a status page: printer connection, jobs not printed yet, printed/failed/skipped counts, and the last GitHub check. After a long disconnect, its skip button drops the jobs that piled up, so the printer doesn't print them all when it reconnects. Jobs the app has already received still print. The counts reset when the backend restarts.

Printed PRs are remembered in memory only. Job IDs are `merged-pr:<repo>#<number>`, and the app never prints the same ID twice. So restarting with an earlier `--since` reprints nothing it already printed, as long as the job is still in the app's journal.

Without a device, run the backend with `--host 127.0.0.1` and connect `uv run python fake_sunmi.py ws://127.0.0.1:8765/$SUNMI_SECRET`. It shows each receipt's text on stdout. For `wss://` with a self-signed certificate, pass it with `--cafile`.
