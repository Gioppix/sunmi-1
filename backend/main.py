import argparse
import asyncio
import hmac
import logging
import os
import re
import ssl
from datetime import datetime, timezone
from http import HTTPStatus

from websockets.asyncio.server import serve

import merged_prs
import page
from printer import Printer

log = logging.getLogger("main")

LOOPBACK_HOSTS = {"127.0.0.1", "::1", "localhost"}


def routes(base: str, printer: Printer, repos: list[str]):
    """Serves the printer's WebSocket, the status page and its skip action, all under the secret path."""
    started = datetime.now(timezone.utc)

    def process_request(connection, request):
        path = request.path.split("?", 1)[0].encode()
        if hmac.compare_digest(path, base.encode()):
            if request.headers.get("Upgrade", "").lower() == "websocket":
                return None
            return page.html_response(connection, page.render(printer, repos, started, base))
        if hmac.compare_digest(path, f"{base}/skip".encode()):
            printer.skip_pending()
            return page.redirect(connection, base)
        log.warning("rejected request from %s: wrong URL secret", connection.remote_address)
        return connection.respond(HTTPStatus.FORBIDDEN, "Forbidden\n")

    return process_request


async def main(args: argparse.Namespace, secret: str, tls: ssl.SSLContext | None) -> None:
    printer = Printer(dry_run=args.dry_run)
    process_request = routes("/" + secret, printer, args.repos)
    async with serve(printer.handler, args.host, args.port, ssl=tls, process_request=process_request):
        log.info("listening on %s://%s:%d/<SUNMI_SECRET>", "wss" if tls else "ws", args.host, args.port)
        if not args.repos:
            log.info("no --repo or GITHUB_REPO set, so nothing gets printed")
            await asyncio.Future()
        else:
            log.info("printing PRs merged into %s after %s", ", ".join(args.repos), args.since.isoformat())
            await asyncio.gather(
                *(merged_prs.watch(repo, printer, args.since, args.interval) for repo in args.repos)
            )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="WebSocket backend for the Sunmi printer app. "
        "The app must connect to wss://<host>:<port>/<SUNMI_SECRET>."
    )
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", 8765)))
    parser.add_argument("--cert", help="PEM certificate chain, for example Let's Encrypt's fullchain.pem")
    parser.add_argument("--key", help="PEM private key, for example Let's Encrypt's privkey.pem")
    parser.add_argument(
        "--tls-proxy",
        action="store_true",
        help="a proxy in front of this server terminates TLS, as on Railway, so serve plain ws on any address",
    )
    parser.add_argument(
        "--repo",
        action="append",
        metavar="OWNER/NAME",
        help="GitHub repo whose merged PRs get printed; repeat for several "
        "(default: $GITHUB_REPO, separated by commas or spaces)",
    )
    parser.add_argument(
        "--since",
        type=lambda s: datetime.fromisoformat(s).astimezone(timezone.utc),
        default=datetime.now(timezone.utc),
        help="print PRs merged after this ISO date or datetime (default: now)",
    )
    parser.add_argument("--interval", type=float, default=60, help="seconds between GitHub checks")
    parser.add_argument("--dry-run", action="store_true", help="the printer validates jobs without using paper")
    args = parser.parse_args()
    args.repos = args.repo or [r for r in re.split(r"[,\s]+", os.environ.get("GITHUB_REPO", "")) if r]

    secret = os.environ.get("SUNMI_SECRET")
    if not secret:
        parser.error("set SUNMI_SECRET to the secret path segment of the app's URL")
    tls = None
    if args.cert:
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.load_cert_chain(args.cert, args.key)
    elif args.host not in LOOPBACK_HOSTS and not args.tls_proxy:
        parser.error("--cert is required unless --host is a loopback address or --tls-proxy is set")

    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    # The library logs every status page view as a rejected WebSocket handshake.
    logging.getLogger("websockets.server").setLevel(logging.WARNING)
    try:
        asyncio.run(main(args, secret, tls))
    except KeyboardInterrupt:
        pass
