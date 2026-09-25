#!/usr/bin/env python3
"""Checks whether RigTune's compiled code still links against another Minecraft version: every member RigTune's
classes reference in Minecraft, its libraries, Fabric Loader, Fabric API and Mod Menu is resolved on both
versions' jars (from the Gradle and Loom caches), every referenced class is javap-diffed, and the strings RigTune
uses by name (reflection, the mixin target) are checked. See tools/MC_VERSIONS.md.

    python tools/mc_apidiff.py <prev> <mc> [--fabric-api V] [--modmenu V] [--out DIR]
"""

import argparse
import difflib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import NamedTuple

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SETS = ("main", "client", "gametest", "e2e", "e2eUndo")
JAVA_MAJOR = 25
GROUPS = (("net/minecraft/", "Minecraft"), ("com/mojang/", "Mojang"), ("net/fabricmc/", "Fabric"),
          ("com/terraformersmc/", "Mod Menu"), ("org/lwjgl/", "LWJGL"))

JDK_PREFIXES = ("java/", "javax/", "jdk/", "sun/", "com/sun/")
DESCRIPTOR_TYPE = re.compile(r"L((?:[\w$]+/)+[\w$]+)[;<]")
DOTTED_CLASS = re.compile(r"[a-z][\w]*(?:\.[a-z_][\w]*)+\.[A-Z][\w$]*")
ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED, ACC_STATIC, ACC_INTERFACE = 0x1, 0x2, 0x4, 0x8, 0x200


class ClassInfo:
    __slots__ = ("name", "super_name", "interfaces", "access", "fields", "methods", "refs", "strings",
                 "annotation_strings", "descriptor_types")

    def __init__(self):
        self.interfaces, self.fields, self.methods = [], {}, {}
        self.refs, self.strings, self.annotation_strings, self.descriptor_types = set(), set(), set(), set()


class _Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, fmt):
        values = struct.unpack_from(fmt, self.data, self.pos)
        self.pos += struct.calcsize(fmt)
        return values if len(values) > 1 else values[0]

    def skip(self, n):
        self.pos += n


REF_KINDS = {9: "field", 10: "method", 11: "imethod"}
POOL_SIZES = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}


def parse_class(data):
    """Reads what the API diff needs from a class file (JVMS 4): the constant pool's member refs, strings and
    type names, the declared fields and methods with their access flags, and annotation string values."""
    if len(data) < 10 or data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    r = _Reader(data)
    r.skip(8)
    count = r.take(">H")
    pool = [None] * count
    i = 1
    while i < count:
        tag = r.take(">B")
        if tag == 1:
            length = r.take(">H")
            pool[i] = (1, data[r.pos:r.pos + length].decode("utf-8", errors="replace"))
            r.skip(length)
        elif tag in (7, 8, 16, 19, 20):
            pool[i] = (tag, r.take(">H"))
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[i] = (tag, r.take(">HH"))
        elif tag in POOL_SIZES:
            r.skip(POOL_SIZES[tag])
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 2 if tag in (5, 6) else 1

    def utf8(index):
        return pool[index][1]

    def class_name(index):
        return utf8(pool[index][1])

    info = ClassInfo()
    for entry in pool:
        if entry is None:
            continue
        tag, value = entry
        if tag == 1:
            info.descriptor_types.update(DESCRIPTOR_TYPE.findall(value))
        elif tag == 7:
            name = utf8(value)
            if name.startswith("["):
                info.descriptor_types.update(DESCRIPTOR_TYPE.findall(name))
            else:
                info.descriptor_types.add(name)
        elif tag == 8:
            info.strings.add(utf8(value))
        elif tag in REF_KINDS:
            owner_index, nat_index = value
            name_index, desc_index = pool[nat_index][1]
            info.refs.add((REF_KINDS[tag], class_name(owner_index), utf8(name_index), utf8(desc_index)))

    def annotations(reader):
        for _ in range(reader.take(">H")):
            annotation(reader)

    def annotation(reader):
        reader.skip(2)
        for _ in range(reader.take(">H")):
            reader.skip(2)
            element(reader)

    def element(reader):
        tag = chr(reader.take(">B"))
        if tag == "s":
            info.annotation_strings.add(utf8(reader.take(">H")))
        elif tag == "e":
            reader.skip(4)
        elif tag == "@":
            annotation(reader)
        elif tag == "[":
            for _ in range(reader.take(">H")):
                element(reader)
        else:
            reader.skip(2)

    def attributes(reader):
        for _ in range(reader.take(">H")):
            name_index, length = reader.take(">HI")
            end = reader.pos + length
            if utf8(name_index) in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                annotations(reader)
            reader.pos = end

    info.access, this_index, super_index = r.take(">HHH")
    info.name = class_name(this_index)
    info.super_name = class_name(super_index) if super_index else None
    info.interfaces = [class_name(r.take(">H")) for _ in range(r.take(">H"))]
    for members in (info.fields, info.methods):
        for _ in range(r.take(">H")):
            access, name_index, desc_index = r.take(">HHH")
            members[(utf8(name_index), utf8(desc_index))] = access
            attributes(r)
    attributes(r)
    info.descriptor_types.discard(info.name)
    return info


