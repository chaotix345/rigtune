import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

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

    def test_respects_retry_after_as_http_date(self):
        from datetime import datetime, timedelta, timezone
        from email.utils import format_datetime
        future = datetime.now(timezone.utc) + timedelta(seconds=5)
        http_date = format_datetime(future, usegmt=True)
        opener = ScriptedOpener({
            "https://x.test/g": [
                (429, b"", {"retry-after": http_date}),
                (200, b"ok", {}),
            ]
        })
        sleeper = RecordingSleeper()
        ur.http_get("https://x.test/g", opener=opener, sleeper=sleeper)
        self.assertAlmostEqual(sleeper.waits[0], 5.0, delta=2.0)

    def test_unparsable_retry_after_falls_back_to_backoff(self):
        opener = ScriptedOpener({
            "https://x.test/h": [
                (429, b"", {"retry-after": "not-a-real-value"}),
                (200, b"ok", {}),
            ]
        })
        sleeper = RecordingSleeper()
        ur.http_get("https://x.test/h", opener=opener, sleeper=sleeper)
        self.assertEqual(sleeper.waits[0], 1.0)  # initial backoff delay

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


class ParseRetryAfterTests(unittest.TestCase):
    def test_parses_seconds(self):
        self.assertEqual(ur.parse_retry_after("120"), 120.0)

    def test_parses_http_date(self):
        from datetime import datetime, timedelta, timezone
        from email.utils import format_datetime
        future = datetime.now(timezone.utc) + timedelta(seconds=30)
        wait = ur.parse_retry_after(format_datetime(future, usegmt=True))
        self.assertAlmostEqual(wait, 30.0, delta=2.0)

    def test_none_for_missing_value(self):
        self.assertIsNone(ur.parse_retry_after(None))
        self.assertIsNone(ur.parse_retry_after(""))

    def test_none_for_unparsable_value(self):
        self.assertIsNone(ur.parse_retry_after("not-a-real-value"))


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


class CollectProjectIdsTests(unittest.TestCase):
    def test_skips_unparsable_toml_and_keeps_valid_entries(self):
        good_toml = 'name = "Sodium"\n[update]\n[update.modrinth]\nmod-id = "AANobbMI"\n'
        bad_toml = "this is not [valid toml"
        opener = ScriptedOpener({
            ur.fo_raw_url("26.2", "sodium.pw.toml"): [(200, good_toml.encode(), {})],
            ur.fo_raw_url("26.2", "broken.pw.toml"): [(200, bad_toml.encode(), {})],
        })
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        ids = ur.collect_project_ids(client, ur.fo_raw_url, "26.2", ["sodium.pw.toml", "broken.pw.toml"])
        self.assertEqual(ids, {"AANobbMI"})

    def test_skips_toml_missing_mod_id(self):
        no_mod_id_toml = 'name = "Weird"\n'
        opener = ScriptedOpener({
            ur.fo_raw_url("26.2", "weird.pw.toml"): [(200, no_mod_id_toml.encode(), {})],
        })
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        ids = ur.collect_project_ids(client, ur.fo_raw_url, "26.2", ["weird.pw.toml"])
        self.assertEqual(ids, set())


