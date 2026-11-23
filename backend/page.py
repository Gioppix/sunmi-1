from datetime import datetime, timezone
from html import escape
from http import HTTPStatus

import merged_prs
from printer import Printer

STYLE = """
:root { color-scheme: light dark; font-family: system-ui, sans-serif; }
body { max-width: 34rem; margin: 2rem auto; padding: 0 16px; line-height: 1.5; }
table { border-collapse: collapse; width: 100%; margin-bottom: 1.5rem; }
td { padding: .35rem 0; border-bottom: 1px solid #8884; }
td:last-child { text-align: right; }
button { font: inherit; padding: .4rem .9rem; cursor: pointer; }
small, li { color: #888; }
"""


def duration(since: datetime) -> str:
    seconds = int((datetime.now(timezone.utc) - since).total_seconds())
    for unit, size in (("d", 86400), ("h", 3600), ("m", 60)):
        if seconds >= size:
            return f"{seconds // size}{unit}"
    return f"{seconds}s"


def event(value: tuple[datetime, str] | None) -> str:
    return "none" if value is None else f"{escape(value[1])}, {duration(value[0])} ago"


def render(printer: Printer, repos: list[str], started: datetime, base: str) -> str:
    state = "connected" if printer.conn else "disconnected"
    rows = [
        ("Printer", f"{state} for {duration(printer.state_since)}"),
        ("Not printed yet", len(printer.pending)),
        ("Printed", printer.printed),
        ("Failed", printer.failed),
        ("Skipped", printer.skipped),
        ("Last result", event(printer.last_result)),
        ("GitHub repos", escape(", ".join(repos) or "none")),
        ("Last GitHub check", f"{duration(merged_prs.last_check)} ago" if merged_prs.last_check else "never"),
        ("Last GitHub error", event(merged_prs.last_error)),
        ("Running for", duration(started)),
    ]
    table = "".join(f"<tr><td>{name}</td><td>{value}</td></tr>" for name, value in rows)
    ids = list(printer.pending)
    jobs = "".join(f"<li>{escape(job_id)}</li>" for job_id in ids[:20])
    if len(ids) > 20:
        jobs += f"<li>and {len(ids) - 20} more</li>"
    skip = (
        f'<form action="{base}/skip" onsubmit="return confirm(\'Skip {len(ids)} jobs?\')">'
        f'<button {"disabled" if not ids else ""}>Skip {len(ids)} jobs not printed yet</button></form>'
        "<small>Jobs the printer has already received still print.</small>"
        f"<ul>{jobs}</ul>"
    )
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        '<meta http-equiv="refresh" content="30"><link rel="icon" href="data:,">'
        f"<title>Sunmi backend</title><style>{STYLE}</style></head>"
        f"<body><h1>Sunmi backend</h1><table>{table}</table>{skip}</body></html>"
    )


def html_response(connection, body: str):
    response = connection.respond(HTTPStatus.OK, body)
    del response.headers["Content-Type"]
    response.headers["Content-Type"] = "text/html; charset=utf-8"
    response.headers["Cache-Control"] = "no-store"
    return response


def redirect(connection, location: str):
    response = connection.respond(HTTPStatus.SEE_OTHER, "")
    response.headers["Location"] = location
    return response
