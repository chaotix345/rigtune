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
ACC_PUBLIC, ACC_PRIVATE, ACC_PROTECTED, ACC_STATIC, ACC_FINAL = 0x1, 0x2, 0x4, 0x8, 0x10
ACC_INTERFACE, ACC_ABSTRACT = 0x200, 0x400


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
SKIPPED_TAGS = {3: 4, 4: 4, 5: 8, 6: 8}
LAMBDA_FACTORIES = {("java/lang/invoke/LambdaMetafactory", "metafactory"),
                    ("java/lang/invoke/LambdaMetafactory", "altMetafactory")}


def parse_class(data):
    """Reads what the API diff needs from a class file (JVMS 4): the constant pool's member refs (plus the interface
    method each lambda or method reference implements), strings and type names, the declared fields and methods
    with their access flags, and annotation string values (class, field and method annotations)."""
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
        elif tag == 15:
            pool[i] = (tag, r.take(">BH"))
        elif tag in SKIPPED_TAGS:
            r.skip(SKIPPED_TAGS[tag])
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        i += 2 if tag in (5, 6) else 1

    def utf8(index):
        return pool[index][1]

    def class_name(index):
        return utf8(pool[index][1])

    def member_ref(index):
        tag, (owner_index, nat_index) = pool[index]
        name_index, desc_index = pool[nat_index][1]
        return REF_KINDS[tag], class_name(owner_index), utf8(name_index), utf8(desc_index)

    info = ClassInfo()
    for index, entry in enumerate(pool):
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
            info.refs.add(member_ref(index))

    bootstraps = []

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
            name = utf8(name_index)
            if name in ("RuntimeVisibleAnnotations", "RuntimeInvisibleAnnotations"):
                annotations(reader)
            elif name == "BootstrapMethods":
                for _ in range(reader.take(">H")):
                    method_ref, arg_count = reader.take(">HH")
                    bootstraps.append((method_ref, [reader.take(">H") for _ in range(arg_count)]))
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

    # A lambda or method reference is an invokedynamic of LambdaMetafactory: the call site's name is the interface
    # method, its return type the interface, and the first bootstrap argument the erased method type.
    for entry in pool:
        if entry is None or entry[0] != 18 or entry[1][0] >= len(bootstraps):
            continue
        method_ref, args = bootstraps[entry[1][0]]
        handle = pool[method_ref]
        if handle is None or handle[0] != 15 or not args or pool[args[0]] is None or pool[args[0]][0] != 16:
            continue
        _, owner, name, _ = member_ref(handle[1][1])
        if (owner, name) not in LAMBDA_FACTORIES:
            continue
        sam_index, site_desc_index = pool[entry[1][1]][1]
        interface = DESCRIPTOR_TYPE.findall(utf8(site_desc_index).rsplit(")", 1)[1])
        if interface:
            info.refs.add(("imethod", interface[0], utf8(sam_index), utf8(pool[args[0]][1])))
    info.descriptor_types.discard(info.name)
    return info


class ClassIndex:
    """Classes by internal name: in-memory class files (RigTune's own), then jars (first jar wins), then a fallback."""

    def __init__(self, jars=(), classes=None, fallback=None):
        self._zips = []
        self._entries = {}
        for jar in jars:
            archive = zipfile.ZipFile(jar)
            self._zips.append(archive)
            for entry in archive.namelist():
                if entry.endswith(".class") and not entry.startswith("META-INF/"):
                    self._entries.setdefault(entry[:-6], (archive, entry))
        self._bytes = {}
        self._cache = {}
        self.fallback = fallback
        self.add_classes(classes or {})

    def add_classes(self, classes):
        for name, data in classes.items():
            self._bytes[name] = data
            self._cache.pop(name, None)

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


def _resolve_field(index, owner, name, desc, seen):
    """JVMS 5.4.3.2: the class itself, then its direct superinterfaces (recursively), then its superclass."""
    if owner is None or owner in seen:
        return None
    seen.add(owner)
    info = index.get(owner)
    if info is None:
        return None
    if (name, desc) in info.fields:
        return info.name, info.fields[(name, desc)]
    for interface in info.interfaces:
        found = _resolve_field(index, interface, name, desc, seen)
        if found:
            return found
    return _resolve_field(index, info.super_name, name, desc, seen)


