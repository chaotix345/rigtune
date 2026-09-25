#!/usr/bin/env python3
"""Creates and maintains the RigTune project on Modrinth: the project
itself, its gallery, its listing body, version uploads and submitting it
for moderator review. See docs/v0.2/SPEC.md item 4 and
docs/research/v0.2/modrinth.md for the API contract this implements.

Talks only to https://api.modrinth.com. The write token (MODRINTH_TOKEN)
is read from this process's environment if present, else from the
Windows current-user environment via a `pwsh` subprocess — never accepted
as a CLI argument, never printed, logged or written to a file. --dry-run
performs no network calls at all and needs no token.
"""

import argparse
import hashlib
import json
import mimetypes
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

USER_AGENT = "chaotix345/rigtune/tools (github.com/chaotix345/rigtune)"
API = "https://api.modrinth.com/v2"

PROJECT_SLUG = "rigtune"
REPO_SLUG = "chaotix345/rigtune"

DEFAULT_CATEGORIES = ["optimization"]
DEFAULT_ADDITIONAL_CATEGORIES = ["utility"]
DEFAULT_SUMMARY = (
    "Hardware-aware performance tuning for Fabric. Detects your CPU/GPU/RAM "
    "and applies recommended settings and mod suggestions."
)


class ModrinthError(Exception):
    pass


class ApiError(ModrinthError):
    def __init__(self, status, url, body):
        super().__init__(f"HTTP {status} for {url}: {body[:500]!r}")
        self.status = status
        self.url = url
        self.body = body


# --- token handling ---------------------------------------------------

def read_token(env_var="MODRINTH_TOKEN"):
    """Reads a Modrinth token from this process's environment, falling
    back to the Windows current-user environment (set outside any shell
    history or CI log by the user). Returns None if neither has it."""
    value = os.environ.get(env_var)
    if value:
        return value
    try:
        result = subprocess.run(
            [
                "pwsh", "-NoProfile", "-Command",
                f"[Environment]::GetEnvironmentVariable('{env_var}','User')",
            ],
            capture_output=True, text=True, timeout=15, check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    value = (result.stdout or "").strip()
    return value or None


# --- multipart encoding (stdlib has no encoder) ------------------------

def encode_multipart(fields, files, boundary=None):
    """fields: list of (name, bytes). files: list of (name, filename,
    content_type, bytes). Returns (content_type_header_value, body_bytes)."""
    boundary = boundary or uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8")
            + value + b"\r\n"
        )
    for name, filename, content_type, value in files:
        parts.append(
            (
                f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'
                f"Content-Type: {content_type}\r\n\r\n"
            ).encode("utf-8")
            + value + b"\r\n"
        )
    parts.append(f"--{boundary}--\r\n".encode("utf-8"))
    return f"multipart/form-data; boundary={boundary}", b"".join(parts)


# --- HTTP layer ----------------------------------------------------------

