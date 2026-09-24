#!/usr/bin/env python3
"""Regenerates rules/rules-v1.json from rules/source/knowledge.json plus live
data from the Fabulously Optimized / Additive packwiz repos and the Modrinth
API. See docs/RULES_SCHEMA.md for the output contract and docs/DESIGN.md's
"Staying current" section for the pipeline this implements.
"""

import argparse
import hashlib
import json
import os
import sys
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

USER_AGENT = "chaotix345/rigtune-updater/1.0 (github.com/chaotix345/rigtune)"
MODRINTH_API = "https://api.modrinth.com/v2"
GITHUB_API = "https://api.github.com"
RETRYABLE_STATUSES = {429, 500, 502, 503, 504}
# Modrinth's own API cap is 300 req/min; 0.25s between calls keeps us at 240/min.
MODRINTH_MIN_INTERVAL = 0.25

FO_OWNER_REPO = "Fabulously-Optimized/fabulously-optimized"
ADDITIVE_OWNER_REPO = "skywardmc/additive"
# Statuses Modrinth treats as "still a working, reachable project" per docs.modrinth.com.
OK_PROJECT_STATUSES = {"approved", "unlisted"}


class UpdateRulesError(Exception):
    pass


class KnowledgeError(UpdateRulesError):
    pass


class HttpStatusError(UpdateRulesError):
    def __init__(self, status, url):
        super().__init__(f"HTTP {status} for {url}")
        self.status = status
        self.url = url


def fo_contents_url(mc_version):
    return f"{GITHUB_API}/repos/{FO_OWNER_REPO}/contents/Packwiz/{mc_version}/mods?ref=main"


def fo_raw_url(mc_version, filename):
    return f"https://raw.githubusercontent.com/{FO_OWNER_REPO}/main/Packwiz/{mc_version}/mods/{filename}"


def additive_contents_url(mc_version):
    return f"{GITHUB_API}/repos/{ADDITIVE_OWNER_REPO}/contents/versions/fabric/{mc_version}/mods?ref=main"


def additive_raw_url(mc_version, filename):
    return f"https://raw.githubusercontent.com/{ADDITIVE_OWNER_REPO}/main/versions/fabric/{mc_version}/mods/{filename}"


PACKS = {
    "fabulouslyOptimized": {"contents_url": fo_contents_url, "raw_url": fo_raw_url},
    "additive": {"contents_url": additive_contents_url, "raw_url": additive_raw_url},
}


def version_sort_key(version):
    return tuple(int(p) if p.isdigit() else p for p in version.split("."))


