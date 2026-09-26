"""Self-update end-to-end test (SPEC item 5): an installed RigTune finds its update on a fake Modrinth, applies it, and
the post-exit helper swaps the jars; then the new version starts on the same instance and reads the old state.

    python tools/e2e/self_update_e2e.py --name dev-v030-to-040 --old-jar <rigtune-0.3.0+mc26.2.jar> --old-sha256 <hex>
        --new-jar versions/26.2/build/libs/rigtune-0.4.0-dev+mc26.2.jar --work <scratch dir> --expect-history auto
        [--evidence docs/smoke/self-update/<name>] [--capture-fixtures src/test/resources/v010/captured]

Launches a real Minecraft client twice (./gradlew :<mc>:e2eClient), holding the machine-wide game-test lock. See
tools/e2e/README.md."""

import argparse
import datetime
import gzip
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path
from urllib.parse import quote

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import e2e_checks  # noqa: E402
import e2e_env  # noqa: E402
import fixtures  # noqa: E402
import written  # noqa: E402

JAVA_SOURCES = HERE / "java" / "io" / "github" / "chaotix345" / "rigtune" / "e2e"
DEFAULT_LOCK = "C:/Dev/Worktrees/.gametest-lock"
PHASE_TIMEOUT = 20 * 60
HELPER_TIMEOUT = 120
HELPER_DONE = ("All operations done", "Some operations were not applied", "Nothing to apply", "Apply failed")
# A fresh instance: no accessibility onboarding (it would sit in front of the title screen), windowed, muted.
OPTIONS = "onboardAccessibility:false\nfullscreen:false\nskipMultiplayerWarning:true\ntutorialStep:none\n" \
          "joinedFirstServer:true\nsoundCategory_master:0.0\n"