class ClassIndex:
    """Classes by internal name, from jars (first jar wins), in-memory class files, then a fallback lookup."""

    def __init__(self, jars=(), classes=None, fallback=None):
        self._zips = []
        self._entries = {}
        for jar in jars:
            archive = zipfile.ZipFile(jar)
            self._zips.append(archive)
            for entry in archive.namelist():
                if entry.endswith(".class") and not entry.startswith("META-INF/"):
                    self._entries.setdefault(entry[:-6], (archive, entry))
        self._bytes = dict(classes or {})
        self._cache = {}
        self.fallback = fallback

    def get(self, name):
        if name in self._cache:
            return self._cache[name]
        info = None
        if name in self._bytes:
            info = parse_class(self._bytes[name])
        elif name in self._entries:
            archive, entry = self._entries[name]
            info = parse_class(archive.read(entry))
        elif self.fallback is not None:
            info = self.fallback(name)
        self._cache[name] = info
        return info

    def jar_of(self, name):
        entry = self._entries.get(name)
        return entry[0].filename if entry else None

    def close(self):
        for archive in self._zips:
            archive.close()


def resolve(index, kind, owner, name, desc):
    """Where (name, desc) is declared as seen from owner, like the JVM's resolution (JVMS 5.4.3): the class and its
    superclasses first, then every superinterface. Returns (declaring class, access flags) or None."""
    chain, seen = [], set()
    current = owner
    while current and current not in seen:
        seen.add(current)
        info = index.get(current)
        if info is None:
            break
        chain.append(info)
        current = info.super_name
    queue = []
    for info in chain:
        members = info.fields if kind == "field" else info.methods
        if (name, desc) in members:
            return info.name, members[(name, desc)]
        queue += info.interfaces
    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        info = index.get(current)
        if info is None:
            continue
        members = info.fields if kind == "field" else info.methods
        if (name, desc) in members:
            return info.name, members[(name, desc)]
        queue += info.interfaces
    return None


def _access_rank(access):
    if access & ACC_PUBLIC:
        return 3
    if access & ACC_PROTECTED:
        return 2
    if access & ACC_PRIVATE:
        return 0
    return 1


def compare_refs(refs, old_index, new_index):
    results = []
    for kind, owner, name, desc in sorted(refs):
        old = resolve(old_index, kind, owner, name, desc)
        new = resolve(new_index, kind, owner, name, desc)
        status = "OK"
        reason = ""
        if old is None and new is None:
            status, reason = "UNRESOLVED", "not found on either version (incomplete classpath?)"
        elif old is None:
            status, reason = "UNRESOLVED", "found only on the new version (incomplete old classpath?)"
        elif new is None:
            status, reason = "MISSING", "not found on the new version"
        else:
            if (old[1] ^ new[1]) & ACC_STATIC:
                status, reason = "CHANGED", "static changed"
            elif _access_rank(new[1]) < _access_rank(old[1]):
                status, reason = "CHANGED", "access narrowed"
            else:
                old_owner, new_owner = old_index.get(owner), new_index.get(owner)
                if old_owner and new_owner and (old_owner.access ^ new_owner.access) & ACC_INTERFACE:
                    status, reason = "CHANGED", "owner switched between class and interface"
        results.append({"kind": kind, "owner": owner, "name": name, "desc": desc, "status": status, "reason": reason,
                        "old": old[0] if old else None, "new": new[0] if new else None,
                        "old_access": old[1] if old else None, "new_access": new[1] if new else None})
    return results


class RawRefs(NamedTuple):
    refs: set
    types: set
    dotted_names: set
    strings: set
    own_prefixes: set
    class_count: dict
    name_sources: list