def resolve(index, kind, owner, name, desc):
    """Where a member is declared as seen from owner, like the JVM's resolution (JVMS 5.4.3): a field per
    5.4.3.2; a method in the class and its superclasses first, then in every superinterface. Returns
    (declaring class, access flags) or None."""
    if kind == "field":
        return _resolve_field(index, owner, name, desc, set())
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
        if (name, desc) in info.methods:
            return info.name, info.methods[(name, desc)]
        queue += info.interfaces
    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        info = index.get(current)
        if info is None:
            continue
        if (name, desc) in info.methods:
            return info.name, info.methods[(name, desc)]
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
        if status != "OK" and old and old[0] != owner:
            reason += f" (declared in {old[0]} on the old version)"
        results.append({"kind": kind, "owner": owner, "name": name, "desc": desc, "status": status, "reason": reason,
                        "old": old[0] if old else None, "new": new[0] if new else None,
                        "old_access": old[1] if old else None, "new_access": new[1] if new else None})
    return results


REFLECTIVE_CALLS = {("java/lang/Class", n) for n in ("forName", "getField", "getDeclaredField", "getFields",
                                                      "getDeclaredFields", "getMethod", "getDeclaredMethod",
                                                      "getMethods", "getDeclaredMethods", "getConstructor",
                                                      "getDeclaredConstructor")}


def _uses_reflection(info):
    return any((r[1], r[2]) in REFLECTIVE_CALLS or (r[1] == "java/lang/invoke/MethodHandles$Lookup"
                                                    and r[2].startswith(("find", "unreflect"))) for r in info.refs)


class RawRefs(NamedTuple):
    refs: set
    types: set
    dotted_names: set
    strings: set
    strong_strings: set
    own_prefixes: set
    class_count: dict
    name_sources: list
    own_classes: dict
    own_infos: dict


def _is_jdk(name):
    return name.startswith(JDK_PREFIXES)


def _own_prefix(name):
    return "/".join(name.split("/")[:3]) + "/"


def collect_rigtune(class_files):
    """class_files: (source set label, class file bytes) pairs from RigTune's build. Collects every member ref outside
    the JDK (RigTune-owned ones too: a call to an inherited Minecraft method names the RigTune subclass as owner),
    the foreign types, the strings RigTune uses and dotted class names."""
    infos, count, own_classes = [], {}, {}
    for label, data in class_files:
        info = parse_class(data)
        infos.append(info)
        own_classes[info.name] = data
        count[label] = count.get(label, 0) + 1
    own = {_own_prefix(i.name) for i in infos}

    def foreign(name):
        return not _is_jdk(name) and not name.startswith(tuple(own))

    refs, types, dotted, strings, strong, sources = set(), set(), set(), set(), set(), []
    for info in infos:
        class_refs = {r for r in info.refs if not r[1].startswith("[") and not _is_jdk(r[1])}
        class_dotted = {s for s in info.strings if DOTTED_CLASS.fullmatch(s)}
        class_types = {t for t in info.descriptor_types if foreign(t)} | {r[1] for r in class_refs if foreign(r[1])}
        class_strings = info.strings | info.annotation_strings
        reflective = _uses_reflection(info)
        refs |= class_refs
        types |= class_types
        strings |= class_strings
        strong |= info.annotation_strings | (info.strings if reflective else set())
        dotted |= class_dotted
        sources.append((frozenset(class_types | {d.replace(".", "/") for d in class_dotted}), frozenset(class_strings),
                        frozenset(info.annotation_strings), reflective))
    return RawRefs(refs, types, dotted, strings, strong, own, count, sources, own_classes,
                   {i.name: i for i in infos})


class Scope(NamedTuple):
    refs: set
    types: set
    reflection_types: set
    other_reflection: set
    not_checked: dict
    unresolved_types: list


CORE_PREFIXES = tuple(prefix for prefix, _ in GROUPS)


def _group(name):
    return "/".join(name.split("/")[:2])