def default_opener(request):
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            return resp.status, resp.read(), {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        headers = {k.lower(): v for k, v in (e.headers or {}).items()}
        return e.code, e.read(), headers


def fixture_key(url):
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


def fixture_opener(fixtures_dir):
    fixtures_dir = Path(fixtures_dir)

    def opener(request):
        url = request.full_url
        path = fixtures_dir / f"{fixture_key(url)}.json"
        if not path.exists():
            raise UpdateRulesError(f"no offline fixture for {url} (expected {path})")
        payload = json.loads(path.read_text(encoding="utf-8"))
        body = payload["body"]
        body_bytes = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else str(body).encode("utf-8")
        headers = {k.lower(): v for k, v in payload.get("headers", {}).items()}
        return payload.get("status", 200), body_bytes, headers

    return opener


def http_get(url, *, headers=None, opener=default_opener, sleeper=time.sleep, max_attempts=6):
    delay = 1.0
    for attempt in range(1, max_attempts + 1):
        request = urllib.request.Request(url, headers=headers or {})
        try:
            status, body, resp_headers = opener(request)
        except OSError:
            if attempt >= max_attempts:
                raise
            sleeper(delay)
            delay = min(delay * 2, 30)
            continue
        if status == 200:
            return body
        github_rate_limited = status == 403 and resp_headers.get("x-ratelimit-remaining") == "0"
        if (status in RETRYABLE_STATUSES or github_rate_limited) and attempt < max_attempts:
            retry_after = resp_headers.get("retry-after")
            if retry_after:
                wait = float(retry_after)
            elif github_rate_limited and resp_headers.get("x-ratelimit-reset"):
                wait = max(0.0, float(resp_headers["x-ratelimit-reset"]) - time.time())
            else:
                wait = delay
            sleeper(min(wait, 60))
            delay = min(delay * 2, 30)
            continue
        raise HttpStatusError(status, url)
    raise HttpStatusError(status, url)


class Client:
    def __init__(self, opener=default_opener, sleeper=time.sleep, github_token=None):
        self.opener = opener
        self.sleeper = sleeper
        self.github_token = github_token
        self._last_modrinth_call = None

    def _throttle_modrinth(self):
        if self._last_modrinth_call is not None:
            wait = MODRINTH_MIN_INTERVAL - (time.monotonic() - self._last_modrinth_call)
            if wait > 0:
                self.sleeper(wait)
        self._last_modrinth_call = time.monotonic()

    def modrinth_json(self, url):
        self._throttle_modrinth()
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        return json.loads(http_get(url, headers=headers, opener=self.opener, sleeper=self.sleeper))

    def github_json(self, url):
        headers = {"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"}
        if self.github_token:
            headers["Authorization"] = f"Bearer {self.github_token}"
        return json.loads(http_get(url, headers=headers, opener=self.opener, sleeper=self.sleeper))

    def raw_text(self, url):
        headers = {"User-Agent": USER_AGENT}
        return http_get(url, headers=headers, opener=self.opener, sleeper=self.sleeper).decode("utf-8")


def resolve_target_versions(client, override):
    if override:
        versions = [v.strip() for v in override.split(",") if v.strip()]
    else:
        tags = client.modrinth_json(f"{MODRINTH_API}/tag/game_version")
        releases = [t["version"] for t in tags if t.get("version_type") == "release"]
        releases.sort(key=version_sort_key, reverse=True)
        versions = releases[:3]
    return sorted(versions, key=version_sort_key, reverse=True)


def list_pw_toml_filenames(client, contents_url_fn, mc_version):
    try:
        entries = client.github_json(contents_url_fn(mc_version))
    except HttpStatusError as e:
        if e.status == 404:
            return None
        raise
    return sorted(
        e["name"] for e in entries
        if isinstance(e, dict) and e.get("type") == "file" and e.get("name", "").endswith(".pw.toml")
    )


def collect_project_ids(client, raw_url_fn, mc_version, filenames):
    ids = set()
    for name in filenames:
        text = client.raw_text(raw_url_fn(mc_version, name))
        data = tomllib.loads(text)
        mod_id = data.get("update", {}).get("modrinth", {}).get("mod-id")
        if mod_id:
            ids.add(mod_id)
    return ids


def pack_ids_by_version(client, pack, mc_versions):
    result = {}
    for v in mc_versions:
        filenames = list_pw_toml_filenames(client, pack["contents_url"], v)
        result[v] = None if filenames is None else collect_project_ids(client, pack["raw_url"], v, filenames)
    return result


def newest_available_version(mc_versions_sorted_desc, ids_by_version):
    for v in mc_versions_sorted_desc:
        if ids_by_version.get(v) is not None:
            return v
    return None


def batch_fetch_projects(client, project_ids, batch_size=100):
    ids = sorted(project_ids)
    projects = {}
    for i in range(0, len(ids), batch_size):
        chunk = ids[i:i + batch_size]
        url = f"{MODRINTH_API}/projects?ids={urllib.parse.quote(json.dumps(chunk), safe='')}"
        for project in client.modrinth_json(url):
            projects[project["id"]] = project
    return projects


def slugs_for_version(ids_by_version, version, projects_by_id):
    if version is None:
        return set()
    return {projects_by_id[i]["slug"] for i in ids_by_version[version] if i in projects_by_id}


def mods_with_upstream(rule_mods, fo_slugs, additive_slugs):
    merged = []
    for mod in rule_mods:
        m = dict(mod)
        m["upstream"] = {
            "fabulouslyOptimized": mod["slug"] in fo_slugs,
            "additive": mod["slug"] in additive_slugs,
        }
        merged.append(m)
    return merged


def compute_availability(rule_mods, projects_by_id, mc_versions):
    availability = {}
    for v in mc_versions:
        slugs = []
        for m in rule_mods:
            project = projects_by_id.get(m["projectId"])
            if project and v in project.get("game_versions", []) and "fabric" in project.get("loaders", []):
                slugs.append(m["slug"])
        availability[v] = sorted(slugs)
    return availability


def top_level_upstream(fo_version, fo_slugs, additive_version, additive_slugs):
    return {
        "fabulouslyOptimized": {"mcVersion": fo_version, "slugs": sorted(fo_slugs)},
        "additive": {"mcVersion": additive_version, "slugs": sorted(additive_slugs)},
    }


def build_review(rule_mods, mc_versions, newest_version, fo_slugs, additive_slugs,
                  projects_by_id, availability, old_mods_by_slug):
    known_slugs = {m["slug"] for m in rule_mods}
    slug_to_project = {p["slug"]: p for p in projects_by_id.values() if "slug" in p}

    new_upstream = []
    for slug in sorted(fo_slugs | additive_slugs):
        if slug in known_slugs:
            continue
        project = slug_to_project.get(slug)
        packs = []
        if slug in fo_slugs:
            packs.append("Fabulously Optimized")
        if slug in additive_slugs:
            packs.append("Additive")
        new_upstream.append({
            "slug": slug,
            "title": project.get("title", slug) if project else slug,
            "packs": packs,
            "optimization": bool(project and "optimization" in project.get("categories", [])),
        })

    status_issues = []
    for m in rule_mods:
        project = projects_by_id.get(m["projectId"])
        if project is None:
            status_issues.append((m["slug"], m.get("title", m["slug"]), "not found on Modrinth (project id returned nothing)"))
            continue
        status = project.get("status")
        if status not in OK_PROJECT_STATUSES:
            status_issues.append((m["slug"], project.get("title", m["slug"]), f"status: {status}"))

    removed_issues = []
    if old_mods_by_slug is not None:
        for m in rule_mods:
            old_upstream = old_mods_by_slug.get(m["slug"])
            if not old_upstream:
                continue
            was_upstream = old_upstream.get("fabulouslyOptimized") or old_upstream.get("additive")
            now_upstream = m["slug"] in fo_slugs or m["slug"] in additive_slugs
            if was_upstream and not now_upstream:
                removed_issues.append((m["slug"], m.get("title", m["slug"]), "removed from both Fabulously Optimized and Additive"))

    newest_slugs = set(availability.get(newest_version, [])) if newest_version else set()
    missing_fabric = [
        (m["slug"], m.get("title", m["slug"]))
        for m in rule_mods
        if m["slug"] not in newest_slugs
    ]

    markdown = render_review_markdown(
        mc_versions, newest_version, new_upstream, status_issues, removed_issues,
        missing_fabric, old_mods_by_slug is None,
    )
    counts = {
        "new_upstream": len(new_upstream),
        "status_or_removed": len(status_issues) + len(removed_issues),
        "missing_fabric": len(missing_fabric),
    }
    return markdown, counts


def render_review_markdown(mc_versions, newest_version, new_upstream, status_issues, removed_issues, missing_fabric, no_history):
    lines = ["# RigTune rules update review", ""]
    lines.append(f"Target MC versions: {', '.join(mc_versions)}. Newest: {newest_version or 'unknown'}.")
    lines.append("")
    lines.append("## Summary")
    lines.append(f"- New upstream mods to triage: {len(new_upstream)}")
    lines.append(f"- Rule mods with a status or removal concern: {len(status_issues) + len(removed_issues)}")
    lines.append(f"- Rule mods missing a Fabric build for {newest_version or 'the newest target version'}: {len(missing_fabric)}")
    lines.append("")

    lines.append("## (a) Upstream mods not yet tracked in knowledge.json")
    if new_upstream:
        lines.append("| slug | title | pack(s) | optimization category |")
        lines.append("|---|---|---|---|")
        for item in new_upstream:
            lines.append(f"| {item['slug']} | {item['title']} | {', '.join(item['packs'])} | {'yes' if item['optimization'] else 'no'} |")
    else:
        lines.append("None found.")
    lines.append("")

    lines.append("## (b) Rule mods needing a status check")
    if no_history:
        lines.append('_No previous rules-v1.json was found, so "removed from both packs" could not be checked this run._')
        lines.append("")
    combined = [(s, t, r) for s, t, r in status_issues] + [(s, t, r) for s, t, r in removed_issues]
    if combined:
        lines.append("| slug | title | reason |")
        lines.append("|---|---|---|")
        for slug, title, reason in combined:
            lines.append(f"| {slug} | {title} | {reason} |")
    else:
        lines.append("None found.")
    lines.append("")

    lines.append(f"## (c) Rule mods with no Fabric release for {newest_version or 'the newest target version'}")
    if missing_fabric:
        lines.append("| slug | title |")
        lines.append("|---|---|")
        for slug, title in missing_fabric:
            lines.append(f"| {slug} | {title} |")
    else:
        lines.append("None found.")
    lines.append("")

    return "\n".join(lines)


def load_knowledge(path):
    if not path.exists():
        raise KnowledgeError(f"knowledge file not found: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise KnowledgeError(f"knowledge file at {path} is not valid JSON: {e}")
    if not isinstance(data, dict):
        raise KnowledgeError(f"knowledge file at {path} must contain a JSON object")
    if not isinstance(data.get("mods"), list):
        raise KnowledgeError(f"knowledge file at {path} must have a 'mods' array")
    for mod in data["mods"]:
        if "slug" not in mod or "projectId" not in mod:
            raise KnowledgeError(f"knowledge file at {path}: every mod needs 'slug' and 'projectId'")
    return data


def assemble_content(knowledge, mods, availability, upstream):
    content = {"schemaVersion": 1}
    if "minModVersion" in knowledge:
        content["minModVersion"] = knowledge["minModVersion"]
    content["gpuTiers"] = knowledge.get("gpuTiers", [])
    content["gpuVendorFallback"] = knowledge.get("gpuVendorFallback", {})
    content["cpuTiers"] = knowledge.get("cpuTiers", [])
    content["heapTiers"] = knowledge.get("heapTiers", [])
    content["mods"] = mods
    content["obsolete"] = knowledge.get("obsolete", [])
    content["settings"] = knowledge.get("settings", [])
    content["advice"] = knowledge.get("advice", [])
    content["availability"] = availability
    content["upstream"] = upstream
    return content


def deep_equal(a, b):
    return json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)


def strip_meta(doc):
    return {k: v for k, v in doc.items() if k not in ("revision", "generatedAt")}


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def finalize_document(content, old_doc):
    old_revision = old_doc.get("revision", 0) if old_doc else 0
    changed = old_doc is None or not deep_equal(strip_meta(old_doc), content)
    if not changed:
        return None
    final = {"schemaVersion": content["schemaVersion"], "revision": old_revision + 1, "generatedAt": now_iso()}
    for key, value in content.items():
        if key != "schemaVersion":
            final[key] = value
    return final


def load_json_if_exists(path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def write_text(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not text.endswith("\n"):
        text += "\n"
    path.write_bytes(text.encode("utf-8"))


def run_pipeline(knowledge, client, mc_versions_override, old_doc):
    mc_versions = resolve_target_versions(client, mc_versions_override)
    newest_version = mc_versions[0]

    ids_by_version = {name: pack_ids_by_version(client, pack, mc_versions) for name, pack in PACKS.items()}
    newest_by_pack = {
        name: newest_available_version(mc_versions, ids_by_version[name])
        for name in PACKS
    }

    rule_mods = knowledge.get("mods", [])
    all_ids = {m["projectId"] for m in rule_mods}
    for name in PACKS:
        version = newest_by_pack[name]
        if version is not None:
            all_ids |= ids_by_version[name][version]
    projects_by_id = batch_fetch_projects(client, all_ids)

    fo_slugs = slugs_for_version(ids_by_version["fabulouslyOptimized"], newest_by_pack["fabulouslyOptimized"], projects_by_id)
    additive_slugs = slugs_for_version(ids_by_version["additive"], newest_by_pack["additive"], projects_by_id)

    mods = mods_with_upstream(rule_mods, fo_slugs, additive_slugs)
    availability = compute_availability(rule_mods, projects_by_id, mc_versions)
    upstream = top_level_upstream(newest_by_pack["fabulouslyOptimized"], fo_slugs, newest_by_pack["additive"], additive_slugs)

    content = assemble_content(knowledge, mods, availability, upstream)

    old_mods_by_slug = None
    if old_doc is not None:
        old_mods_by_slug = {m["slug"]: m.get("upstream", {}) for m in old_doc.get("mods", [])}
    review_md, review_counts = build_review(
        rule_mods, mc_versions, newest_version, fo_slugs, additive_slugs,
        projects_by_id, availability, old_mods_by_slug,
    )

    return content, review_md, review_counts, mc_versions, newest_by_pack, fo_slugs, additive_slugs


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--knowledge", help="Path to knowledge.json (default: rules/source/knowledge.json)")
    parser.add_argument("--out-dir", help="Root directory under which rules/ and src/main/resources/rigtune/ are written (default: repo root)")
    parser.add_argument("--mc-versions", help="Comma-separated MC versions, overriding Modrinth auto-detection")
    parser.add_argument("--dry-run", action="store_true", help="Compute everything and print a summary, without writing files")
    parser.add_argument("--offline-fixtures", help="Directory of canned HTTP responses keyed by sha256(url).json, for offline runs")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    repo_root = Path(__file__).resolve().parent.parent
    knowledge_path = Path(args.knowledge) if args.knowledge else repo_root / "rules" / "source" / "knowledge.json"
    out_root = Path(args.out_dir) if args.out_dir else repo_root

    try:
        knowledge = load_knowledge(knowledge_path)
    except KnowledgeError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    rules_path = out_root / "rules" / "rules-v1.json"
    bundled_path = out_root / "src" / "main" / "resources" / "rigtune" / "rules-v1.json"
    review_path = out_root / "rules" / "REVIEW.md"
    old_doc = load_json_if_exists(rules_path)

    opener = fixture_opener(args.offline_fixtures) if args.offline_fixtures else default_opener
    client = Client(opener=opener, sleeper=time.sleep, github_token=os.environ.get("GITHUB_TOKEN"))

    try:
        content, review_md, review_counts, mc_versions, newest_by_pack, fo_slugs, additive_slugs = run_pipeline(
            knowledge, client, args.mc_versions, old_doc,
        )
    except UpdateRulesError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    final_doc = finalize_document(content, old_doc)

    print(f"target MC versions: {', '.join(mc_versions)}")
    print(f"Fabulously Optimized: newest available = {newest_by_pack['fabulouslyOptimized']}, {len(fo_slugs)} mods")
    print(f"Additive: newest available = {newest_by_pack['additive']}, {len(additive_slugs)} mods")
    print(f"REVIEW.md: {review_counts}")
    if final_doc is None:
        print(f"no content change; keeping revision {old_doc.get('revision') if old_doc else 'n/a'}")
    else:
        print(f"revision {old_doc.get('revision', 0) if old_doc else 0} -> {final_doc['revision']}")

    if args.dry_run:
        print("dry run: no files written")
        return 0

    if final_doc is not None:
        write_json(rules_path, final_doc)
        write_json(bundled_path, final_doc)
    write_text(review_path, review_md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