def _is_jdk(name):
    return name.startswith(JDK_PREFIXES)


def _own_prefix(name):
    return "/".join(name.split("/")[:3]) + "/"


def collect_rigtune(class_files):
    """class_files: (source set label, class file bytes) pairs from RigTune's build. Collects every member ref and
    type outside the JDK and outside RigTune's own packages, the strings it uses and dotted class names."""
    infos, count = [], {}
    for label, data in class_files:
        infos.append(parse_class(data))
        count[label] = count.get(label, 0) + 1
    own = {_own_prefix(i.name) for i in infos}

    def foreign(name):
        return not _is_jdk(name) and not name.startswith(tuple(own))

    refs, types, dotted, strings, sources = set(), set(), set(), set(), []
    for info in infos:
        class_refs = {r for r in info.refs if not r[1].startswith("[") and foreign(r[1])}
        class_dotted = {s for s in info.strings if DOTTED_CLASS.fullmatch(s)}
        class_types = {t for t in info.descriptor_types if foreign(t)} | {r[1] for r in class_refs}
        class_strings = info.strings | info.annotation_strings
        refs |= class_refs
        types |= class_types
        strings |= class_strings
        dotted |= class_dotted
        sources.append((frozenset(class_types | {d.replace(".", "/") for d in class_dotted}), frozenset(class_strings)))
    return RawRefs(refs, types, dotted, strings, own, count, sources)


class Scope(NamedTuple):
    refs: set
    types: set
    reflection_types: set
    other_reflection: set
    not_checked: dict


def _group(name):
    return "/".join(name.split("/")[:2])


def classify(raw, old_index):
    """Keeps what the old version's classpath has (Minecraft and its libraries, Loader, Fabric API, Mod Menu); counts
    references to anything else (compile-only mod APIs such as Iris or Distant Horizons) as not checked."""
    refs = {r for r in raw.refs if old_index.get(r[1]) is not None}
    not_checked = {}
    for r in raw.refs - refs:
        not_checked[_group(r[1])] = not_checked.get(_group(r[1]), 0) + 1
    types = {t for t in raw.types if old_index.get(t) is not None}
    reflection, other = set(), set()
    for dotted in raw.dotted_names:
        internal = dotted.replace(".", "/")
        if _is_jdk(internal) or internal.startswith(tuple(raw.own_prefixes)):
            continue
        if old_index.get(internal) is not None:
            reflection.add(internal)
        else:
            other.add(dotted)
    return Scope(refs, types | reflection, reflection, other, not_checked)


class Analysis(NamedTuple):
    scope: Scope
    refs: list
    missing_classes: list
    named_members: list
    string_changes: dict


UNIVERSAL_NAMES = {"<init>", "<clinit>", "equals", "hashCode", "toString", "values", "valueOf", "ordinal", "name",
                   "compareTo", "getClass", "clone"}


def _member_names(info):
    return {n for n, _ in info.fields} | {n for n, _ in info.methods}


def named_members(sources, types, old_index, new_index):
    """Strings that name a member of a class referenced by the same RigTune class (reflection such as
    getDeclaredField("serverRenderDistance"), a mixin's @Inject(method = ...)): is that member still declared?"""
    pairs = set()
    for class_types, class_strings in sources:
        for name in class_types & types:
            pairs |= {(name, s) for s in (class_strings & _member_names(old_index.get(name))) - UNIVERSAL_NAMES}
    found = []
    for name, member in sorted(pairs):
        new = new_index.get(name)
        present = new is not None and member in _member_names(new)
        found.append({"class": name, "name": member, "status": "OK" if present else "MISSING"})
    return found


KEY_LIKE = re.compile(r"[A-Za-z_][\w.$-]{2,}")


def string_changes(strings, types, old_index, new_index):
    """String constants (method bodies included, which javap -p doesn't show) that a referenced class gained or lost."""
    changes = {}
    for name in sorted(types):
        old, new = old_index.get(name), new_index.get(name)
        if new is None:
            continue
        removed, added = sorted(old.strings - new.strings), sorted(new.strings - old.strings)
        if removed or added:
            used = sorted(v for v in set(removed) & strings if KEY_LIKE.fullmatch(v) and v not in ("null", "true", "false"))
            changes[name] = {"removed": removed, "added": added, "removed_used": used}
    return changes