def classify(raw, old_index):
    """Keeps what the old version's classpath has (Minecraft and its libraries, Loader, Fabric API, Mod Menu), and
    RigTune-owned refs that resolve to an inherited member of such a class. A Minecraft/Mojang/Fabric/Mod Menu/LWJGL
    owner or type missing from the old classpath is kept too: it shows up as UNRESOLVED, a gap in the check.
    References to anything else (compile-only mod APIs such as Iris or Distant Horizons) are counted as not checked."""
    own = tuple(raw.own_prefixes)
    refs, not_checked, inherited_from = set(), {}, set()
    for r in raw.refs:
        if r[1].startswith(own):
            found = resolve(old_index, r[0], r[1], r[2], r[3])
            if found and not found[0].startswith(own) and not _is_jdk(found[0]):
                refs.add(r)
                inherited_from.add(found[0])
        elif old_index.get(r[1]) is not None or r[1].startswith(CORE_PREFIXES):
            refs.add(r)
        else:
            not_checked[_group(r[1])] = not_checked.get(_group(r[1]), 0) + 1
    types = {t for t in raw.types if old_index.get(t) is not None} | inherited_from
    unresolved_types = sorted(t for t in raw.types if t not in types and t.startswith(CORE_PREFIXES))
    reflection, other = set(), set()
    for dotted in raw.dotted_names:
        internal = dotted.replace(".", "/")
        if _is_jdk(internal) or internal.startswith(own):
            continue
        if old_index.get(internal) is not None:
            reflection.add(internal)
        else:
            other.add(dotted)
    return Scope(refs, types | reflection, reflection, other, not_checked, unresolved_types)


def _inherited(index, info, name, desc):
    """The declaration a method of info overrides or implements, from its superclass chain and interfaces."""
    for start in ([info.super_name] if info.super_name else []) + list(info.interfaces):
        found = resolve(index, "method", start, name, desc)
        if found:
            return found
    return None


def overrides(raw, old_index, new_index):
    """Every RigTune method that overrides or implements a Minecraft/library method (Screen.init, onInitializeClient,
    a TypeAdapter's read): the framework calls it only while that method still exists, isn't final and isn't static."""
    own = tuple(raw.own_prefixes)
    results = []
    for class_name in sorted(raw.own_infos):
        info = raw.own_infos[class_name]
        for (name, desc), access in sorted(info.methods.items()):
            if access & (ACC_PRIVATE | ACC_STATIC) or name in ("<init>", "<clinit>"):
                continue
            old = _inherited(old_index, info, name, desc)
            if old is None or old[0].startswith(own) or _is_jdk(old[0]):
                continue
            new = _inherited(new_index, info, name, desc)
            status, reason = "OK", ""
            if new is None:
                status, reason = "MISSING", f"overrides {old[0]} on the old version; nothing to override on the new one"
            elif new[1] & ACC_STATIC:
                status, reason = "CHANGED", f"{new[0]}.{name} is static on the new version"
            elif new[1] & ACC_FINAL and not (new_index.get(new[0]).access & ACC_INTERFACE):
                status, reason = "CHANGED", f"{new[0]}.{name} is final on the new version"
            results.append({"class": class_name, "name": name, "desc": desc, "old": old[0],
                            "new": new[0] if new else None, "status": status, "reason": reason})
    return results


def _unimplemented(index, class_name, own):
    """Abstract methods of foreign supertypes that nothing in the class's hierarchy implements."""
    seen, queue, abstract, concrete = set(), [class_name], {}, set()
    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        info = index.get(current)
        if info is None:
            continue
        for key, access in info.methods.items():
            if access & ACC_STATIC or key[0] in ("<init>", "<clinit>"):
                continue
            if access & ACC_ABSTRACT:
                if not current.startswith(own) and not _is_jdk(current):
                    abstract.setdefault(key, current)
            elif not access & ACC_PRIVATE:
                concrete.add(key)
        queue += ([info.super_name] if info.super_name else []) + list(info.interfaces)
    return {key: owner for key, owner in abstract.items() if key not in concrete}


def new_abstract_methods(raw, old_index, new_index):
    """Abstract methods a concrete RigTune class would leave unimplemented on the new version (AbstractMethodError
    when called) and didn't on the old one."""
    own = tuple(raw.own_prefixes)
    results = []
    for class_name in sorted(raw.own_infos):
        info = raw.own_infos[class_name]
        if info.access & (ACC_ABSTRACT | ACC_INTERFACE):
            continue
        before = _unimplemented(old_index, class_name, own)
        for (name, desc), owner in sorted(_unimplemented(new_index, class_name, own).items()):
            if (name, desc) not in before:
                results.append({"class": class_name, "name": name, "desc": desc, "owner": owner})
    return results


class Analysis(NamedTuple):
    scope: Scope
    refs: list
    missing_classes: list
    named_members: list
    string_changes: dict
    overrides: list
    abstract: list


UNIVERSAL_NAMES = {"<init>", "<clinit>", "equals", "hashCode", "toString", "values", "valueOf", "ordinal", "name",
                   "compareTo", "getClass", "clone"}


