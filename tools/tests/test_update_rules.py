import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import update_rules as ur

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def load_fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def json_body(obj):
    return json.dumps(obj).encode("utf-8")


class ScriptedOpener:
    """Records calls; each url maps to a queue of (status, body, headers) responses.
    A single-item queue is replayed forever (so repeated GETs of a stable resource work)."""

    def __init__(self, responses):
        self.responses = {url: list(seq) for url, seq in responses.items()}
        self.calls = []

    def __call__(self, request):
        url = request.full_url
        self.calls.append(url)
        if url not in self.responses:
            raise AssertionError(f"unscripted request to {url}")
        seq = self.responses[url]
        return seq.pop(0) if len(seq) > 1 else seq[0]


class RecordingSleeper:
    def __init__(self):
        self.waits = []

    def __call__(self, seconds):
        self.waits.append(seconds)


class HttpGetRetryTests(unittest.TestCase):
    def test_retries_on_429_then_succeeds(self):
        opener = ScriptedOpener({
            "https://x.test/a": [
                (429, b"", {}),
                (429, b"", {}),
                (200, b"ok", {}),
            ]
        })
        sleeper = RecordingSleeper()
        body = ur.http_get("https://x.test/a", opener=opener, sleeper=sleeper)
        self.assertEqual(body, b"ok")
        self.assertEqual(len(sleeper.waits), 2)
        self.assertEqual(len(opener.calls), 3)

    def test_respects_retry_after_header(self):
        opener = ScriptedOpener({
            "https://x.test/b": [
                (429, b"", {"retry-after": "5"}),
                (200, b"ok", {}),
            ]
        })
        sleeper = RecordingSleeper()
        ur.http_get("https://x.test/b", opener=opener, sleeper=sleeper)
        self.assertEqual(sleeper.waits[0], 5.0)

    def test_retries_on_connection_error(self):
        calls = {"n": 0}

        def flaky_opener(request):
            calls["n"] += 1
            if calls["n"] < 3:
                raise ConnectionError("boom")
            return 200, b"ok", {}

        sleeper = RecordingSleeper()
        body = ur.http_get("https://x.test/c", opener=flaky_opener, sleeper=sleeper)
        self.assertEqual(body, b"ok")
        self.assertEqual(calls["n"], 3)
        self.assertEqual(len(sleeper.waits), 2)

    def test_gives_up_after_max_attempts(self):
        opener = ScriptedOpener({"https://x.test/d": [(500, b"", {})]})
        sleeper = RecordingSleeper()
        with self.assertRaises(ur.HttpStatusError):
            ur.http_get("https://x.test/d", opener=opener, sleeper=sleeper, max_attempts=3)
        self.assertEqual(len(opener.calls), 3)

    def test_non_retryable_status_raises_immediately(self):
        opener = ScriptedOpener({"https://x.test/e": [(404, b"", {})]})
        sleeper = RecordingSleeper()
        with self.assertRaises(ur.HttpStatusError) as ctx:
            ur.http_get("https://x.test/e", opener=opener, sleeper=sleeper)
        self.assertEqual(ctx.exception.status, 404)
        self.assertEqual(len(sleeper.waits), 0)

    def test_github_rate_limit_403_is_retried(self):
        opener = ScriptedOpener({
            "https://x.test/f": [
                (403, b"", {"x-ratelimit-remaining": "0", "x-ratelimit-reset": str(int(__import__("time").time()) - 1)}),
                (200, b"ok", {}),
            ]
        })
        sleeper = RecordingSleeper()
        body = ur.http_get("https://x.test/f", opener=opener, sleeper=sleeper)
        self.assertEqual(body, b"ok")
        self.assertEqual(len(sleeper.waits), 1)


