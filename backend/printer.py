import json
import logging
from datetime import datetime, timezone

from websockets.asyncio.server import ServerConnection
from websockets.exceptions import ConnectionClosed

log = logging.getLogger("printer")


class Printer:
    """The WebSocket endpoint the Sunmi app connects to.

    Jobs stay in `pending` until the app returns a final result, and all pending
    jobs are resent whenever the app reconnects. Resending is safe because the app
    answers a repeated job ID from its journal instead of printing it again.
    """

    def __init__(self, dry_run: bool = False):
        self.dry_run = dry_run
        self.conn: ServerConnection | None = None
        self.pending: dict[str, dict] = {}
        self.state_since = datetime.now(timezone.utc)
        self.printed = 0
        self.failed = 0
        self.skipped = 0
        self.last_result: tuple[datetime, str] | None = None

    async def submit(self, job_id: str, commands: list[dict]) -> None:
        job = {"id": job_id, "commands": commands}
        if self.dry_run:
            job["dry_run"] = True
        self.pending[job_id] = job
        if self.conn is None:
            log.info("no printer connected, %s will be sent when one connects", job_id)
            return
        await self._send(self.conn, job)

    def skip_pending(self) -> None:
        """Drops jobs not yet printed. Jobs the app has already accepted still print."""
        log.info("skipping %d pending jobs", len(self.pending))
        self.skipped += len(self.pending)
        self.pending.clear()

    async def _send(self, conn: ServerConnection, job: dict) -> None:
        try:
            await conn.send(json.dumps(job))
        except ConnectionClosed:
            pass

    async def handler(self, conn: ServerConnection) -> None:
        try:
            hello = json.loads(await conn.recv())
        except ConnectionClosed:
            return
        log.info("printer connected: %s", hello)
        self.conn = conn
        self.state_since = datetime.now(timezone.utc)
        for job in list(self.pending.values()):
            await self._send(conn, job)
        try:
            async for raw in conn:
                msg = json.loads(raw)
                if msg.get("type") != "result":
                    continue
                job_id = msg.get("id")
                if job_id is not None:
                    await conn.send(json.dumps({"type": "ack", "id": job_id}))
                if isinstance(job_id, str):
                    self.pending.pop(job_id, None)
                if msg.get("ok"):
                    self.printed += 1
                    outcome = msg.get("data", {}).get("confirmation")
                    log.info("%s done: %s", job_id, outcome)
                else:
                    self.failed += 1
                    outcome = msg.get("error", {}).get("code")
                    log.warning("%s failed: %s", job_id, msg.get("error"))
                self.last_result = (datetime.now(timezone.utc), f"{job_id}: {outcome}")
        except ConnectionClosed:
            pass
        finally:
            if self.conn is conn:
                self.conn = None
                self.state_since = datetime.now(timezone.utc)
            log.info("printer disconnected")