class AvailabilityTests(unittest.TestCase):
    def test_computes_slugs_with_fabric_release_per_version(self):
        rule_mods = [
            {"slug": "sodium", "projectId": "AANobbMI"},
            {"slug": "lithium", "projectId": "gvQqBUqZ"},
            {"slug": "moreculling", "projectId": "51shyZVL"},
        ]
        # Project-level fields are unions across every version ever shipped, so they
        # can look available even where a per-version check would say otherwise:
        # here sodium's project-level fields say 26.3 too, but its actual 26.3
        # version list is empty (Fabric-only for 26.2 in this fixture's story).
        projects_by_id = {
            "AANobbMI": {"slug": "sodium", "game_versions": ["26.2", "26.3"], "loaders": ["fabric"]},
            "gvQqBUqZ": {"slug": "lithium", "game_versions": ["26.2"], "loaders": ["fabric"]},
            "51shyZVL": {"slug": "moreculling", "game_versions": ["26.2", "26.3"], "loaders": ["forge"]},
        }
        opener = ScriptedOpener({
            ur.modrinth_version_list_url("AANobbMI", "26.2"): [(200, json_body([{"id": "v1"}]), {})],
            ur.modrinth_version_list_url("AANobbMI", "26.3"): [(200, json_body([]), {})],
            ur.modrinth_version_list_url("gvQqBUqZ", "26.2"): [(200, json_body([{"id": "v2"}]), {})],
        })
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        availability = ur.compute_availability(client, rule_mods, projects_by_id, ["26.2", "26.3"])
        self.assertEqual(availability["26.2"], ["lithium", "sodium"])
        self.assertEqual(availability["26.3"], [])
        # moreculling is forge-only at the project level, so it's ruled out before
        # any version-level request is made for it.
        self.assertTrue(all("51shyZVL" not in url for url in opener.calls))

    def test_missing_project_is_unavailable_everywhere(self):
        rule_mods = [{"slug": "ghost", "projectId": "nope"}]
        opener = ScriptedOpener({})
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        availability = ur.compute_availability(client, rule_mods, {}, ["26.2"])
        self.assertEqual(availability["26.2"], [])
        self.assertEqual(opener.calls, [])

    def test_skips_version_level_call_when_project_level_rules_it_out(self):
        rule_mods = [{"slug": "forgeonly", "projectId": "xxx"}]
        projects_by_id = {"xxx": {"slug": "forgeonly", "game_versions": ["26.2"], "loaders": ["forge"]}}
        opener = ScriptedOpener({})
        client = ur.Client(opener=opener, sleeper=RecordingSleeper())
        availability = ur.compute_availability(client, rule_mods, projects_by_id, ["26.2"])
        self.assertEqual(availability["26.2"], [])
        self.assertEqual(opener.calls, [])


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


class SanitizeCellTests(unittest.TestCase):
    def test_escapes_pipes(self):
        self.assertEqual(ur.sanitize_cell("a | b"), "a \\| b")

    def test_collapses_newlines_to_spaces(self):
        self.assertEqual(ur.sanitize_cell("line1\nline2\r\nline3"), "line1 line2 line3")

    def test_neutralises_mentions(self):
        result = ur.sanitize_cell("cc @someone please")
        self.assertNotIn("@someone", result)
        self.assertIn("someone", result)

    def test_markdown_injection_via_title_is_neutralised(self):
        rule_mods = [{"slug": "safe", "title": "Safe Mod", "projectId": "id1"}]
        projects_by_id = {
            "id1": {
                "id": "id1",
                "slug": "safe",
                "title": "Evil | title\n@everyone\n| more | cells",
                "status": "archived",
                "categories": [],
            }
        }
        md, counts = ur.build_review(
            rule_mods, ["26.2"], "26.2",
            fo_slugs=set(), additive_slugs=set(),
            projects_by_id=projects_by_id,
            availability={"26.2": ["safe"]},
            old_mods_by_slug=None,
        )
        self.assertNotIn("@everyone", md)
        self.assertGreaterEqual(counts["status_or_removed"], 1)
        # The injected newlines must not have split the title into extra rows:
        # everything from the malicious title lands on the same table row.
        matching_lines = [line for line in md.splitlines() if "Evil" in line]
        self.assertEqual(len(matching_lines), 1)
        self.assertIn("more \\| cells", matching_lines[0])


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
        self.assertEqual(counts, {"new_upstream": 0, "status_or_removed": 0, "missing_fabric": 0, "v1_projection": 0})
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

    def test_revision_at_or_above_max_safe_raises(self):
        old = {"schemaVersion": 1, "revision": ur.MAX_SAFE_REVISION - 1, "mods": []}
        content = {"schemaVersion": 1, "mods": [{"slug": "new-mod"}]}
        with self.assertRaises(ur.UpdateRulesError):
            ur.finalize_document(content, old)

    def test_revision_below_max_safe_does_not_raise(self):
        old = {"schemaVersion": 1, "revision": ur.MAX_SAFE_REVISION - 2, "mods": []}
        content = {"schemaVersion": 1, "mods": [{"slug": "new-mod"}]}
        final = ur.finalize_document(content, old)
        self.assertEqual(final["revision"], ur.MAX_SAFE_REVISION - 1)


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


