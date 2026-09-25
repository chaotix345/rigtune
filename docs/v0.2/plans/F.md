# WS-F: Modrinth Publishing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development for every task; superpowers:verification-before-completion before reporting. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Publish RigTune to Modrinth: a committed, tested `tools/modrinth_project.py` that creates/maintains the `rigtune` project via the API, listing copy for v0.1.0 (and a v0.2.0 draft for later), and — after user confirmation — the real project creation, gallery, v0.1.0 version upload and submit-for-review.

**Architecture:** One Python 3.11 stdlib script (`urllib`, no external deps) with an `opener`-injection pattern matching `tools/update_rules.py` (a callable taking a `urllib.request.Request` and returning `(status, body, headers)`), so tests run with a scripted fake opener and no network. Multipart bodies (project create, version upload) are hand-encoded since stdlib has no multipart encoder; gallery upload is a raw binary body with query-string metadata (verified against `docs.modrinth.com/api/operations/addgalleryimage/`). CLI subcommands are thin wrappers around a `Client` class holding the token, base URL and dry-run flag.

**Tech Stack:** Python 3.11 stdlib only (argparse, json, urllib, subprocess, hashlib, mimetypes). `python -m unittest`.

**Spec:** docs/v0.2/SPEC.md item 4 + "Amendments from the plan review" (M11, M15, L7); docs/v0.2/PLAN.md WS-F; docs/research/v0.2/modrinth.md.

## Global Constraints
- Talks only to `https://api.modrinth.com` (base URL overridable for tests, never for the real run).
- Token: `MODRINTH_TOKEN` from `os.environ`, else `[Environment]::GetEnvironmentVariable('MODRINTH_TOKEN','User')` via `pwsh -NoProfile -Command`. Never printed, logged, committed, or accepted as a CLI arg. `--dry-run` makes zero network calls and needs no token.
- User-Agent: `chaotix345/rigtune/tools (github.com/chaotix345/rigtune)`.
- Idempotent: `create` is a no-op if the project exists; `gallery` skips images already present by title; `upload-version` skips if the version_number already exists.
- Real destructive/public calls (create, v0.1.0 upload, submit) require the user's explicit go-ahead, already given 2026-09-25 per the coordinator's brief (relaying the user) and recorded in `docs/v0.2/design/F.md` before they're made.

---

### Task 1: Multipart encoding + token reading (pure helpers)

**Files:**
- Create: `tools/modrinth_project.py`
- Test: `tools/tests/test_modrinth_project.py`

**Interfaces:**
- Produces: `encode_multipart(fields: list[tuple[str, bytes]], files: list[tuple[str, str, str, bytes]]) -> tuple[str, bytes]` (content-type header value, body). `read_token(env_var="MODRINTH_TOKEN") -> str | None`.

- [ ] Write `test_encode_multipart_contains_field_and_file_parts`: encode one field `("data", b'{"a":1}')` and one file `("icon", "icon.png", "image/png", b"\x89PNG...")`; parse the result by splitting on the boundary extracted from the returned content-type, assert both parts appear with correct `Content-Disposition` and bytes.
- [ ] Write `test_read_token_prefers_env_var`: monkeypatch `os.environ` with `MODRINTH_TOKEN=x`; assert `read_token()` returns `"x"` without calling `subprocess.run` (patch it to raise if called).
- [ ] Write `test_read_token_falls_back_to_powershell`: empty env; monkeypatch `subprocess.run` to return a fake `CompletedProcess(stdout="tok\n")`; assert `read_token()` returns `"tok"` and the command list's program is `"pwsh"`.
- [ ] Write `test_read_token_returns_none_when_unset`: empty env; `subprocess.run` returns `stdout=""`; assert `read_token()` is `None`.
- [ ] Implement `encode_multipart` and `read_token` in `tools/modrinth_project.py`. Run `python -m unittest tools.tests.test_modrinth_project -v`; confirm pass.
- [ ] Commit: "feat(modrinth): multipart encoding and token reading".

