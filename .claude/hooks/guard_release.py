import json
import re
import shlex
import subprocess
import sys
from pathlib import Path

MARKER = "rigtune-hq"
OPERATORS = {";", "&&", "||", "|", "&", "(", ")", "|&"}
WRAPPERS = {"command", "exec", "env", "time", "nohup", "sudo", "builtin"}
SHELLS = {"bash", "sh", "zsh", "pwsh", "powershell", "cmd"}
GH_RELEASE_WRITES = {"create", "delete", "edit", "upload", "delete-asset", "new", "rm"}
GIT_OPTS_WITH_VALUE = {"-C", "-c", "--git-dir", "--work-tree", "--namespace", "--exec-path", "--config-env"}
TAG_REF = re.compile(r"^(refs/tags/.*|v\d.*)$")


def base(word):
    name = re.split(r"[\\/]", word)[-1].lower()
    return name[:-4] if name.endswith(".exe") else name


def tokens(text):
    try:
        lex = shlex.shlex(text, posix=True, punctuation_chars=";&|()")
        lex.whitespace_split = True
        lex.commenters = ""
        return list(lex)
    except ValueError:
        return re.findall(r"[;&|()]+|[^\s;&|()]+", text)


def commands(text, depth=0):
    for line in re.split(r"[\r\n]+", text):
        argv = []
        for tok in tokens(line) + [";"]:
            if tok in OPERATORS or set(tok) <= set(";&|()"):
                if argv:
                    yield from expand(argv, depth)
                argv = []
            else:
                argv.append(tok.strip("`"))


def expand(argv, depth):
    while argv and (re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", argv[0]) or base(argv[0]) in WRAPPERS):
        argv = argv[1:]
    if not argv:
        return
    if base(argv[0]) in SHELLS and depth < 3:
        for i, a in enumerate(argv[1:-1], 1):
            if a.lower() in ("-c", "/c", "-command", "/k"):
                yield from commands(" ".join(argv[i + 1:]), depth + 1)
                return
    yield argv


def git_push_tags(args):
    i = 0
    while i < len(args) and args[i].startswith("-"):
        i += 2 if args[i] in GIT_OPTS_WITH_VALUE else 1
    if i >= len(args) or args[i] != "push":
        return False
    rest = args[i + 1:]
    if any(a in ("--tags", "--follow-tags", "--mirror") or a.startswith("--follow-tags") for a in rest):
        return True
    positional = [a for a in rest if not a.startswith("-")]
    for j, ref in enumerate(positional[1:], 1):
        if ref == "tag":
            return True
        for part in ref.lstrip("+").split(":"):
            if TAG_REF.match(part):
                return True
    return False


def gh_api_release(args):
    endpoint = next((a for a in args if not a.startswith("-") and "/" in a), "")
    method = None
    has_fields = False
    for i, a in enumerate(args):
        if a in ("-X", "--method") and i + 1 < len(args):
            method = args[i + 1].upper()
        elif a.startswith("--method="):
            method = a.split("=", 1)[1].upper()
        elif a in ("-f", "-F", "--field", "--raw-field", "--input") or a.startswith(("--field=", "--raw-field=", "--input=")):
            has_fields = True
    method = method or ("POST" if has_fields else "GET")
    if "/dispatches" in endpoint and "release" in endpoint:
        return True
    if method != "GET" and re.search(r"/(releases|git/refs|git/tags)(/|$)", endpoint):
        return True
    return False


def run_workflow_name(run_id):
    r = subprocess.run(["gh", "run", "view", run_id, "--json", "workflowName", "-q", ".workflowName"],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise RuntimeError(f"gh run view {run_id} failed: {r.stderr.strip() or r.returncode}")
    return r.stdout.strip()


def release_action(argv):
    name, args = base(argv[0]), argv[1:]
    if name == "git" and git_push_tags(args):
        return "pushing a tag (a v* tag push publishes a release)"
    if name != "gh" or not args:
        return None
    sub = args[0]
    if sub == "release" and len(args) > 1 and args[1] in GH_RELEASE_WRITES:
        return f"`gh release {args[1]}`"
    if sub == "workflow" and len(args) > 1 and args[1] == "run" and any(
            base(a) in ("release", "release.yml") for a in args[2:] if not a.startswith("-")):
        return "dispatching release.yml"
    if sub == "api" and gh_api_release(args[1:]):
        return "a GitHub API call that creates or changes a release, tag or release dispatch"
    if sub == "run" and len(args) > 1 and args[1] == "rerun":
        run_id = next((a for a in args[2:] if a.isdigit()), None)
        if run_id is None or run_workflow_name(run_id).lower() == "release":
            return "re-running a release run"
    return None


def is_hq(cwd):
    r = subprocess.run(["git", "-C", str(cwd), "rev-parse", "--absolute-git-dir"], capture_output=True, text=True, timeout=20)
    if r.returncode != 0:
        return False
    return (Path(r.stdout.strip()) / MARKER).is_file()


def problems(payload):
    command = (payload.get("tool_input") or {}).get("command") or ""
    found = [a for a in (release_action(argv) for argv in commands(command)) if a]
    if found and not is_hq(Path(payload.get("cwd") or ".")):
        yield (f"{found[0]} is a public publish, and only RigTune HQ releases, with Charlie's OK each time "
               "(CLAUDE.md, docs/hq.md \"Releasing\"). Open a PR and tell HQ instead.")


def deny(reason):
    json.dump({"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "deny",
                                      "permissionDecisionReason": "Blocked by .claude/hooks/guard_release.py: " + reason}}, sys.stdout)


def main():
    try:
        payload = json.load(sys.stdin)
        found = list(problems(payload))
    except Exception as e:
        deny(f"the release check could not run ({e}), so the call is blocked.")
        return 0
    if found:
        deny(" ".join(found))
    return 0


if __name__ == "__main__":
    sys.exit(main())
