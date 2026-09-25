#!/usr/bin/env python3
"""Adds a Minecraft version to the Stonecutter build in one step: checks the Mojang manifest, Fabric meta, the
Fabric API and Mod Menu mavens and Modrinth, inserts the version into settings.gradle and writes
versions/<mc>/gradle.properties, then prints the steps only a human can do. See tools/MC_VERSIONS.md.

    python tools/add_mc_version.py <mc> [--prerelease-ok] [--dry-run]
"""

import argparse
import difflib
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import NamedTuple

import gametest_matrix

ROOT = Path(__file__).resolve().parent.parent
USER_AGENT = "chaotix345/rigtune-add-mc-version/1.0 (github.com/chaotix345/rigtune)"
JAVA_MAJOR = 25
MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
FABRIC_META = "https://meta.fabricmc.net/v2"
MODRINTH_API = "https://api.modrinth.com/v2"
FABRIC_API_METADATA_URL = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml"
MODMENU_METADATA_URL = "https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml"
FABRIC_FEED_URL = "https://fabricmc.net/feed.xml"
MODRINTH_MIN_INTERVAL = 1.0
RETRYABLE = {429, 500, 502, 503, 504}
MAX_ATTEMPTS = 3


class Refusal(Exception):
    """A check failed: nothing is written, the message says why."""


class NetworkError(Exception):
    pass


class McId(NamedTuple):
    base: str
    kind: str
    number: int


MC_ID = re.compile(r"(\d+)\.(\d+)(?:\.(\d+))?(?:-(snapshot|pre|rc)-(\d+))?")


def parse_mc_id(mc):
    m = MC_ID.fullmatch(mc)
    if not m:
        raise Refusal(f"{mc!r} isn't a Minecraft version id this tool understands (26.4, 26.3.1, 26.4-snapshot-1, "
                      "26.4-pre-1, 26.4-rc-1)")
    base = mc.split("-", 1)[0]
    return McId(base, m.group(4) or "release", int(m.group(5) or 0))


def version_key(mc):
    """The node order CI uses (tools/gametest_matrix.py), for the ids this tool accepts."""
    parse_mc_id(mc)
    return gametest_matrix._sort_key(mc)


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


def read_properties(text):
    props = {}
    for raw in text.splitlines():
        line = raw.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def fabric_loader_url(mc):
    return f"{FABRIC_META}/versions/loader/{urllib.parse.quote(mc, safe='')}"


def modrinth_versions_url(project, mc):
    query = urllib.parse.urlencode({"game_versions": json.dumps([mc]), "loaders": json.dumps(["fabric"])})
    return f"{MODRINTH_API}/project/{project}/version?{query}"


def default_opener(request):
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read(), dict(response.headers.items())
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers.items()) if e.headers else {}


class Http:
    """GETs with the repo's User-Agent, a few retries, and at most one Modrinth request per second."""

    def __init__(self, opener=default_opener, sleeper=time.sleep, clock=time.monotonic):
        self.opener = opener
        self.sleeper = sleeper
        self.clock = clock
        self._last_modrinth = None

    def _throttle(self, url):
        if not url.startswith(MODRINTH_API):
            return
        if self._last_modrinth is not None:
            wait = MODRINTH_MIN_INTERVAL - (self.clock() - self._last_modrinth)
            if wait > 0:
                self.sleeper(wait)
        self._last_modrinth = self.clock()

    def get(self, url, *, allow_missing=False):
        for attempt in range(1, MAX_ATTEMPTS + 1):
            self._throttle(url)
            request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
            try:
                status, body, headers = self.opener(request)
            except OSError as e:
                if attempt == MAX_ATTEMPTS:
                    raise NetworkError(f"{url}: {e}") from e
                self.sleeper(2.0 ** attempt)
                continue
            if status == 200:
                return body
            if allow_missing and status in (400, 404):
                return None
            if status in RETRYABLE and attempt < MAX_ATTEMPTS:
                retry_after = {k.lower(): v for k, v in headers.items()}.get("retry-after", "").strip()
                self.sleeper(min(float(retry_after), 30.0) if retry_after.isdigit() else 2.0 ** attempt)
                continue
            raise NetworkError(f"HTTP {status} from {url}")
        raise NetworkError(f"no response from {url}")

    def json(self, url, *, allow_missing=False):
        body = self.get(url, allow_missing=allow_missing)
        return None if body is None else json.loads(body.decode("utf-8"))

    def text(self, url):
        return self.get(url).decode("utf-8")


VERSION_RANK = {"release": 0, "beta": 1, "alpha": 2}