def analyse(raw, old_index, new_index):
    scope = classify(raw, old_index)
    return Analysis(
        scope,
        compare_refs(scope.refs, old_index, new_index),
        sorted(t for t in scope.types if new_index.get(t) is None),
        named_members(raw.name_sources, scope.types, old_index, new_index),
        string_changes(raw.strings, scope.types, old_index, new_index),
    )


def _member(r):
    return f"{r['owner']}.{r['name']}{r['desc']}" if r["kind"] != "field" else f"{r['owner']}.{r['name']}:{r['desc']}"


def breaking(analysis):
    lines = [f"{r['status']} {'field' if r['kind'] == 'field' else 'method'} {_member(r)}: {r['reason']}"
             for r in analysis.refs if r["status"] in ("MISSING", "CHANGED")]
    lines += [f"MISSING class {t}" for t in analysis.missing_classes]
    lines += [f"MISSING name {m['class']}.{m['name']} (a string RigTune uses)"
              for m in analysis.named_members if m["status"] == "MISSING"]
    for name, change in analysis.string_changes.items():
        lines += [f'REMOVED string "{s}" from {name} (RigTune uses it)' for s in change["removed_used"]]
    return lines


def incomplete(analysis):
    return [f"UNRESOLVED {_member(r)}: {r['reason']}" for r in analysis.refs if r["status"] == "UNRESOLVED"]


HEADER_NAME = re.compile(r"\b(?:class|interface) ([\w.$]+)")


def split_javap(text):
    """javap output → {internal class name: its block}, without the "Compiled from" lines."""
    blocks, current, lines = {}, None, []
    for line in text.splitlines():
        if line.startswith("Compiled from ") or line.startswith("Classfile ") or not line.strip():
            continue
        if current is None:
            if line[0].isspace() or line.startswith("}"):
                continue
            match = HEADER_NAME.search(line)
            if not match:
                continue
            current, lines = match.group(1).replace(".", "/"), [line]
        else:
            lines.append(line)
            if line.startswith("}"):
                blocks[current] = "\n".join(lines)
                current = None
    return blocks


def normalize_bytecode(text):
    out = []
    for line in text.splitlines():
        if line.startswith(("Compiled from", "Classfile")) or "Last modified" in line or "SHA-256" in line \
                or "MD5 checksum" in line:
            continue
        out.append(" ".join(re.sub(r"#\d+(?:[.:]#\d+)*", "#", line).split()))
    return out


class SetupError(Exception):
    pass


class Classpath(NamedTuple):
    jars: list
    missing_libraries: list
    description: str


def read_properties(path):
    props = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith(("#", "!")) and "=" in line:
            key, value = line.split("=", 1)
            props[key.strip()] = value.strip()
    return props


def node_props(root, mc, fabric_api=None, modmenu=None):
    path = Path(root) / "versions" / mc / "gradle.properties"
    props = read_properties(path) if path.is_file() else {}
    if fabric_api:
        props["fabric_api_version"] = fabric_api
    if modmenu:
        props["modmenu_version"] = modmenu
    missing = [k for k in ("fabric_api_version", "modmenu_version") if not props.get(k)]
    if missing:
        raise SetupError(f"no {' or '.join(missing)} for {mc}: versions/{mc}/gradle.properties doesn't have it, "
                         "so pass --fabric-api and --modmenu")
    return {k: props[k] for k in ("fabric_api_version", "modmenu_version")}


def _one(pattern_dir, filename):
    if pattern_dir.is_dir():
        for hash_dir in sorted(pattern_dir.iterdir()):
            candidate = hash_dir / filename
            if candidate.is_file():
                return candidate
    return None


HOW_TO_GET = """They appear once Gradle has built {mc}. For a version that isn't a node here, use a throwaway worktree:
  git worktree add ../rigtune-apidiff-tmp HEAD && cd ../rigtune-apidiff-tmp
  python tools/add_mc_version.py {mc} --prerelease-ok
  ./gradlew :{mc}:compileJava      (Loom sets up Minecraft; Gradle fetches Fabric API, Mod Menu and the libraries)
  cd - && git worktree remove --force ../rigtune-apidiff-tmp
The Gradle caches are per user, so the jars stay after the worktree is gone."""


POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def _pom_jars(pom, modules, jars, missing):
    """The jars of a pom's dependencies; a pom-only dependency (fabric-api-deprecated) is followed to its own."""
    for dep in ET.parse(pom).getroot().iterfind("m:dependencies/m:dependency", POM_NS):
        group, name, version = (dep.findtext(f"m:{k}", namespaces=POM_NS) for k in ("groupId", "artifactId", "version"))
        jar = _one(modules / group / name / version, f"{name}-{version}.jar")
        child = _one(modules / group / name / version, f"{name}-{version}.pom")
        if jar:
            if jar not in jars:
                jars.append(jar)
        elif child is not None and ET.parse(child).getroot().findtext("m:packaging", namespaces=POM_NS) == "pom":
            _pom_jars(child, modules, jars, missing)
        else:
            missing.append(modules / group / name / version / "*" / f"{name}-{version}.jar")


def find_classpath(mc, props, loader_version, gradle_home):
    """The jars RigTune compiles against for one Minecraft version, from the Gradle and Loom caches."""
    caches = Path(gradle_home) / "caches"
    modules = caches / "modules-2" / "files-2.1"
    loom = caches / "fabric-loom"
    jars, missing, missing_libraries = [], [], []
    for side in ("clientonly", "common"):
        jar = loom / "minecraftMaven" / "net" / "minecraft" / f"minecraft-{side}-deobf" / mc / f"minecraft-{side}-deobf-{mc}.jar"
        (jars if jar.is_file() else missing).append(jar)
    info_path = loom / mc / "mojang_minecraft_info.json"
    libraries = 0
    if info_path.is_file():
        for library in json.loads(info_path.read_text(encoding="utf-8")).get("libraries", []):
            parts = library.get("name", "").split(":")
            artifact = library.get("downloads", {}).get("artifact")
            if len(parts) != 3 or not artifact:
                continue
            group, name, version = parts
            base = modules / group / name / version
            jar = base / artifact.get("sha1", "") / Path(artifact["path"]).name
            jar = jar if jar.is_file() else _one(base, f"{name}-{version}.jar")
            if jar:
                jars.append(jar)
                libraries += 1
            else:
                missing_libraries.append(library["name"])
    else:
        missing.append(info_path)
    fabric_api = props["fabric_api_version"]
    pom = _one(modules / "net.fabricmc.fabric-api" / "fabric-api" / fabric_api, f"fabric-api-{fabric_api}.pom")
    module_jars = []
    if pom is None:
        missing.append(modules / "net.fabricmc.fabric-api" / "fabric-api" / fabric_api / "*" / f"fabric-api-{fabric_api}.pom")
    else:
        _pom_jars(pom, modules, module_jars, missing)
    jars += module_jars
    module_count = len(module_jars)
    modmenu = props["modmenu_version"]
    for group, name, version in (("com.terraformersmc", "modmenu", modmenu), ("net.fabricmc", "fabric-loader", loader_version)):
        jar = _one(modules / group / name / version, f"{name}-{version}.jar")
        if jar:
            jars.append(jar)
        else:
            missing.append(modules / group / name / version / "*" / f"{name}-{version}.jar")
    if missing:
        listed = "\n".join(f"  - {p.as_posix()}" for p in missing)
        raise SetupError(f"missing from the Gradle caches for {mc}:\n{listed}\n{HOW_TO_GET.format(mc=mc)}")
    description = (f"Minecraft {mc} (2 jars), {libraries} Mojang libraries, Fabric API {fabric_api} ({module_count} "
                   f"modules), Mod Menu {modmenu}, Fabric Loader {loader_version}")
    return Classpath(jars, missing_libraries, description)


def java_tool(name, java_home):
    exe = name + (".exe" if os.name == "nt" else "")
    if java_home and (Path(java_home) / "bin" / exe).is_file():
        return str(Path(java_home) / "bin" / exe)
    found = shutil.which(name)
    if not found:
        raise SetupError(f"{name} not found: set JAVA_HOME to a JDK {JAVA_MAJOR}")
    return found


class JdkClasses:
    """java/* classes from the running JDK's lib/modules (extracted once with jimage), for members that Minecraft
    classes inherit from the JDK (Enum.ordinal, Object.hashCode, ...)."""

    def __init__(self, jimage, modules, workdir):
        self.jimage, self.modules, self.dir = jimage, modules, Path(workdir) / "jdk"
        self.extracted = False

    def __call__(self, name):
        if not name.startswith("java/"):
            return None
        if not self.extracted:
            self.extracted = True
            proc = subprocess.run([self.jimage, "extract", "--dir", str(self.dir), "--include", "regex:/java[.]base/java/.*",
                                   str(self.modules)], capture_output=True, text=True)
            if proc.returncode != 0:
                raise SetupError(f"jimage extract failed: {proc.stderr.strip()}")
        path = self.dir / "java.base" / f"{name}.class"
        return parse_class(path.read_bytes()) if path.is_file() else None


