"""The Distant Horizons server-note run (docs/v0.5/SPEC.md 3f, AC3f.4; vg §6), local only.

A Fabric server (dh-server.init.gradle's Loom production server task) on a free ephemeral port of 127.0.0.1, never 25565,
and a production client (Loom's e2eClient) with RigTune, fabric-api, Sodium and Distant Horizons, driven by
src/e2eUndo's DhServerDriver. One client start, three server starts on the same world:

    explore   view-distance=16, no DH on the server: the client explores 16 chunks around spawn
    limited   view-distance=6, no DH on the server: what DH shows beyond 6 (the explored ring, nothing past it?)
    dhserver  view-distance=6, DH on the server too: new distant terrain beyond what was explored?

The driver screenshots the horizon (debug overlay on) during each phase and ends it with /stop (ops.json names its fixed
user); the script then starts the server for the next phase. The judgment is made from the screenshots, not here.
Run it under the game-test lock and two build slots (both Gradle builds run at once):

    python tools/e2e/dhnote/dh_server_note.py --rigtune-jar <jar> --dh-jar <DistantHorizons-3.3.2-26.2-fabric-neoforge.jar>
        --fabric-api <jar> --sodium <jar> --work <scratch dir> [--phases explore:90,limited:90,dhserver:240]
"""

import argparse
import datetime
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent.parent
sys.path.insert(0, str(HERE.parent))

import self_update_e2e  # noqa: E402

INIT = HERE / "dh-server.init.gradle"
USER = "RigTuneDH"
RESERVED = 25565
VIEW = {"explore": 16, "limited": 6, "dhserver": 6}
DH_ON_SERVER = {"dhserver"}
SERVER_START = 300
# DH keeps its LODs in SQLite files deep under the instance and the server's world, and the native SQLite can't open a path
# past Windows' MAX_PATH (the first local run's "SQLITE_CANTOPEN ... level loading failed" under the long scratch folder).
MAX_RUN_DIR = 80
CLIENT_TIMEOUT = 45 * 60
# The client's DH: no update check or download (a local run has the network; the player's instance is never touched).
DH_CLIENT_TOML = "[client.advanced.autoUpdater]\n\tenableAutoUpdater = false\n\tenableSilentUpdates = false\n"


def offline_uuid(name):
    """The UUID an offline-mode server gives a user name: Java's UUID.nameUUIDFromBytes("OfflinePlayer:" + name)."""
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = digest[6] & 0x0F | 0x30
    digest[8] = digest[8] & 0x3F | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


def free_port():
    """A free ephemeral port on 127.0.0.1, never 25565 (the player's own server holds it)."""
    while True:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.bind(("127.0.0.1", 0))
            port = s.getsockname()[1]
        if port != RESERVED:
            return port


def server_properties(port, view_distance):
    return "\n".join(["server-ip=127.0.0.1", "server-port={}".format(port), "online-mode=false", "enforce-secure-profile=false",
                      "view-distance={}".format(view_distance), "simulation-distance=5", "spawn-protection=0", "level-seed=rigtune-dh-note",
                      "motd=RigTune DH note", "enable-command-block=false", "max-players=2"]) + "\n"


def parse_phases(value):
    phases = [tuple(p.split(":", 1)) for p in value.split(",") if p.strip()]
    for name, seconds in phases:
        if name not in VIEW or not seconds.isdigit():
            raise SystemExit("--phases: {}:{} (names: {})".format(name, seconds, ", ".join(VIEW)))
    return phases