class MissingPackwizFolderTests(unittest.TestCase):
    def test_list_pw_toml_filenames_returns_none_on_404(self):
        opener = ScriptedOpener({ur.fo_contents_url("26.3"): [(404, b"Not Found", {})]})
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        result = ur.list_pw_toml_filenames(client, ur.fo_contents_url, "26.3")
        self.assertIsNone(result)

    def test_pack_ids_by_version_skips_missing_folder(self):
        entries_262 = [{"type": "file", "name": "sodium.pw.toml"}]
        sodium_toml = 'name = "Sodium"\n[update]\n[update.modrinth]\nmod-id = "AANobbMI"\n'
        opener = ScriptedOpener({
            ur.fo_contents_url("26.2"): [(200, json_body(entries_262), {})],
            ur.fo_raw_url("26.2", "sodium.pw.toml"): [(200, sodium_toml.encode(), {})],
            ur.fo_contents_url("26.3"): [(404, b"", {})],
        })
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        result = ur.pack_ids_by_version(client, ur.PACKS["fabulouslyOptimized"], ["26.3", "26.2"])
        self.assertIsNone(result["26.3"])
        self.assertEqual(result["26.2"], {"AANobbMI"})

    def test_newest_available_version_skips_none(self):
        ids_by_version = {"26.3": None, "26.2": {"AANobbMI"}}
        self.assertEqual(ur.newest_available_version(["26.3", "26.2"], ids_by_version), "26.2")

    def test_newest_available_version_all_missing(self):
        ids_by_version = {"26.3": None, "26.2": None}
        self.assertIsNone(ur.newest_available_version(["26.3", "26.2"], ids_by_version))


class AvailabilityTests(unittest.TestCase):
    def test_computes_slugs_with_fabric_release_per_version(self):
        rule_mods = [
            {"slug": "sodium", "projectId": "AANobbMI"},
            {"slug": "lithium", "projectId": "gvQqBUqZ"},
            {"slug": "moreculling", "projectId": "51shyZVL"},
        ]
        projects_by_id = {
            "AANobbMI": {"slug": "sodium", "game_versions": ["26.2", "26.3"], "loaders": ["fabric"]},
            "gvQqBUqZ": {"slug": "lithium", "game_versions": ["26.2"], "loaders": ["fabric"]},
            "51shyZVL": {"slug": "moreculling", "game_versions": ["26.2", "26.3"], "loaders": ["forge"]},
        }
        availability = ur.compute_availability(rule_mods, projects_by_id, ["26.2", "26.3"])
        self.assertEqual(availability["26.2"], ["lithium", "sodium"])
        self.assertEqual(availability["26.3"], ["sodium"])

    def test_missing_project_is_unavailable_everywhere(self):
        rule_mods = [{"slug": "ghost", "projectId": "nope"}]
        availability = ur.compute_availability(rule_mods, {}, ["26.2"])
        self.assertEqual(availability["26.2"], [])


class UpstreamFlagTests(unittest.TestCase):
    def test_mods_with_upstream_flags(self):
        rule_mods = [{"slug": "sodium"}, {"slug": "krypton"}]
        merged = ur.mods_with_upstream(rule_mods, fo_slugs={"sodium"}, additive_slugs={"sodium", "krypton"})
        by_slug = {m["slug"]: m["upstream"] for m in merged}
        self.assertEqual(by_slug["sodium"], {"fabulouslyOptimized": True, "additive": True})
        self.assertEqual(by_slug["krypton"], {"fabulouslyOptimized": False, "additive": True})

    def test_top_level_upstream_shape(self):
        result = ur.top_level_upstream("26.2", {"b", "a"}, "26.3", set())
        self.assertEqual(result["fabulouslyOptimized"], {"mcVersion": "26.2", "slugs": ["a", "b"]})
        self.assertEqual(result["additive"], {"mcVersion": "26.3", "slugs": []})

    def test_slugs_for_version_none_is_empty(self):
        self.assertEqual(ur.slugs_for_version({}, None, {}), set())


