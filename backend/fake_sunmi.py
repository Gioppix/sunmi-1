"""Stands in for the Sunmi app when no device is around.

Connects to the backend, shows each job's text on stdout, and answers like the
app does: a repeated job ID gets its stored result back without printing again.
"""
import argparse
import asyncio
import json
import ssl

from websockets.asyncio.client import connect
from websockets.exceptions import ConnectionClosed

HELLO = {"type": "hello", "protocol": 1, "version": "fake", "model": "fake", "max_pending": 64, "result_ack": True}


async def main(url: str, cafile: str | None) -> None:
    printed: set[str] = set()
    options = {"ssl": ssl.create_default_context(cafile=cafile)} if cafile else {}
    async for ws in connect(url, **options):
        try:
            await ws.send(json.dumps(HELLO))
            print("connected", flush=True)
            async for raw in ws:
                job = json.loads(raw)
                if job.get("type") == "ack":
                    print(f"ack {job['id']}", flush=True)
                    continue
                if job["id"] not in printed:
                    text = "".join(c.get("text", "") for c in job["commands"] if c["op"] == "text")
                    print(f"--- {job['id']}{' (dry run)' if job.get('dry_run') else ''}\n{text}---", flush=True)
                    printed.add(job["id"])
                result = {"id": job["id"], "type": "result", "ok": True, "data": {"confirmation": "printed"}}
                await ws.send(json.dumps(result))
        except ConnectionClosed:
            print("disconnected, reconnecting", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("url", help="wss://<host>:<port>/<SUNMI_SECRET>")
    parser.add_argument("--cafile", help="trust this CA certificate, for testing with a self-signed certificate")
    args = parser.parse_args()
    asyncio.run(main(args.url, args.cafile))
