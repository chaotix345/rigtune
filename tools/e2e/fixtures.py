"""Captured 0.1.0 files as test fixtures: the scratch instance's absolute path is replaced with ${INSTANCE}, which a test
substitutes with its own folder (JSON-escaped in JSON files). Otherwise the 0.2 helper would refuse the ops as outside
its folders (ApplyExecutor.containmentProblem) and the game would drop them (PendingActions.relocated)."""

import json
import re
import shutil
from pathlib import Path

TOKEN = "${INSTANCE}"


def template(text, instance_root):
    """Every spelling of the instance root (as is, with '/', JSON-escaped; any case) becomes the token. For evidence."""
    root = str(Path(instance_root))
    spellings = {root, root.replace("\\", "/"), json.dumps(root)[1:-1]}
    for spelling in sorted(spellings, key=len, reverse=True):
        text = re.sub(re.escape(spelling), lambda m: TOKEN, text, flags=re.IGNORECASE)
    return text


def portable_paths(text):
    """Forward slashes after the token, so a test on Linux (CI) can substitute its own folder too. Java on Windows accepts
    them as well. In plain text a path ends at whitespace (none of these paths has a space after the instance root)."""
    return re.sub(r"\$\{INSTANCE\}\S*", lambda m: m.group(0).replace("\\", "/"), text)


def template_json(text, instance_root):
    """JSON: only string values that start with the instance root change (the token, then '/' separators), so no escape
    sequence is touched. Written back with Gson's pretty-printing layout (two-space indent)."""
    root = str(Path(instance_root))
    prefixes = sorted({root, root.replace("\\", "/")}, key=len, reverse=True)

    def rewrite(value):
        if isinstance(value, dict):
            return {key: rewrite(item) for key, item in value.items()}
        if isinstance(value, list):
            return [rewrite(item) for item in value]
        if isinstance(value, str):
            for prefix in prefixes:
                if value.lower().startswith(prefix.lower()):
                    return TOKEN + value[len(prefix):].replace("\\", "/")
        return value

    out = json.dumps(rewrite(json.loads(text)), indent=2, ensure_ascii=False)
    return out + "\n" if text.endswith("\n") else out


def capture(files, instance_root, dest):
    """files: fixture name -> source path. Missing sources are skipped. Returns the written paths."""
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    written = []
    for name, source in files.items():
        source = Path(source)
        if not source.is_file():
            continue
        text = source.read_text(encoding="utf-8")
        text = template_json(text, instance_root) if name.endswith(".json") else portable_paths(template(text, instance_root))
        target = dest / name
        target.write_bytes(text.encode("utf-8"))
        written.append(target)
    return written


def copy_evidence(source, dest):
    if Path(source).is_file():
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        return True
    return False