def _chunks(items, size):
    items = list(items)
    for i in range(0, len(items), size):
        yield items[i:i + size]


def run_javap(javap, args):
    proc = subprocess.run([javap, "-J-Dstdout.encoding=UTF-8"] + args, capture_output=True)
    return proc.stdout.decode("utf-8", errors="replace")


def javap_blocks(javap, index, names):
    by_jar = {}
    for name in names:
        jar = index.jar_of(name)
        if jar:
            by_jar.setdefault(jar, []).append(name)
    blocks = {}
    for jar, group in sorted(by_jar.items()):
        for chunk in _chunks(sorted(group), 60):
            blocks.update(split_javap(run_javap(javap, ["-p", "-s", "-constants", "-cp", jar] +
                                                [n.replace("/", ".") for n in chunk])))
    return blocks


def class_dirs(root, node, sets):
    base = Path(root) / "versions" / node / "build" / "classes" / "java"
    return {s: base / s for s in sets if (base / s).is_dir()}


def class_files(dirs):
    for label, base in sorted(dirs.items()):
        for path in sorted(base.rglob("*.class")):
            yield label, path


def bytecode_diffs(javap, old_dirs, new_dirs):
    """RigTune's own classes compiled for each version, javap -c with constant-pool indices stripped."""
    diffs, only_old, only_new, compared = {}, [], [], 0
    for label in sorted(set(old_dirs) & set(new_dirs)):
        old = {p.relative_to(old_dirs[label]).as_posix() for p in old_dirs[label].rglob("*.class")}
        new = {p.relative_to(new_dirs[label]).as_posix() for p in new_dirs[label].rglob("*.class")}
        only_old += [f"{label}/{p}" for p in sorted(old - new)]
        only_new += [f"{label}/{p}" for p in sorted(new - old)]
        for chunk in _chunks(sorted(old & new), 60):
            dumps = []
            for dirs in (old_dirs, new_dirs):
                text = run_javap(javap, ["-c", "-p", "-constants"] + [str(dirs[label] / p) for p in chunk])
                dumps.append({k: normalize_bytecode(v) for k, v in split_javap(text).items()})
            for rel in chunk:
                compared += 1
                name = rel[:-len(".class")]
                a, b = dumps[0].get(name), dumps[1].get(name)
                if a != b:
                    diffs[f"{label}/{rel}"] = list(difflib.unified_diff(a or [], b or [], "old", "new", n=1, lineterm=""))
    return {"compared": compared, "diffs": diffs, "only_old": only_old, "only_new": only_new}


def group_counts(refs):
    counts = {label: 0 for _, label in GROUPS}
    counts["other Minecraft libraries"] = 0
    for r in refs:
        label = next((lbl for prefix, lbl in GROUPS if r[1].startswith(prefix)), "other Minecraft libraries")
        counts[label] += 1
    return counts