def rank_modrinth(versions):
    """Releases first, then betas, then alphas; newest first within each."""
    newest_first = sorted(versions, key=lambda v: v.get("date_published", ""), reverse=True)
    return sorted(newest_first, key=lambda v: VERSION_RANK.get(v.get("version_type"), 3))


def maven_versions(xml_text):
    return {e.text.strip() for e in ET.fromstring(xml_text).iter("version") if e.text}


def blog_lines(http, base):
    """The Loom/Gradle sentences of Fabric's "Fabric for Minecraft <base>" post, or a note saying there's none."""
    title = f"Fabric for Minecraft {base}"
    atom = {"a": "http://www.w3.org/2005/Atom"}
    try:
        feed = ET.fromstring(http.text(FABRIC_FEED_URL))
        link = None
        for entry in feed.findall("a:entry", atom):
            if (entry.findtext("a:title", default="", namespaces=atom) or "").strip() == title:
                alternate = entry.find("a:link[@rel='alternate']", atom)
                link = alternate.get("href") if alternate is not None else None
                break
        if not link:
            return [f'No "{title}" post on the Fabric blog yet: check https://fabricmc.net/blog/ for the Loom and '
                    "Gradle versions it needs before building."]
        page = http.text(link)
    except (NetworkError, ET.ParseError, UnicodeDecodeError) as e:
        return [f"Couldn't read the Fabric blog ({e}): check https://fabricmc.net/blog/ yourself."]
    page = re.sub(r"(?is)<(script|style)\b.*?</\1>", "", page)
    lines = []
    for block in re.split(r"(?i)</(?:p|li|h\d|pre)>", page):
        text = " ".join(html.unescape(re.sub(r"<[^>]+>", " ", block)).split())
        if re.search(r"\b(Loom|Gradle)\b", text):
            lines.append(text[:300])
    return [f"{title} ({link}):"] + [f"  {line}" for line in lines or ["(no Loom/Gradle line found; read the post)"]]


class Plan(NamedTuple):
    mc: str
    prev: str
    settings_before: str
    settings_after: str
    properties: str
    facts: list
    notes: list


def check_not_a_node(root, mc):
    settings = (root / "settings.gradle").read_text(encoding="utf-8")
    if mc in read_settings_versions(settings):
        raise Refusal(f"{mc} is already a node in settings.gradle")
    if (root / "versions" / mc).exists():
        raise Refusal(f"versions/{mc} already exists (not in settings.gradle): remove it first")
    return settings


def previous_node(nodes, mc):
    known = [n for n in nodes if MC_ID.fullmatch(n)]
    if not known:
        raise Refusal("settings.gradle has no existing version to copy from")
    lower = [n for n in known if version_key(n) < version_key(mc)]
    return max(lower, key=version_key) if lower else min(known, key=version_key)


def pick_on_maven(project, mc, http, metadata_url, host, label):
    on_maven = maven_versions(http.text(metadata_url))
    candidates = rank_modrinth(http.json(modrinth_versions_url(project, mc)))
    chosen = next((v for v in candidates if v["version_number"] in on_maven), None)
    if chosen is None:
        where = f" ({len(candidates)} on Modrinth, none of them on {host})" if candidates else ""
        raise Refusal(f"no {label} build for {mc} yet{where}")
    return chosen