class TargetVersionTests(unittest.TestCase):
    TAGS = [{"version": v, "version_type": t} for v, t in (
        ("26.4.1", "release"), ("26.4", "release"), ("26.4-snapshot-1", "snapshot"), ("26.3.2", "release"),
        ("26.3.1", "release"), ("26.3.1-rc-1", "snapshot"), ("26.3", "release"), ("26.20", "release"),
        ("26.2", "release"), ("26.1.2", "release"), ("26.1", "release"), ("26.2.x", "release"))]

    def client(self):
        opener = ScriptedOpener({f"{ur.MODRINTH_API}/tag/game_version": [(200, json_body(self.TAGS), {})]})
        return ur.Client(opener=opener, sleeper=RecordingSleeper())

    def test_override_wins_without_a_request(self):
        client = ur.Client(opener=lambda r: (_ for _ in ()).throw(AssertionError("should not fetch")))
        self.assertEqual(ur.resolve_target_versions(client, "26.2,26.3,26.1", ["26.2"]), ["26.3", "26.2", "26.1"])

    def test_targets_are_the_nodes_plus_their_hotfix_releases(self):
        self.assertEqual(ur.resolve_target_versions(self.client(), None, ["26.2", "26.3"]), ["26.3.2", "26.3.1", "26.3", "26.2"])

    def test_a_newer_release_and_its_hotfix_do_not_push_out_a_node(self):
        targets = ur.resolve_target_versions(self.client(), None, ["26.2", "26.3"])
        self.assertNotIn("26.4.1", targets)
        self.assertNotIn("26.4", targets)
        self.assertIn("26.2", targets)

    def test_a_node_modrinth_does_not_list_is_still_a_target(self):
        self.assertEqual(ur.resolve_target_versions(self.client(), None, ["26.3", "26.5"]), ["26.5", "26.3.2", "26.3.1", "26.3"])

    def test_a_prerelease_node_sorts_below_its_release(self):
        self.assertEqual(ur.resolve_target_versions(self.client(), None, ["26.3", "26.4-snapshot-1"]),
                         ["26.4-snapshot-1", "26.3.2", "26.3.1", "26.3"])
        self.assertEqual(sorted(["26.4", "26.4-snapshot-1", "26.3.2", "26.10", "26.4.1"], key=ur.version_sort_key),
                         ["26.3.2", "26.4-snapshot-1", "26.4", "26.4.1", "26.10"])

    def test_prereleases_sort_snapshot_pre_rc_then_by_number(self):
        ids = ["26.4-rc-10", "26.4", "26.4-pre-1", "26.4-rc-2", "26.4-snapshot-2", "26.4-rc-1", "26.3.1", "26.4-snapshot-10"]
        self.assertEqual(sorted(ids, key=ur.version_sort_key),
                         ["26.3.1", "26.4-snapshot-2", "26.4-snapshot-10", "26.4-pre-1", "26.4-rc-1", "26.4-rc-2", "26.4-rc-10", "26.4"])

    def test_no_nodes_and_no_override_is_an_error(self):
        with self.assertRaises(ur.UpdateRulesError):
            ur.resolve_target_versions(self.client(), None, [])