class ReviewMarkdownTests(unittest.TestCase):
    def setUp(self):
        self.rule_mods = load_fixture("knowledge_sample.json")["mods"]
        self.projects_by_id = {
            "AANobbMI": {"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "status": "approved", "categories": ["optimization"]},
            "gvQqBUqZ": {"id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium", "status": "archived", "categories": ["optimization"]},
            "51shyZVL": {"id": "51shyZVL", "slug": "moreculling", "title": "MoreCulling", "status": "approved", "categories": ["optimization"]},
            "newid001": {"id": "newid001", "slug": "nvidium", "title": "Nvidium", "status": "approved", "categories": ["optimization"]},
        }
        self.availability = {"26.2": ["sodium", "moreculling"]}

    def test_new_upstream_mod_listed_with_packs_and_optimization_flag(self):
        md, counts = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium", "lithium", "moreculling", "nvidium"},
            additive_slugs={"sodium", "nvidium"},
            projects_by_id=self.projects_by_id,
            availability=self.availability,
            old_mods_by_slug=None,
        )
        self.assertEqual(counts["new_upstream"], 1)
        self.assertIn("nvidium", md)
        self.assertIn("Nvidium", md)
        self.assertIn("Fabulously Optimized, Additive", md)
        self.assertIn("yes", md)

    def test_status_issue_listed(self):
        md, counts = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium"}, additive_slugs={"sodium"},
            projects_by_id=self.projects_by_id,
            availability=self.availability,
            old_mods_by_slug=None,
        )
        self.assertGreaterEqual(counts["status_or_removed"], 1)
        self.assertIn("lithium", md)
        self.assertIn("status: archived", md)

    def test_missing_fabric_release_listed(self):
        md, counts = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium"}, additive_slugs={"sodium"},
            projects_by_id=self.projects_by_id,
            availability=self.availability,
            old_mods_by_slug=None,
        )
        self.assertEqual(counts["missing_fabric"], 1)
        self.assertIn("lithium", md)

    def test_removed_from_both_packs_detected_via_old_doc(self):
        old = load_fixture("old_rules_sample.json")
        old_mods_by_slug = {m["slug"]: m.get("upstream", {}) for m in old["mods"]}
        rule_mods = [{"slug": "oldmod", "title": "OldMod", "projectId": "zzzzzzzz"}]
        projects_by_id = {"zzzzzzzz": {"id": "zzzzzzzz", "slug": "oldmod", "title": "OldMod", "status": "approved", "categories": []}}
        md, counts = ur.build_review(
            rule_mods, ["26.2"], "26.2",
            fo_slugs=set(), additive_slugs=set(),
            projects_by_id=projects_by_id,
            availability={"26.2": []},
            old_mods_by_slug=old_mods_by_slug,
        )
        self.assertIn("removed from both Fabulously Optimized and Additive", md)
        self.assertGreaterEqual(counts["status_or_removed"], 1)

    def test_no_history_note_when_old_doc_absent(self):
        md, _ = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium"}, additive_slugs={"sodium"},
            projects_by_id=self.projects_by_id,
            availability=self.availability,
            old_mods_by_slug=None,
        )
        self.assertIn("No previous rules-v1.json", md)

    def test_none_found_when_everything_clean(self):
        clean_projects = {
            "AANobbMI": {"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "status": "approved", "categories": []},
            "gvQqBUqZ": {"id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium", "status": "approved", "categories": []},
            "51shyZVL": {"id": "51shyZVL", "slug": "moreculling", "title": "MoreCulling", "status": "approved", "categories": []},
        }
        availability = {"26.2": ["sodium", "lithium", "moreculling"]}
        md, counts = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium", "lithium", "moreculling"},
            additive_slugs={"sodium", "lithium", "moreculling"},
            projects_by_id=clean_projects,
            availability=availability,
            old_mods_by_slug={},
        )
        self.assertEqual(counts, {"new_upstream": 0, "status_or_removed": 0, "missing_fabric": 0})
        self.assertEqual(md.count("None found."), 3)


class ReviewIgnoreTests(unittest.TestCase):
    def setUp(self):
        self.rule_mods = load_fixture("knowledge_sample.json")["mods"]
        self.projects_by_id = {
            "AANobbMI": {"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "status": "approved", "categories": ["optimization"]},
            "gvQqBUqZ": {"id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium", "status": "approved", "categories": ["optimization"]},
            "51shyZVL": {"id": "51shyZVL", "slug": "moreculling", "title": "MoreCulling", "status": "approved", "categories": ["optimization"]},
            "newid001": {"id": "newid001", "slug": "nvidium", "title": "Nvidium", "status": "approved", "categories": ["optimization"]},
            "newid002": {"id": "newid002", "slug": "servercore", "title": "ServerCore", "status": "approved", "categories": ["optimization"]},
        }
        self.availability = {"26.2": ["sodium", "moreculling"]}

    def test_build_review_excludes_reviewignore_slugs_from_new_upstream(self):
        md, counts = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium", "lithium", "moreculling", "nvidium", "servercore"},
            additive_slugs={"sodium", "nvidium", "servercore"},
            projects_by_id=self.projects_by_id,
            availability=self.availability,
            old_mods_by_slug=None,
            review_ignore_slugs={"servercore"},
        )
        self.assertEqual(counts["new_upstream"], 1)
        self.assertIn("nvidium", md)
        self.assertNotIn("servercore", md)

    def test_build_review_default_review_ignore_is_empty(self):
        md, counts = ur.build_review(
            self.rule_mods, ["26.2"], "26.2",
            fo_slugs={"sodium", "lithium", "moreculling", "nvidium", "servercore"},
            additive_slugs={"sodium", "nvidium", "servercore"},
            projects_by_id=self.projects_by_id,
            availability=self.availability,
            old_mods_by_slug=None,
        )
        self.assertEqual(counts["new_upstream"], 2)
        self.assertIn("servercore", md)