# Evidence and fixtures are written with LF, as the repository stores text (.gitattributes).
LF = chr(10)
CAPTURED = ("pending.json", "last-apply.json", "rigtune.json", "rules-cache.json", "helper.log")
SEEDED = ("pending.json", "last-apply.json")
# The released jars a self-update run starts from, by fabric.mod.json version: file name and sha256 of the GitHub
# release asset. CI's "Compile the E2E drivers" step pins the same jars (tests/test_e2e_v04.py keeps them equal).
RELEASED = {
    "0.1.0": ("rigtune-0.1.0.jar", "8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950"),
    "0.2.0+mc26.2": ("rigtune-0.2.0+mc26.2.jar", "67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9"),
    "0.3.0+mc26.2": ("rigtune-0.3.0+mc26.2.jar", "5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9"),
}
UNDO_PHASES = ("mod-apply", "mod-undo", "mod-check")
# Plan review B-M3, on the same instance after UNDO_PHASES: Undo this on an older Apply.
ENTRY_PHASES = ("entry-apply", "entry-undo", "entry-check")
# The profile part (docs/v0.4/design/ws-h.md; plan review P-H1), after ENTRY_PHASES with --profile-switch, on an
# instance of its own that also has Sodium, so staged config keys are covered: two switches in one start
# (profile-apply; the helper applies the staged keys at exit), then Undo last twice in the next start (profile-undo)
# and a check start (profile-check); and on a copy of the instance taken after profile-apply, Undo all
# (profile-undo-all) and a check start (profile-check-all). Mode settings (the stand-in until WS-P's API is in
# UndoDriver.switchProfile) applies PROFILE_SWITCHES through controller.apply, as a switch does; mode profile switches
# to --profile-names through WS-P's API and also checks their labels in profiles.json.
PROFILE_PHASES = ("profile-apply", "profile-undo", "profile-check", "profile-undo-all", "profile-check-all")
# Both change the vanilla keys (set at once) and the same Sodium key (staged twice before one restart: P-H1); the first
# also stages a key the second leaves alone.
PROFILE_SWITCHES = [
    {"name": "stand-in A", "settings": {"vanilla.renderDistance": "6", "vanilla.maxFps": "90",
                                        "sodium.performance.chunk_builder_threads": "2", "sodium.performance.use_fog_occlusion": "false"}},
    {"name": "stand-in B", "settings": {"vanilla.renderDistance": "10", "vanilla.maxFps": "60",
                                        "sodium.performance.chunk_builder_threads": "4"}},
]
# The downgrade run (SPEC AC3.2): the released 0.3.0 on files 0.4 wrote, then 0.4 again. OFF_ID: the test mod 0.3.0's
# own Apply disables.
DOWNGRADE_PHASES = ("downgrade-old", "downgrade-new")
OFF_ID = "e2e-downgrade-off"
ADDED_ID = "e2e-added"
ADDED_PROJECT = "E2EAddMd"
OTHER_ID = "e2e-disable-me"
FIRST_ID, FIRST_PROJECT = "e2e-first", "E2EFrst1"
SECOND_ID, SECOND_PROJECT = "e2e-second", "E2EScnd1"
PHASE_TITLES = {
    "update": "After the old version applied the update and quit (helper done)",
    "verify": "After the new version started on the same instance",
    "mod-apply": "After RigTune applied {add " + ADDED_ID + " from Modrinth, disable " + OTHER_ID + "} and quit (helper done)",
    "mod-undo": "After Undo last apply and a restart (helper done)",
    "mod-check": "After the next start",
    "entry-apply": "B-M3: after two Applies in one start, each adding a mod (" + FIRST_ID + ", then " + SECOND_ID + "), and quit (helper done)",
    "entry-undo": "B-M3: after Undo this on the older Apply (" + FIRST_ID + ") and a restart (helper done)",
    "entry-check": "B-M3: after the next start",
    "downgrade-old": "The released old version on files the new one wrote: History, Undo last, its own Apply, quit (helper done)",
    "downgrade-new": "The new version again, on what the old one left",
    "profile-apply": "Profiles (P-H1): after two switches in one start and quit (helper done)",
    "profile-undo": "Profiles: after Undo last twice in the next start (helper done)",
    "profile-check": "Profiles: after the next start",
    "profile-undo-all": "Profiles, on a copy from after the switches: after Undo all (helper done)",
    "profile-check-all": "Profiles, on that copy: after the next start",
}


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
        # Unique, because processes are recognised as this run's by the folder name in their command line.
        self.run_dir = Path(args.work).resolve() / "{}-{}-{}".format(self.name, stamp, uuid.uuid4().hex[:6])
        self.instance = self.run_dir / "instance"
        self.out = self.run_dir / "out"
        self.mods = self.instance / "mods"
        self.rigtune_dir = self.instance / "config" / "rigtune"
        self.undo = args.scenario == "undo"
        self.downgrade = args.scenario == "downgrade"
        # self-update: old_jar is installed and new_jar served as its update. undo: new_jar is installed, nothing to update.
        self.old_jar = Path(args.old_jar).resolve() if args.old_jar else None
        self.new_jar = Path(args.new_jar).resolve()
        self.api_jar = Path(args.driver_api_jar or args.old_jar).resolve() if (args.driver_api_jar or args.old_jar) else None
        self.lock = None if args.lock == "none" else Path(args.lock)
        self.server = None
        self.watcher = None
        self.profile = args.profile_switch
        self.profile_instance = self.run_dir / "instance-profile"
        phases = UNDO_PHASES + ENTRY_PHASES + (PROFILE_PHASES if self.profile else ()) if self.undo \
            else DOWNGRADE_PHASES if self.downgrade else ("update", "verify")
        self.checks = {p: [] for p in phases}
        self.facts = {}
        self.jars = self.run_dir / "jars"
        # Test mods: disabled by the old version with its update (so its journal or the legacy import has a non-RigTune
        # change), and for the undo scenario one served by the fake Modrinth to add and one in mods/ to disable.
        self.legacy_jar = self.jars / "e2e-legacy-1.0.0.jar" if args.legacy_disable and args.scenario == "self-update" else None
        self.off_jar = self.jars / "{}-1.0.0.jar".format(OFF_ID)
        self.seeded = None
        self.started = {}
        # H-M2: a real 0.1.0 instance's state (tools/e2e/seeds/<name>); its pending ops are expected to be carried over.
        self.seed = load_seed(args.seed) if args.seed else None
        # --expect-history resolved against the old jar's version (prepare).
        self.expect_history = None
        self.carried = []
        self.added_jar = self.jars / "{}-1.0.0.jar".format(ADDED_ID)
        self.other_jar = self.jars / "{}-1.0.0.jar".format(OTHER_ID)
        self.first_jar = self.jars / "{}-1.0.0.jar".format(FIRST_ID)
        self.second_jar = self.jars / "{}-1.0.0.jar".format(SECOND_ID)

    # --- plumbing -------------------------------------------------------------------------------------------------

    def log(self, message):
        line = "[{}] {}".format(datetime.datetime.now().strftime("%H:%M:%S"), message)
        with open(self.run_dir / "e2e.log", "a", encoding="utf-8") as out:
            out.write(line + "\n")
        try:
            print(line, flush=True)
        except UnicodeEncodeError:
            # A redirected stdout on Windows is cp1252; RigTune's text (e.g. an undo plan's "90 → 120") isn't.
            print(line.encode("ascii", "backslashreplace").decode("ascii"), flush=True)

    def gradle(self, log_name, *arguments):
        command = (["cmd", "/c", str(REPO / "gradlew.bat")] if os.name == "nt" else [str(REPO / "gradlew")]) + list(arguments)
        self.log("gradle " + " ".join(arguments))
        env = dict(os.environ, JAVA_HOME=str(self.java_home))
        with open(self.run_dir / log_name, "w", encoding="utf-8") as out:
            try:
                return subprocess.run(command, cwd=REPO, env=env, stdout=out, stderr=subprocess.STDOUT, timeout=PHASE_TIMEOUT).returncode
            except subprocess.TimeoutExpired:
                # Killing cmd.exe leaves this run's Gradle wrapper waiting on the build; kill it too, so the daemon
                # cancels the build rather than starting a client later, after the lock is gone.
                self.log("gradle timed out after {} s; killing this run's client, helper and Gradle wrapper".format(PHASE_TIMEOUT))
                self.kill_own(lambda cl: "KnotClient" in cl or "ApplyHelper" in cl or "GradleWrapperMain" in cl)
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

    # Never a process tree: a Gradle daemon started by this run's wrapper is the wrapper's child and serves other builds.
    def kill_own(self, predicate):
        for pid, command_line in self.own(predicate):
            self.log("killing own process {}: {}".format(pid, command_line[:160]))
            subprocess.run(["taskkill", "/PID", str(pid), "/F"], capture_output=True)

    def ensure_no_client(self):
        """Before the lock goes: none of this run's clients may be running, or start shortly after (a cancelled build)."""
        deadline = time.time() + 20
        while time.time() < deadline:
            self.kill_own(lambda cl: "KnotClient" in cl or "ApplyHelper" in cl or "FakeModrinth" in cl or "GradleWrapperMain" in cl)
            time.sleep(2)
            if not self.own(lambda cl: "KnotClient" in cl or "GradleWrapperMain" in cl):
                return
        self.log("WARNING: a client of this run may still be running")

    def take_lock(self):
        if self.lock is None:
            return
        try:
            self.lock.mkdir()
        except FileExistsError:
            owner = self.lock / "owner.txt"
            raise LockBusy(owner.read_text(encoding="utf-8") if owner.is_file() else "(no owner.txt)")
        try:
            (self.lock / "owner.txt").write_text(owner_text(self.args.agent, REPO, self.run_dir,
                                                            datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")),
                                                 encoding="utf-8", newline=LF)
        except OSError:
            self.lock.rmdir()  # still empty: we just made it
            raise
        self.log("took the game-test lock " + str(self.lock))

    def release_lock(self):
        """PLAN's protocol: owner.txt, then the empty folder (never a recursive delete), and only this run's lock."""
        owner = None if self.lock is None else self.lock / "owner.txt"
        if owner is None or not owner.is_file() or not owns_lock(owner.read_text(encoding="utf-8"), self.run_dir):
            return
        others = sorted(p.name for p in self.lock.iterdir() if p.name != "owner.txt")
        if others:
            # Left whole, owner.txt included, so whoever looks can still see whose it is.
            self.log("WARNING: not releasing {}: it also holds {}".format(self.lock, others))
            return
        owner.unlink()
        try:
            self.lock.rmdir()
            self.log("released the game-test lock")
        except OSError as e:
            self.log("WARNING: removed owner.txt but not the lock folder {}: {}".format(self.lock, e))

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
        installed = self.new_jar if self.undo else self.old_jar
        if installed is None or not self.undo and self.api_jar is None:
            raise SystemExit("--old-jar is required for the self-update scenario")
        for jar in [j for j in (self.old_jar, self.new_jar, self.api_jar) if j is not None]:
            if e2e_env.mod_json(jar).get("id") != "rigtune":
                raise SystemExit("{} isn't a RigTune jar".format(jar))
        if self.old_jar is not None:
            old_sha256 = e2e_checks.digest(self.old_jar, "sha256")
            if self.args.old_sha256 and old_sha256 != self.args.old_sha256.lower():
                raise SystemExit("{} has sha256 {}, expected {}".format(self.old_jar, old_sha256, self.args.old_sha256))
            self.facts["old"] = {"file": self.old_jar.name, "version": e2e_env.mod_json(self.old_jar)["version"], "sha256": old_sha256}
            problem = released_problem(self.facts["old"]["version"], old_sha256)
            if problem:
                raise SystemExit("{}: {}".format(self.old_jar, problem))
            self.expect_history = resolve_expect_history(self.args.expect_history, self.facts["old"]["version"])
            self.log("old {file} {version} sha256 {sha256}".format(**self.facts["old"])
                     + ("; expected history: " + self.expect_history if self.expect_history else ""))
        self.facts["new"] = {"file": self.new_jar.name, "version": e2e_env.mod_json(self.new_jar)["version"],
                             "sha256": e2e_checks.digest(self.new_jar, "sha256")}
        self.log("new {file} {version} sha256 {sha256}".format(**self.facts["new"]))

        self.jars.mkdir()
        self.mods.mkdir(parents=True)
        self.rigtune_dir.parent.mkdir(parents=True)
        shutil.copyfile(installed, self.mods / installed.name)
        api = self.fabric_api()
        shutil.copyfile(api, self.mods / api.name)
        extra_projects = []
        if self.legacy_jar is not None:
            e2e_env.test_mod_jar(self.legacy_jar, "e2e-legacy")
            shutil.copyfile(self.legacy_jar, self.mods / self.legacy_jar.name)
        if self.undo:
            e2e_env.test_mod_jar(self.added_jar, ADDED_ID)
            e2e_env.test_mod_jar(self.other_jar, OTHER_ID)
            shutil.copyfile(self.other_jar, self.mods / self.other_jar.name)
            extra_projects.append((ADDED_PROJECT, ADDED_ID, self.added_jar))
            for jar, mod_id, project in ((self.first_jar, FIRST_ID, FIRST_PROJECT), (self.second_jar, SECOND_ID, SECOND_PROJECT)):
                e2e_env.test_mod_jar(jar, mod_id)
                extra_projects.append((project, mod_id, jar))
        if self.seed is not None:
            self.seed_instance()
        (self.instance / "options.txt").write_text(OPTIONS, encoding="utf-8")
        if self.profile:
            self.prepare_profile_instance()
        if self.downgrade:
            self.prepare_downgrade()
        self.facts["fabricApi"] = api.name
        self.log("instance mods: " + ", ".join(sorted(p.name for p in self.mods.iterdir())))

        self.tls = e2e_env.make_tls(self.run_dir / "tls", self.java_home)
        self.hosts = self.run_dir / "hosts.txt"
        self.hosts.write_text(e2e_env.hosts_file_text(socket.gethostname()), encoding="utf-8")
        rules = sorted((REPO / "rules").glob("rules-v*.json"))
        self.catalog = self.run_dir / "catalog.json"
        # The downgrade run's fake Modrinth knows only the released jar, so neither side is offered an update.
        self.served = self.old_jar if self.downgrade else self.new_jar
        self.catalog.write_text(json.dumps(e2e_env.catalog(None if self.downgrade else self.old_jar, self.served, self.mc, rules,
                                                           extra_projects=extra_projects),
                                           indent=1), encoding="utf-8")
        common = e2e_env.jvm_args(self.hosts, self.tls) + list(self.args.jvm_arg or [])
        for phase in self.checks:
            lines = common + ["-Drigtune.e2e.phase=" + phase, "-Drigtune.e2e.out=" + str(self.out)]
            if phase in DOWNGRADE_PHASES:
                lines.append("-Drigtune.e2e.disable=" + OFF_ID)
            if phase == "update" and self.legacy_jar is not None:
                lines.append("-Drigtune.e2e.alsoDisable=e2e-legacy")
            if self.undo:
                lines += ["-Drigtune.e2e.addSlug=" + ADDED_ID, "-Drigtune.e2e.addProject=" + ADDED_PROJECT, "-Drigtune.e2e.disable=" + OTHER_ID,
                          "-Drigtune.e2e.entryMods={}:{},{}:{}".format(FIRST_ID, FIRST_PROJECT, SECOND_ID, SECOND_PROJECT)]
            if phase in PROFILE_PHASES:
                lines.append("-Drigtune.e2e.profilePlan=" + str(self.run_dir / "profile-plan.json"))
            (self.run_dir / "jvm-{}.txt".format(phase)).write_text("\n".join(lines) + "\n", encoding="utf-8")

        code = self.gradle("gradle-driver.log", *self.driver_args(":{}:{}".format(self.mc, "e2eUndoDriverJar" if self.undo else "e2eDriverJar")))
        if code != 0:
            raise SystemExit("building the driver failed; see " + str(self.run_dir / "gradle-driver.log"))

    def profile_plan(self):
        """What the undo driver's profile-apply does (profile-plan.json): the stand-in's switches, or the profiles to switch to."""
        if self.profile == "profile":
            return {"mode": "profile", "switches": [{"name": n} for n in self.args.profile_names]}
        return {"mode": "settings", "switches": [dict(s) for s in PROFILE_SWITCHES]}

    def prepare_profile_instance(self):
        """The profile part's own fresh instance: the installed jar, fabric-api and Sodium (a staged config target)."""
        self.profile_instance.joinpath("mods").mkdir(parents=True)
        self.profile_instance.joinpath("config").mkdir()
        for jar in (self.new_jar, self.fabric_api(), self.sodium()):
            shutil.copyfile(jar, self.profile_instance / "mods" / jar.name)
        (self.profile_instance / "options.txt").write_text(OPTIONS, encoding="utf-8")
        (self.run_dir / "profile-plan.json").write_text(json.dumps(self.profile_plan(), indent=1) + LF, encoding="utf-8", newline=LF)
        self.facts["sodium"] = self.sodium().name

    def sodium(self):
        props = (REPO / "versions" / self.mc / "gradle.properties").read_text(encoding="utf-8")
        version = re.search(r"^sodium_version=(.+)$", props, re.MULTILINE).group(1).strip()
        return e2e_env.gradle_jar(Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1", "maven.modrinth", "sodium", version)

    def use_instance(self, instance):
        """The instance the next launches, waits and snapshots use."""
        self.instance = instance
        self.mods = instance / "mods"
        self.rigtune_dir = instance / "config" / "rigtune"

    def add_jvm_args(self, phase, lines):
        """For values known only after an earlier launch (the entry id of B-M3's older Apply)."""
        with open(self.run_dir / "jvm-{}.txt".format(phase), "a", encoding="utf-8") as out:
            out.write("\n".join(lines) + "\n")

    def seed_instance(self):
        """The seed's fake jars, and its templated files with this instance's folder put in."""
        for jar in self.seed["jars"]:
            target = self.instance / jar["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            e2e_env.test_mod_jar(target, jar["id"], jar["version"], name=jar.get("name"))
        self.rigtune_dir.mkdir(parents=True, exist_ok=True)
        for name in SEEDED:
            source = self.seed["dir"] / name
            if source.is_file():
                text = fixtures.instantiate_json(source.read_text(encoding="utf-8"), self.instance)
                (self.rigtune_dir / name).write_text(text, encoding="utf-8", newline=LF)
                (self.out / ("seeded-" + name)).write_text(text, encoding="utf-8", newline=LF)
        self.carried = (e2e_checks._load(self.rigtune_dir / "pending.json") or {}).get("ops") or []
        self.log("seeded from {}: {} carried-over op(s), jars {}".format(self.seed["dir"], len(self.carried),
                                                                         [j["path"] for j in self.seed["jars"]]))

    def driver_args(self, task):
        """The Gradle task plus the properties that pick and build this scenario's driver."""
        if self.undo:
            return [task, "-Pe2e.driver=undo"]
        if self.downgrade:
            return [task, "-Pe2e.driver=downgrade", "-Pe2e.oldJar=" + str(self.api_jar)]
        return [task, "-Pe2e.oldJar=" + str(self.api_jar)]

    def prepare_downgrade(self):
        """The instance as 0.4 left it: the "written by 0.4" sets in config/rigtune/ (written.py), the jars and options.txt
        values their journal implies, and a test mod for 0.3.0's own Apply."""
        sets = written.resolve(self.args.written)
        sources = written.compose(sets, self.instance)
        state = written.instance_state(self.instance)
        for path, mod_id in state["jars"].items():
            target = self.instance / path
            target.parent.mkdir(parents=True, exist_ok=True)
            e2e_env.test_mod_jar(target, mod_id or "e2e-unknown")
        e2e_env.test_mod_jar(self.off_jar, OFF_ID)
        shutil.copyfile(self.off_jar, self.mods / self.off_jar.name)
        with open(self.instance / "options.txt", "a", encoding="utf-8") as options:
            options.write("".join("{}:{}\n".format(k, v) for k, v in state["options"].items()))
        self.seeded = seeded_state(self.instance)
        self.facts["writtenSets"] = [{"name": s.name, "placeholder": s.placeholder, "folder": self.scrub(str(s.folder))} for s in sets]
        (self.out / "seeded.json").write_text(json.dumps({"sets": self.facts["writtenSets"], "files": sources, "jars": state["jars"],
                                                          "options": state["options"], "newFiles": self.seeded["newFiles"],
                                                          "undoLast": self.seeded["undoLast"]}, indent=1) + LF, encoding="utf-8", newline=LF)
        self.log("composed from {}; jars {}; options {}".format(
            ", ".join(s.name + (" (placeholder)" if s.placeholder else "") for s in sets), sorted(state["jars"]), state["options"]))

    def run_downgrade(self):
        """SPEC AC3.2: the released 0.3.0 on 0.4's files (History, Undo last, its own Apply; its helper at exit), then the
        player reinstalls 0.4 and it starts on what 0.3.0 left."""
        code, helper_ok, _ = self.launch_and_apply("downgrade-old")
        checks = [e2e_checks.Check("the client exited normally and the helper finished", code == 0 and helper_ok,
                                   "gradle exit {}, helper finished: {}".format(code, helper_ok))]
        checks += e2e_checks.after_downgrade_old(self.instance, self.driver("downgrade-old"), self.seeded, self.facts["old"]["version"],
                                                 self.off_jar.name, session_log(self.instance, self.started["downgrade-old"]))
        self.checks["downgrade-old"] = checks
        if not all(c.ok for c in checks):
            return False
        shutil.move(str(self.mods / self.old_jar.name), str(self.jars / ("uninstalled-" + self.old_jar.name)))
        shutil.copyfile(self.new_jar, self.mods / self.new_jar.name)
        self.log("reinstalled {} in place of {}".format(self.new_jar.name, self.old_jar.name))
        code = self.launch("downgrade-new")
        self.snapshot("downgrade-new")
        checks = [e2e_checks.Check("the relaunched client exited normally", code == 0, "gradle exit {}".format(code))]
        checks += e2e_checks.after_downgrade_new(self.instance, self.driver("downgrade-new"), self.new_jar, self.seeded,
                                                 session_log(self.instance, self.started["downgrade-new"]))
        self.checks["downgrade-new"] = checks
        return all(c.ok for c in checks)

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
        new_url = "https://{}/data/{}/versions/E2Enew01/{}".format(e2e_env.CDN_HOST, e2e_env.PROJECT_ID, quote(self.served.name, safe=""))
        urls = ["https://{}/v2/project/rigtune/version".format(e2e_env.API_HOST), new_url]
        urls += ["https://{}{}{}".format(e2e_env.RAW_HOST, e2e_env.RULES_URL_DIR, p.name) for p in sorted((REPO / "rules").glob("rules-v*.json"))]
        command = [str(self.java)] + e2e_env.jvm_args(self.hosts, self.tls) + [str(JAVA_SOURCES / "RedirectProbe.java"),
                                                                                "--unresolved", "example.com"] + urls
        result = subprocess.run(command, capture_output=True, text=True)
        (self.out / "redirect-probe.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        self.log("redirect probe: " + ("OK" if result.returncode == 0 else "FAILED"))
        if result.returncode != 0:
            raise SystemExit("the redirect probe failed:\n" + result.stdout + result.stderr)

    def start_watcher(self, phase):
        """Records the command line of every ApplyHelper JVM of this run while it lives (it lives a few seconds)."""
        if os.name != "nt":
            return
        self.watched = self.run_dir / "helper-cmdlines-{}.txt".format(phase)
        target = str(self.watched).replace("\\", "/")
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
        path = getattr(self, "watched", None)
        if path is None or not path.is_file():
            return []
        seen = {}
        for line in path.read_text(encoding="utf-8-sig", errors="replace").splitlines():
            pid, _, command_line = line.partition("\t")
            if command_line:
                seen.setdefault(pid, command_line.strip())
        return list(seen.values())

    def wait_for_helper(self, before=None):
        """before: helper.log's state before the launch (helper_log_state); a helper run rewrites the file, so an
        unchanged file is an earlier run's log in a reused instance and doesn't count."""
        pending = self.rigtune_dir / "pending.json"
        helper_log = self.rigtune_dir / "helper.log"
        deadline = time.time() + HELPER_TIMEOUT
        while time.time() < deadline:
            running = self.own(lambda cl: "ApplyHelper" in cl)
            text = helper_log_since(helper_log, before)
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
        self.started[phase] = started
        code = self.gradle("gradle-{}.log".format(phase), *self.driver_args(":{}:e2eClient".format(self.mc)),
                           "-Pe2e.instance=" + str(self.instance), "-Pe2e.jvmArgsFile=" + str(self.run_dir / "jvm-{}.txt".format(phase)))
        self.facts["{}Seconds".format(phase)] = round(time.time() - started)
        self.log("client exited (gradle exit {}) after {} s".format(code, self.facts["{}Seconds".format(phase)]))
        fixtures.copy_evidence(self.instance / "logs" / "latest.log", self.out / "latest-{}.log".format(phase))
        left = self.own(lambda cl: "KnotClient" in cl)
        if left:
            self.log("client still running after gradle returned: {}".format([pid for pid, _ in left]))
        return code

    def launch_and_apply(self, phase):
        """One launch that stages changes, then the helper after the game exits. Returns (gradle exit, helper done,
        helper command lines), and keeps the phase's files for the evidence."""
        self.start_watcher(phase)
        before = helper_log_state(self.rigtune_dir / "helper.log")
        try:
            code = self.launch(phase)
            helper_ok = self.wait_for_helper(before)
        finally:
            self.stop_watcher()
        cmdlines = self.helper_cmdlines()
        (self.out / "helper-cmdlines-{}.txt".format(phase)).write_text("\n".join(cmdlines) + "\n", encoding="utf-8")
        self.log("helper command line(s) seen: {}".format(len(cmdlines)))
        self.snapshot(phase)
        return code, helper_ok, cmdlines

    def snapshot(self, phase):
        for name in ("history.json", "last-apply.json", "helper.log"):
            fixtures.copy_evidence(self.rigtune_dir / name, self.out / "{}-after-{}{}".format(Path(name).stem, phase, Path(name).suffix))
        if phase in PROFILE_PHASES:
            fixtures.copy_evidence(self.instance / "options.txt", self.out / "options-after-{}.txt".format(phase))
            fixtures.copy_evidence(self.instance / "config" / "sodium-options.json", self.out / "sodium-options-after-{}.json".format(phase))
            fixtures.copy_evidence(self.rigtune_dir / "profiles.json", self.out / "profiles-after-{}.json".format(phase))
        (self.out / "mods-after-{}.json".format(phase)).write_text(json.dumps(e2e_checks.listing(self.mods), indent=1), encoding="utf-8")

    def run_update(self):
        # The seed's held-open files stay open from the launch until the helper is done (seed.json holdOpenWhy).
        held = [open(self.instance / p, "rb") for p in (self.seed or {}).get("holdOpenAtOldExit", [])]
        if held:
            self.log("holding open during the old version's exit: {}".format([Path(h.name).name for h in held]))
        try:
            code, helper_ok, cmdlines = self.launch_and_apply("update")
        finally:
            for handle in held:
                handle.close()
        raw = self.run_dir / "captured-raw"
        raw.mkdir()
        fixtures.copy_evidence(self.out / "pending-before-exit.json", raw / "pending.json")
        for name in CAPTURED[1:]:
            fixtures.copy_evidence(self.rigtune_dir / name, raw / name)
        (self.out / "helper-dir.txt").write_text(json.dumps(e2e_checks.listing(self.rigtune_dir / "helper"), indent=1), encoding="utf-8")
        driver = self.driver("update")
        extra = [self.legacy_jar.name] if self.legacy_jar is not None else []
        checks = e2e_checks.after_update(self.instance, self.old_jar, self.new_jar, driver, self.server_log(), cmdlines,
                                         extra_disables=extra, carried=self.carried)
        checks.insert(0, e2e_checks.Check("the client exited normally and the helper finished", code == 0 and helper_ok,
                                          "gradle exit {}, helper finished: {}".format(code, helper_ok)))
        self.checks["update"] = checks
        return all(c.ok for c in checks)

    def run_verify(self):
        mods_before = e2e_checks.listing(self.mods)
        statuses_before = e2e_checks.history_statuses(self.instance)
        last_apply = e2e_checks._load(self.rigtune_dir / "last-apply.json") or {}
        if self.seed is not None:
            # Watched like a staging launch: the check is that no helper runs at exit and mods/ stays as it was.
            tree_before = e2e_checks.listing(self.mods, recursive=True)
            code, _, cmdlines = self.launch_and_apply("verify")
        else:
            code = self.launch("verify")
            self.snapshot("verify")
        legacy = [self.legacy_jar.name] if self.legacy_jar is not None else []
        checks = e2e_checks.after_verify(self.instance, self.new_jar, self.driver("verify"), last_apply.get("finishedAt"),
                                         None if self.seed else mods_before, self.expect_history,
                                         legacy_disables=legacy, old_jar=self.old_jar, statuses_before=statuses_before)
        if self.seed is not None:
            log = self.out / "latest-verify.log"
            failed = [r.get("op") or {} for r in last_apply.get("results") or [] if r.get("status") == "FAILED"]
            checks += e2e_checks.after_seeded_verify(self.instance, self.carried, (self.seed["modId"], self.seed["modName"]),
                                                     self.driver("verify"),
                                                     log.read_text(encoding="utf-8", errors="replace") if log.is_file() else "",
                                                     failed, cmdlines, tree_before)
        checks.insert(0, e2e_checks.Check("the relaunched client exited normally", code == 0, "gradle exit {}".format(code)))
        self.checks["verify"] = checks
        return all(c.ok for c in checks)

    def run_undo_scenario(self):
        """Plan review M14: 0.2 applies a mod change, the helper applies it, Undo last after a restart, the helper
        reverts it, and the next start has the mods as they were."""
        def exited(code, helper_ok):
            return e2e_checks.Check("the client exited normally and the helper finished", code == 0 and helper_ok,
                                    "gradle exit {}, helper finished: {}".format(code, helper_ok))

        code, helper_ok, _ = self.launch_and_apply("mod-apply")
        checks = [exited(code, helper_ok)] + e2e_checks.after_mod_apply(self.instance, self.added_jar, self.other_jar.name,
                                                                         self.driver("mod-apply"))
        self.checks["mod-apply"] = checks
        applies = [e for e in e2e_checks.history_entries(self.instance) or [] if e.get("kind") == "apply"]
        if not all(c.ok for c in checks) or len(applies) != 1:
            return False
        self.facts["applyEntry"] = applies[0].get("id")

        code, helper_ok, _ = self.launch_and_apply("mod-undo")
        checks = [exited(code, helper_ok)] + e2e_checks.after_mod_undo(self.instance, self.added_jar.name, self.other_jar.name,
                                                                        self.driver("mod-undo"), self.facts["applyEntry"])
        self.checks["mod-undo"] = checks
        if not all(c.ok for c in checks):
            return False

        mods_before = e2e_checks.listing(self.mods)
        statuses_before = e2e_checks.history_statuses(self.instance)
        code = self.launch("mod-check")
        self.snapshot("mod-check")
        checks = e2e_checks.after_mod_check(self.instance, ADDED_ID, OTHER_ID, self.driver("mod-check"), mods_before, statuses_before)
        checks.insert(0, e2e_checks.Check("the client exited normally", code == 0, "gradle exit {}".format(code)))
        self.checks["mod-check"] = checks
        return all(c.ok for c in checks) and self.run_entry_undo(exited)

    def run_entry_undo(self, exited):
        """Plan review B-M3, on the same instance: two Applies each adding a mod, Undo this on the older one, a restart
        and the helper; only the older Apply's mod is off and both entries say so."""
        known = [e.get("id") for e in e2e_checks.history_entries(self.instance) or []]
        code, helper_ok, _ = self.launch_and_apply("entry-apply")
        checks = [exited(code, helper_ok)] + e2e_checks.after_entry_apply(self.instance, self.first_jar, self.second_jar,
                                                                           self.driver("entry-apply"), known)
        self.checks["entry-apply"] = checks
        new = [e for e in e2e_checks.history_entries(self.instance) or [] if e.get("id") not in known]
        if not all(c.ok for c in checks):
            return False
        self.facts["olderEntry"], self.facts["newerEntry"] = new[0].get("id"), new[1].get("id")
        for phase in ENTRY_PHASES[1:]:
            self.add_jvm_args(phase, ["-Drigtune.e2e.entryId=" + self.facts["olderEntry"], "-Drigtune.e2e.entryMod=" + FIRST_ID])

        code, helper_ok, _ = self.launch_and_apply("entry-undo")
        checks = [exited(code, helper_ok)] + e2e_checks.after_entry_undo(self.instance, self.first_jar.name, self.second_jar.name,
                                                                          self.driver("entry-undo"), self.facts["olderEntry"],
                                                                          self.facts["newerEntry"])
        self.checks["entry-undo"] = checks
        if not all(c.ok for c in checks):
            return False

        mods_before = e2e_checks.listing(self.mods)
        statuses_before = e2e_checks.history_statuses(self.instance)
        code = self.launch("entry-check")
        self.snapshot("entry-check")
        checks = e2e_checks.after_entry_check(self.instance, FIRST_ID, SECOND_ID, OTHER_ID, self.driver("entry-check"),
                                              mods_before, statuses_before)
        checks.insert(0, e2e_checks.Check("the client exited normally", code == 0, "gradle exit {}".format(code)))
        self.checks["entry-check"] = checks
        return all(c.ok for c in checks) and (not self.profile or self.run_profile_switch())

    def run_profile_switch(self):
        """The profile part (P-H1), on its own instance: two switches in one start, then Undo last twice and a check
        start; and on a copy from after the switches, Undo all and a check start. Each undo launch ends with the helper
        applying the staged (Sodium) reverts."""
        def exited(code, helper_ok=True):
            return e2e_checks.Check("the client exited normally" + (" and the helper finished" if helper_ok is not True else ""),
                                    code == 0 and helper_ok, "gradle exit {}, helper finished: {}".format(code, helper_ok))

        base = self.instance
        labels = list(self.args.profile_names) if self.profile == "profile" else None
        targets = [s["settings"] for s in PROFILE_SWITCHES] if self.profile == "settings" else None
        try:
            self.use_instance(self.profile_instance)
            code, helper_ok, _ = self.launch_and_apply("profile-apply")
            driver = self.driver("profile-apply") or {}
            checks = [exited(code, helper_ok)] + e2e_checks.after_profile_apply(self.instance, driver, [], targets, labels)
            self.checks["profile-apply"] = checks
            if not all(c.ok for c in checks):
                return False
            ids = [e.get("id") for e in e2e_checks.history_entries(self.instance) or []]
            self.facts["switchEntries"] = ids
            originals = driver.get("settingsBefore") or {}
            for phase in PROFILE_PHASES[1:]:
                self.add_jvm_args(phase, ["-Drigtune.e2e.entryIds=" + ",".join(ids)])
            # Undo all starts from the same point as Undo last: a copy of the instance now.
            copy = self.run_dir / "instance-profile-all"
            shutil.copytree(self.profile_instance, copy)

            ok = True
            for instance, undo, check, undo_all in ((self.profile_instance, "profile-undo", "profile-check", False),
                                                    (copy, "profile-undo-all", "profile-check-all", True)):
                self.use_instance(instance)
                code, helper_ok, _ = self.launch_and_apply(undo)
                checks = [exited(code, helper_ok)] + e2e_checks.after_profile_undo(self.instance, self.driver(undo), ids, originals, labels,
                                                                                    undo_all)
                self.checks[undo] = checks
                if not all(c.ok for c in checks):
                    ok = False
                    continue
                mods_before = e2e_checks.listing(self.mods)
                statuses_before = e2e_checks.history_statuses(self.instance)
                code = self.launch(check)
                self.snapshot(check)
                checks = [exited(code)] + e2e_checks.after_profile_check(self.instance, self.driver(check), ids, originals, mods_before,
                                                                          statuses_before)
                self.checks[check] = checks
                ok = ok and all(c.ok for c in checks)
            return ok
        finally:
            self.use_instance(base)

    def driver(self, phase):
        return e2e_checks._load(self.out / "driver-{}.json".format(phase))

    def server_log(self):
        path = self.run_dir / "requests.jsonl"
        if not path.is_file():
            return []
        return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]

    # --- evidence -------------------------------------------------------------------------------------------------

    def scrub(self, text):
        """Evidence names the scratch folders <instance> and <run>, the repository <repo> and the home folder ~ rather
        than their absolute paths."""
        for folder, name in ((self.instance, "<instance>"), (self.run_dir, "<run>"), (REPO, "<repo>"), (Path.home(), "~")):
            text = fixtures.template(text, folder).replace(fixtures.TOKEN, name)
        return text

    def evidence(self, verdict):
        dest = Path(self.args.evidence).resolve() if self.args.evidence else self.run_dir / "evidence"
        if dest.exists():
            # Replace only a previous evidence folder, never e.g. the parent of several.
            if any(dest.iterdir()) and not (dest / "RESULT.md").is_file():
                self.log("not replacing {}: it isn't an evidence folder; evidence stays in {}".format(dest, self.run_dir))
                dest = self.run_dir / "evidence"
            else:
                shutil.rmtree(dest)
        dest.mkdir(parents=True)
        texts = [self.out / n for n in ("redirect-probe.txt", "helper-dir.txt", "pending-before-exit.json", "seeded.json")]
        texts += [self.out / ("seeded-" + n) for n in SEEDED]
        for phase in self.checks:
            texts += [self.out / n.format(phase) for n in ("driver-{}.json", "report-{}.txt", "helper-cmdlines-{}.txt",
                                                            "mods-after-{}.json", "history-after-{}.json", "last-apply-after-{}.json",
                                                            "helper-after-{}.log", "pending-{}.json", "options-after-{}.txt",
                                                            "sodium-options-after-{}.json", "profiles-after-{}.json")]
        texts += [self.run_dir / n for n in ("requests.jsonl", "e2e.log", "catalog.json", "profile-plan.json")]
        texts += [self.run_dir / "captured-raw" / n for n in CAPTURED]
        for source in texts:
            if source.is_file():
                name = ("captured-" + source.name) if source.parent.name == "captured-raw" else source.name
                (dest / name).write_text(self.scrub(source.read_text(encoding="utf-8", errors="replace")), encoding="utf-8", newline=LF)
        for phase in self.checks:
            source = self.out / "latest-{}.log".format(phase)
            if source.is_file():
                (dest / "latest-{}.filtered.log".format(phase)).write_text(self.scrub(filtered_log(source)), encoding="utf-8", newline=LF)
        # Every instance of the run (the profile part has its own two).
        for shot in sorted(self.run_dir.glob("instance*/screenshots/e2e-*.png")):
            shutil.copyfile(shot, dest / shot.name)
        # Scrubbed before serialising: a detail holding a Python repr of paths would be escaped twice by json.dumps.
        (dest / "checks.json").write_text(json.dumps({phase: [dict(c.__dict__, detail=self.scrub(c.detail)) for c in checks]
                                                      for phase, checks in self.checks.items()}, indent=1), encoding="utf-8",
                                          newline=LF)
        (dest / "RESULT.md").write_text(self.result_markdown(verdict, sorted(p.name for p in dest.iterdir())), encoding="utf-8", newline=LF)
        self.log("evidence in " + str(dest))

    def result_markdown(self, verdict, files):
        installed = self.facts["new"] if self.undo else self.facts["old"]
        if self.downgrade:
            return self.downgrade_markdown(verdict, files)
        lines = ["# {} E2E: {}".format("Undo after restart" if self.undo else "Self-update", self.name), "",
                 "- Verdict: **{}**".format(verdict),
                 "- Run: {} UTC, MC {}, {} + {}{} in a fresh scratch instance".format(
                     self.run_dir.name.rsplit("-", 2)[-2], self.mc, installed["file"], self.facts.get("fabricApi"),
                     " + " + self.other_jar.name if self.undo else " + " + self.legacy_jar.name if self.legacy_jar else "")]
        if self.undo:
            lines += ["- RigTune: `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["new"]),
                      "- Added from the fake Modrinth: `{}` (project {}); disabled: `{}`".format(self.added_jar.name, ADDED_PROJECT,
                                                                                                  self.other_jar.name),
                      "- B-M3, same instance: one Apply adds `{}` (project {}), a second Apply adds `{}` ({}); Undo this on the "
                      "older one (entry {}; through the undo screen: {}; controller method: {})".format(
                          self.first_jar.name, FIRST_PROJECT, self.second_jar.name, SECOND_PROJECT, self.facts.get("olderEntry"),
                          (self.driver("entry-undo") or {}).get("viaScreen"), (self.driver("entry-undo") or {}).get("entryPlanMethod"))]
            if self.profile:
                lines.append("- Profiles (`--profile-switch {}`, plan review P-H1), on an instance of its own with `{}`: two switches "
                             "in one start ({}; entries {}), a restart, then Undo last twice, and Undo all on a copy of the instance "
                             "from after the switches".format(
                                 self.profile, self.facts.get("sodium"),
                                 "; ".join("`{}` {}".format(s["name"], s.get("settings", "")) for s in self.profile_plan()["switches"]),
                                 self.facts.get("switchEntries")))
        else:
            lines += ["- Old: `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["old"]),
                      "- New (served by the fake Modrinth): `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["new"])]
            if self.expect_history:
                lines.append("- Expected history: `{}` (a {} old side)".format(
                    self.expect_history, "0.1.x" if self.expect_history == "legacy-import" else "0.2.0 or later"))
            if self.legacy_jar is not None:
                lines.append("- The old version also disabled `{}` in the same apply (a change that isn't RigTune's own, for "
                             "the journal check)".format(self.legacy_jar.name))
            if self.seed is not None:
                lines += ["- Seeded (plan review H-M2) from `{}`: {}".format(self.scrub(str(self.seed["dir"])), self.seed.get("description", "")),
                          "- Fake jars: " + ", ".join("`{path}` ({id} {version})".format(**j) for j in self.seed["jars"]),
                          "- Held open during the old version's exit: {}. {}".format(
                              ", ".join("`{}`".format(p) for p in self.seed.get("holdOpenAtOldExit", [])), self.seed.get("holdOpenWhy", ""))]
            lines.append("- Rescan pressed because the report stayed offline (the startup lookup race, docs/v0.2/design/ws-g.md): "
                         "update phase {}, verify phase {}".format(*("yes" if (self.driver(p) or {}).get("rescanned") else "no"
                                                                     for p in ("update", "verify"))))
        lines.append("- Client time: " + ", ".join("{} {} s".format(p, self.facts.get("{}Seconds".format(p))) for p in self.checks))
        lines.append("")
        for phase, title in ((p, PHASE_TITLES[p]) for p in self.checks):
            lines += ["## " + title, "", "| check | result | detail |", "|---|---|---|"]
            if not self.checks[phase]:
                lines.append("| (not run) | | |")
            for c in self.checks[phase]:
                lines.append("| {} | {} | {} |".format(c.name, "PASS" if c.ok else "**FAIL**", self.scrub(c.detail).replace("|", "\\|")))
            lines.append("")
        lines += ["## Files", ""] + ["- `{}`".format(f) for f in files if f != "RESULT.md"] + [""]
        return "\n".join(lines)

    def downgrade_markdown(self, verdict, files):
        lines = ["# Downgrade E2E: " + self.name, "", "- Verdict: **{}**".format(verdict),
                 "- Run: {} UTC, MC {}, a fresh scratch instance with {} + {} + `{}` (for 0.3.0's own Apply)".format(
                     self.run_dir.name.rsplit("-", 2)[-2], self.mc, self.facts["old"]["file"], self.facts.get("fabricApi"), self.off_jar.name),
                 "- Old (started on 0.4's files): `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["old"]),
                 "- New (reinstalled after it): `{file}` version {version}, sha256 `{sha256}`".format(**self.facts["new"]),
                 "- \"Written by 0.4\" sets (plan review H-M1; `seeded.json`): " + ", ".join(
                     "`{}`{}".format(s["name"], " (**placeholder**)" if s["placeholder"] else "") for s in self.facts.get("writtenSets", [])),
                 "- Client time: " + ", ".join("{} {} s".format(p, self.facts.get("{}Seconds".format(p))) for p in self.checks), ""]
        for phase in self.checks:
            lines += ["## " + PHASE_TITLES[phase], "", "| check | result | detail |", "|---|---|---|"]
            if not self.checks[phase]:
                lines.append("| (not run) | | |")
            for c in self.checks[phase]:
                lines.append("| {} | {} | {} |".format(c.name, "PASS" if c.ok else "**FAIL**", self.scrub(c.detail).replace("|", "\\|")))
            lines.append("")
        lines += ["## Files", ""] + ["- `{}`".format(f) for f in files if f != "RESULT.md"] + [""]
        return "\n".join(lines)

    def capture_fixtures(self, verdict):
        dest = Path(self.args.capture_fixtures).resolve()
        raw = self.run_dir / "captured-raw"
        written = fixtures.capture({name: raw / name for name in CAPTURED}, self.instance, dest)
        failed = ["{}: {}".format(phase, c.name) for phase, checks in self.checks.items() for c in checks if not c.ok]
        manifest = {"capturedFrom": self.facts["old"], "updateTo": self.facts["new"], "minecraft": self.mc,
                    "run": self.run_dir.name, "verdict": verdict, "failedChecks": failed, "token": fixtures.TOKEN,
                    "files": {p.name: e2e_checks.digest(p, "sha256") for p in written}}
        (dest / "manifest.json").write_text(json.dumps(manifest, indent=1) + "\n", encoding="utf-8", newline=LF)
        self.log("fixtures: " + ", ".join(p.name for p in written))

    # --- main -----------------------------------------------------------------------------------------------------

    def preflight(self):
        """Fail before touching anything: the process watching and cleanup need Windows and PowerShell 7."""
        if os.name != "nt":
            raise SystemExit("the harness runs on Windows only (process checks use Win32_Process)")
        if shutil.which("pwsh") is None:
            raise SystemExit("pwsh (PowerShell 7) isn't on PATH")
        if self.lock is not None and not self.lock.parent.is_dir():
            raise SystemExit("the lock's folder {} doesn't exist; pass --lock <path> or --lock none".format(self.lock.parent))

    def main(self):
        self.preflight()
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
            if self.undo:
                if self.run_undo_scenario():
                    verdict = "PASS"
            elif self.downgrade:
                if self.run_downgrade():
                    verdict = "PASS"
            elif self.run_update():
                if self.run_verify():
                    verdict = "PASS"
            else:
                self.log("update checks failed; the relaunch is skipped")
        finally:
            self.stop_server()
            self.stop_watcher()
            self.ensure_no_client()
            self.release_lock()
            for phase, checks in self.checks.items():
                for c in checks:
                    self.log("{} [{}] {}: {}".format(phase, "PASS" if c.ok else "FAIL", c.name, c.detail))
            self.log("VERDICT " + verdict)
            self.evidence(verdict)
        if self.args.capture_fixtures and not self.undo and (verdict == "PASS" or self.args.capture_anyway and self.checks["update"]):
            self.capture_fixtures(verdict)
        elif self.args.capture_fixtures:
            self.log("fixtures not captured: the run didn't pass (--capture-anyway overrides)")
        return 0 if verdict == "PASS" else 1


def load_seed(folder):
    """A seed folder (tools/e2e/seeds/<name>): seed.json, plus the templated files it seeds (SEEDED)."""
    folder = Path(folder).resolve()
    seed = json.loads((folder / "seed.json").read_text(encoding="utf-8"))
    seed["dir"] = folder
    return seed


def seeded_state(instance):
    """What 0.4 left in config/rigtune/ (after written.compose), for the downgrade checks: the journal entries, the
    staged ops, the sha256 of the 0.4-only files, their content where 0.4 must keep items (written.KEPT), profiles.json,
    and the entry Undo last should pick (the newest non-undo entry with a change still applied or staged)."""
    config = Path(instance) / "config" / "rigtune"
    entries = e2e_checks.history_entries(instance) or []
    pending = e2e_checks._load(config / "pending.json") or {}
    undo_last = None
    for entry in entries:
        if entry.get("kind") != "undo" and any(c.get("status") in ("APPLIED", "STAGED") for c in entry.get("changes", [])):
            undo_last = entry.get("id")
    return {"entries": entries, "pendingOps": pending.get("ops") or [],
            "newFiles": {n: e2e_checks.digest(config / n, "sha256") for n in written.NEW_FILES if (config / n).is_file()},
            "json": {n: e2e_checks._load(config / n) for n in written.KEPT if (config / n).is_file()},
            "profiles": e2e_checks._load(config / "profiles.json"), "undoLast": undo_last}


def session_log(instance, since):
    """The launch's client log: latest.log, after any log rotated during it (logs/*.log.gz newer than `since`; a run
    across midnight UTC rotates latest.log)."""
    logs = Path(instance) / "logs"
    text = ""
    for rotated in sorted(logs.glob("*.log.gz")) if logs.is_dir() else []:
        if rotated.stat().st_mtime >= since:
            with gzip.open(rotated, "rt", encoding="utf-8", errors="replace") as f:
                text += f.read()
    latest = logs / "latest.log"
    return text + (latest.read_text(encoding="utf-8", errors="replace") if latest.is_file() else "")


def released_problem(version, sha256):
    """None, or why a jar claiming a released version isn't that release (RELEASED)."""
    pinned = RELEASED.get(version)
    if pinned is None or pinned[1] == sha256:
        return None
    return "version {} has sha256 {}, but the released {} has {}".format(version, sha256, pinned[0], pinned[1])


def resolve_expect_history(value, old_version):
    """--expect-history against the old jar's version: "auto" becomes what that version journals
    (e2e_checks.history_expectation); an explicit value that doesn't match it is refused."""
    if not value:
        return None
    try:
        expected = e2e_checks.history_expectation(old_version)
    except ValueError as e:
        raise SystemExit("--expect-history: {}".format(e))
    if value == "auto":
        return expected
    if value != expected:
        raise SystemExit("--expect-history {} doesn't fit an old side of version {} (it journals as {}); use auto".format(
            value, old_version, expected))
    return value


def owner_text(agent, repo, run_dir, started):
    """The lock's owner.txt as PLAN's lock protocol asks (agent, worktree, started), plus the run folder that proves
    ownership on release."""
    return "agent: {}\nworktree: {}\nstarted: {}\nrun: {}\n".format(agent, str(repo).replace(chr(92), "/"), started, run_dir)


def owns_lock(text, run_dir):
    return "run: {}".format(run_dir) in text.splitlines()


def helper_log_state(path):
    path = Path(path)
    if not path.is_file():
        return None
    stat = path.stat()
    return stat.st_mtime_ns, stat.st_size


def helper_log_since(path, before):
    """helper.log if it changed since `before` (helper_log_state), else empty. HelperLauncher redirects the helper's
    output to the file, which empties it at every helper start, so a changed file holds only the new run's lines."""
    path = Path(path)
    if not path.is_file() or helper_log_state(path) == before:
        return ""
    return path.read_bytes().decode("utf-8", errors="replace")


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
    parser.add_argument("--scenario", choices=("self-update", "undo", "downgrade"), default="self-update",
                        help="self-update (default), or undo: the new version applies mod changes and undoes them after a restart (M14, B-M3)")
    parser.add_argument("--old-jar", help="self-update: the installed RigTune jar (a released one: 0.1.0, 0.2.0 or 0.3.0)")
    parser.add_argument("--old-sha256", help="expected sha256 of --old-jar")
    parser.add_argument("--new-jar", required=True, help="self-update: the update the fake Modrinth serves; undo: the installed jar")
    parser.add_argument("--written", default=str(REPO / "src" / "test" / "resources" / "v040-written"),
                        help="downgrade: the \"written by 0.4\" fixture sets (plan review H-M1)")
    parser.add_argument("--seed", help="self-update: seed the instance from a folder like tools/e2e/seeds/v010-dh (H-M2)")
    parser.add_argument("--legacy-disable", action="store_true",
                        help="self-update: the old version also disables a test mod in the same apply, so the journal check has a change that isn't RigTune's")
    parser.add_argument("--driver-api-jar", help="the released jar the driver compiles against (default --old-jar)")
    parser.add_argument("--work", required=True, help="scratch folder for the run (a fresh instance is made inside)")
    parser.add_argument("--mc", default="26.2")
    parser.add_argument("--fabric-api", help="fabric-api jar (default: from the Gradle cache)")
    parser.add_argument("--evidence", help="copy the evidence here (replaces an earlier evidence folder only)")
    parser.add_argument("--capture-fixtures", help="write the old version's files here, templated (${INSTANCE}); passing runs only")
    parser.add_argument("--capture-anyway", action="store_true", help="capture fixtures from a failed run too (see manifest.json verdict)")
    parser.add_argument("--expect-history", nargs="?", const="legacy-import", choices=("auto", "legacy-import", "own-update"),
                        help="auto: from the old jar's version; legacy-import (the value without an argument; a 0.1.x "
                             "old side): the new version imports 0.1.x's last apply once; own-update (a 0.2.0 or later "
                             "old side): the old version's journal of its own update is read as it is")
    parser.add_argument("--profile-switch", choices=("settings", "profile"),
                        help="undo: also two profile switches, a restart, Undo last twice / Undo all (P-H1): settings = the "
                             "stand-in (applies of vanilla and Sodium settings), profile = through WS-P's API, with profiles.json labels")
    parser.add_argument("--profile-names", type=lambda v: [n.strip() for n in v.split(",")],
                        help="--profile-switch profile: the two profiles to switch to, comma-separated (default Battery,Max FPS)")
    parser.add_argument("--port", type=int, default=443)
    parser.add_argument("--lock", default=DEFAULT_LOCK, help="game-test lock folder, or 'none'")
    parser.add_argument("--agent", default="ws-h", help="the agent named in the lock's owner.txt")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--jvm-arg", action="append", help="extra JVM argument for both launches")
    args = parser.parse_args(argv)
    if not args.java_home:
        parser.error("set JAVA_HOME or pass --java-home")
    if args.seed and args.scenario != "self-update":
        parser.error("--seed is for the self-update scenario")
    if args.scenario == "downgrade" and not args.old_jar:
        parser.error("--scenario downgrade needs --old-jar (the released 0.3.0 jar) and --new-jar (0.4)")
    if args.profile_switch and args.scenario != "undo":
        parser.error("--profile-switch is for the undo scenario")
    if args.profile_names and args.profile_switch != "profile":
        parser.error("--profile-names is for --profile-switch profile")
    if args.profile_switch == "profile":
        args.profile_names = args.profile_names or ["Battery", "Max FPS"]
        if len(args.profile_names) != 2 or not all(args.profile_names):
            parser.error("--profile-names needs two profile names")
    if args.expect_history and args.scenario != "self-update":
        parser.error("--expect-history is for the self-update scenario")
    return args


def main(argv=None):
    return Run(parse_args(argv)).main()


if __name__ == "__main__":
    sys.exit(main())