class StonecutterNodesTests(unittest.TestCase):
    REPO = Path(__file__).resolve().parent.parent.parent

    def nodes(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "settings.gradle"
            path.write_text(text, encoding="utf-8")
            return ur.stonecutter_nodes(path)

    def test_repo_settings_list_every_versions_folder(self):
        folders = sorted(d.name for d in (self.REPO / "versions").iterdir() if d.is_dir())
        self.assertEqual(sorted(ur.stonecutter_nodes(self.REPO / "settings.gradle")), folders)

    def test_quote_and_call_styles(self):
        block = "plugins {{ id 'dev.kikugie.stonecutter' version '0.9.8' }}\nstonecutter {{ create(getRootProject()) {{\n{}\nvcsVersion = '26.2' }} }}"
        for line in ("versions '26.2', '26.3'", 'versions "26.2", "26.3"', "versions('26.2', '26.3')", "versions '26.2',\n\t\t\t'26.3'"):
            self.assertEqual(self.nodes(block.format(line)), ["26.2", "26.3"], line)

    def test_no_versions_list_is_an_error(self):
        with self.assertRaises(ur.UpdateRulesError):
            self.nodes("stonecutter { create(getRootProject()) { vcsVersion = '26.2' } }")

    def test_comments_are_ignored(self):
        for text in ("// versions '26.1', '26.2'\n\t\tversions '26.2', // old\n\t\t\t'26.3' /* next: '26.4' */\n",
                     "/* versions '26.0'\n versions '26.1' */\nversions '26.2', /* '26.2.5', */ '26.3'\n",
                     "url = 'https://maven.fabricmc.net/'\nid 'x' version '1.0'\n\tversions '26.2', '26.3'\n\tvcsVersion = '26.2'\n"):
            self.assertEqual(self.nodes(text), ["26.2", "26.3"], text)

    def test_two_versions_lists_are_an_error(self):
        with self.assertRaises(ur.UpdateRulesError):
            self.nodes("versions '26.2'\nversions '26.3'\n")

    def test_missing_file_is_an_error(self):
        with self.assertRaises(ur.UpdateRulesError):
            ur.stonecutter_nodes(Path(tempfile.gettempdir()) / "no-such-dir-rigtune" / "settings.gradle")


class MainTargetTests(unittest.TestCase):
    def run_main(self, argv, nodes=None):
        seen = {}

        def fake_pipeline(knowledge, client, override, old_doc, nodes=None):
            seen["override"], seen["nodes"] = override, nodes
            raise ur.UpdateRulesError("stop here")

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ur, "run_pipeline", fake_pipeline):
            code = ur.main(["--knowledge", str(FIXTURES / "knowledge_sample.json"), "--out-dir", tmp] + argv)
        return code, seen

    def test_main_takes_the_nodes_from_the_repo_settings(self):
        repo = Path(ur.__file__).resolve().parent.parent
        with mock.patch.object(ur, "stonecutter_nodes", wraps=ur.stonecutter_nodes) as nodes:
            code, seen = self.run_main([])
        nodes.assert_called_once_with(repo / "settings.gradle")
        self.assertEqual(code, 1)
        self.assertEqual(seen, {"override": None, "nodes": ur.stonecutter_nodes(repo / "settings.gradle")})

    def test_mc_versions_override_skips_the_settings(self):
        with mock.patch.object(ur, "stonecutter_nodes", side_effect=AssertionError("read settings.gradle")):
            code, seen = self.run_main(["--mc-versions", "26.3"])
        self.assertEqual(seen, {"override": "26.3", "nodes": None})

    def test_unreadable_settings_exit_1(self):
        with mock.patch.object(ur, "stonecutter_nodes", side_effect=ur.UpdateRulesError("no list")):
            code, seen = self.run_main([])
        self.assertEqual((code, seen), (1, {}))


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
        # sodium and lithium pass the project-level pre-filter for 26.2, so
        # compute_availability confirms them with a version-level call each.
        # moreculling has empty game_versions/loaders, so it's ruled out first
        # and no version-level call is scripted (or needed) for it.
        responses[ur.modrinth_version_list_url("AANobbMI", "26.2")] = [(200, json_body([{"id": "v1"}]), {})]
        responses[ur.modrinth_version_list_url("gvQqBUqZ", "26.2")] = [(200, json_body([{"id": "v2"}]), {})]

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
        responses[ur.modrinth_version_list_url("AANobbMI", "26.2")] = [(200, json_body([{"id": "v1"}]), {})]
        responses[ur.modrinth_version_list_url("gvQqBUqZ", "26.2")] = [(200, json_body([{"id": "v2"}]), {})]

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