def _descriptors(info, member):
    return {d for n, d in list(info.fields) + list(info.methods) if n == member}


def named_members(sources, types, old_index, new_index):
    """Strings that name a member of a class referenced by the same RigTune class (reflection such as
    getDeclaredField("serverRenderDistance"), a mixin's @Inject(method = ...)): is that member still declared, with
    the same descriptors? "strong" when the string is an annotation value or its class uses reflection; otherwise
    the match may be a coincidence and is only listed for review."""
    pairs = {}
    for class_types, class_strings, class_annotation_strings, reflective in sources:
        for name in class_types & types:
            old = old_index.get(name)
            declared = {n for n, _ in old.fields} | {n for n, _ in old.methods}
            for member in (class_strings & declared) - UNIVERSAL_NAMES:
                strong = reflective or member in class_annotation_strings
                pairs[(name, member)] = pairs.get((name, member), False) or strong
    found = []
    for (name, member), strong in sorted(pairs.items()):
        old_descs = _descriptors(old_index.get(name), member)
        new = new_index.get(name)
        new_descs = _descriptors(new, member) if new is not None else set()
        status, reason = "OK", ""
        if not new_descs:
            status = "MISSING"
        elif old_descs - new_descs:
            status, reason = "CHANGED", f"descriptor(s) gone: {', '.join(sorted(old_descs - new_descs))}"
        found.append({"class": name, "name": member, "status": status, "reason": reason, "strong": strong,
                      "old_descs": sorted(old_descs), "new_descs": sorted(new_descs)})
    return found


KEY_LIKE = re.compile(r"[A-Za-z_][\w.$-]{2,}")


def string_changes(strings, strong_strings, types, old_index, new_index):
    """String constants (method bodies included, which javap -p doesn't show) that a referenced class gained or lost.
    removed_used: lost key-like strings RigTune also uses; strong when RigTune uses them in an annotation or in a
    class that uses reflection."""
    changes = {}
    for name in sorted(types):
        old, new = old_index.get(name), new_index.get(name)
        if new is None:
            continue
        removed, added = sorted(old.strings - new.strings), sorted(new.strings - old.strings)
        if removed or added:
            used = sorted(v for v in set(removed) & strings if KEY_LIKE.fullmatch(v) and v not in ("null", "true", "false"))
            changes[name] = {"removed": removed, "added": added, "removed_used": used,
                             "removed_used_strong": [v for v in used if v in strong_strings]}
    return changes


def analyse(raw, old_index, new_index):
    for index in (old_index, new_index):
        index.add_classes(raw.own_classes)
    scope = classify(raw, old_index)
    return Analysis(
        scope,
        compare_refs(scope.refs, old_index, new_index),
        sorted(t for t in scope.types if new_index.get(t) is None),
        named_members(raw.name_sources, scope.types, old_index, new_index),
        string_changes(raw.strings, raw.strong_strings, scope.types, old_index, new_index),
        overrides(raw, old_index, new_index),
        new_abstract_methods(raw, old_index, new_index),
    )


def _member(r):
    return f"{r['owner']}.{r['name']}{r['desc']}" if r["kind"] != "field" else f"{r['owner']}.{r['name']}:{r['desc']}"


def _named(m):
    reason = f": {m['reason']}" if m["reason"] else ""
    return f"{m['status']} name {m['class']}.{m['name']} (a string RigTune uses){reason}"


def breaking(analysis):
    lines = [f"{r['status']} {'field' if r['kind'] == 'field' else 'method'} {_member(r)}: {r['reason']}"
             for r in analysis.refs if r["status"] in ("MISSING", "CHANGED")]
    lines += [f"MISSING class {t}" for t in analysis.missing_classes]
    lines += [f"{o['status']} override {o['class']}.{o['name']}{o['desc']}: {o['reason']}"
              for o in analysis.overrides if o["status"] != "OK"]
    lines += [f"ABSTRACT {m['class']} doesn't implement {m['owner']}.{m['name']}{m['desc']} (new on this version)"
              for m in analysis.abstract]
    lines += [_named(m) for m in analysis.named_members if m["status"] != "OK" and m["strong"]]
    for name, change in analysis.string_changes.items():
        lines += [f'REMOVED string "{s}" from {name} (RigTune uses it)' for s in change["removed_used_strong"]]
    return lines