def build_plan(root, mc, http, prerelease_ok):
    settings = check_not_a_node(root, mc)
    parsed = parse_mc_id(mc)
    nodes = read_settings_versions(settings)
    prev = previous_node(nodes, mc)
    template_path = root / "versions" / prev / "gradle.properties"
    if not template_path.is_file():
        raise Refusal(f"versions/{prev}/gradle.properties is missing")
    template = template_path.read_text(encoding="utf-8")
    facts, notes = [], []

    manifest = http.json(MANIFEST_URL)
    entry = next((v for v in manifest.get("versions", []) if v.get("id") == mc), None)
    if entry is None:
        raise Refusal(f"{mc} isn't in the Mojang version manifest")
    if entry.get("type") != "release" and not prerelease_ok:
        raise Refusal(f"{mc} is a {entry.get('type')}, not a release: pass --prerelease-ok to add it anyway "
                      "(pre-release nodes aren't shipped)")
    facts.append(f"Mojang: {mc} is a {entry.get('type')}, released {str(entry.get('releaseTime', '?'))[:10]}")
    java = (http.json(entry["url"]).get("javaVersion") or {}).get("majorVersion")
    if java != JAVA_MAJOR:
        raise Refusal(f"{mc} needs Java {java}, RigTune builds with Java {JAVA_MAJOR}: the toolchain has to change "
                      "first (build.gradle release/compatibility, the CI setup-java steps, JAVA_HOME)")
    facts.append(f"Java: {java}")

    loaders = http.json(fabric_loader_url(mc), allow_missing=True) or []
    loader_versions = [e["loader"]["version"] for e in loaders if "loader" in e]
    if not loader_versions:
        raise Refusal(f"Fabric Loader doesn't support {mc} yet (Fabric meta lists no loader for it)")
    ours = read_properties((root / "gradle.properties").read_text(encoding="utf-8")).get("loader_version")
    if ours in loader_versions:
        facts.append(f"Fabric Loader: {ours} (gradle.properties) is listed for {mc}")
    else:
        stable = next((e["loader"]["version"] for e in loaders if e.get("loader", {}).get("stable")), loader_versions[0])
        facts.append(f"Fabric Loader: {len(loader_versions)} versions listed for {mc}")
        notes.append(f"gradle.properties loader_version={ours} isn't listed for {mc} by Fabric meta; its newest "
                     f"stable is {stable}. Upgrade it (for every node) if the build fails.")

    fabric_api = pick_on_maven("fabric-api", mc, http, FABRIC_API_METADATA_URL, "maven.fabricmc.net", "Fabric API")
    facts.append(f"Fabric API: {fabric_api['version_number']} ({fabric_api['version_type']})")
    modmenu = pick_on_maven("modmenu", mc, http, MODMENU_METADATA_URL, "maven.terraformersmc.com", "Mod Menu")
    facts.append(f"Mod Menu: {modmenu['version_number']} ({modmenu['version_type']})")

    sodium = next(iter(rank_modrinth(http.json(modrinth_versions_url("sodium", mc)))), None)
    facts.append(f"Sodium: {sodium['version_number']} ({sodium['version_type']})" if sodium else f"Sodium: none for {mc}")
    iris = next(iter(rank_modrinth(http.json(modrinth_versions_url("iris", mc)))), None)
    if iris:
        iris_comment = f"# Iris {iris['version_number']} (Modrinth version id), compileOnly for the benchmark"
        iris_id = iris["id"]
        facts.append(f"Iris: {iris['version_number']} ({iris['version_type']}), id {iris_id}")
    else:
        iris_id = read_properties(template).get("iris_version")
        if not iris_id:
            raise Refusal(f"no Iris build for {mc} and no iris_version in versions/{prev}/gradle.properties to keep")
        label = re.search(r"(?m)^#\s*Iris\s+(\S+)", template)
        iris_comment = (f"# Iris {label.group(1) if label else iris_id} (Modrinth version id), compileOnly for the "
                        f"benchmark; no Iris build for {mc} yet, so {prev}'s is kept")
        facts.append(f"Iris: none for {mc}; {prev}'s id {iris_id} is kept (compileOnly, API only)")

    properties, copied = render_properties({
        "minecraft_dependency": minecraft_dependency(mc),
        "fabric_api_version": fabric_api["version_number"],
        "modmenu_version": modmenu["version_number"],
        "sodium_version": sodium["version_number"] if sodium else None,
        "iris_comment": iris_comment,
        "iris_version": iris_id,
    }, template)

    if sodium is None:
        notes.append(f"No Sodium build for {mc} yet: sodium_version is left out, so dev runs and game tests run "
                     "without Sodium. Add it when Sodium ships for this version.")
        build_gradle = (root / "build.gradle").read_text(encoding="utf-8") if (root / "build.gradle").is_file() else ""
        if "sodium_version" in build_gradle and not re.search(
                r"(?:hasProperty|findProperty)\(\s*['\"]sodium_version['\"]\s*\)", build_gradle):
            notes.append("WARNING: build.gradle puts Sodium on localRuntime unconditionally (one-time change A is "
                         "missing), so this node won't build without sodium_version.")
    for key in copied:
        notes.append(f"{key} was copied unchanged from versions/{prev}/gradle.properties: check it for {mc}.")
    if parsed.kind != "release":
        notes.append(f"{mc} is a pre-release node: don't ship it (docs/research/v0.3/mc-versions.md, section 5.4). Once "
                     f"{parsed.base} is released, remove this node and add {parsed.base}.")
    else:
        same_minor = [n for n in nodes if MC_ID.fullmatch(n) and version_key(n)[:2] == version_key(mc)[:2]
                      and version_key(n) < version_key(mc) and parse_mc_id(n).kind == "release"]
        if same_minor:
            old = max(same_minor, key=version_key)
            notes.append(f"Hotfix node: narrow versions/{old}/gradle.properties to minecraft_dependency=>={old} "
                         f"<{mc}- so the two jars never accept the same version. A hotfix that keeps the API needs "
                         "no node at all (tools/MC_VERSIONS.md, Hotfixes).")
    release_yml = root / ".github" / "workflows" / "release.yml"
    if release_yml.is_file() and re.search(r":\d+\.\d+[^:\s'\"]*:modrinth", release_yml.read_text(encoding="utf-8")):
        notes.append(f"release.yml still has per-version publish steps: add one for {mc} (or land one-time change B).")
    facts += blog_lines(http, parsed.base)
    return Plan(mc, prev, settings, insert_settings_version(settings, mc), properties, facts, notes)


