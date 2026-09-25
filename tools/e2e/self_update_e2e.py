"""Self-update end-to-end test (SPEC item 5): an installed RigTune finds its update on a fake Modrinth, applies it, and
the post-exit helper swaps the jars; then the new version starts on the same instance and reads the old state.

    python tools/e2e/self_update_e2e.py --name v010-to-dev --old-jar <rigtune-0.1.0.jar> --old-sha256 <hex>
        --new-jar versions/26.2/build/libs/rigtune-0.2.0-dev+mc26.2.jar --work <scratch dir>
        [--evidence docs/smoke/self-update/<name>] [--capture-fixtures src/test/resources/v010/captured] [--expect-history]

Launches a real Minecraft client twice (./gradlew :<mc>:e2eClient), holding the machine-wide game-test lock. See
tools/e2e/README.md."""

import argparse
import datetime
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import e2e_checks  # noqa: E402
import e2e_env  # noqa: E402
import fixtures  # noqa: E402

JAVA_SOURCES = HERE / "java" / "io" / "github" / "chaotix345" / "rigtune" / "e2e"
DEFAULT_LOCK = "C:/Dev/Worktrees/.gametest-lock"
PHASE_TIMEOUT = 20 * 60
HELPER_TIMEOUT = 120
HELPER_DONE = ("All operations done", "Some operations were not applied", "Nothing to apply", "Apply failed")
# A fresh instance: no accessibility onboarding (it would sit in front of the title screen), windowed, muted.
OPTIONS = "onboardAccessibility:false\nfullscreen:false\nskipMultiplayerWarning:true\ntutorialStep:none\n" \
          "joinedFirstServer:true\nsoundCategory_master:0.0\n"
CAPTURED = ("pending.json", "last-apply.json", "rigtune.json", "rules-cache.json", "helper.log")


class LockBusy(Exception):
    pass