def review(analysis):
    """Heuristic matches that may be coincidences: listed, but they don't fail the run."""
    lines = [_named(m) for m in analysis.named_members if m["status"] != "OK" and not m["strong"]]
    for name, change in analysis.string_changes.items():
        lines += [f'REMOVED string "{s}" from {name} (RigTune has the same string)'
                  for s in change["removed_used"] if s not in change["removed_used_strong"]]
    return lines


def incomplete(analysis):
    lines = [f"UNRESOLVED {_member(r)}: {r['reason']}" for r in analysis.refs if r["status"] == "UNRESOLVED"]
    lines += [f"UNRESOLVED class {t}: not on the old classpath" for t in analysis.scope.unresolved_types]
    return lines


HEADER_NAME = re.compile(r"\b(?:class|interface) ([\w.$-]+)")


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


def class_statuses(names, old_blocks, new_blocks):
    """SAME/DIFF per class from the two javap dumps; NO DUMP when javap printed nothing for it on either side."""
    statuses = {}
    for name in names:
        old, new = old_blocks.get(name), new_blocks.get(name)
        statuses[name] = "NO DUMP" if old is None or new is None else "SAME" if old == new else "DIFF"
    return statuses


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
                if a is None or b is None:
                    diffs[f"{label}/{rel}"] = ["javap printed nothing for this class"]
                elif a != b:
                    diffs[f"{label}/{rel}"] = list(difflib.unified_diff(a, b, "old", "new", n=1, lineterm=""))
    return {"compared": compared, "diffs": diffs, "only_old": only_old, "only_new": only_new}


def group_counts(results):
    counts = {label: 0 for _, label in GROUPS}
    counts["other Minecraft libraries"] = 0
    for r in results:
        declaring = r["old"] or r["owner"]
        label = next((lbl for prefix, lbl in GROUPS if declaring.startswith(prefix)), "other Minecraft libraries")
        counts[label] += 1
    return counts


def _bytecode_differs(bytecode):
    return bool(bytecode and (bytecode["diffs"] or bytecode["only_old"] or bytecode["only_new"]))


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
    groups = group_counts(a.refs)
    core = sum(v for k, v in groups.items() if k != "other Minecraft libraries")
    inherited = sum(1 for r in a.refs if r["owner"].startswith(tuple(raw.own_prefixes)))
    lambdas = sum(1 for r in a.refs if r["kind"] == "imethod")
    lines.append("")
    lines.append(f"Member references: {len(a.refs)} ({', '.join(f'{k} {v}' for k, v in groups.items())}; "
                 f"{core} without the other libraries; {inherited} through a RigTune subclass; {lambdas} interface "
                 "methods, lambdas included)")
    lines.append("  " + ", ".join(f"{k} {status.get(k, 0)}" for k in ("OK", "MISSING", "CHANGED", "UNRESOLVED")))
    if a.scope.not_checked:
        lines.append("  not checked (not on the classpath, compile-only mod APIs): "
                     + ", ".join(f"{k} {v}" for k, v in sorted(a.scope.not_checked.items())))
    override_status = {}
    for o in a.overrides:
        override_status[o["status"]] = override_status.get(o["status"], 0) + 1
    lines.append(f"Overridden or implemented methods: {len(a.overrides)} ("
                 + ", ".join(f"{k} {override_status.get(k, 0)}" for k in ("OK", "MISSING", "CHANGED")) + ")")
    lines.append(f"Abstract methods newly left unimplemented: {len(a.abstract)}")
    same = sorted(n for n, s in class_status.items() if s == "SAME")
    diff = sorted(n for n, s in class_status.items() if s == "DIFF")
    no_dump = sorted(n for n, s in class_status.items() if s == "NO DUMP")
    lines.append(f"Referenced classes: {len(a.scope.types)}: SAME {len(same)}, DIFF {len(diff)}, "
                 f"MISSING {len(a.missing_classes)}" + (f", NO DUMP {len(no_dump)}" if no_dump else ""))
    for name in diff:
        lines.append(f"  DIFF {name}")
    for name in a.missing_classes:
        lines.append(f"  MISSING {name}")
    lines.append(f"Reflection targets by class name: {', '.join(sorted(a.scope.reflection_types)) or 'none'}")
    if a.scope.other_reflection:
        lines.append(f"  outside the checked classpath (check by hand): {', '.join(sorted(a.scope.other_reflection))}")
    strong = [f"{m['class'].rsplit('/', 1)[-1]}.{m['name']}" for m in a.named_members if m["strong"]]
    weak = [f"{m['class'].rsplit('/', 1)[-1]}.{m['name']}" for m in a.named_members if not m["strong"]]
    lines.append(f"Member names in strings: {len(strong)} from annotations or reflecting classes: {', '.join(strong)}")
    lines.append(f"  {len(weak)} more that may be coincidences (review only): {', '.join(weak)}")
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
    gaps = incomplete(a) + [f"NO DUMP {n}: javap printed nothing for it, compare it by hand" for n in no_dump]
    to_review = review(a)
    lines.append("")
    if problems or gaps:
        lines.append(f"RESULT: {len(problems)} breaking change(s), {len(gaps)} gap(s) in the check:")
        lines += [f"  {p}" for p in problems + gaps]
    else:
        changed = f"; review the {len(diff)} changed class(es) above" if diff else ""
        lines.append(f"RESULT: no breaking change found{changed}")
    if to_review:
        lines.append(f"REVIEW ({len(to_review)}, heuristic matches that may be coincidences; they don't fail the run):")
        lines += [f"  {p}" for p in to_review]
    if _bytecode_differs(bytecode):
        lines.append("RESULT: RigTune's own bytecode differs between the two builds: review the classes listed above")
    return lines