def checklist(mc, prev):
    base = parse_mc_id(mc).base
    return [
        f"Read Fabric's announcement for {base}; upgrade Loom and Gradle on their own first if it asks.",
        f"./gradlew :{mc}:build (JAVA_HOME = JDK {JAVA_MAJOR}). Fix compile errors with `//? if >={mc} {{` blocks "
        f"(./gradlew \"Set active project to {mc}\" to edit them in the IDE).",
        f"python tools/mc_apidiff.py {prev} {mc} --out build/apidiff: review every changed class it lists "
        "(reflection targets, the mixin target, Options keys, runtime defaults), even when the build is green.",
        f"./gradlew :{mc}:runClientGameTest (opens a game window) and a production smoke test with {mc}'s mods.",
        "Review the version-specific rules (vulkan-backend, ixeris, vulkanmod, mixintrace-reborn) and regenerate the "
        "rules for every supported version: python tools/update_rules.py, then check rules/REVIEW.md.",
        "Update the README, DESIGN, Modrinth body and CHANGELOG version lines (and KnowledgeV2ScenarioTest's "
        "version list).",
        "./gradlew \"Reset active project\", check git diff, commit on a feature branch and push: CI builds every node "
        "and runs its game-test legs (tools/gametest_matrix.py reads versions/*/, so no workflow edit).",
        "Release: tag it; release.yml publishes every versions/*/ node (versionType alpha for a pre-release id).",
        f"For each later hotfix of {base}: bytecode-compare, then PATCH the Modrinth versions' game_versions "
        "(tools/MC_VERSIONS.md, Hotfixes).",
    ]


def print_plan(result, dry_run, out):
    mc = result.mc
    print(f"Adding Minecraft {mc} (after {result.prev})", file=out)
    for fact in result.facts:
        print(f"  {fact}", file=out)
    print("\nsettings.gradle:", file=out)
    for line in difflib.unified_diff(result.settings_before.splitlines(), result.settings_after.splitlines(),
                                     "a/settings.gradle", "b/settings.gradle", n=1, lineterm=""):
        print(line, file=out)
    print(f"\nversions/{mc}/gradle.properties (new):", file=out)
    for line in result.properties.splitlines():
        print(f"  {line}", file=out)
    if result.notes:
        print("\nNotes:", file=out)
        for note in result.notes:
            print(f"  - {note}", file=out)
    print("", file=out)


def main(argv=None, *, root=None, http=None, out=None, err=None):
    root = Path(root) if root else ROOT
    out = out or sys.stdout
    err = err or sys.stderr
    parser = argparse.ArgumentParser(description="Add a Minecraft version as a new Stonecutter node.")
    parser.add_argument("mc", help="Mojang version id, e.g. 26.4 or 26.4-snapshot-1")
    parser.add_argument("--prerelease-ok", action="store_true", help="allow a snapshot, pre-release or release candidate")
    parser.add_argument("--dry-run", action="store_true", help="print the edits without writing anything")
    args = parser.parse_args(argv)
    try:
        check_not_a_node(root, args.mc)
        parse_mc_id(args.mc)
        result = build_plan(root, args.mc, http or Http(), args.prerelease_ok)
    except (Refusal, NetworkError) as e:
        print(f"add_mc_version: {e}", file=err)
        return 1

    print_plan(result, args.dry_run, out)
    if args.dry_run:
        print("Dry run: nothing was written.", file=out)
    else:
        node = root / "versions" / result.mc
        node.mkdir(parents=True)
        (node / "gradle.properties").write_text(result.properties, encoding="utf-8", newline="\n")
        (root / "settings.gradle").write_text(result.settings_after, encoding="utf-8", newline="\n")
        print(f"Wrote settings.gradle and versions/{result.mc}/gradle.properties.", file=out)
    print("\nNext steps (tools/MC_VERSIONS.md):", file=out)
    for i, step in enumerate(checklist(result.mc, result.prev), 1):
        print(f"  {i}. {step}", file=out)
    return 0


if __name__ == "__main__":
    sys.exit(main())
