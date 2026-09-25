#!/usr/bin/env python3
"""Checks whether RigTune's compiled code still links against another Minecraft version: every member RigTune's
classes reference in Minecraft, its libraries, Fabric Loader, Fabric API and Mod Menu is resolved on both
versions' jars (from the Gradle and Loom caches), every referenced class is javap-diffed, and the strings RigTune
uses by name (reflection, the mixin target) are checked. See tools/MC_VERSIONS.md.

    python tools/mc_apidiff.py <prev> <mc> [--fabric-api V] [--modmenu V] [--out DIR]
"""

import re
import struct
import zipfile
from typing import NamedTuple

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

    refs, types, dotted, strings = set(), set(), set(), set()
    for info in infos:
        refs.update(r for r in info.refs if not r[1].startswith("[") and foreign(r[1]))
        types.update(t for t in info.descriptor_types if foreign(t))
        strings.update(info.strings | info.annotation_strings)
        dotted.update(s for s in info.strings if DOTTED_CLASS.fullmatch(s))
    types.update(r[1] for r in refs)
    return RawRefs(refs, types, dotted, strings, own, count)


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
