"""Checks one game-test leg's Modrinth traffic (docs/v0.5/SPEC.md AC1b.2, docs/v0.5/design/ws-ci.md).

    python3 tools/ci/check_fake_modrinth.py <requests.jsonl> <the run's logs folder>

- The fake Modrinth logged the start-up lookups: POST /v2/version_files, POST /v2/version_files/update,
  GET /v2/projects and GET /v2/project/<id>/version, each answered 200.
- Every request was answered 2xx, or 404 by a lookup of one project or one file hash (unknown ones are a real answer).
  A 400 (the fake's answer when its own routing throws), a 405 or any other 404 fails like a 5xx.
- The game's log (latest.log and any rotated *.log.gz) has no "Modrinth lookups failed; using offline data" line except
  the ones a test causes by switching Modrinth off ("Modrinth is off in RigTune's settings").

Exit 0 when all hold; 1 with one "::error::" line per problem otherwise. Standard library only.
"""

import gzip
import json
import re
import sys
from pathlib import Path

LOOKUP_FAILED = "Modrinth lookups failed; using offline data"
SWITCHED_OFF = "Modrinth is off in RigTune's settings"
STARTUP = {
    "POST /v2/version_files": re.compile(r"^/v2/version_files$"),
    "POST /v2/version_files/update": re.compile(r"^/v2/version_files/update$"),
    "GET /v2/projects": re.compile(r"^/v2/projects$"),
    "GET /v2/project/<id>/version": re.compile(r"^/v2/project/[^/]+/version$"),
}
NOT_FOUND_IS_AN_ANSWER = re.compile(r"^/v2/(project/[^/]+(/version)?|version_file/[^/]+)$")


def read_requests(path):
    requests = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            requests.append(json.loads(line))
    return requests


def log_lines(logs_dir):
    folder = Path(logs_dir)
    files = sorted(folder.glob("*.log.gz")) + [folder / "latest.log"]
    for file in files:
        if not file.is_file():
            continue
        if file.suffix == ".gz":
            with gzip.open(file, "rt", encoding="utf-8", errors="replace") as handle:
                yield from handle
        else:
            with file.open(encoding="utf-8", errors="replace") as handle:
                yield from handle


def problems(requests, lines):
    found = []
    if not requests:
        return ["the fake Modrinth logged no request"]
    for label, pattern in STARTUP.items():
        method = label.split(" ", 1)[0]
        if not any(r.get("method") == method and pattern.match(r.get("path", "")) and r.get("status") == 200 for r in requests):
            found.append(f"no {label} answered 200 by the fake Modrinth")
    errors = [r for r in requests if not answered(r)]
    for r in errors[:5]:
        found.append(f"the fake Modrinth answered {r.get('status')} to {r.get('method')} {r.get('path')}")
    for line in lines:
        if LOOKUP_FAILED in line and SWITCHED_OFF not in line:
            found.append("a lookup fell back to offline data: " + line.strip()[:300])
    return found


def answered(request):
    status = request.get("status")
    if not isinstance(status, int):
        return False
    return 200 <= status < 300 or (status == 404 and NOT_FOUND_IS_AN_ANSWER.match(request.get("path", "")) is not None)


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    requests = read_requests(argv[1])
    found = problems(requests, log_lines(argv[2]))
    for problem in found:
        print("::error::" + problem)
    if not found:
        print(f"{len(requests)} requests to the fake Modrinth, the start-up lookups answered, every answer 2xx or a lookup's 404, no offline fallback")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
