#!/usr/bin/env python3
"""Adds a Minecraft version to the Stonecutter build in one step: checks the Mojang manifest, Fabric meta, the
Fabric API and Mod Menu mavens and Modrinth, inserts the version into settings.gradle and writes
versions/<mc>/gradle.properties, then prints the steps only a human can do. See tools/MC_VERSIONS.md.

    python tools/add_mc_version.py <mc> [--prerelease-ok] [--dry-run]
"""

import re
from typing import NamedTuple

ROOT_MARKER = "settings.gradle"


class Refusal(Exception):
    """A check failed: nothing is written, the message says why."""


class McId(NamedTuple):
    base: str
    kind: str
    number: int


MC_ID = re.compile(r"(\d+)\.(\d+)(?:\.(\d+))?(?:-(snapshot|pre|rc)-(\d+))?")
KIND_RANK = {"snapshot": 0, "pre": 1, "rc": 2, "release": 3}


def parse_mc_id(mc):
    m = MC_ID.fullmatch(mc)
    if not m:
        raise Refusal(f"{mc!r} isn't a Minecraft version id this tool understands (26.4, 26.3.1, 26.4-snapshot-1, "
                      "26.4-pre-1, 26.4-rc-1)")
    base = mc.split("-", 1)[0]
    return McId(base, m.group(4) or "release", int(m.group(5) or 0))


def version_key(mc):
    m = MC_ID.fullmatch(mc)
    if not m:
        raise Refusal(f"{mc!r} isn't a Minecraft version id this tool understands")
    return (int(m.group(1)), int(m.group(2)), int(m.group(3) or 0), KIND_RANK[m.group(4) or "release"],
            int(m.group(5) or 0))


def minecraft_dependency(mc):
    parsed = parse_mc_id(mc)
    return f"~{mc}" if parsed.kind == "release" else f"~{parsed.base}-"


VERSIONS_CALL = re.compile(r"(?m)^([ \t]*versions[ \t]+)((?:(['\"])[^'\"\n]+\3[ \t]*,[ \t]*)*(['\"])[^'\"\n]+\4)[ \t]*$")
QUOTED = re.compile(r"(['\"])([^'\"\n]+)\1")


def _versions_call(text):
    matches = list(VERSIONS_CALL.finditer(text))
    if len(matches) != 1:
        raise Refusal(f"settings.gradle: expected exactly one `versions '...', '...'` line, found {len(matches)}")
    return matches[0]


def read_settings_versions(text):
    return [q[1] for q in QUOTED.findall(_versions_call(text).group(2))]


def insert_settings_version(text, mc):
    call = _versions_call(text)
    quote = QUOTED.search(call.group(2)).group(1)
    versions = read_settings_versions(text)
    if mc in versions:
        raise Refusal(f"{mc} is already in settings.gradle")
    key = version_key(mc)
    at = next((i for i, v in enumerate(versions) if MC_ID.fullmatch(v) and version_key(v) > key), len(versions))
    versions.insert(at, mc)
    listed = ", ".join(f"{quote}{v}{quote}" for v in versions)
    return text[:call.start(2)] + listed + text[call.end(2):]


KNOWN_KEYS = ("minecraft_dependency", "fabric_api_version", "modmenu_version", "sodium_version", "iris_version")


def render_properties(values, template_text):
    """Renders versions/<mc>/gradle.properties. Returns (text, keys copied unchanged from the template)."""
    lines = [
        f"minecraft_dependency={values['minecraft_dependency']}",
        f"fabric_api_version={values['fabric_api_version']}",
        f"modmenu_version={values['modmenu_version']}",
    ]
    if values.get("sodium_version"):
        lines.append(f"sodium_version={values['sodium_version']}")
    lines += [values["iris_comment"], f"iris_version={values['iris_version']}"]
    copied, comments = [], []
    for raw in template_text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            if line:
                comments.append(raw)
            continue
        key = re.split(r"\s*[=:]\s*|\s+", line, maxsplit=1)[0]
        if key not in KNOWN_KEYS:
            lines += comments + [raw]
            copied.append(key)
        comments = []
    lines += comments
    return "\n".join(lines) + "\n", copied
