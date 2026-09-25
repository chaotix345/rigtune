import io
import json
import sys
import tempfile
import unittest
import urllib.parse
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import modrinth_project as mp

FIXTURES = Path(__file__).resolve().parent / "fixtures"


class ScriptedOpener:
    """Records every call; each (method, url-without-query) maps to a queue
    of (status, body, headers) responses. A single-item queue replays
    forever. Raises AssertionError on an unscripted call, so tests double
    as a proof that dry-run makes no network calls."""

    def __init__(self, responses=None):
        self.responses = {key: list(seq) for key, seq in (responses or {}).items()}
        self.calls = []

    def _key(self, request):
        url = request.full_url
        base = url.split("?", 1)[0]
        return request.get_method(), base

    def __call__(self, request):
        key = self._key(request)
        self.calls.append({
            "method": request.get_method(),
            "url": request.full_url,
            "headers": {k.lower(): v for k, v in request.headers.items()},
            "data": request.data,
        })
        if key not in self.responses:
            raise AssertionError(f"unscripted request: {key}")
        seq = self.responses[key]
        return seq.pop(0) if len(seq) > 1 else seq[0]


def json_response(obj, status=200):
    return status, json.dumps(obj).encode("utf-8"), {"content-type": "application/json"}


def split_multipart(content_type, body):
    """Parses the exact wire format encode_multipart produces. Returns a
    dict keyed on the form field `name`, mapping to
    {"filename": str|None, "content_type": str|None, "content": bytes}."""
    boundary = content_type.split("boundary=", 1)[1]
    marker = f"--{boundary}".encode("utf-8")
    raw_parts = body.split(marker)
    parts = {}
    for raw in raw_parts:
        raw = raw.strip(b"\r\n")
        if not raw or raw == b"--":
            continue
        header_blob, _, content = raw.partition(b"\r\n\r\n")
        headers = header_blob.decode("utf-8")
        disposition = next(h for h in headers.split("\r\n") if h.lower().startswith("content-disposition"))
        name_match = re_search_name(disposition)
        filename_match = re_search_filename(disposition)
        content_type_match = None
        for h in headers.split("\r\n"):
            if h.lower().startswith("content-type"):
                content_type_match = h.split(":", 1)[1].strip()
        parts[name_match] = {
            "filename": filename_match,
            "content_type": content_type_match,
            "content": content.rstrip(b"\r\n"),
        }
    return parts


def re_search_name(disposition):
    import re
    m = re.search(r'name="([^"]*)"', disposition)
    return m.group(1) if m else None


def re_search_filename(disposition):
    import re
    m = re.search(r'filename="([^"]*)"', disposition)
    return m.group(1) if m else None


class EncodeMultipartTests(unittest.TestCase):
    def test_contains_field_and_file_parts(self):
        content_type, body = mp.encode_multipart(
            [("data", b'{"a":1}')],
            [("icon", "icon.png", "image/png", b"\x89PNGfake")],
        )
        parts = split_multipart(content_type, body)
        self.assertEqual(parts["data"]["content"], b'{"a":1}')
        self.assertIsNone(parts["data"]["filename"])
        self.assertEqual(parts["icon"]["filename"], "icon.png")
        self.assertEqual(parts["icon"]["content_type"], "image/png")
        self.assertEqual(parts["icon"]["content"], b"\x89PNGfake")

    def test_fields_only_no_files(self):
        content_type, body = mp.encode_multipart([("data", b"{}")], [])
        parts = split_multipart(content_type, body)
        self.assertEqual(set(parts), {"data"})


class ReadTokenTests(unittest.TestCase):
    def test_prefers_env_var(self):
        with mock.patch.dict("os.environ", {"MODRINTH_TOKEN": "x"}, clear=False):
            with mock.patch("subprocess.run", side_effect=AssertionError("must not shell out")):
                self.assertEqual(mp.read_token(), "x")

    def test_falls_back_to_powershell(self):
        env = {k: v for k, v in __import__("os").environ.items() if k != "MODRINTH_TOKEN"}
        with mock.patch.dict("os.environ", env, clear=True):
            fake = mock.Mock(stdout="tok\n")
            with mock.patch("subprocess.run", return_value=fake) as run:
                self.assertEqual(mp.read_token(), "tok")
            self.assertEqual(run.call_args.args[0][0], "pwsh")

    def test_returns_none_when_unset(self):
        env = {k: v for k, v in __import__("os").environ.items() if k != "MODRINTH_TOKEN"}
        with mock.patch.dict("os.environ", env, clear=True):
            fake = mock.Mock(stdout="")
            with mock.patch("subprocess.run", return_value=fake):
                self.assertIsNone(mp.read_token())

    def test_subprocess_failure_returns_none(self):
        env = {k: v for k, v in __import__("os").environ.items() if k != "MODRINTH_TOKEN"}
        with mock.patch.dict("os.environ", env, clear=True):
            with mock.patch("subprocess.run", side_effect=OSError("no pwsh")):
                self.assertIsNone(mp.read_token())