class Run:
    def __init__(self, args):
        self.args = args
        self.name = args.name
        self.mc = args.mc
        self.java_home = Path(args.java_home)
        self.java = self.java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        self.run_dir = Path(args.work).resolve() / "{}-{}".format(self.name, stamp)
        self.instance = self.run_dir / "instance"
        self.out = self.run_dir / "out"
        self.mods = self.instance / "mods"
        self.rigtune_dir = self.instance / "config" / "rigtune"
        self.old_jar = Path(args.old_jar).resolve()
        self.new_jar = Path(args.new_jar).resolve()
        self.api_jar = Path(args.driver_api_jar or args.old_jar).resolve()
        self.lock = None if args.lock == "none" else Path(args.lock)
        self.server = None
        self.watcher = None
        self.checks = {"update": [], "verify": []}
        self.facts = {}

    # --- plumbing -------------------------------------------------------------------------------------------------

    def log(self, message):
        line = "[{}] {}".format(datetime.datetime.now().strftime("%H:%M:%S"), message)
        print(line, flush=True)
        with open(self.run_dir / "e2e.log", "a", encoding="utf-8") as out:
            out.write(line + "\n")

    def gradle(self, log_name, *arguments):
        command = (["cmd", "/c", "gradlew.bat"] if os.name == "nt" else ["./gradlew"]) + list(arguments)
        self.log("gradle " + " ".join(arguments))
        env = dict(os.environ, JAVA_HOME=str(self.java_home))
        with open(self.run_dir / log_name, "w", encoding="utf-8") as out:
            try:
                return subprocess.run(command, cwd=REPO, env=env, stdout=out, stderr=subprocess.STDOUT, timeout=PHASE_TIMEOUT).returncode
            except subprocess.TimeoutExpired:
                self.log("gradle timed out after {} s; killing this run's processes".format(PHASE_TIMEOUT))
                self.kill_own(lambda p: True)
                return -1

    def java_processes(self):
        if os.name != "nt":
            return []
        script = ("Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
                  "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" }")
        output = subprocess.run(["pwsh", "-NoProfile", "-Command", script], capture_output=True, text=True).stdout
        processes = []
        for line in output.splitlines():
            pid, _, command_line = line.partition("\t")
            if pid.strip().isdigit():
                processes.append((int(pid), command_line))
        return processes

    def own(self, predicate):
        """Only processes whose command line names this run's folder (unique per run) are ours."""
        return [(pid, cl) for pid, cl in self.java_processes() if self.run_dir.name in cl and predicate(cl)]

    def kill_own(self, predicate):
        for pid, command_line in self.own(predicate):
            self.log("killing own process {}: {}".format(pid, command_line[:160]))
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True)

    def take_lock(self):
        if self.lock is None:
            return
        try:
            self.lock.mkdir()
        except FileExistsError:
            owner = self.lock / "owner.txt"
            raise LockBusy(owner.read_text(encoding="utf-8") if owner.is_file() else "(no owner.txt)")
        (self.lock / "owner.txt").write_text("ws-g self-update E2E ({})\nworktree {}\nrun {}\nsince {}\n".format(
            self.name, REPO, self.run_dir, datetime.datetime.now(datetime.timezone.utc).isoformat()), encoding="utf-8")
        self.log("took the game-test lock " + str(self.lock))

    def release_lock(self):
        if self.lock is not None and (self.lock / "owner.txt").is_file() \
                and str(self.run_dir) in (self.lock / "owner.txt").read_text(encoding="utf-8"):
            shutil.rmtree(self.lock)
            self.log("released the game-test lock")

    # --- setup ----------------------------------------------------------------------------------------------------

    def fabric_api(self):
        if self.args.fabric_api:
            return Path(self.args.fabric_api).resolve()
        props = (REPO / "versions" / self.mc / "gradle.properties").read_text(encoding="utf-8")
        version = re.search(r"^fabric_api_version=(.+)$", props, re.MULTILINE).group(1).strip()
        cache = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1" / "net.fabricmc.fabric-api" / "fabric-api" / version
        found = sorted(cache.glob("*/fabric-api-{}.jar".format(version)))
        if not found:
            raise SystemExit("fabric-api {} isn't in the Gradle cache; run ./gradlew :{}:build or pass --fabric-api".format(version, self.mc))
        return found[0]

    def prepare(self):
        self.run_dir.mkdir(parents=True)
        self.out.mkdir()
        self.log("run folder " + str(self.run_dir))
        for jar in (self.old_jar, self.new_jar, self.api_jar):
            if e2e_env.mod_json(jar).get("id") != "rigtune":
                raise SystemExit("{} isn't a RigTune jar".format(jar))
        old_sha256 = e2e_checks.digest(self.old_jar, "sha256")
        if self.args.old_sha256 and old_sha256 != self.args.old_sha256.lower():
            raise SystemExit("{} has sha256 {}, expected {}".format(self.old_jar, old_sha256, self.args.old_sha256))
        self.facts.update({
            "old": {"file": self.old_jar.name, "version": e2e_env.mod_json(self.old_jar)["version"], "sha256": old_sha256},
            "new": {"file": self.new_jar.name, "version": e2e_env.mod_json(self.new_jar)["version"],
                    "sha256": e2e_checks.digest(self.new_jar, "sha256")},
        })
        self.log("old {file} {version} sha256 {sha256}".format(**self.facts["old"]))
        self.log("new {file} {version} sha256 {sha256}".format(**self.facts["new"]))

        self.mods.mkdir(parents=True)
        self.rigtune_dir.parent.mkdir(parents=True)
        shutil.copyfile(self.old_jar, self.mods / self.old_jar.name)
        api = self.fabric_api()
        shutil.copyfile(api, self.mods / api.name)
        (self.instance / "options.txt").write_text(OPTIONS, encoding="utf-8")
        self.facts["fabricApi"] = api.name
        self.log("instance mods: " + ", ".join(sorted(p.name for p in self.mods.iterdir())))

        self.tls = e2e_env.make_tls(self.run_dir / "tls", self.java_home)
        self.hosts = self.run_dir / "hosts.txt"
        self.hosts.write_text(e2e_env.hosts_file_text(socket.gethostname()), encoding="utf-8")
        rules = sorted((REPO / "rules").glob("rules-v*.json"))
        self.catalog = self.run_dir / "catalog.json"
        self.catalog.write_text(json.dumps(e2e_env.catalog(self.old_jar, self.new_jar, self.mc, rules), indent=1), encoding="utf-8")
        common = e2e_env.jvm_args(self.hosts, self.tls) + list(self.args.jvm_arg or [])
        for phase in ("update", "verify"):
            lines = common + ["-Drigtune.e2e.phase=" + phase, "-Drigtune.e2e.out=" + str(self.out)]
            (self.run_dir / "jvm-{}.txt".format(phase)).write_text("\n".join(lines) + "\n", encoding="utf-8")

        code = self.gradle("gradle-driver.log", ":{}:e2eDriverJar".format(self.mc), "-Pe2e.oldJar=" + str(self.api_jar))
        if code != 0:
            raise SystemExit("building the driver failed; see " + str(self.run_dir / "gradle-driver.log"))

    def start_server(self):
        requests = self.run_dir / "requests.jsonl"
        command = [str(self.java), str(JAVA_SOURCES / "FakeModrinth.java"), "--catalog", str(self.catalog),
                   "--port", str(self.args.port), "--keystore", str(self.tls.keystore), "--storepass", self.tls.password,
                   "--log", str(requests)]
        server_out = self.run_dir / "server.out"
        self.server = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=open(server_out, "w"), stderr=subprocess.STDOUT)
        deadline = time.time() + 60
        while time.time() < deadline:
            text = server_out.read_text(errors="replace") if server_out.exists() else ""
            if "listening" in text:
                self.log(text.strip())
                return
            if self.server.poll() is not None:
                raise SystemExit("the fake server exited: " + text)
            time.sleep(0.3)
        raise SystemExit("the fake server didn't start within 60 s")

    def stop_server(self):
        if self.server is None:
            return
        try:
            self.server.stdin.close()
            self.server.wait(timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            self.server.kill()
        self.server = None

    def probe(self):
        new_url = "https://{}/data/{}/versions/E2Enew01/{}".format(e2e_env.CDN_HOST, e2e_env.PROJECT_ID, quote(self.new_jar.name, safe=""))
        urls = ["https://{}/v2/project/rigtune/version".format(e2e_env.API_HOST), new_url]
        urls += ["https://{}{}{}".format(e2e_env.RAW_HOST, e2e_env.RULES_URL_DIR, p.name) for p in sorted((REPO / "rules").glob("rules-v*.json"))]
        command = [str(self.java)] + e2e_env.jvm_args(self.hosts, self.tls) + [str(JAVA_SOURCES / "RedirectProbe.java"),
                                                                                "--unresolved", "example.com"] + urls
        result = subprocess.run(command, capture_output=True, text=True)
        (self.out / "redirect-probe.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        self.log("redirect probe: " + ("OK" if result.returncode == 0 else "FAILED"))
        if result.returncode != 0:
            raise SystemExit("the redirect probe failed:\n" + result.stdout + result.stderr)

    def start_watcher(self):
        """Records the command line of every ApplyHelper JVM of this run while it lives (it lives a few seconds)."""
        if os.name != "nt":
            return
        target = str(self.run_dir / "helper-cmdlines.txt").replace("\\", "/")
        script = ("$out = '" + target + "'; while ($true) { Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | "
                  "Where-Object { $_.CommandLine -like '*ApplyHelper*' -and $_.CommandLine -like '*" + self.run_dir.name + "*' } | "
                  "ForEach-Object { \"$($_.ProcessId)`t$($_.CommandLine)\" } | Add-Content -Encoding utf8 -Path $out; "
                  "Start-Sleep -Milliseconds 200 }")
        self.watcher = subprocess.Popen(["pwsh", "-NoProfile", "-Command", script], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def stop_watcher(self):
        if self.watcher is not None:
            self.watcher.kill()
            self.watcher.wait()
            self.watcher = None

    def helper_cmdlines(self):
        path = self.run_dir / "helper-cmdlines.txt"
        if not path.is_file():
            return []
        seen = {}
        for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
            pid, _, command_line = line.partition("\t")
            if command_line:
                seen.setdefault(pid, command_line.strip())
        return list(seen.values())

    def wait_for_helper(self):
        pending = self.rigtune_dir / "pending.json"
        helper_log = self.rigtune_dir / "helper.log"
        deadline = time.time() + HELPER_TIMEOUT
        while time.time() < deadline:
            running = self.own(lambda cl: "ApplyHelper" in cl)
            text = helper_log.read_text(errors="replace") if helper_log.is_file() else ""
            if not running and (any(done in text for done in HELPER_DONE) or not pending.exists()):
                self.log("helper finished: " + (text.strip().splitlines()[-1] if text.strip() else "(no helper.log)"))
                return True
            time.sleep(1)
        self.log("the helper didn't finish within {} s".format(HELPER_TIMEOUT))
        return False

    # --- phases ---------------------------------------------------------------------------------------------------

    def launch(self, phase):
        self.log("launching the client, phase " + phase)
        started = time.time()
        code = self.gradle("gradle-{}.log".format(phase), ":{}:e2eClient".format(self.mc), "-Pe2e.oldJar=" + str(self.api_jar),
                           "-Pe2e.instance=" + str(self.instance), "-Pe2e.jvmArgsFile=" + str(self.run_dir / "jvm-{}.txt".format(phase)))
        self.facts["{}Seconds".format(phase)] = round(time.time() - started)
        self.log("client exited (gradle exit {}) after {} s".format(code, self.facts["{}Seconds".format(phase)]))
        fixtures.copy_evidence(self.instance / "logs" / "latest.log", self.out / "latest-{}.log".format(phase))
        left = self.own(lambda cl: "KnotClient" in cl)
        if left:
            self.log("client still running after gradle returned: {}".format([pid for pid, _ in left]))
        return code

    def run_update(self):
        self.start_watcher()
        try:
            code = self.launch("update")
            helper_ok = self.wait_for_helper()
        finally:
            self.stop_watcher()
        cmdlines = self.helper_cmdlines()
        (self.out / "helper-cmdlines.txt").write_text("\n".join(cmdlines) + "\n", encoding="utf-8")
        self.log("helper command line(s) seen: {}".format(len(cmdlines)))
        raw = self.run_dir / "captured-raw"
        raw.mkdir()
        fixtures.copy_evidence(self.out / "pending-before-exit.json", raw / "pending.json")
        for name in CAPTURED[1:]:
            fixtures.copy_evidence(self.rigtune_dir / name, raw / name)
        (self.out / "helper-dir.txt").write_text(json.dumps(e2e_checks.listing(self.rigtune_dir / "helper"), indent=1), encoding="utf-8")
        (self.out / "mods-after-update.json").write_text(json.dumps(e2e_checks.listing(self.mods), indent=1), encoding="utf-8")
        driver = self.driver("update")
        checks = e2e_checks.after_update(self.instance, self.old_jar, self.new_jar, driver, self.server_log(), cmdlines)
        checks.insert(0, e2e_checks.Check("the client exited normally and the helper finished", code == 0 and helper_ok,
                                          "gradle exit {}, helper finished: {}".format(code, helper_ok)))
        self.checks["update"] = checks
        return all(c.ok for c in checks)

    def run_verify(self):
        mods_before = e2e_checks.listing(self.mods)
        last_apply = e2e_checks._load(self.rigtune_dir / "last-apply.json") or {}
        code = self.launch("verify")
        (self.out / "mods-after-verify.json").write_text(json.dumps(e2e_checks.listing(self.mods), indent=1), encoding="utf-8")
        checks = e2e_checks.after_verify(self.instance, self.new_jar, self.driver("verify"), last_apply.get("finishedAt"),
                                         mods_before, self.args.expect_history)
        checks.insert(0, e2e_checks.Check("the relaunched client exited normally", code == 0, "gradle exit {}".format(code)))
        self.checks["verify"] = checks
        return all(c.ok for c in checks)

    def driver(self, phase):
        return e2e_checks._load(self.out / "driver-{}.json".format(phase))

    def server_log(self):
        path = self.run_dir / "requests.jsonl"
        if not path.is_file():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    # --- evidence -------------------------------------------------------------------------------------------------

    def scrub(self, text):
        """Evidence names the scratch folders <instance> and <run> rather than their absolute paths."""
        text = fixtures.template(text, self.instance).replace(fixtures.TOKEN, "<instance>")
        return fixtures.template(text, self.run_dir).replace(fixtures.TOKEN, "<run>")

    def evidence(self, verdict):
        dest = Path(self.args.evidence).resolve() if self.args.evidence else self.run_dir / "evidence"
        if dest.exists():
            shutil.rmtree(dest)
        dest.mkdir(parents=True)
        texts = [self.out / n for n in ("driver-update.json", "driver-verify.json", "report-update.txt", "report-verify.txt",
                                        "redirect-probe.txt", "helper-cmdlines.txt", "helper-dir.txt", "mods-after-update.json",
                                        "mods-after-verify.json")]
        texts += [self.run_dir / n for n in ("requests.jsonl", "e2e.log", "catalog.json")]
        texts += [self.run_dir / "captured-raw" / n for n in CAPTURED]
        for source in texts:
            if source.is_file():
                name = ("captured-" + source.name) if source.parent.name == "captured-raw" else source.name
                (dest / name).write_text(self.scrub(source.read_text(encoding="utf-8", errors="replace")), encoding="utf-8")
        for phase in ("update", "verify"):
            source = self.out / "latest-{}.log".format(phase)
            if source.is_file():
                (dest / "latest-{}.filtered.log".format(phase)).write_text(self.scrub(filtered_log(source)), encoding="utf-8")
        for shot in sorted((self.instance / "screenshots").glob("e2e-*.png")):
            shutil.copyfile(shot, dest / shot.name)
        (dest / "checks.json").write_text(json.dumps({phase: [c.__dict__ for c in checks] for phase, checks in self.checks.items()},
                                                     indent=1), encoding="utf-8")
        (dest / "RESULT.md").write_text(self.result_markdown(verdict, sorted(p.name for p in dest.iterdir())), encoding="utf-8")
        self.log("evidence in " + str(dest))

    def result_markdown(self, verdict, files):
        lines = ["# Self-update E2E: {}".format(self.name), "",
                 "- Verdict: **{}**".format(verdict),
                 "- Run: {} UTC, MC {}, {} + {} in a fresh scratch instance".format(
                     self.run_dir.name.rsplit("-", 1)[-1], self.mc, self.facts.get("old", {}).get("file"), self.facts.get("fabricApi")),
                 "- Old: `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["old"]),
                 "- New (served by the fake Modrinth): `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["new"]),
                 "- Client time: update phase {} s, verify phase {} s".format(self.facts.get("updateSeconds"), self.facts.get("verifySeconds")),
                 ""]
        for phase, title in (("update", "After the old version applied the update and quit (helper done)"),
                             ("verify", "After the new version started on the same instance")):
            lines += ["## " + title, "", "| check | result | detail |", "|---|---|---|"]
            if not self.checks[phase]:
                lines.append("| (not run) | | |")
            for c in self.checks[phase]:
                lines.append("| {} | {} | {} |".format(c.name, "PASS" if c.ok else "**FAIL**", self.scrub(c.detail).replace("|", "\\|")))
            lines.append("")
        lines += ["## Files", ""] + ["- `{}`".format(f) for f in files if f != "RESULT.md"] + [""]
        return "\n".join(lines)

    def capture_fixtures(self):
        dest = Path(self.args.capture_fixtures).resolve()
        raw = self.run_dir / "captured-raw"
        written = fixtures.capture({name: raw / name for name in CAPTURED}, self.instance, dest)
        manifest = {"capturedFrom": self.facts["old"], "updateTo": self.facts["new"], "minecraft": self.mc,
                    "run": self.run_dir.name, "token": fixtures.TOKEN,
                    "files": {p.name: e2e_checks.digest(p, "sha256") for p in written}}
        (dest / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8")
        self.log("fixtures: " + ", ".join(p.name for p in written))

    # --- main -----------------------------------------------------------------------------------------------------

    def main(self):
        self.prepare()
        verdict = "FAIL"
        try:
            self.take_lock()
        except LockBusy as busy:
            self.log("the game-test lock is held; try again later. owner.txt:\n" + str(busy))
            return 3
        try:
            self.start_server()
            self.probe()
            if self.run_update():
                if self.run_verify():
                    verdict = "PASS"
            else:
                self.log("update checks failed; the relaunch is skipped")
        finally:
            self.stop_server()
            self.stop_watcher()
            self.kill_own(lambda cl: "KnotClient" in cl or "ApplyHelper" in cl or "FakeModrinth" in cl)
            self.release_lock()
            for phase, checks in self.checks.items():
                for c in checks:
                    self.log("{} [{}] {}: {}".format(phase, "PASS" if c.ok else "FAIL", c.name, c.detail))
            self.log("VERDICT " + verdict)
            self.evidence(verdict)
        if self.args.capture_fixtures and self.checks["update"]:
            self.capture_fixtures()
        return 0 if verdict == "PASS" else 1


def filtered_log(path):
    """The lines of a client log that matter here: RigTune, the driver, warnings and errors, and the mod list."""
    keep = re.compile(r"rigtune|e2e|error|warn|exception|loading \d+ mods", re.IGNORECASE)
    out = []
    in_mod_list = False
    for line in Path(path).read_text(encoding="utf-8", errors="replace").splitlines():
        if re.search(r"loading \d+ mods", line, re.IGNORECASE):
            in_mod_list = True
            out.append(line)
            continue
        if in_mod_list and line.startswith("\t"):
            out.append(line)
            continue
        in_mod_list = False
        if keep.search(line):
            out.append(line)
    return "\n".join(out) + "\n"


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", required=True, help="scenario name, e.g. v010-to-dev")
    parser.add_argument("--old-jar", required=True, help="the installed RigTune jar (e.g. the released v0.1.0)")
    parser.add_argument("--old-sha256", help="expected sha256 of --old-jar")
    parser.add_argument("--new-jar", required=True, help="the update the fake Modrinth serves")
    parser.add_argument("--driver-api-jar", help="the released v0.1.0 jar the driver compiles against (default --old-jar)")
    parser.add_argument("--work", required=True, help="scratch folder for the run (a fresh instance is made inside)")
    parser.add_argument("--mc", default="26.2")
    parser.add_argument("--fabric-api", help="fabric-api jar (default: from the Gradle cache)")
    parser.add_argument("--evidence", help="copy the evidence here (replaced)")
    parser.add_argument("--capture-fixtures", help="write the old version's files here, templated (${INSTANCE})")
    parser.add_argument("--expect-history", action="store_true", help="require the 0.2 history.json legacy import")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--lock", default=DEFAULT_LOCK, help="game-test lock folder, or 'none'")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--jvm-arg", action="append", help="extra JVM argument for both launches")
    args = parser.parse_args(argv)
    if not args.java_home:
        parser.error("set JAVA_HOME or pass --java-home")
    return args


def main(argv=None):
    return Run(parse_args(argv)).main()


if __name__ == "__main__":
    sys.exit(main())