def render_summary(prev, mc, raw, analysis, classpaths, class_status, bytecode, sets_dir):
    a = analysis
    lines = [f"mc_apidiff {prev} -> {mc}", ""]
    counts = ", ".join(f"{k} {v}" for k, v in raw.class_count.items())
    lines.append(f"RigTune classes ({sets_dir}): {counts} ({sum(raw.class_count.values())} files)")
    for version, cp in classpaths.items():
        lines.append(f"Classpath {version}: {cp.description}")
        if cp.missing_libraries:
            lines.append(f"  libraries not in the cache (not needed unless a reference points there): "
                         f"{', '.join(cp.missing_libraries)}")
    status = {}
    for r in a.refs:
        status[r["status"]] = status.get(r["status"], 0) + 1
    groups = group_counts(a.scope.refs)
    core = sum(v for k, v in groups.items() if k != "other Minecraft libraries")
    lines.append("")
    lines.append(f"Member references: {len(a.refs)} ({', '.join(f'{k} {v}' for k, v in groups.items())}; "
                 f"{core} without the other libraries)")
    lines.append("  " + ", ".join(f"{k} {status.get(k, 0)}" for k in ("OK", "MISSING", "CHANGED", "UNRESOLVED")))
    if a.scope.not_checked:
        lines.append("  not checked (not on the classpath, compile-only mod APIs): "
                     + ", ".join(f"{k} {v}" for k, v in sorted(a.scope.not_checked.items())))
    same = sorted(n for n, s in class_status.items() if s == "SAME")
    diff = sorted(n for n, s in class_status.items() if s == "DIFF")
    lines.append(f"Referenced classes: {len(a.scope.types)}: SAME {len(same)}, DIFF {len(diff)}, "
                 f"MISSING {len(a.missing_classes)}")
    for name in diff:
        lines.append(f"  DIFF {name}")
    for name in a.missing_classes:
        lines.append(f"  MISSING {name}")
    lines.append(f"Reflection targets by class name: {', '.join(sorted(a.scope.reflection_types)) or 'none'}")
    if a.scope.other_reflection:
        lines.append(f"  outside the checked classpath (check by hand): {', '.join(sorted(a.scope.other_reflection))}")
    ok_names = [f"{m['class'].rsplit('/', 1)[-1]}.{m['name']}" for m in a.named_members if m["status"] == "OK"]
    lines.append(f"Strings naming a member of a referenced class: {len(a.named_members)} "
                 f"({len(ok_names)} still there): {', '.join(ok_names)}")
    lines.append(f"String constants changed in referenced classes: {len(a.string_changes)}")
    for name, change in a.string_changes.items():
        used = f"; RigTune uses {change['removed_used']}" if change["removed_used"] else ""
        lines.append(f"  {name}: -{len(change['removed'])} +{len(change['added'])}{used}")
        for sign, key in (("-", "removed"), ("+", "added")):
            for value in change[key][:5]:
                lines.append(f"      {sign} {json.dumps(value[:100])}")
            if len(change[key]) > 5:
                lines.append(f"      {sign} ... {len(change[key]) - 5} more in report.json")
    lines.append("")
    if bytecode is None:
        lines.append(f"RigTune bytecode: skipped (versions/{mc}/build/classes/java doesn't exist: {mc} isn't a built node)")
    else:
        lines.append(f"RigTune bytecode ({prev} build vs {mc} build): {bytecode['compared']} classes compared, "
                     f"{len(bytecode['diffs'])} differ, {len(bytecode['only_old'])} only in {prev}, "
                     f"{len(bytecode['only_new'])} only in {mc}")
        for name in list(bytecode["diffs"]) + bytecode["only_old"] + bytecode["only_new"]:
            lines.append(f"  {name}")
    problems = breaking(a)
    gaps = incomplete(a)
    lines.append("")
    if problems or gaps:
        lines.append(f"RESULT: {len(problems)} breaking change(s), {len(gaps)} unresolved reference(s):")
        lines += [f"  {p}" for p in problems + gaps]
    else:
        review = f"; review the {len(diff)} changed class(es) above" if diff else ""
        lines.append(f"RESULT: no breaking change found{review}")
    if bytecode and (bytecode["diffs"] or bytecode["only_old"] or bytecode["only_new"]):
        lines.append("RESULT: RigTune's own bytecode differs between the two builds: review the classes listed above")
    return lines


