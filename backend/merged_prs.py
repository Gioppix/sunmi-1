import asyncio
import json
import logging
import os
import urllib.request
from datetime import datetime, timezone

from printer import Printer

log = logging.getLogger("merged_prs")

last_check: datetime | None = None
last_error: tuple[datetime, str] | None = None


def merged_at(pr: dict) -> datetime:
    return datetime.fromisoformat(pr["merged_at"])


def github_get(path: str):
    request = urllib.request.Request(
        f"https://api.github.com/{path}",
        headers={"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"},
    )
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


async def fetch_merged(repo: str) -> list[dict]:
    """Returns merged PRs among the 100 most recently updated closed PRs, oldest merge first."""
    prs = await asyncio.to_thread(
        github_get, f"repos/{repo}/pulls?state=closed&sort=updated&direction=desc&per_page=100"
    )
    return sorted((pr for pr in prs if pr["merged_at"]), key=merged_at)


def receipt(repo: str, pr: dict) -> list[dict]:
    when = merged_at(pr).astimezone().strftime("%Y-%m-%d %H:%M")
    return [
        {"op": "text", "text": "PR MERGED\n", "align": "center", "bold": True, "size": 32},
        {"op": "text", "text": f"{repo} #{pr['number']}\n"},
        {"op": "text", "text": f"{pr['title']}\n", "bold": True},
        {"op": "text", "text": f"by {pr['user']['login']} into {pr['base']['ref']}\n"},
        {"op": "text", "text": f"{when}\n"},
        {"op": "feed", "lines": 3},
    ]


async def watch(repo: str, printer: Printer, since: datetime, interval: float) -> None:
    """Prints every PR merged into `repo` after `since`, checking every `interval` seconds.

    Printed PR numbers are kept in memory only, so after a restart pass an
    earlier `since` to catch up on merges that happened while it was down.
    """
    global last_check, last_error
    printed: set[int] = set()
    reachable = False
    while True:
        try:
            prs = await fetch_merged(repo)
        except Exception as exc:
            log.warning("fetching merged PRs of %s failed: %s", repo, exc)
            last_error = (datetime.now(timezone.utc), str(exc))
        else:
            last_check = datetime.now(timezone.utc)
            if not reachable:
                log.info("reading %s works: %d merged PRs among the latest closed", repo, len(prs))
                reachable = True
            for pr in prs:
                if pr["number"] in printed or merged_at(pr) <= since:
                    continue
                log.info("printing %s #%d: %s", repo, pr["number"], pr["title"])
                await printer.submit(f"merged-pr:{repo}#{pr['number']}", receipt(repo, pr))
                printed.add(pr["number"])
        await asyncio.sleep(interval)