### Task 2: `Client` (HTTP layer, dry-run, project/version helpers)

**Interfaces:**
- Consumes: Task 1's `encode_multipart`.
- Produces: `class Client(token=None, opener=default_opener, base_url=API, dry_run=False)` with `.get_project(slug_or_id) -> dict | None`, `.list_versions(project_id) -> list[dict]`, `.create_project(payload, icon_path=None) -> dict`, `.upload_gallery_image(project_id, path, *, ext, featured, title, description, ordering) -> None`, `.patch_project(project_id, fields: dict) -> None`, `.create_version(payload, file_path) -> dict`. `class ApiError(ModrinthError)` with `.status`.

- [ ] Write `ScriptedOpener`/`RecordingSleeper`-equivalent fixtures in the test file (same shape as `tools/tests/test_update_rules.py`'s), keyed on `(method, url)`.
- [ ] Write `test_get_project_returns_none_on_404` and `test_get_project_returns_dict_on_200`.
- [ ] Write `test_create_project_is_noop_when_project_exists`: GET returns 200; assert no POST call recorded.
- [ ] Write `test_create_project_posts_multipart_with_icon`: GET returns 404; assert the POST body (parsed via the Task-1 splitter) contains a `data` part whose JSON matches the payload and an `icon` part with the icon bytes; assert `Authorization` header equals the token.
- [ ] Write `test_upload_gallery_image_sends_raw_body_with_query_params`: assert URL query contains `ext=png&featured=true&title=...` (order-independent parse via `urllib.parse.parse_qs`) and the POST body is the raw file bytes (no multipart).
- [ ] Write `test_create_version_posts_multipart_with_named_file_part`: payload `file_parts=["file"]`; assert the multipart file part name is `file`.
- [ ] Write `test_api_error_raised_on_4xx`: opener returns 400; assert `ApiError` raised with `.status == 400`.
- [ ] Write `test_dry_run_client_makes_no_network_calls`: construct `Client(dry_run=True, opener=<raises AssertionError>)`; call each mutating method; assert opener never invoked and each returns a placeholder/dry-run marker.
- [ ] Implement `Client` and `ApiError`. Run the suite; confirm pass.
- [ ] Commit: "feat(modrinth): HTTP client with dry-run and idempotent create".

### Task 3: gallery.md / body.md loaders + subcommand functions

**Interfaces:**
- Consumes: Task 2's `Client`.
- Produces: `parse_gallery_md(path) -> list[dict]` (each `{"path", "title", "description", "featured"}`); `cmd_create(client, args) -> int`; `cmd_gallery(client, args) -> int`; `cmd_sync_body(client, args) -> int`; `cmd_upload_version(client, args) -> int`; `cmd_submit(client, args) -> int`; `cmd_status(client, args) -> int`.

- [ ] Write `test_parse_gallery_md_reads_entries_in_order` against a small literal fixture string (temp file) with two `## path` sections and `- key: value` bullets, asserting fields and `featured` boolean parsing.
- [ ] Write `test_cmd_gallery_skips_existing_titles_by_matching_project_gallery`: `get_project` fixture returns a project whose `gallery` already has one matching title; assert only the missing entries are uploaded (spy on `upload_gallery_image` call count).
- [ ] Write `test_cmd_upload_version_rejects_sha256_mismatch`: pass `--sha256` that doesn't match the jar's actual digest; assert `ModrinthError` and no `create_version` call.
- [ ] Write `test_cmd_upload_version_skips_if_version_number_exists`: `list_versions` fixture already contains `version_number: "0.1.0"`; assert no `create_version` call.
- [ ] Write `test_cmd_submit_patches_requested_status_approved`: assert `patch_project` called with `{"requested_status": "approved"}`.
- [ ] Implement the parser and subcommand functions (each takes the parsed `argparse.Namespace` and a `Client`). Run the suite; confirm pass.
- [ ] Commit: "feat(modrinth): subcommand implementations".

### Task 4: CLI wiring + `--dry-run` prints payloads

**Interfaces:**
- Produces: `def build_parser() -> argparse.ArgumentParser`, `def main(argv=None) -> int`.

- [ ] Write `test_main_dry_run_create_prints_payload_not_token`: run `main(["create", "--dry-run"])` with `MODRINTH_TOKEN` set in the test's env; capture stdout; assert the payload JSON (slug, title, categories) is printed and the token string never appears in stdout.
- [ ] Write `test_main_status_exit_code_nonzero_on_missing_project`: `get_project` fixture returns `None`; assert `main(["status"])` returns non-zero and prints a clear "project not found" message.
- [ ] Implement `build_parser`/`main` with subparsers for `create`, `gallery`, `sync-body`, `upload-version`, `submit`, `status`, each accepting `--dry-run`; `upload-version` additionally takes `--file`, `--version-number`, `--name`, `--game-versions`, `--loaders` (default `fabric`), `--version-type` (default `release`), `--changelog-file`, `--sha256`.
- [ ] Run the full suite (`python -m unittest discover -s tools/tests`); confirm the pre-existing 61 tests plus the new ones are green.
- [ ] Commit: "feat(modrinth): CLI entry point".

### Task 5: Listing content

**Files:**
- Create: `docs/modrinth/body.md` (v0.1.0-only behaviour, disclosure paragraph from modrinth.md §5, privacy, install, links — no FPS numbers, no unverifiable claims).
- Create: `docs/modrinth/body-0.2.md` (same shape, updated for 26.2+26.3 and the v0.2 feature set per SPEC; header notes it's finalised at release).
- Create: `docs/modrinth/gallery.md` (report.png, benchmark.png, title.png, rigtune-in-world.png with accurate titles/descriptions; report.png featured).

- [ ] Draft `body.md` from `README.md`'s "What it does"/"Install"/"Use"/"Privacy" sections, restricted to what v0.1.0 actually does (MC 26.2 only; no Undo, no v0.2 UI). Include the disclosure paragraph verbatim from `docs/research/v0.2/modrinth.md` §5.
- [ ] Draft `body-0.2.md` as a copy with a "(v0.2.0, to be finalised at release)" header note and the 26.2+26.3 / v0.2 feature additions once tagged.
- [ ] Draft `gallery.md` per Task 3's parser format.
- [ ] Commit: "docs(modrinth): listing body and gallery copy".

### Task 6: Real run (after explicit confirmation) + design/F.md

- [ ] Verify token presence (`read_token()` non-None) without printing it.
- [ ] `create --dry-run`; eyeball the payload.
- [ ] `create` for real; record the returned project id.
- [ ] `gallery` for real.
- [ ] `gh release download v0.1.0 --repo chaotix345/rigtune -p rigtune-0.1.0.jar -D <scratchpad>`; verify sha256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`.
- [ ] `upload-version` for the v0.1.0 jar with `--sha256` set to the above.
- [ ] `submit`; if it fails, diagnose (don't retry blindly) and fall back to recording the exact web-UI step.
- [ ] `status`; write `docs/v0.2/design/F.md` with project id, URLs, version id, status, and anything UNVERIFIED.
- [ ] Commit docs/v0.2/design/F.md.

---

## Self-review
- SPEC item 4 / AC4.1-4.3: Tasks 1-4 (tool), Task 6 (real run + honest status reporting) cover them; Minotaur/release.yml (AC4.1's "release run") is Task 3 of the workstream brief, deferred until Phase 3 merges.
- M15/L7: Task 6 only runs after the recorded user confirmation; v0.1.0 uploads before any 0.2.0 version; body.md describes only 0.1.0 until 0.2.0 ships.
- M11: out of scope for this plan (release.yml, done post-Phase-3).
