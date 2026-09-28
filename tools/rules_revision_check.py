"""A pull request into main that changes the rules raises their revision (review-11 CI-6).

A client breaks a revision tie remote > cache > bundled (RulesLoader), so two different files at one revision mean an
offline client can prefer a cached one without the release's sections. The rules-consistency job compares the PR's
rules/rules-v2.json with main's: different content (ignoring revision and generatedAt) needs a higher revision, the same
content the same revision.

    python3 tools/rules_revision_check.py --base <main's rules-v2.json> --head rules/rules-v2.json
"""

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from update_rules import deep_equal, strip_meta  # noqa: E402


def problems(base, head):
    old = json.loads(Path(base).read_text(encoding="utf-8"))
    new = json.loads(Path(head).read_text(encoding="utf-8"))
    was, now = old.get("revision", 0), new.get("revision", 0)
    if deep_equal(strip_meta(old), strip_meta(new)):
        return [] if now == was else ["the rules are unchanged but the revision moved from {} to {}".format(was, now)]
    return [] if now > was else ["the rules changed but the revision is {} (main has {}); raise it above main's".format(now, was)]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base", required=True, help="main's rules-v2.json")
    parser.add_argument("--head", required=True, help="the pull request's rules-v2.json")
    args = parser.parse_args(argv)
    found = problems(args.base, args.head)
    for problem in found:
        print("::error::" + problem)
    if not found:
        print("rules revision OK")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