def _run(args, root, out):
    gradle_home = Path(args.gradle_home or os.environ.get("GRADLE_USER_HOME") or Path.home() / ".gradle")
    sets = [s for s in args.sets.split(",") if s]
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
    raw = collect_rigtune((label, path.read_bytes()) for label, path in class_files(old_dirs))
    old_index = new_index = None
    try:
        with tempfile.TemporaryDirectory(prefix="mc_apidiff-") as work:
            jdk = JdkClasses(jimage, java_home / "lib" / "modules", work)
            old_index = ClassIndex(classpaths[args.prev].jars, fallback=jdk)
            new_index = ClassIndex(classpaths[args.mc].jars, fallback=jdk)
            analysis = analyse(raw, old_index, new_index)
            both = sorted(t for t in analysis.scope.types if t not in analysis.missing_classes)
            old_blocks = javap_blocks(javap, old_index, both)
            new_blocks = javap_blocks(javap, new_index, both)
    finally:
        for index in (old_index, new_index):
            if index is not None:
                index.close()
    class_status = class_statuses(both, old_blocks, new_blocks)
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
            "member_refs": analysis.refs, "overrides": analysis.overrides, "abstract": analysis.abstract,
            "classes": class_status, "missing_classes": analysis.missing_classes,
            "unresolved_types": analysis.scope.unresolved_types,
            "reflection_types": sorted(analysis.scope.reflection_types),
            "other_reflection": sorted(analysis.scope.other_reflection), "not_checked": analysis.scope.not_checked,
            "named_members": analysis.named_members, "string_changes": analysis.string_changes,
            "bytecode": bytecode, "breaking": breaking(analysis), "unresolved": incomplete(analysis),
            "review": review(analysis),
        }
        (target / "report.json").write_text(json.dumps(report, indent=1) + "\n", encoding="utf-8", newline="\n")
        for name in (n for n, s in class_status.items() if s == "DIFF"):
            diff = difflib.unified_diff(old_blocks[name].splitlines(), new_blocks[name].splitlines(), args.prev, args.mc,
                                        n=1, lineterm="")
            text = "\n".join(diff) + "\n"
            change = analysis.string_changes.get(name)
            if change:
                text += f"\nString constants removed: {change['removed']}\nString constants added: {change['added']}\n"
            (target / f"diff-{name.replace('/', '.')}.txt").write_text(text, encoding="utf-8", newline="\n")
        if args.dumps:
            for version, blocks in ((args.prev, old_blocks), (args.mc, new_blocks)):
                text = "\n".join(blocks[n] for n in sorted(blocks)) + "\n"
                (target / f"dump-{version}.txt").write_text(text, encoding="utf-8", newline="\n")
    problems = breaking(analysis) + incomplete(analysis) + [n for n, s in class_status.items() if s == "NO DUMP"]
    return 1 if problems or _bytecode_differs(bytecode) else 0


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
    try:
        return _run(args, Path(root) if root else ROOT, out)
    except SetupError as e:
        print(f"mc_apidiff: {e}", file=err)
    except Exception as e:  # noqa: BLE001 - one line instead of a traceback, and a distinct exit code
        print(f"mc_apidiff: unexpected error: {type(e).__name__}: {e}", file=err)
    return 2


if __name__ == "__main__":
    sys.exit(main())