def default_opener(request):
    try:
        with urllib.request.urlopen(request, timeout=60) as resp:
            return resp.status, resp.read(), {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        headers = {k.lower(): v for k, v in (e.headers or {}).items()}
        return e.code, e.read(), headers


class Client:
    """Thin wrapper over the Modrinth v2 API. In dry-run mode, every
    mutating method logs the call it would have made (to .dry_run_log)
    and returns a placeholder, without touching `opener` at all."""

    def __init__(self, token=None, opener=default_opener, base_url=API, dry_run=False):
        self.token = token
        self.opener = opener
        self.base_url = base_url
        self.dry_run = dry_run
        self.dry_run_log = []

    def _headers(self, extra=None):
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        if self.token:
            headers["Authorization"] = self.token
        if extra:
            headers.update(extra)
        return headers

    def _call(self, method, url, *, headers=None, data=None):
        request = urllib.request.Request(url, data=data, headers=self._headers(headers), method=method)
        status, body, resp_headers = self.opener(request)
        if status >= 400:
            raise ApiError(status, url, body.decode("utf-8", "replace"))
        return status, body, resp_headers

    # -- reads --

    def get_project(self, slug_or_id):
        url = f"{self.base_url}/project/{slug_or_id}"
        try:
            _, body, _ = self._call("GET", url)
        except ApiError as e:
            if e.status == 404:
                return None
            raise
        return json.loads(body)

    def list_versions(self, project_id):
        url = f"{self.base_url}/project/{project_id}/version"
        _, body, _ = self._call("GET", url)
        return json.loads(body)

    # -- writes (each dry-run-safe: logs and returns without calling opener) --

    def create_project(self, payload, icon_path=None):
        if self.dry_run:
            self.dry_run_log.append({"op": "create_project", "payload": payload, "icon": str(icon_path) if icon_path else None})
            return {"id": "<dry-run>", "slug": payload.get("slug")}
        fields = [("data", json.dumps(payload).encode("utf-8"))]
        files = []
        if icon_path is not None:
            icon_bytes = Path(icon_path).read_bytes()
            content_type = mimetypes.guess_type(str(icon_path))[0] or "application/octet-stream"
            files.append(("icon", Path(icon_path).name, content_type, icon_bytes))
        content_type, body = encode_multipart(fields, files)
        _, resp_body, _ = self._call(
            "POST", f"{self.base_url}/project", headers={"Content-Type": content_type}, data=body
        )
        return json.loads(resp_body)

    def upload_gallery_image(self, project_id, path, *, ext, featured, title=None, description=None, ordering=None):
        if self.dry_run:
            self.dry_run_log.append({
                "op": "upload_gallery_image", "project_id": project_id, "path": str(path),
                "ext": ext, "featured": featured, "title": title, "description": description, "ordering": ordering,
            })
            return
        params = {"ext": ext, "featured": "true" if featured else "false"}
        if title is not None:
            params["title"] = title
        if description is not None:
            params["description"] = description
        if ordering is not None:
            params["ordering"] = str(ordering)
        url = f"{self.base_url}/project/{project_id}/gallery?{urllib.parse.urlencode(params)}"
        body = Path(path).read_bytes()
        content_type = mimetypes.guess_type(str(path))[0] or "application/octet-stream"
        self._call("POST", url, headers={"Content-Type": content_type}, data=body)

    def patch_project(self, project_id, fields):
        if self.dry_run:
            self.dry_run_log.append({"op": "patch_project", "project_id": project_id, "fields": fields})
            return
        data = json.dumps(fields).encode("utf-8")
        self._call(
            "PATCH", f"{self.base_url}/project/{project_id}",
            headers={"Content-Type": "application/json"}, data=data,
        )

    def create_version(self, payload, file_path, file_part_name="file"):
        if self.dry_run:
            self.dry_run_log.append({"op": "create_version", "payload": payload, "file": str(file_path)})
            return {"id": "<dry-run>", "version_number": payload.get("version_number")}
        file_bytes = Path(file_path).read_bytes()
        fields = [("data", json.dumps(payload).encode("utf-8"))]
        files = [(file_part_name, Path(file_path).name, "application/java-archive", file_bytes)]
        content_type, body = encode_multipart(fields, files)
        _, resp_body, _ = self._call(
            "POST", f"{self.base_url}/version", headers={"Content-Type": content_type}, data=body
        )
        return json.loads(resp_body)


# --- gallery.md parsing --------------------------------------------------

_HEADING_RE = re.compile(r"^##\s+(\S.*)$")
_BULLET_RE = re.compile(r"^-\s+(\w[\w-]*)\s*:\s*(.*)$")


def parse_gallery_md(path):
    """Parses the `## <image path>` / `- key: value` gallery listing format
    described in docs/modrinth/gallery.md. Returns a list of dicts with
    keys path, title, description, featured (bool, default False), in
    document order."""
    entries = []
    current = None
    for raw_line in Path(path).read_text(encoding="utf-8").splitlines():
        heading = _HEADING_RE.match(raw_line)
        if heading:
            current = {"path": heading.group(1).strip(), "title": None, "description": None, "featured": False}
            entries.append(current)
            continue
        if current is None:
            continue
        bullet = _BULLET_RE.match(raw_line)
        if not bullet:
            continue
        key, value = bullet.group(1).strip().lower(), bullet.group(2).strip()
        if key == "featured":
            current["featured"] = value.strip().lower() == "true"
        elif key in ("title", "description"):
            current[key] = value
    return entries


# --- subcommands ---------------------------------------------------------

def project_payload(body_text):
    return {
        "slug": PROJECT_SLUG,
        "title": "RigTune",
        "project_type": "mod",
        "description": DEFAULT_SUMMARY,
        "body": body_text,
        "categories": list(DEFAULT_CATEGORIES),
        "additional_categories": list(DEFAULT_ADDITIONAL_CATEGORIES),
        "client_side": "required",
        "server_side": "unsupported",
        "license_id": "MIT",
        "source_url": f"https://github.com/{REPO_SLUG}",
        "issues_url": f"https://github.com/{REPO_SLUG}/issues",
        "is_draft": True,
        # Documented as deprecated, but the live API rejects a create without it (HTTP 400, 2026-09-25).
        "initial_versions": [],
    }


def cmd_create(client, args):
    body_text = Path(args.body_file).read_text(encoding="utf-8")
    payload = project_payload(body_text)
    if not client.dry_run:
        existing = client.get_project(PROJECT_SLUG)
        if existing is not None:
            print(f"project already exists: id={existing['id']} slug={existing['slug']} status={existing.get('status')}")
            return 0
    icon_path = Path(args.icon) if args.icon else None
    result = client.create_project(payload, icon_path=icon_path)
    if client.dry_run:
        print(json.dumps({"would_create": payload, "icon": str(icon_path) if icon_path else None}, indent=2))
    else:
        print(f"created project: id={result['id']} slug={result['slug']}")
    return 0


def cmd_gallery(client, args):
    entries = parse_gallery_md(args.gallery_file)
    if not entries:
        print(f"no entries in {args.gallery_file}")
        return 0
    existing_titles = set()
    project_id = PROJECT_SLUG
    if not client.dry_run:
        project = client.get_project(PROJECT_SLUG)
        if project is None:
            print(f"project {PROJECT_SLUG!r} does not exist; run `create` first")
            return 1
        project_id = project["id"]
        existing_titles = {img.get("title") for img in project.get("gallery", [])}
    uploaded = 0
    for index, entry in enumerate(entries):
        if entry["title"] in existing_titles:
            print(f"skip (already uploaded): {entry['title']}")
            continue
        ext = Path(entry["path"]).suffix.lstrip(".").lower()
        client.upload_gallery_image(
            project_id, entry["path"], ext=ext, featured=entry["featured"],
            title=entry["title"], description=entry["description"], ordering=index,
        )
        uploaded += 1
        if not client.dry_run:
            print(f"uploaded: {entry['title']}")
    if client.dry_run:
        print(json.dumps({"would_upload": entries}, indent=2))
    else:
        print(f"gallery: {uploaded} uploaded, {len(entries) - uploaded} already present")
    return 0


def cmd_sync_body(client, args):
    body_text = Path(args.body_file).read_text(encoding="utf-8")
    if client.dry_run:
        print(json.dumps({"would_patch_body_from": args.body_file, "length": len(body_text)}, indent=2))
        return 0
    project = client.get_project(PROJECT_SLUG)
    if project is None:
        print(f"project {PROJECT_SLUG!r} does not exist; run `create` first")
        return 1
    client.patch_project(project["id"], {"body": body_text})
    print(f"synced body from {args.body_file} ({len(body_text)} chars)")
    return 0


def _sha256_of(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cmd_upload_version(client, args):
    if args.sha256:
        actual = _sha256_of(args.file)
        if actual.lower() != args.sha256.lower():
            raise ModrinthError(f"sha256 mismatch for {args.file}: expected {args.sha256}, got {actual}")
    changelog = None
    if args.changelog_file:
        changelog = Path(args.changelog_file).read_text(encoding="utf-8")
    payload = {
        "project_id": PROJECT_SLUG,
        "file_parts": ["file"],
        "version_number": args.version_number,
        "name": args.name,
        "changelog": changelog,
        "game_versions": [v.strip() for v in args.game_versions.split(",") if v.strip()],
        "loaders": [v.strip() for v in args.loaders.split(",") if v.strip()],
        "version_type": args.version_type,
        "featured": True,
        "dependencies": [{"project_id": "fabric-api", "version_id": None, "file_name": None, "dependency_type": "required"}],
    }
    if not client.dry_run:
        project = client.get_project(PROJECT_SLUG)
        if project is None:
            print(f"project {PROJECT_SLUG!r} does not exist; run `create` first")
            return 1
        payload["project_id"] = project["id"]
        # The API wants base62 project ids in dependencies, not slugs (HTTP 400 otherwise, 2026-09-25).
        fabric_api = client.get_project("fabric-api")
        if fabric_api is None:
            raise ModrinthError("could not resolve the fabric-api project id")
        payload["dependencies"][0]["project_id"] = fabric_api["id"]
        for existing in client.list_versions(project["id"]):
            if existing.get("version_number") == args.version_number:
                print(f"version {args.version_number} already exists: id={existing['id']}")
                return 0
    result = client.create_version(payload, args.file)
    if client.dry_run:
        print(json.dumps({"would_upload_version": payload, "file": args.file}, indent=2))
    else:
        print(f"created version: id={result['id']} version_number={result.get('version_number')}")
    return 0


def cmd_submit(client, args):
    if client.dry_run:
        client.patch_project("<dry-run>", {"requested_status": "approved"})
        print(json.dumps({"would_patch": {"requested_status": "approved"}}, indent=2))
        return 0
    project = client.get_project(PROJECT_SLUG)
    if project is None:
        print(f"project {PROJECT_SLUG!r} does not exist; run `create` first")
        return 1
    # Re-applying the sides sets the v3 "environment" field on every version (labrinth routes/v2/projects.rs); a version
    # uploaded through v2 after the project was created has none, and review refuses a missing environment.
    client.patch_project(project["id"], {"client_side": "required", "server_side": "unsupported"})
    client.patch_project(project["id"], {"requested_status": "approved"})
    # requested_status alone leaves a draft as a draft (checked live 2026-09-25); moving a draft to "processing" is
    # what the web UI's "Submit for review" does.
    if project.get("status") == "draft":
        client.patch_project(project["id"], {"status": "processing"})
    print(f"submitted for review: id={project['id']} requested_status=approved")
    return 0


def cmd_status(client, args):
    project = client.get_project(PROJECT_SLUG)
    if project is None:
        print(f"project {PROJECT_SLUG!r} not found")
        return 1
    print(f"id={project['id']} slug={project['slug']} status={project.get('status')} requested_status={project.get('requested_status')}")
    print(f"title={project.get('title')!r}")
    print(f"license={project.get('license', {}).get('id')}")
    print(f"source_url={project.get('source_url')} issues_url={project.get('issues_url')}")
    print(f"gallery images: {len(project.get('gallery', []))}")
    for version in client.list_versions(project["id"]):
        print(
            f"version id={version['id']} number={version.get('version_number')} "
            f"game_versions={version.get('game_versions')} loaders={version.get('loaders')} "
            f"status={version.get('status')}"
        )
    return 0


# --- CLI -------------------------------------------------------------------

def build_parser():
    parser = argparse.ArgumentParser(description="Manage the RigTune Modrinth project.")
    sub = parser.add_subparsers(dest="command", required=True)

    p_create = sub.add_parser("create", help="create the draft project (no-op if it exists)")
    p_create.add_argument("--body-file", default="docs/modrinth/body.md")
    p_create.add_argument("--icon", default="src/main/resources/assets/rigtune/icon.png")
    p_create.add_argument("--dry-run", action="store_true")
    p_create.set_defaults(func=cmd_create)

    p_gallery = sub.add_parser("gallery", help="upload gallery images listed in docs/modrinth/gallery.md")
    p_gallery.add_argument("--gallery-file", default="docs/modrinth/gallery.md")
    p_gallery.add_argument("--dry-run", action="store_true")
    p_gallery.set_defaults(func=cmd_gallery)

    p_sync = sub.add_parser("sync-body", help="push docs/modrinth/body.md as the listing body")
    p_sync.add_argument("--body-file", default="docs/modrinth/body.md")
    p_sync.add_argument("--dry-run", action="store_true")
    p_sync.set_defaults(func=cmd_sync_body)

    p_upload = sub.add_parser("upload-version", help="upload a jar as a new Modrinth version")
    p_upload.add_argument("--file", required=True)
    p_upload.add_argument("--version-number", required=True)
    p_upload.add_argument("--name", required=True)
    p_upload.add_argument("--game-versions", required=True, help="comma-separated, e.g. 26.2")
    p_upload.add_argument("--loaders", default="fabric")
    p_upload.add_argument("--version-type", default="release")
    p_upload.add_argument("--changelog-file")
    p_upload.add_argument("--sha256", help="verify the file's sha256 before uploading")
    p_upload.add_argument("--dry-run", action="store_true")
    p_upload.set_defaults(func=cmd_upload_version)

    p_submit = sub.add_parser("submit", help="submit the project for moderator review")
    p_submit.add_argument("--dry-run", action="store_true")
    p_submit.set_defaults(func=cmd_submit)

    p_status = sub.add_parser("status", help="print the project's current status and versions")
    p_status.set_defaults(func=cmd_status)

    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)
    dry_run = getattr(args, "dry_run", False)
    token = None if dry_run else read_token()
    if not dry_run and token is None:
        print("MODRINTH_TOKEN not found in the environment or the Windows user environment", file=sys.stderr)
        return 2
    client = Client(token=token, dry_run=dry_run)
    try:
        return args.func(client, args)
    except ModrinthError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
