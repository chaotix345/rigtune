"""Captured 0.1.0 files as test fixtures: the scratch instance's absolute path is replaced with ${INSTANCE}, which a test
substitutes with its own folder (JSON-escaped in JSON files). Otherwise the 0.2 helper would refuse the ops as outside
its folders (ApplyExecutor.containmentProblem) and the game would drop them (PendingActions.relocated)."""

import json
import re
import shutil
from pathlib import Path

TOKEN = "${INSTANCE}"


def template(text, instance_root):
    root = str(Path(instance_root))
    spellings = {root, root.replace("\\", "/"), json.dumps(root)[1:-1]}
    for spelling in sorted(spellings, key=len, reverse=True):
        text = re.sub(re.escape(spelling), lambda m: TOKEN, text, flags=re.IGNORECASE)
    return text


def portable_paths(text):
    """Forward slashes after the token, so a test on Linux (CI) can substitute its own folder too. Java on Windows accepts
    them as well. A path ends at a quote or whitespace (none of these paths has a space after the instance root)."""
    return re.sub(r"\$\{INSTANCE\}[^\"\s]*", lambda m: m.group(0).replace("\\\\", "/").replace("\\", "/"), text)


def capture(files, instance_root, dest):
    """files: fixture name -> source path. Missing sources are skipped. Returns the written paths."""
    dest = Path(dest)
    dest.mkdir(parents=True, exist_ok=True)
    written = []
    for name, source in files.items():
        source = Path(source)
        if not source.is_file():
            continue
        target = dest / name
        target.write_bytes(portable_paths(template(source.read_text(encoding="utf-8"), instance_root)).encode("utf-8"))
        written.append(target)
    return written


def copy_evidence(source, dest):
    if Path(source).is_file():
        Path(dest).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, dest)
        return True
    return False
