"""A returning player's config/rigtune for FootprintGameTest's returning-player run (review-11 PERF-3; docs/v0.5/SPEC.md 1d).

    python tools/gametest/returning_seed.py --out build/returning-seed [--entries 50]

What 0.4 and 0.5 write: the committed "written by" sets (src/test/resources/v040-written and v050-written) composed the way
the E2E harnesses compose them (tools/e2e/written.py), without pending.json (nothing staged), and history.json padded to
--entries entries with copies of its own entries (new ids, earlier times: generated, not recorded). Every absolute path
keeps the ${INSTANCE} token; build.gradle's -PgametestSeedConfig fills in the run folder. Standard library only.
"""
import argparse
import copy
import json
import shutil
import sys
from datetime import datetime, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "tools" / "e2e"))

import fixtures  # noqa: E402
import written  # noqa: E402

ROOTS = [REPO / "src" / "test" / "resources" / "v040-written", REPO / "src" / "test" / "resources" / "v050-written"]
LEFT_OUT = ("pending.json",)


def pad_history(history, entries):
    """history.json with at least `entries` entries: copies of its own, cycling, each with a new id and a time a day
    before the oldest so far (the real entries stay the newest)."""
    real = history.get("entries") or []
    if not real or len(real) >= entries:
        return history
    out = list(real)
    oldest = min(datetime.fromisoformat(e["at"].replace("Z", "+00:00")) for e in real if e.get("at"))
    for n in range(entries - len(real)):
        entry = copy.deepcopy(real[n % len(real)])
        entry["id"] = "returning-%02d-%s" % (n, entry.get("id", ""))
        entry["at"] = (oldest - timedelta(days=n + 1)).strftime("%Y-%m-%dT%H:%M:%SZ")
        out.append(entry)
    return dict(history, entries=sorted(out, key=lambda e: e.get("at") or ""))


def build(out, entries):
    out = Path(out)
    instance = out.parent / (out.name + "-instance")
    for folder in (out, instance):
        if folder.exists():
            shutil.rmtree(folder)
    written.compose(written.resolve_all(ROOTS), instance)
    out.mkdir(parents=True)
    names = []
    for path in sorted((instance / "config" / "rigtune").glob("*.json")):
        if path.name in LEFT_OUT:
            continue
        text = fixtures.template_json(path.read_text(encoding="utf-8"), instance)
        if path.name == written.HISTORY:
            text = json.dumps(pad_history(json.loads(text), entries), indent=2) + "\n"
        (out / path.name).write_text(text, encoding="utf-8", newline="\n")
        names.append(path.name)
    shutil.rmtree(instance)
    return names


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", required=True)
    parser.add_argument("--entries", type=int, default=50)
    args = parser.parse_args(argv)
    print("returning-player seed in %s: %s" % (args.out, ", ".join(build(args.out, args.entries))))


if __name__ == "__main__":
    main()