class ReviewIgnoreKnowledgeLoadingTests(unittest.TestCase):
    def test_load_knowledge_accepts_reviewignore(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "knowledge.json"
            data = load_fixture("knowledge_sample.json")
            data["reviewIgnore"] = [{"slug": "servercore", "reason": "server-only"}]
            path.write_text(json.dumps(data), encoding="utf-8")
            loaded = ur.load_knowledge(path)
            self.assertEqual(loaded["reviewIgnore"], [{"slug": "servercore", "reason": "server-only"}])

    def test_load_knowledge_rejects_reviewignore_not_a_list(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "knowledge.json"
            data = load_fixture("knowledge_sample.json")
            data["reviewIgnore"] = {"slug": "servercore", "reason": "server-only"}
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ur.KnowledgeError):
                ur.load_knowledge(path)

    def test_load_knowledge_rejects_reviewignore_entry_missing_reason(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "knowledge.json"
            data = load_fixture("knowledge_sample.json")
            data["reviewIgnore"] = [{"slug": "servercore"}]
            path.write_text(json.dumps(data), encoding="utf-8")
            with self.assertRaises(ur.KnowledgeError):
                ur.load_knowledge(path)


class ReviewIgnoreNotCopiedToOutputTests(unittest.TestCase):
    def test_assemble_content_excludes_reviewignore(self):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["reviewIgnore"] = [{"slug": "servercore", "reason": "server-only"}]
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})
        self.assertNotIn("reviewIgnore", content)


class RevisionBumpTests(unittest.TestCase):
    def test_first_run_has_no_old_doc_gets_revision_1(self):
        content = {"schemaVersion": 1, "mods": []}
        final = ur.finalize_document(content, None)
        self.assertEqual(final["revision"], 1)
        self.assertIn("generatedAt", final)

    def test_unchanged_content_keeps_old_file(self):
        old = load_fixture("old_rules_sample.json")
        content = ur.strip_meta(old)
        final = ur.finalize_document(content, old)
        self.assertIsNone(final)

    def test_changed_content_bumps_revision(self):
        old = load_fixture("old_rules_sample.json")
        content = ur.strip_meta(old)
        content = dict(content)
        content["mods"] = content["mods"] + [{"slug": "new-mod"}]
        final = ur.finalize_document(content, old)
        self.assertIsNotNone(final)
        self.assertEqual(final["revision"], old["revision"] + 1)

    def test_ignores_generatedAt_and_revision_when_comparing(self):
        old = load_fixture("old_rules_sample.json")
        content = ur.strip_meta(old)
        # same content, different revision/generatedAt on the "old" side shouldn't matter
        old_variant = dict(old)
        old_variant["revision"] = 999
        old_variant["generatedAt"] = "2020-01-01T00:00:00Z"
        final = ur.finalize_document(content, old_variant)
        self.assertIsNone(final)

    def test_key_order_is_schemaVersion_revision_generatedAt_first(self):
        final = ur.finalize_document({"schemaVersion": 1, "mods": []}, None)
        keys = list(final.keys())
        self.assertEqual(keys[:3], ["schemaVersion", "revision", "generatedAt"])


