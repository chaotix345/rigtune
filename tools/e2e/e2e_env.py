"""Environment for the self-update end-to-end test: TLS material, the JVM hosts file, JVM arguments and the fake
Modrinth catalog. See tools/e2e/README.md."""

import datetime
import json
import subprocess
import zipfile
from dataclasses import dataclass
from pathlib import Path

API_HOST = "api.modrinth.com"
CDN_HOST = "cdn.modrinth.com"
RAW_HOST = "raw.githubusercontent.com"
REDIRECTED_HOSTS = (API_HOST, CDN_HOST, RAW_HOST)
RULES_URL_DIR = "/chaotix345/rigtune/main/rules/"
STORE_PASSWORD = "rigtune-e2e"
PROJECT_ID = "E2ERigTn"
FABRIC_API_PROJECT = "P7dR8mSH"


@dataclass(frozen=True)
class Tls:
    keystore: Path
    truststore: Path
    password: str


def hosts_file_text(hostname, include_local=True):
    """jdk.net.hosts.file content: the redirected hosts on 127.0.0.1. Every host not listed fails to resolve, so
    localhost and the machine's own name are listed too (InetAddress.getLocalHost looks the name up)."""
    lines = ["# RigTune self-update E2E: only these names resolve inside the game JVM"]
    lines += ["127.0.0.1 " + host for host in REDIRECTED_HOSTS]
    if include_local:
        lines.append("127.0.0.1 localhost")
        if hostname and hostname.lower() != "localhost":
            lines.append("127.0.0.1 " + hostname)
    return "\n".join(lines) + "\n"


def keytool_commands(keytool, tls_dir, password=STORE_PASSWORD):
    """A self-signed server certificate for every redirected host, and a PKCS12 truststore holding only it."""
    tls_dir = Path(tls_dir)
    keystore = str(tls_dir / "server.p12")
    cert = str(tls_dir / "server.cer")
    truststore = str(tls_dir / "truststore.p12")
    san = ",".join(["dns:" + host for host in REDIRECTED_HOSTS] + ["dns:localhost", "ip:127.0.0.1"])
    return [
        [str(keytool), "-genkeypair", "-alias", "fake-modrinth", "-keyalg", "RSA", "-keysize", "2048", "-validity", "30",
         "-dname", "CN=" + API_HOST, "-ext", "SAN=" + san,
         "-keystore", keystore, "-storetype", "PKCS12", "-storepass", password],
        [str(keytool), "-exportcert", "-alias", "fake-modrinth", "-keystore", keystore, "-storetype", "PKCS12",
         "-storepass", password, "-file", cert],
        [str(keytool), "-importcert", "-noprompt", "-alias", "fake-modrinth", "-file", cert,
         "-keystore", truststore, "-storetype", "PKCS12", "-storepass", password],
    ]


def make_tls(tls_dir, java_home):
    tls_dir = Path(tls_dir)
    tls_dir.mkdir(parents=True, exist_ok=True)
    keytool = Path(java_home) / "bin" / "keytool"
    for command in keytool_commands(keytool, tls_dir):
        subprocess.run(command, check=True, capture_output=True, text=True)
    return Tls(tls_dir / "server.p12", tls_dir / "truststore.p12", STORE_PASSWORD)


def jvm_args(hosts_file, tls):
    return [
        "-Djdk.net.hosts.file=" + str(Path(hosts_file).resolve()),
        "-Djavax.net.ssl.trustStore=" + str(Path(tls.truststore).resolve()),
        "-Djavax.net.ssl.trustStorePassword=" + tls.password,
        "-Djavax.net.ssl.trustStoreType=PKCS12",
    ]


def mod_json(jar):
    """fabric.mod.json of a mod jar, as a dict."""
    with zipfile.ZipFile(jar) as archive:
        return json.loads(archive.read("fabric.mod.json").decode("utf-8"))


def _utc(moment):
    return moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def test_mod_jar(path, mod_id, version="1.0.0", name=None):
    """A minimal Fabric mod: a fabric.mod.json and nothing else, which Fabric Loader loads like any other mod."""
    path = Path(path)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fabric.mod.json", json.dumps({
            "schemaVersion": 1, "id": mod_id, "version": version, "name": name or "RigTune E2E test mod " + mod_id,
            "description": "Test-only mod for tools/e2e; it does nothing.", "license": "MIT", "environment": "*",
            "depends": {"fabricloader": ">=0.19.5"}}, indent=2))
    return path


def _file(jar):
    return str(Path(jar).resolve()).replace(chr(92), "/")


def catalog(old_jar, new_jar, game_version, rules_files, now=None, extra_projects=()):
    """The fake server's catalog: one RigTune project with the installed (old) version and the update (new), published
    a day later, both for Fabric and game_version, plus the rules files served at their raw.githubusercontent.com
    paths. File URLs use the real CDN host, so downloads are checked against https://cdn.modrinth.com/. Without an old
    jar, RigTune has only the new version (nothing to update to). extra_projects: (project id, slug, jar) of other mods,
    one version each, with no dependencies."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    versions = []
    for version_id, jar, published in (("E2Eold01", old_jar, now - datetime.timedelta(days=1)), ("E2Enew01", new_jar, now)):
        if jar is None:
            continue
        versions.append({
            "id": version_id,
            "version_number": mod_json(jar)["version"],
            "version_type": "release",
            "date_published": _utc(published),
            "game_versions": [game_version],
            "loaders": ["fabric"],
            "file": _file(jar),
            "dependencies": [{"project_id": FABRIC_API_PROJECT, "version_id": None, "file_name": None,
                              "dependency_type": "required"}],
        })
    projects = [{"id": PROJECT_ID, "slug": "rigtune", "title": "RigTune", "versions": versions}]
    for index, (project_id, slug, jar) in enumerate(extra_projects):
        projects.append({"id": project_id, "slug": slug, "title": slug, "versions": [{
            "id": "E2Eext{:02d}".format(index), "version_number": mod_json(jar)["version"], "version_type": "release",
            "date_published": _utc(now - datetime.timedelta(hours=1)), "game_versions": [game_version],
            "loaders": ["fabric"], "file": _file(jar), "dependencies": []}]})
    return {
        "hosts": {"api": API_HOST, "cdn": CDN_HOST},
        "cdnBase": "https://" + CDN_HOST,
        "projects": projects,
        "static": [{"host": RAW_HOST, "path": RULES_URL_DIR + Path(f).name, "file": _file(f),
                    "contentType": "text/plain; charset=utf-8"} for f in rules_files],
    }