class ClientReadTests(unittest.TestCase):
    def test_get_project_returns_none_on_404(self):
        opener = ScriptedOpener({("GET", f"{mp.API}/project/rigtune"): [(404, b"{}", {})]})
        client = mp.Client(token="t", opener=opener)
        self.assertIsNone(client.get_project("rigtune"))

    def test_get_project_returns_dict_on_200(self):
        opener = ScriptedOpener({("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc", "slug": "rigtune"})]})
        client = mp.Client(token="t", opener=opener)
        self.assertEqual(client.get_project("rigtune"), {"id": "abc", "slug": "rigtune"})

    def test_api_error_raised_on_4xx(self):
        opener = ScriptedOpener({("GET", f"{mp.API}/project/rigtune"): [(400, b"bad", {})]})
        client = mp.Client(token="t", opener=opener)
        with self.assertRaises(mp.ApiError) as cm:
            client.get_project("rigtune")
        self.assertEqual(cm.exception.status, 400)

    def test_list_versions(self):
        opener = ScriptedOpener({("GET", f"{mp.API}/project/abc/version"): [json_response([{"id": "v1"}])]})
        client = mp.Client(token="t", opener=opener)
        self.assertEqual(client.list_versions("abc"), [{"id": "v1"}])


class CreateProjectTests(unittest.TestCase):
    def test_noop_when_project_exists(self):
        opener = ScriptedOpener({("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc", "slug": "rigtune", "status": "draft"})]})
        client = mp.Client(token="t", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_create(client, _ns(body_file=_body_fixture(), icon=None, dry_run=False))
        self.assertEqual(rc, 0)
        self.assertEqual(len(opener.calls), 1)
        self.assertIn("already exists", buf.getvalue())

    def test_posts_multipart_with_icon_when_missing(self):
        icon = _tmp_file(b"\x89PNGdata")
        create_resp = json_response({"id": "new123", "slug": "rigtune"})
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [(404, b"{}", {})],
            ("POST", f"{mp.API}/project"): [create_resp],
        })
        client = mp.Client(token="secret-token", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_create(client, _ns(body_file=_body_fixture(), icon=str(icon), dry_run=False))
        self.assertEqual(rc, 0)
        post_call = next(c for c in opener.calls if c["method"] == "POST")
        self.assertEqual(post_call["headers"]["authorization"], "secret-token")
        content_type = post_call["headers"]["content-type"]
        parts = split_multipart(content_type, post_call["data"])
        payload = json.loads(parts["data"]["content"])
        self.assertEqual(payload["slug"], "rigtune")
        self.assertEqual(payload["title"], "RigTune")
        self.assertEqual(payload["categories"], ["optimization"])
        self.assertEqual(payload["additional_categories"], ["utility"])
        self.assertEqual(payload["client_side"], "required")
        self.assertEqual(payload["server_side"], "unsupported")
        self.assertEqual(payload["license_id"], "MIT")
        self.assertEqual(parts["icon"]["content"], b"\x89PNGdata")
        self.assertIn("created project", buf.getvalue())

    def test_dry_run_makes_no_network_calls(self):
        def exploding_opener(request):
            raise AssertionError("dry-run must not touch the network")
        client = mp.Client(token=None, opener=exploding_opener, dry_run=True)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_create(client, _ns(body_file=_body_fixture(), icon=None, dry_run=True))
        self.assertEqual(rc, 0)
        self.assertIn("would_create", buf.getvalue())
        self.assertNotIn("secret", buf.getvalue())


class GalleryTests(unittest.TestCase):
    def setUp(self):
        self.gallery_md = _tmp_file(
            (
                "## docs/images/report.png\n"
                "- title: RigTune report\n"
                "- description: The report screen\n"
                "- featured: true\n"
                "\n"
                "## docs/images/benchmark.png\n"
                "- title: Benchmark results\n"
                "- description: The benchmark results screen\n"
            ).encode("utf-8"),
            suffix=".md",
        )

    def test_parse_gallery_md(self):
        entries = mp.parse_gallery_md(self.gallery_md)
        self.assertEqual(len(entries), 2)
        self.assertEqual(entries[0], {
            "path": "docs/images/report.png", "title": "RigTune report",
            "description": "The report screen", "featured": True,
        })
        self.assertEqual(entries[1]["featured"], False)

    def test_skips_existing_titles(self):
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({
                "id": "abc", "gallery": [{"title": "RigTune report"}],
            })],
            ("POST", f"{mp.API}/project/abc/gallery"): [(204, b"", {})],
        })
        client = mp.Client(token="t", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_gallery(client, _ns(gallery_file=self.gallery_md, dry_run=False))
        self.assertEqual(rc, 0)
        posts = [c for c in opener.calls if c["method"] == "POST"]
        self.assertEqual(len(posts), 1)
        self.assertIn("skip", buf.getvalue())

    def test_upload_sends_raw_body_with_query_params(self):
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc", "gallery": []})],
            ("POST", f"{mp.API}/project/abc/gallery"): [(204, b"", {}), (204, b"", {})],
        })
        client = mp.Client(token="t", opener=opener)
        with redirect_stdout(io.StringIO()):
            mp.cmd_gallery(client, _ns(gallery_file=self.gallery_md, dry_run=False))
        first_post = next(c for c in opener.calls if c["method"] == "POST")
        query = urllib.parse.urlparse(first_post["url"]).query
        params = urllib.parse.parse_qs(query)
        self.assertEqual(params["ext"], ["png"])
        self.assertEqual(params["featured"], ["true"])
        self.assertEqual(params["title"], ["RigTune report"])
        self.assertFalse(first_post["data"].startswith(b"--"))

    def test_dry_run_no_network(self):
        def exploding_opener(request):
            raise AssertionError("dry-run must not touch the network")
        client = mp.Client(opener=exploding_opener, dry_run=True)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_gallery(client, _ns(gallery_file=self.gallery_md, dry_run=True))
        self.assertEqual(rc, 0)
        self.assertIn("would_upload", buf.getvalue())