class WriteOutputTests(unittest.TestCase):
    def test_write_json_uses_two_space_indent_and_trailing_newline(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "out.json"
            ur.write_json(path, {"a": 1, "b": [1, 2]})
            text = path.read_text(encoding="utf-8")
            self.assertTrue(text.endswith("\n"))
            self.assertFalse(text.endswith("\n\n"))
            self.assertIn('\n  "a": 1', text)
            self.assertNotIn("\r\n", text)

    def test_write_text_adds_trailing_newline(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "REVIEW.md"
            ur.write_text(path, "hello")
            self.assertEqual(path.read_bytes(), b"hello\n")


class KnowledgeLoadingTests(unittest.TestCase):
    def test_missing_file_raises_knowledge_error(self):
        with self.assertRaises(ur.KnowledgeError):
            ur.load_knowledge(Path("does/not/exist.json"))

    def test_invalid_json_raises_knowledge_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.json"
            path.write_text("{not valid json", encoding="utf-8")
            with self.assertRaises(ur.KnowledgeError):
                ur.load_knowledge(path)

    def test_missing_mods_array_raises_knowledge_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "bad.json"
            path.write_text(json.dumps({"gpuTiers": []}), encoding="utf-8")
            with self.assertRaises(ur.KnowledgeError):
                ur.load_knowledge(path)

    def test_valid_fixture_loads(self):
        knowledge = ur.load_knowledge(FIXTURES / "knowledge_sample.json")
        self.assertEqual(len(knowledge["mods"]), 3)

    def test_main_exits_2_on_missing_knowledge(self):
        code = ur.main(["--knowledge", str(FIXTURES / "does-not-exist.json")])
        self.assertEqual(code, 2)


class MainExitCodeTests(unittest.TestCase):
    def test_main_exits_2_on_invalid_knowledge_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "knowledge.json"
            bad.write_text("not json", encoding="utf-8")
            code = ur.main(["--knowledge", str(bad)])
            self.assertEqual(code, 2)


class OfflineFixtureOpenerTests(unittest.TestCase):
    def test_fixture_opener_reads_keyed_response(self):
        with tempfile.TemporaryDirectory() as tmp:
            fixtures_dir = Path(tmp)
            url = "https://api.modrinth.com/v2/tag/game_version"
            key = ur.fixture_key(url)
            (fixtures_dir / f"{key}.json").write_text(
                json.dumps({"status": 200, "body": [{"version": "26.2", "version_type": "release"}]}),
                encoding="utf-8",
            )
            opener = ur.fixture_opener(fixtures_dir)
            request = type("Req", (), {"full_url": url})()
            status, body, headers = opener(request)
            self.assertEqual(status, 200)
            self.assertEqual(json.loads(body), [{"version": "26.2", "version_type": "release"}])

    def test_fixture_opener_missing_fixture_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            opener = ur.fixture_opener(Path(tmp))
            request = type("Req", (), {"full_url": "https://x.test/missing"})()
            with self.assertRaises(ur.UpdateRulesError):
                opener(request)


class VersionSortTests(unittest.TestCase):
    def test_resolve_target_versions_with_override(self):
        client = ur.Client(opener=lambda r: (_ for _ in ()).throw(AssertionError("should not fetch")))
        result = ur.resolve_target_versions(client, "26.2,26.3,26.1")
        self.assertEqual(result, ["26.3", "26.2", "26.1"])

    def test_resolve_target_versions_auto_detects_top_3_releases(self):
        tags = [
            {"version": "26.3", "version_type": "release"},
            {"version": "26.3-rc1", "version_type": "snapshot"},
            {"version": "26.2", "version_type": "release"},
            {"version": "26.1", "version_type": "release"},
            {"version": "26.0", "version_type": "release"},
        ]
        opener = ScriptedOpener({f"{ur.MODRINTH_API}/tag/game_version": [(200, json_body(tags), {})]})
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        result = ur.resolve_target_versions(client, None)
        self.assertEqual(result, ["26.3", "26.2", "26.1"])


class EndToEndPipelineTests(unittest.TestCase):
    def test_run_pipeline_end_to_end_with_scripted_client(self):
        knowledge = load_fixture("knowledge_sample.json")

        fo_entries = [{"type": "file", "name": "sodium.pw.toml"}, {"type": "file", "name": "lithium.pw.toml"}]
        additive_entries = [{"type": "file", "name": "sodium.pw.toml"}]

        def pw(mod_id):
            return f'name = "x"\n[update]\n[update.modrinth]\nmod-id = "{mod_id}"\n'.encode()

        responses = {
            ur.fo_contents_url("26.2"): [(200, json_body(fo_entries), {})],
            ur.fo_raw_url("26.2", "sodium.pw.toml"): [(200, pw("AANobbMI"), {})],
            ur.fo_raw_url("26.2", "lithium.pw.toml"): [(200, pw("gvQqBUqZ"), {})],
            ur.additive_contents_url("26.2"): [(200, json_body(additive_entries), {})],
            ur.additive_raw_url("26.2", "sodium.pw.toml"): [(200, pw("AANobbMI"), {})],
        }
        projects = [
            {"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "status": "approved", "categories": ["optimization"], "game_versions": ["26.2"], "loaders": ["fabric"]},
            {"id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium", "status": "approved", "categories": ["optimization"], "game_versions": ["26.2"], "loaders": ["fabric"]},
            {"id": "51shyZVL", "slug": "moreculling", "title": "MoreCulling", "status": "approved", "categories": ["optimization"], "game_versions": [], "loaders": []},
        ]
        import urllib.parse as up
        ids = sorted({"AANobbMI", "gvQqBUqZ", "51shyZVL"})
        batch_url = f"{ur.MODRINTH_API}/projects?ids={up.quote(json.dumps(ids), safe='')}"
        responses[batch_url] = [(200, json_body(projects), {})]

        opener = ScriptedOpener(responses)
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())

        content, review_md, counts, mc_versions, newest_by_pack, fo_slugs, additive_slugs = ur.run_pipeline(
            knowledge, client, "26.2", old_doc=None,
        )

        self.assertEqual(mc_versions, ["26.2"])
        self.assertEqual(newest_by_pack["fabulouslyOptimized"], "26.2")
        self.assertEqual(fo_slugs, {"sodium", "lithium"})
        self.assertEqual(additive_slugs, {"sodium"})

        mods_by_slug = {m["slug"]: m for m in content["mods"]}
        self.assertEqual(mods_by_slug["sodium"]["upstream"], {"fabulouslyOptimized": True, "additive": True})
        self.assertEqual(mods_by_slug["lithium"]["upstream"], {"fabulouslyOptimized": True, "additive": False})
        self.assertEqual(mods_by_slug["moreculling"]["upstream"], {"fabulouslyOptimized": False, "additive": False})

        self.assertEqual(content["availability"]["26.2"], ["lithium", "sodium"])
        self.assertEqual(content["upstream"]["fabulouslyOptimized"]["slugs"], ["lithium", "sodium"])
        self.assertEqual(counts["missing_fabric"], 1)
        self.assertIn("moreculling", review_md)

    def test_run_pipeline_honors_review_ignore(self):
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["reviewIgnore"] = [{"slug": "servercore", "reason": "server-only"}]

        fo_entries = [{"type": "file", "name": "sodium.pw.toml"}, {"type": "file", "name": "servercore.pw.toml"}]
        additive_entries = [{"type": "file", "name": "sodium.pw.toml"}]

        def pw(mod_id):
            return f'name = "x"\n[update]\n[update.modrinth]\nmod-id = "{mod_id}"\n'.encode()

        responses = {
            ur.fo_contents_url("26.2"): [(200, json_body(fo_entries), {})],
            ur.fo_raw_url("26.2", "sodium.pw.toml"): [(200, pw("AANobbMI"), {})],
            ur.fo_raw_url("26.2", "servercore.pw.toml"): [(200, pw("newid002"), {})],
            ur.additive_contents_url("26.2"): [(200, json_body(additive_entries), {})],
            ur.additive_raw_url("26.2", "sodium.pw.toml"): [(200, pw("AANobbMI"), {})],
        }
        projects = [
            {"id": "AANobbMI", "slug": "sodium", "title": "Sodium", "status": "approved", "categories": ["optimization"], "game_versions": ["26.2"], "loaders": ["fabric"]},
            {"id": "gvQqBUqZ", "slug": "lithium", "title": "Lithium", "status": "approved", "categories": ["optimization"], "game_versions": ["26.2"], "loaders": ["fabric"]},
            {"id": "51shyZVL", "slug": "moreculling", "title": "MoreCulling", "status": "approved", "categories": ["optimization"], "game_versions": [], "loaders": []},
            {"id": "newid002", "slug": "servercore", "title": "ServerCore", "status": "approved", "categories": ["optimization"], "game_versions": ["26.2"], "loaders": ["fabric"]},
        ]
        import urllib.parse as up
        ids = sorted({"AANobbMI", "gvQqBUqZ", "51shyZVL", "newid002"})
        batch_url = f"{ur.MODRINTH_API}/projects?ids={up.quote(json.dumps(ids), safe='')}"
        responses[batch_url] = [(200, json_body(projects), {})]

        opener = ScriptedOpener(responses)
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())

        content, review_md, counts, mc_versions, newest_by_pack, fo_slugs, additive_slugs = ur.run_pipeline(
            knowledge, client, "26.2", old_doc=None,
        )

        self.assertNotIn("servercore", review_md)
        self.assertEqual(counts["new_upstream"], 0)
        self.assertNotIn("reviewIgnore", content)


if __name__ == "__main__":
    unittest.main()