class NoteRun:
    def __init__(self, args):
        self.args = args
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        self.run_dir = Path(args.work).resolve() / "dh-note-{}-{}".format(stamp, uuid.uuid4().hex[:6])
        if len(str(self.run_dir)) > MAX_RUN_DIR:
            raise SystemExit("--work: {} is too long for DH's SQLite files under it; use a short folder".format(self.run_dir))
        self.server_dir = self.run_dir / "server"
        self.instance = self.run_dir / "instance"
        self.out = self.run_dir / "out"
        self.phases = parse_phases(args.phases)
        self.port = free_port()
        self.java_home = Path(args.java_home)

    def log(self, message):
        line = "[{}] {}".format(datetime.datetime.now().strftime("%H:%M:%S"), message)
        with open(self.run_dir / "dh-note.log", "a", encoding="utf-8") as out:
            out.write(line + "\n")
        print(line, flush=True)

    def gradle(self, log_name, *arguments):
        common = ["-I", str(INIT), "-PdhServer.node=" + self.args.node, "-PdhServer.dir=" + str(self.server_dir),
                  "-PdhServer.mods=" + os.pathsep.join(str(m) for m in self.server_mods), "-PdhServer.username=" + USER]
        command = (["cmd", "/c", str(REPO / "gradlew.bat")] if os.name == "nt" else [str(REPO / "gradlew")]) + common + list(arguments)
        self.log("gradle " + " ".join(arguments) + " (server mods: {})".format([m.name for m in self.server_mods]))
        env = dict(os.environ, JAVA_HOME=str(self.java_home))
        return subprocess.Popen(command, cwd=REPO, env=env, stdout=open(self.run_dir / log_name, "w", encoding="utf-8"),
                                stderr=subprocess.STDOUT)

    def prepare(self):
        self.run_dir.mkdir(parents=True)
        self.out.mkdir()
        (self.server_dir / "mods").mkdir(parents=True)
        (self.server_dir / "eula.txt").write_text("# The Minecraft EULA, for this local test server only\neula=true\n", encoding="utf-8")
        (self.server_dir / "ops.json").write_text(json.dumps([{"uuid": offline_uuid(USER), "name": USER, "level": 4,
                                                                "bypassesPlayerLimit": False}], indent=2) + "\n", encoding="utf-8")
        mods = self.instance / "mods"
        mods.mkdir(parents=True)
        for jar in (self.args.rigtune_jar, self.args.fabric_api, self.args.sodium, self.args.dh_jar):
            if jar:
                shutil.copyfile(jar, mods / Path(jar).name)
        (self.instance / "options.txt").write_text(self_update_e2e.OPTIONS + "renderDistance:16\npauseOnLostFocus:false\n", encoding="utf-8")
        (self.instance / "config").mkdir()
        (self.instance / "config" / "DistantHorizons.toml").write_text(DH_CLIENT_TOML, encoding="utf-8")
        (self.run_dir / "jvm-dh.txt").write_text("\n".join([
            "-Drigtune.e2e.dhServer=127.0.0.1:{}".format(self.port),
            "-Drigtune.e2e.dhPhases=" + ",".join("{}:{}".format(n, s) for n, s in self.phases),
            "-Drigtune.e2e.out=" + str(self.out)]) + "\n", encoding="utf-8")
        self.log("port {} (127.0.0.1); phases {}; client mods {}".format(self.port, self.phases, sorted(p.name for p in mods.iterdir())))

    def start_server(self, index, name):
        self.server_mods = [Path(self.args.fabric_api)] + ([Path(self.args.dh_jar)] if name in DH_ON_SERVER else [])
        (self.server_dir / "server.properties").write_text(server_properties(self.port, VIEW[name]), encoding="utf-8")
        latest = self.server_dir / "logs" / "latest.log"
        before = latest.stat().st_mtime if latest.is_file() else None
        process = self.gradle("gradle-server-{}-{}.log".format(index, name), ":{}:dhServer".format(self.args.node))
        deadline = time.time() + SERVER_START
        while time.time() < deadline:
            if process.poll() is not None:
                raise SystemExit("the server's Gradle build ended before the server was up; see gradle-server-{}-{}.log".format(index, name))
            if latest.is_file() and latest.stat().st_mtime != before and "Done (" in latest.read_text(encoding="utf-8", errors="replace"):
                self.log("server up for phase {} (view-distance {}, DH on the server: {})".format(name, VIEW[name], name in DH_ON_SERVER))
                return process
            time.sleep(2)
        raise SystemExit("the server wasn't up within {} s".format(SERVER_START))

    def save_server_log(self, index, name):
        latest = self.server_dir / "logs" / "latest.log"
        if latest.is_file():
            shutil.copyfile(latest, self.out / "server-{}-{}.log".format(index, name))

    def main(self):
        self.prepare()
        client = None
        server = None
        try:
            server = self.start_server(0, self.phases[0][0])
            client = self.gradle("gradle-client.log", ":{}:e2eClient".format(self.args.node), "-Pe2e.driver=undo",
                                 "-Pe2e.instance=" + str(self.instance), "-Pe2e.jvmArgsFile=" + str(self.run_dir / "jvm-dh.txt"))
            for index, (name, seconds) in enumerate(self.phases):
                if index > 0:
                    server = self.start_server(index, name)
                # The driver ends the phase with /stop (it joins within SERVER_START, stays `seconds`, then turns around).
                budget = int(seconds) + SERVER_START + 180
                limit = time.time() + budget
                while server.poll() is None and time.time() < limit:
                    if client.poll() is not None:
                        raise SystemExit("the client ended during phase " + name)
                    time.sleep(2)
                if server.poll() is None:
                    raise SystemExit("phase {}: the server is still up {} s after it started".format(name, budget))
                self.log("server stopped after phase {} (Gradle exit {})".format(name, server.returncode))
                self.save_server_log(index, name)
            client.wait(timeout=CLIENT_TIMEOUT)
            self.log("client exited (Gradle exit {})".format(client.returncode))
        finally:
            for process in (client, server):
                if process is not None and process.poll() is None:
                    process.terminate()
            self.kill_own()
            self.evidence()
        driver = json.loads((self.out / "driver-dh.json").read_text(encoding="utf-8")) if (self.out / "driver-dh.json").is_file() else {}
        self.log("driver: ok={} error={}".format(driver.get("ok"), driver.get("error")))
        return 0 if driver.get("ok") else 1

    def kill_own(self):
        """Only java processes whose command line names this run's folder (unique) are ours: never another."""
        for pid, command_line in self_update_e2e.Run.java_processes(None):
            if self.run_dir.name in command_line:
                self.log("killing own process {}: {}".format(pid, command_line[:160]))
                self_update_e2e.kill_process(pid)

    def evidence(self):
        for shot in sorted((self.instance / "screenshots").glob("dh-*.png")) if (self.instance / "screenshots").is_dir() else []:
            shutil.copyfile(shot, self.out / shot.name)
        client_log = self.instance / "logs" / "latest.log"
        if client_log.is_file():
            lines = [line for line in client_log.read_text(encoding="utf-8", errors="replace").splitlines()
                     if "DistantHorizons" in line or "Distant Horizons" in line or "[E2E DH]" in line or "RigTune" in line]
            (self.out / "client-dh-lines.log").write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.log("evidence in " + str(self.out))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--node", default="26.2")
    parser.add_argument("--rigtune-jar", required=True)
    parser.add_argument("--dh-jar", required=True, help="Distant Horizons for the node (a copy; the player's instance is only read)")
    parser.add_argument("--fabric-api", required=True)
    parser.add_argument("--sodium")
    parser.add_argument("--work", required=True)
    parser.add_argument("--phases", default="explore:90,limited:90,dhserver:240")
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    args = parser.parse_args(argv)
    if not args.java_home:
        parser.error("set JAVA_HOME or pass --java-home")
    return NoteRun(args).main()


if __name__ == "__main__":
    sys.exit(main())