class SyncBodyTests(unittest.TestCase):
    def test_patches_body_field(self):
        body_file = _body_fixture()
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc"})],
            ("PATCH", f"{mp.API}/project/abc"): [(204, b"", {})],
        })
        client = mp.Client(token="t", opener=opener)
        with redirect_stdout(io.StringIO()):
            rc = mp.cmd_sync_body(client, _ns(body_file=body_file, dry_run=False))
        self.assertEqual(rc, 0)
        patch_call = next(c for c in opener.calls if c["method"] == "PATCH")
        self.assertEqual(json.loads(patch_call["data"]), {"body": Path(body_file).read_text(encoding="utf-8")})


class UploadVersionTests(unittest.TestCase):
    def _args(self, **overrides):
        base = dict(
            file=None, version_number="0.1.0", name="RigTune 0.1.0",
            game_versions="26.2", loaders="fabric", version_type="release",
            changelog_file=None, sha256=None, dry_run=False,
        )
        base.update(overrides)
        return _ns(**base)

    def test_rejects_sha256_mismatch(self):
        jar = _tmp_file(b"jar-bytes")
        with self.assertRaises(mp.ModrinthError):
            mp.cmd_upload_version(mp.Client(token="t", opener=lambda r: (_ for _ in ()).throw(AssertionError("no network"))),
                                   self._args(file=str(jar), sha256="0" * 64))

    def test_skips_if_version_number_exists(self):
        jar = _tmp_file(b"jar-bytes")
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc"})],
            ("GET", f"{mp.API}/project/fabric-api"): [json_response({"id": "P7dR8mSH"})],
            ("GET", f"{mp.API}/project/abc/version"): [json_response([{"id": "v1", "version_number": "0.1.0"}])],
        })
        client = mp.Client(token="t", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_upload_version(client, self._args(file=str(jar)))
        self.assertEqual(rc, 0)
        self.assertNotIn(("POST", f"{mp.API}/version"), [(c["method"], c["url"].split("?")[0]) for c in opener.calls])
        self.assertIn("already exists", buf.getvalue())

    def test_uploads_with_named_file_part_and_dependency(self):
        jar = _tmp_file(b"jar-bytes")
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc"})],
            ("GET", f"{mp.API}/project/fabric-api"): [json_response({"id": "P7dR8mSH"})],
            ("GET", f"{mp.API}/project/abc/version"): [json_response([])],
            ("POST", f"{mp.API}/version"): [json_response({"id": "verid", "version_number": "0.1.0"})],
        })
        client = mp.Client(token="t", opener=opener)
        with redirect_stdout(io.StringIO()):
            rc = mp.cmd_upload_version(client, self._args(file=str(jar)))
        self.assertEqual(rc, 0)
        post_call = next(c for c in opener.calls if c["method"] == "POST")
        parts = split_multipart(post_call["headers"]["content-type"], post_call["data"])
        self.assertEqual(parts["file"]["content"], b"jar-bytes")
        payload = json.loads(parts["data"]["content"])
        self.assertEqual(payload["project_id"], "abc")
        self.assertEqual(payload["game_versions"], ["26.2"])
        self.assertEqual(payload["loaders"], ["fabric"])
        # Modrinth wants the base62 project id, not the slug.
        self.assertEqual(payload["dependencies"][0]["project_id"], "P7dR8mSH")
        self.assertEqual(payload["dependencies"][0]["dependency_type"], "required")

    def test_dry_run_no_network(self):
        jar = _tmp_file(b"jar-bytes")
        def exploding_opener(request):
            raise AssertionError("dry-run must not touch the network")
        client = mp.Client(opener=exploding_opener, dry_run=True)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_upload_version(client, self._args(file=str(jar), dry_run=True))
        self.assertEqual(rc, 0)
        self.assertIn("would_upload_version", buf.getvalue())