def main(argv=None, *, root=None, out=None, err=None):
    out = out or sys.stdout
    err = err or sys.stderr
    parser = argparse.ArgumentParser(description="Diff the Minecraft/Fabric/Mod Menu API RigTune uses between two versions.")
    parser.add_argument("prev", help="the version RigTune's classes were compiled for (a node with a build), e.g. 26.3")
    parser.add_argument("mc", help="the version to check against, e.g. 26.4-snapshot-1")
    parser.add_argument("--fabric-api", help="Fabric API version for <mc> (default: versions/<mc>/gradle.properties)")
    parser.add_argument("--modmenu", help="Mod Menu version for <mc> (default: versions/<mc>/gradle.properties)")
    parser.add_argument("--sets", default=",".join(DEFAULT_SETS), help="source sets to read (default: %(default)s)")
    parser.add_argument("--gradle-home", help="Gradle user home (default: $GRADLE_USER_HOME or ~/.gradle)")
    parser.add_argument("--out", help="write summary.txt, report.json and the class diffs to this directory")
    parser.add_argument("--dumps", action="store_true", help="with --out, also write both versions' javap dumps")
    args = parser.parse_args(argv)
    root = Path(root) if root else ROOT
    gradle_home = Path(args.gradle_home or os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    sets = [s for s in args.sets.split(",") if s]
    old_index = new_index = None
    try:
        javap = java_tool("javap", os.environ.get("JAVA_HOME"))
        jimage = java_tool("jimage", os.environ.get("JAVA_HOME"))
        java_home = Path(javap).resolve().parent.parent
        old_dirs = class_dirs(root, args.prev, sets)
        if not old_dirs:
            raise SetupError(f"no compiled classes under versions/{args.prev}/build/classes/java/{{{','.join(sets)}}}: "
                             f"run ./gradlew :{args.prev}:classes :{args.prev}:clientClasses :{args.prev}:gametestClasses "
                             f":{args.prev}:compileE2eUndoJava :{args.prev}:compileE2eJava -Pe2e.oldJar=<rigtune-0.1.0.jar>")
        missing_sets = [s for s in sets if s not in old_dirs]
        loader = read_properties(root / "gradle.properties").get("loader_version")
        classpaths = {
            args.prev: find_classpath(args.prev, node_props(root, args.prev), loader, gradle_home),
            args.mc: find_classpath(args.mc, node_props(root, args.mc, args.fabric_api, args.modmenu), loader, gradle_home),
        }
        with tempfile.TemporaryDirectory(prefix="mc_apidiff-") as work:
            jdk = JdkClasses(jimage, java_home / "lib" / "modules", work)
            old_index = ClassIndex(classpaths[args.prev].jars, fallback=jdk)
            new_index = ClassIndex(classpaths[args.mc].jars, fallback=jdk)
            raw = collect_rigtune((label, path.read_bytes()) for label, path in class_files(old_dirs))
            analysis = analyse(raw, old_index, new_index)
            both = sorted(t for t in analysis.scope.types if t not in analysis.missing_classes)
            old_blocks = javap_blocks(javap, old_index, both)
            new_blocks = javap_blocks(javap, new_index, both)
    except SetupError as e:
        print(f"mc_apidiff: {e}", file=err)
        return 2
    finally:
        for index in (old_index, new_index):
            if index is not None:
                index.close()
    class_status = {n: "SAME" if old_blocks.get(n) == new_blocks.get(n) else "DIFF" for n in both}
    new_dirs = class_dirs(root, args.mc, sets)
    bytecode = bytecode_diffs(javap, old_dirs, new_dirs) if new_dirs else None
    sets_dir = f"versions/{args.prev}/build/classes/java"
    lines = render_summary(args.prev, args.mc, raw, analysis, classpaths, class_status, bytecode, sets_dir)
    if missing_sets:
        lines.insert(3, f"  not compiled, so not checked: {', '.join(missing_sets)}")
    for line in lines:
        print(line, file=out)
    if args.out:
        target = Path(args.out)
        target.mkdir(parents=True, exist_ok=True)
        (target / "summary.txt").write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        report = {
            "prev": args.prev, "mc": args.mc, "class_count": raw.class_count, "missing_sets": missing_sets,
            "classpath": {v: cp.description for v, cp in classpaths.items()},
            "member_refs": analysis.refs, "classes": class_status, "missing_classes": analysis.missing_classes,
            "reflection_types": sorted(analysis.scope.reflection_types),
            "other_reflection": sorted(analysis.scope.other_reflection), "not_checked": analysis.scope.not_checked,
            "named_members": analysis.named_members, "string_changes": analysis.string_changes,
            "bytecode": bytecode, "breaking": breaking(analysis), "unresolved": incomplete(analysis),
        }
        (target / "report.json").write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8", newline="\n")
        for name in (n for n, s in class_status.items() if s == "DIFF"):
            diff = difflib.unified_diff(old_blocks[name].splitlines(), new_blocks[name].splitlines(), args.prev, args.mc,
                                        n=0, lineterm="")
            text = "\n".join(diff) + "\n"
            change = analysis.string_changes.get(name)
            if change:
                text += f"\nString constants removed: {change['removed']}\nString constants added: {change['added']}\n"
            (target / f"diff-{name.replace('/', '.')}.txt").write_text(text, encoding="utf-8", newline="\n")
        if args.dumps:
            for version, blocks in ((args.prev, old_blocks), (args.mc, new_blocks)):
                text = "\n".join(blocks[n] for n in sorted(blocks)) + "\n"
                (target / f"dump-{version}.txt").write_text(text, encoding="utf-8", newline="\n")
    problems = breaking(analysis) + incomplete(analysis)
    return 1 if problems or (bytecode and (bytecode["diffs"] or bytecode["only_old"] or bytecode["only_new"])) else 0


if __name__ == "__main__":
    sys.exit(main())