class SubmitTests(unittest.TestCase):
    def test_patches_requested_status_approved(self):
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc"})],
            ("PATCH", f"{mp.API}/project/abc"): [(204, b"", {}), (204, b"", {})],
        })
        client = mp.Client(token="t", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_submit(client, _ns(dry_run=False))
        self.assertEqual(rc, 0)
        patches = [json.loads(c["data"]) for c in opener.calls if c["method"] == "PATCH"]
        self.assertEqual(patches, [{"client_side": "required", "server_side": "unsupported"}, {"requested_status": "approved"}])
        self.assertIn("submitted for review", buf.getvalue())

    def test_moves_a_draft_to_processing(self):
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({"id": "abc", "status": "draft"})],
            ("PATCH", f"{mp.API}/project/abc"): [(204, b"", {}), (204, b"", {}), (204, b"", {})],
        })
        client = mp.Client(token="t", opener=opener)
        with redirect_stdout(io.StringIO()):
            rc = mp.cmd_submit(client, _ns(dry_run=False))
        self.assertEqual(rc, 0)
        patches = [json.loads(c["data"]) for c in opener.calls if c["method"] == "PATCH"]
        self.assertEqual(patches, [{"client_side": "required", "server_side": "unsupported"},
                                   {"requested_status": "approved"}, {"status": "processing"}])


class StatusTests(unittest.TestCase):
    def test_reports_not_found(self):
        opener = ScriptedOpener({("GET", f"{mp.API}/project/rigtune"): [(404, b"{}", {})]})
        client = mp.Client(token="t", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_status(client, _ns())
        self.assertEqual(rc, 1)
        self.assertIn("not found", buf.getvalue())

    def test_reports_project_and_versions(self):
        opener = ScriptedOpener({
            ("GET", f"{mp.API}/project/rigtune"): [json_response({
                "id": "abc", "slug": "rigtune", "status": "draft", "requested_status": None,
                "title": "RigTune", "license": {"id": "MIT"}, "source_url": "x", "issues_url": "y",
                "gallery": [{"title": "a"}],
            })],
            ("GET", f"{mp.API}/project/abc/version"): [json_response([
                {"id": "v1", "version_number": "0.1.0", "game_versions": ["26.2"], "loaders": ["fabric"], "status": "listed"},
            ])],
        })
        client = mp.Client(token="t", opener=opener)
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = mp.cmd_status(client, _ns())
        self.assertEqual(rc, 0)
        out = buf.getvalue()
        self.assertIn("id=abc", out)
        self.assertIn("version id=v1", out)


class MainCliTests(unittest.TestCase):
    def test_dry_run_create_prints_payload_not_token(self):
        buf = io.StringIO()
        with mock.patch.dict("os.environ", {"MODRINTH_TOKEN": "super-secret-token"}, clear=False):
            with redirect_stdout(buf):
                rc = mp.main(["create", "--dry-run", "--body-file", _body_fixture()])
        self.assertEqual(rc, 0)
        out = buf.getvalue()
        self.assertIn('"slug": "rigtune"', out)
        self.assertNotIn("super-secret-token", out)

    def test_missing_token_without_dry_run_exits_nonzero(self):
        env = {k: v for k, v in __import__("os").environ.items() if k != "MODRINTH_TOKEN"}
        with mock.patch.dict("os.environ", env, clear=True):
            with mock.patch("subprocess.run", return_value=mock.Mock(stdout="")):
                with redirect_stdout(io.StringIO()), mock.patch("sys.stderr", io.StringIO()):
                    rc = mp.main(["status"])
        self.assertEqual(rc, 2)


def _tmp_file(data, suffix=""):
    fd = tempfile.NamedTemporaryFile(delete=False, suffix=suffix)
    fd.write(data)
    fd.close()
    return fd.name


def _body_fixture():
    return _tmp_file(b"# RigTune\n\nBody text.\n", suffix=".md")


class _Namespace:
    def __init__(self, **kwargs):
        self.__dict__.update(kwargs)


def _ns(**kwargs):
    return _Namespace(**kwargs)


if __name__ == "__main__":
    unittest.main()
