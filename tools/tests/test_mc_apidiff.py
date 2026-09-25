import io
import json
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import mc_apidiff as mad

PUBLIC, PRIVATE, PROTECTED, STATIC, FINAL, INTERFACE, ABSTRACT, ENUM = 0x1, 0x2, 0x4, 0x8, 0x10, 0x200, 0x400, 0x4000


class ClassWriter:
    """Writes minimal (unverifiable, but well-formed) class files for the reader to parse."""

    def __init__(self, name, super_name="java/lang/Object", interfaces=(), access=PUBLIC):
        self.entries = [None]
        self.index = {}
        self.name, self.super_name, self.interfaces, self.access = name, super_name, list(interfaces), access
        self.fields, self.methods, self.attributes, self.bootstraps = [], [], [], []

    def _add(self, key, payload, slots=1):
        if key in self.index:
            return self.index[key]
        at = len(self.entries)
        self.entries.append(payload)
        self.entries.extend([None] * (slots - 1))
        self.index[key] = at
        return at

    def utf8(self, text):
        data = text.encode("utf-8")
        return self._add(("utf8", text), struct.pack(">BH", 1, len(data)) + data)

    def cls(self, name):
        return self._add(("class", name), struct.pack(">BH", 7, self.utf8(name)))

    def string(self, text):
        return self._add(("string", text), struct.pack(">BH", 8, self.utf8(text)))

    def long(self, value):
        return self._add(("long", value), struct.pack(">Bq", 5, value), slots=2)

    def int_float_double(self, i, f, d):
        self._add(("int", i), struct.pack(">Bi", 3, i))
        self._add(("float", f), struct.pack(">Bf", 4, f))
        self._add(("double", d), struct.pack(">Bd", 6, d), slots=2)
        return self

    def method_handle(self, kind, ref_index):
        return self._add(("handle", kind, ref_index), struct.pack(">BBH", 15, kind, ref_index))

    def method_type(self, desc):
        return self._add(("mtype", desc), struct.pack(">BH", 16, self.utf8(desc)))

    def indy(self, bootstrap, name, desc):
        return self._add(("indy", bootstrap, name, desc), struct.pack(">BHH", 18, bootstrap, self.nat(name, desc)))

    def lambda_to(self, interface, sam, erased_desc, captured="()"):
        """An invokedynamic like javac's for a lambda implementing interface.sam."""
        factory = self.ref("method", "java/lang/invoke/LambdaMetafactory", "metafactory",
                           "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                           "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                           "Ljava/lang/invoke/CallSite;")
        self.bootstraps.append((self.method_handle(6, factory), [self.method_type(erased_desc)]))
        return self.indy(len(self.bootstraps) - 1, sam, f"{captured}L{interface};")

    def concat(self):
        """An invokedynamic of StringConcatFactory, which isn't a lambda."""
        factory = self.ref("method", "java/lang/invoke/StringConcatFactory", "makeConcatWithConstants", "()V")
        self.bootstraps.append((self.method_handle(6, factory), [self.string("recipe: \u0001!")]))
        return self.indy(len(self.bootstraps) - 1, "makeConcatWithConstants", "(I)Ljava/lang/String;")

    def nat(self, name, desc):
        return self._add(("nat", name, desc), struct.pack(">BHH", 12, self.utf8(name), self.utf8(desc)))

    def ref(self, kind, owner, name, desc):
        tag = {"field": 9, "method": 10, "imethod": 11}[kind]
        return self._add((kind, owner, name, desc), struct.pack(">BHH", tag, self.cls(owner), self.nat(name, desc)))

    def field(self, name, desc, access=PUBLIC):
        self.fields.append((access, self.utf8(name), self.utf8(desc), []))
        return self

    def method(self, name, desc, access=PUBLIC, annotations=()):
        attributes = [self._annotations_attribute(type_desc, values) for type_desc, values in annotations]
        self.methods.append((access, self.utf8(name), self.utf8(desc), attributes))
        return self

    def _element(self, value):
        if isinstance(value, str):
            return struct.pack(">BH", ord("s"), self.utf8(value))
        if isinstance(value, tuple) and value[0] == "class":
            return struct.pack(">BH", ord("c"), self.utf8(value[1]))
        if isinstance(value, tuple) and value[0] == "@":
            return b"@" + self._annotation(value[1], value[2])
        if isinstance(value, list):
            return struct.pack(">BH", ord("["), len(value)) + b"".join(self._element(v) for v in value)
        raise TypeError(value)

    def _annotation(self, type_desc, values):
        out = struct.pack(">HH", self.utf8(type_desc), len(values))
        for key, value in values.items():
            out += struct.pack(">H", self.utf8(key)) + self._element(value)
        return out

    def _annotations_attribute(self, type_desc, values):
        body = struct.pack(">H", 1) + self._annotation(type_desc, values)
        return struct.pack(">HI", self.utf8("RuntimeInvisibleAnnotations"), len(body)) + body

    def annotate(self, type_desc, values):
        self.attributes.append(self._annotations_attribute(type_desc, values))
        return self

    def bytes(self):
        this, sup = self.cls(self.name), self.cls(self.super_name) if self.super_name else 0
        interfaces = [self.cls(i) for i in self.interfaces]
        attributes = list(self.attributes)
        if self.bootstraps:
            body = struct.pack(">H", len(self.bootstraps)) + b"".join(
                struct.pack(">HH", handle, len(args)) + b"".join(struct.pack(">H", a) for a in args)
                for handle, args in self.bootstraps)
            attributes.append(struct.pack(">HI", self.utf8("BootstrapMethods"), len(body)) + body)
        pool = b"".join(e for e in self.entries[1:] if e is not None)
        out = struct.pack(">IHHH", 0xCAFEBABE, 0, 69, len(self.entries)) + pool
        out += struct.pack(">HHHH", self.access, this, sup, len(interfaces))
        out += b"".join(struct.pack(">H", i) for i in interfaces)
        for members in (self.fields, self.methods):
            out += struct.pack(">H", len(members))
            out += b"".join(struct.pack(">HHHH", a, n, d, len(attrs)) + b"".join(attrs) for a, n, d, attrs in members)
        out += struct.pack(">H", len(attributes)) + b"".join(attributes)
        return out


def index_of(*writers, jdk=None):
    return mad.ClassIndex(classes={w.name: w.bytes() for w in writers}, fallback=jdk)


def jdk_index():
    obj = ClassWriter("java/lang/Object", super_name=None)
    obj.method("hashCode", "()I").method("getClass", "()Ljava/lang/Class;").method("<init>", "()V")
    enum = ClassWriter("java/lang/Enum", interfaces=["java/lang/Comparable"], access=PUBLIC | ABSTRACT)
    enum.method("ordinal", "()I").method("name", "()Ljava/lang/String;")
    comparable = ClassWriter("java/lang/Comparable", super_name="java/lang/Object", access=PUBLIC | INTERFACE | ABSTRACT)
    comparable.method("compareTo", "(Ljava/lang/Object;)I", PUBLIC | ABSTRACT)
    return index_of(obj, enum, comparable).get


class ReaderTest(unittest.TestCase):
    def test_parse_reads_members_and_refs(self):
        w = ClassWriter("io/github/x/Probe", interfaces=["java/lang/Runnable"])
        w.field("count", "I", PRIVATE | STATIC).method("run", "()V")
        w.ref("method", "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;")
        w.ref("field", "net/minecraft/client/Options", "renderDistance", "Lnet/minecraft/client/OptionInstance;")
        w.ref("imethod", "net/fabricmc/api/ClientModInitializer", "onInitializeClient", "()V")
        w.string("serverRenderDistance")
        info = mad.parse_class(w.bytes())
        self.assertEqual(info.name, "io/github/x/Probe")
        self.assertEqual(info.super_name, "java/lang/Object")
        self.assertEqual(info.interfaces, ["java/lang/Runnable"])
        self.assertEqual(info.fields, {("count", "I"): PRIVATE | STATIC})
        self.assertEqual(info.methods, {("run", "()V"): PUBLIC})
        self.assertEqual(info.refs, {
            ("method", "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;"),
            ("field", "net/minecraft/client/Options", "renderDistance", "Lnet/minecraft/client/OptionInstance;"),
            ("imethod", "net/fabricmc/api/ClientModInitializer", "onInitializeClient", "()V"),
        })
        self.assertEqual(info.strings, {"serverRenderDistance"})
        self.assertIn("net/minecraft/client/OptionInstance", info.descriptor_types)

    def test_parse_handles_long_constants_taking_two_slots(self):
        w = ClassWriter("a/B")
        w.long(1 << 40)
        w.string("after-the-long")
        w.ref("method", "net/minecraft/A", "b", "()V")
        info = mad.parse_class(w.bytes())
        self.assertEqual(info.strings, {"after-the-long"})
        self.assertEqual(info.refs, {("method", "net/minecraft/A", "b", "()V")})

    def test_annotation_strings_and_class_values(self):
        w = ClassWriter("io/github/x/mixin/DebugScreenOverlayMixin")
        w.annotate("Lorg/spongepowered/asm/mixin/Mixin;",
                   {"value": [("class", "Lnet/minecraft/client/gui/components/DebugScreenOverlay;")]})
        w.annotate("Lorg/spongepowered/asm/mixin/injection/Inject;",
                   {"method": ["logFrameDuration"], "at": [("@", "Lorg/spongepowered/asm/mixin/injection/At;", {"value": "HEAD"})]})
        info = mad.parse_class(w.bytes())
        self.assertEqual(info.annotation_strings, {"logFrameDuration", "HEAD"})
        self.assertIn("net/minecraft/client/gui/components/DebugScreenOverlay", info.descriptor_types)

    def test_method_level_annotations_like_a_mixin_inject(self):
        w = ClassWriter("io/github/x/mixin/DebugScreenOverlayMixin")
        w.annotate("Lorg/spongepowered/asm/mixin/Mixin;",
                   {"value": [("class", "Lnet/minecraft/client/gui/components/DebugScreenOverlay;")]})
        w.method("rigtune$recordFrame", "(JLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", PRIVATE,
                 annotations=[("Lorg/spongepowered/asm/mixin/injection/Inject;",
                               {"method": ["logFrameDuration"],
                                "at": [("@", "Lorg/spongepowered/asm/mixin/injection/At;", {"value": "HEAD"})]})])
        w.field("marker", "I")
        info = mad.parse_class(w.bytes())
        self.assertEqual(info.annotation_strings, {"logFrameDuration", "HEAD"})
        self.assertIn(("rigtune$recordFrame", "(JLorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V"),
                      info.methods)
        self.assertEqual(info.fields, {("marker", "I"): PUBLIC})

    def test_int_float_double_constants_are_skipped(self):
        w = ClassWriter("a/B").int_float_double(7, 1.5, 2.5)
        w.string("after")
        w.ref("field", "net/minecraft/A", "f", "D")
        info = mad.parse_class(w.bytes())
        self.assertEqual(info.strings, {"after"})
        self.assertEqual(info.refs, {("field", "net/minecraft/A", "f", "D")})

    def test_rejects_non_class_data(self):
        with self.assertRaises(ValueError):
            mad.parse_class(b"PK\x03\x04")


class CollectTest(unittest.TestCase):
    def test_collect_and_classify(self):
        w = ClassWriter("io/github/chaotix345/rigtune/client/Probe")
        w.ref("method", "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;")
        w.ref("method", "java/lang/String", "length", "()I")
        w.ref("method", "io/github/chaotix345/rigtune/core/Report", "build", "()V")
        w.ref("method", "[Lnet/minecraft/world/Difficulty;", "clone", "()Ljava/lang/Object;")
        w.ref("method", "net/irisshaders/iris/api/v0/IrisApi", "getInstance", "()Lnet/irisshaders/iris/api/v0/IrisApi;")
        w.string("net.minecraft.client.Options$FieldAccess")
        w.string("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer")
        w.string("processOptions")
        report = ClassWriter("io/github/chaotix345/rigtune/core/Report")
        raw = mad.collect_rigtune([("client", w.bytes()), ("main", report.bytes())])
        self.assertEqual(raw.class_count, {"client": 1, "main": 1})
        self.assertIn("processOptions", raw.strings)

        minecraft = ClassWriter("net/minecraft/client/Minecraft").method("getInstance", "()Lnet/minecraft/client/Minecraft;", PUBLIC | STATIC)
        field_access = ClassWriter("net/minecraft/client/Options$FieldAccess", access=PUBLIC | INTERFACE | ABSTRACT)
        scope = mad.classify(raw, index_of(minecraft, field_access, jdk=jdk_index()))
        self.assertEqual(scope.refs, {("method", "net/minecraft/client/Minecraft", "getInstance", "()Lnet/minecraft/client/Minecraft;")})
        self.assertEqual(scope.types, {"net/minecraft/client/Minecraft", "net/minecraft/client/Options$FieldAccess"})
        self.assertEqual(scope.reflection_types, {"net/minecraft/client/Options$FieldAccess"})
        self.assertEqual(scope.other_reflection, {"net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer"})
        self.assertEqual(scope.not_checked, {"net/irisshaders": 1})


class ResolveTest(unittest.TestCase):
    def test_resolve_through_superclass_and_interface(self):
        base = ClassWriter("net/minecraft/Base").method("tick", "()V").field("level", "I")
        iface = ClassWriter("net/minecraft/Ticker", access=PUBLIC | INTERFACE | ABSTRACT).method("rate", "()I", PUBLIC | ABSTRACT)
        child = ClassWriter("net/minecraft/Child", super_name="net/minecraft/Base", interfaces=["net/minecraft/Ticker"])
        index = index_of(base, iface, child)
        self.assertEqual(mad.resolve(index, "method", "net/minecraft/Child", "tick", "()V"), ("net/minecraft/Base", PUBLIC))
        self.assertEqual(mad.resolve(index, "field", "net/minecraft/Child", "level", "I"), ("net/minecraft/Base", PUBLIC))
        self.assertEqual(mad.resolve(index, "method", "net/minecraft/Child", "rate", "()I"),
                         ("net/minecraft/Ticker", PUBLIC | ABSTRACT))
        self.assertIsNone(mad.resolve(index, "method", "net/minecraft/Child", "tick", "(I)V"))

    def test_field_lookup_checks_superinterfaces_before_the_superclass(self):
        iface = ClassWriter("net/minecraft/I", access=PUBLIC | INTERFACE | ABSTRACT).field("f", "I", PUBLIC | STATIC)
        sup = ClassWriter("net/minecraft/S").field("f", "I")
        child = ClassWriter("net/minecraft/C", super_name="net/minecraft/S", interfaces=["net/minecraft/I"])
        self.assertEqual(mad.resolve(index_of(iface, sup, child), "field", "net/minecraft/C", "f", "I"),
                         ("net/minecraft/I", PUBLIC | STATIC))

    def test_jdk_inherited_member_resolves(self):
        enum = ClassWriter("net/minecraft/world/Difficulty", super_name="java/lang/Enum", access=PUBLIC | ENUM)
        index = index_of(enum, jdk=jdk_index())
        self.assertEqual(mad.resolve(index, "method", "net/minecraft/world/Difficulty", "ordinal", "()I"),
                         ("java/lang/Enum", PUBLIC))
        self.assertEqual(mad.resolve(index, "method", "net/minecraft/world/Difficulty", "compareTo", "(Ljava/lang/Object;)I"),
                         ("java/lang/Comparable", PUBLIC | ABSTRACT))
        self.assertEqual(mad.resolve(index, "method", "net/minecraft/world/Difficulty", "hashCode", "()I"),
                         ("java/lang/Object", PUBLIC))


REF_TICK = ("method", "net/minecraft/A", "tick", "()V")


class CompareTest(unittest.TestCase):
    def compare(self, old, new, refs=(REF_TICK,)):
        results = mad.compare_refs(set(refs), index_of(old), index_of(new))
        return {r["name"]: r for r in results}

    def test_same_member_is_ok(self):
        old = ClassWriter("net/minecraft/A").method("tick", "()V")
        new = ClassWriter("net/minecraft/A").method("tick", "()V")
        self.assertEqual(self.compare(old, new)["tick"]["status"], "OK")

    def test_missing_member_on_new_is_breaking(self):
        old = ClassWriter("net/minecraft/A").method("tick", "()V")
        new = ClassWriter("net/minecraft/A").method("tick", "(F)V")
        result = self.compare(old, new)["tick"]
        self.assertEqual(result["status"], "MISSING")
        self.assertEqual(result["old"], "net/minecraft/A")
        self.assertIsNone(result["new"])

    def test_static_change_is_breaking(self):
        old = ClassWriter("net/minecraft/A").method("tick", "()V", PUBLIC)
        new = ClassWriter("net/minecraft/A").method("tick", "()V", PUBLIC | STATIC)
        self.assertEqual(self.compare(old, new)["tick"]["status"], "CHANGED")

    def test_access_narrowed_is_breaking(self):
        old = ClassWriter("net/minecraft/A").method("tick", "()V", PUBLIC)
        new = ClassWriter("net/minecraft/A").method("tick", "()V", PROTECTED)
        self.assertEqual(self.compare(old, new)["tick"]["status"], "CHANGED")

    def test_access_widened_is_ok(self):
        old = ClassWriter("net/minecraft/A").method("tick", "()V", PROTECTED)
        new = ClassWriter("net/minecraft/A").method("tick", "()V", PUBLIC)
        self.assertEqual(self.compare(old, new)["tick"]["status"], "OK")

    def test_member_moved_to_superclass_is_ok(self):
        old = ClassWriter("net/minecraft/A").method("tick", "()V")
        base = ClassWriter("net/minecraft/Base").method("tick", "()V")
        new = ClassWriter("net/minecraft/A", super_name="net/minecraft/Base")
        results = mad.compare_refs({REF_TICK}, index_of(old), index_of(new, base))
        self.assertEqual([(r["status"], r["new"]) for r in results], [("OK", "net/minecraft/Base")])

    def test_unresolved_on_both_sides_is_reported(self):
        old = ClassWriter("net/minecraft/B")
        new = ClassWriter("net/minecraft/B")
        self.assertEqual(self.compare(old, new)["tick"]["status"], "UNRESOLVED")


GET_DECLARED_FIELD = ("method", "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;")


def rigtune_probe(reflective=True):
    w = ClassWriter("io/github/chaotix345/rigtune/client/Probe")
    if reflective:
        w.ref(*GET_DECLARED_FIELD)
    w.ref("method", "net/minecraft/A", "tick", "()V")
    w.ref("field", "net/minecraft/client/Options", "renderDistance", "I")
    w.string("renderDistance")
    w.string("serverRenderDistance")
    w.annotate("Lorg/spongepowered/asm/mixin/injection/Inject;", {"method": ["logFrameDuration"]})
    w.annotate("Lorg/spongepowered/asm/mixin/Mixin;", {"value": [("class", "Lnet/minecraft/client/gui/Overlay;")]})
    return mad.collect_rigtune([("client", w.bytes())])


def options(*strings, fields=("renderDistance", "serverRenderDistance")):
    w = ClassWriter("net/minecraft/client/Options")
    for name in fields:
        w.field(name, "I")
    for s in strings:
        w.string(s)
    return w


def overlay(method="logFrameDuration"):
    return ClassWriter("net/minecraft/client/gui/Overlay").method(method, "(J)V")


class AnalyseTest(unittest.TestCase):
    def test_no_change_is_clean(self):
        a = ClassWriter("net/minecraft/A").method("tick", "()V")
        result = mad.analyse(rigtune_probe(), index_of(a, options("renderDistance"), overlay()),
                             index_of(a, options("renderDistance"), overlay()))
        self.assertEqual(mad.breaking(result), [])
        self.assertEqual([(m["class"], m["name"], m["status"]) for m in result.named_members], [
            ("net/minecraft/client/Options", "renderDistance", "OK"),
            ("net/minecraft/client/Options", "serverRenderDistance", "OK"),
            ("net/minecraft/client/gui/Overlay", "logFrameDuration", "OK"),
        ])

    def test_trivial_removed_strings_are_not_flagged(self):
        probe = ClassWriter("io/github/chaotix345/rigtune/client/Probe")
        probe.ref("field", "net/minecraft/client/Options", "renderDistance", "I")
        for s in ("", "null", ": ", "renderDistance"):
            probe.string(s)
        raw = mad.collect_rigtune([("client", probe.bytes())])
        result = mad.analyse(raw, index_of(options("", "null", ": ", "renderDistance")), index_of(options()))
        self.assertEqual(result.string_changes["net/minecraft/client/Options"]["removed_used"], ["renderDistance"])

    def test_named_members_need_the_string_and_the_class_in_one_rigtune_class(self):
        probe = ClassWriter("io/github/chaotix345/rigtune/client/Probe")
        probe.ref("field", "net/minecraft/client/Options", "renderDistance", "I")
        probe.string("serverRenderDistance")
        probe.string("toString")
        elsewhere = ClassWriter("io/github/chaotix345/rigtune/core/Json")
        elsewhere.string("minecraft")
        raw = mad.collect_rigtune([("client", probe.bytes()), ("main", elsewhere.bytes())])
        opts = options(fields=("renderDistance", "serverRenderDistance", "minecraft")).method("toString", "()Ljava/lang/String;")
        result = mad.analyse(raw, index_of(opts), index_of(opts))
        self.assertEqual([(m["class"], m["name"]) for m in result.named_members],
                         [("net/minecraft/client/Options", "serverRenderDistance")])

    def test_missing_member_and_class_are_breaking(self):
        old = index_of(ClassWriter("net/minecraft/A").method("tick", "()V"), options(), overlay())
        new = index_of(ClassWriter("net/minecraft/A"), options())
        result = mad.analyse(rigtune_probe(), old, new)
        lines = mad.breaking(result)
        self.assertIn("MISSING method net/minecraft/A.tick()V: not found on the new version", lines)
        self.assertIn("MISSING class net/minecraft/client/gui/Overlay", lines)

    def test_named_member_missing_is_flagged(self):
        a = ClassWriter("net/minecraft/A").method("tick", "()V")
        result = mad.analyse(rigtune_probe(), index_of(a, options(), overlay()),
                             index_of(a, options(fields=("renderDistance",)), overlay("logFrameTime")))
        self.assertIn("MISSING name net/minecraft/client/Options.serverRenderDistance (a string RigTune uses)",
                      mad.breaking(result))
        self.assertIn("MISSING name net/minecraft/client/gui/Overlay.logFrameDuration (a string RigTune uses)",
                      mad.breaking(result))

    def test_string_removed_is_flagged(self):
        a = ClassWriter("net/minecraft/A").method("tick", "()V")
        result = mad.analyse(rigtune_probe(), index_of(a, options("renderDistance", "old"), overlay()),
                             index_of(a, options("new"), overlay()))
        self.assertEqual(result.string_changes["net/minecraft/client/Options"],
                         {"removed": ["old", "renderDistance"], "added": ["new"], "removed_used": ["renderDistance"],
                          "removed_used_strong": ["renderDistance"]})
        self.assertIn('REMOVED string "renderDistance" from net/minecraft/client/Options (RigTune uses it)',
                      mad.breaking(result))

    def test_coincidental_names_and_strings_are_review_only(self):
        a = ClassWriter("net/minecraft/A").method("tick", "()V")
        result = mad.analyse(rigtune_probe(reflective=False),
                             index_of(a, options("renderDistance"), overlay()),
                             index_of(a, options(fields=("renderDistance",)), overlay()))
        self.assertEqual(mad.breaking(result), [])
        self.assertEqual(mad.review(result), [
            "MISSING name net/minecraft/client/Options.serverRenderDistance (a string RigTune uses)",
            'REMOVED string "renderDistance" from net/minecraft/client/Options (RigTune has the same string)',
        ])

    def test_annotation_names_stay_strong_without_reflection(self):
        a = ClassWriter("net/minecraft/A").method("tick", "()V")
        result = mad.analyse(rigtune_probe(reflective=False), index_of(a, options(), overlay()),
                             index_of(a, options(), overlay("logFrameTime")))
        self.assertIn("MISSING name net/minecraft/client/gui/Overlay.logFrameDuration (a string RigTune uses)",
                      mad.breaking(result))

    def test_named_member_with_a_changed_descriptor_is_breaking(self):
        a = ClassWriter("net/minecraft/A").method("tick", "()V")
        changed = ClassWriter("net/minecraft/client/gui/Overlay").method("logFrameDuration", "(JI)V")
        result = mad.analyse(rigtune_probe(), index_of(a, options(), overlay()), index_of(a, options(), changed))
        self.assertIn("CHANGED name net/minecraft/client/gui/Overlay.logFrameDuration (a string RigTune uses): "
                      "descriptor(s) gone: (J)V", mad.breaking(result))

    def test_minecraft_owner_missing_from_the_old_classpath_is_a_gap(self):
        w = ClassWriter("io/github/chaotix345/rigtune/client/Probe")
        w.ref("method", "net/minecraft/Gone", "x", "()V")
        w.ref("method", "net/irisshaders/iris/api/v0/IrisApi", "getInstance", "()Lnet/irisshaders/iris/api/v0/IrisApi;")
        result = mad.analyse(mad.collect_rigtune([("client", w.bytes())]), index_of(), index_of())
        self.assertEqual(result.scope.not_checked, {"net/irisshaders": 1})
        self.assertIn("UNRESOLVED net/minecraft/Gone.x()V: not found on either version (incomplete classpath?)",
                      mad.incomplete(result))
        self.assertIn("UNRESOLVED class net/minecraft/Gone: not on the old classpath", mad.incomplete(result))


SCREEN = "net/minecraft/client/gui/screens/Screen"
MY_SCREEN = "io/github/chaotix345/rigtune/client/ui/MyScreen"


class InheritanceTest(unittest.TestCase):
    def test_inherited_members_called_through_a_rigtune_subclass_are_checked(self):
        own = ClassWriter(MY_SCREEN, super_name=SCREEN)
        own.method("helper", "()V")
        own.ref("method", MY_SCREEN, "addRenderableWidget", "(I)V")
        own.ref("field", MY_SCREEN, "width", "I")
        own.ref("method", MY_SCREEN, "helper", "()V")
        raw = mad.collect_rigtune([("client", own.bytes())])
        old = ClassWriter(SCREEN).method("addRenderableWidget", "(I)V").field("width", "I")
        new = ClassWriter(SCREEN).field("width", "I")
        result = mad.analyse(raw, index_of(old, jdk=jdk_index()), index_of(new, jdk=jdk_index()))
        self.assertEqual({(r["name"], r["status"]) for r in result.refs}, {("addRenderableWidget", "MISSING"), ("width", "OK")})
        self.assertIn(f"MISSING method {MY_SCREEN}.addRenderableWidget(I)V: not found on the new version "
                      f"(declared in {SCREEN} on the old version)", mad.breaking(result))
        self.assertIn(SCREEN, result.scope.types)

    def test_overrides_must_still_override(self):
        own = ClassWriter(MY_SCREEN, super_name=SCREEN)
        own.method("init", "()V", PROTECTED).method("tick", "()V").method("mine", "()V").method("secret", "()V", PRIVATE)
        raw = mad.collect_rigtune([("client", own.bytes())])
        old = ClassWriter(SCREEN).method("init", "()V", PROTECTED).method("tick", "()V")
        new = ClassWriter(SCREEN).method("tick", "()V", PUBLIC | FINAL)
        result = mad.analyse(raw, index_of(old, jdk=jdk_index()), index_of(new, jdk=jdk_index()))
        self.assertEqual({(o["name"], o["status"]) for o in result.overrides}, {("init", "MISSING"), ("tick", "CHANGED")})
        lines = mad.breaking(result)
        self.assertIn(f"MISSING override {MY_SCREEN}.init()V: overrides {SCREEN} on the old version; nothing to "
                      "override on the new one", lines)
        self.assertIn(f"CHANGED override {MY_SCREEN}.tick()V: {SCREEN}.tick is final on the new version", lines)

    def test_interface_implementations_and_new_abstract_methods(self):
        api = "net/fabricmc/api/ClientModInitializer"
        own = ClassWriter("io/github/chaotix345/rigtune/client/Init", interfaces=[api])
        own.method("onInitializeClient", "()V")
        raw = mad.collect_rigtune([("client", own.bytes())])
        old = ClassWriter(api, access=PUBLIC | INTERFACE | ABSTRACT).method("onInitializeClient", "()V", PUBLIC | ABSTRACT)
        new = ClassWriter(api, access=PUBLIC | INTERFACE | ABSTRACT).method("onInitializeClient", "()V", PUBLIC | ABSTRACT)
        new.method("onReload", "()V", PUBLIC | ABSTRACT).method("withDefault", "()V", PUBLIC)
        result = mad.analyse(raw, index_of(old, jdk=jdk_index()), index_of(new, jdk=jdk_index()))
        self.assertEqual([(o["name"], o["status"]) for o in result.overrides], [("onInitializeClient", "OK")])
        self.assertEqual(mad.breaking(result), [
            f"ABSTRACT io/github/chaotix345/rigtune/client/Init doesn't implement {api}.onReload()V (new on this version)"])

    def test_lambda_interfaces_are_checked(self):
        event = "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents$EndTick"
        own = ClassWriter("io/github/chaotix345/rigtune/client/Ticks")
        own.lambda_to(event, "onEndTick", "(Lnet/minecraft/client/Minecraft;)V")
        own.concat()
        info = mad.parse_class(own.bytes())
        self.assertIn(("imethod", event, "onEndTick", "(Lnet/minecraft/client/Minecraft;)V"), info.refs)
        self.assertFalse(any(r[2] == "makeConcatWithConstants" and r[0] == "imethod" for r in info.refs))
        raw = mad.collect_rigtune([("client", own.bytes())])
        old = ClassWriter(event, access=PUBLIC | INTERFACE | ABSTRACT)
        old.method("onEndTick", "(Lnet/minecraft/client/Minecraft;)V", PUBLIC | ABSTRACT)
        new = ClassWriter(event, access=PUBLIC | INTERFACE | ABSTRACT)
        new.method("onEndTick", "(Lnet/minecraft/client/Minecraft;F)V", PUBLIC | ABSTRACT)
        result = mad.analyse(raw, index_of(old, jdk=jdk_index()), index_of(new, jdk=jdk_index()))
        self.assertIn(f"MISSING method {event}.onEndTick(Lnet/minecraft/client/Minecraft;)V: not found on the new version",
                      mad.breaking(result))


JAVAP_SAMPLE = """Compiled from "Options.java"
public class net.minecraft.client.Options {
  public int renderDistance;
    descriptor: I
}
public interface net.minecraft.client.Options$FieldAccess {
  public abstract void process(java.lang.String, int);
    descriptor: (Ljava/lang/String;I)V
}
Compiled from "Difficulty.java"
public final class net.minecraft.world.Difficulty extends java.lang.Enum<net.minecraft.world.Difficulty> {
  public static final net.minecraft.world.Difficulty PEACEFUL;
    descriptor: Lnet/minecraft/world/Difficulty;
}
"""


class JavapTest(unittest.TestCase):
    def test_split_javap_output(self):
        blocks = mad.split_javap(JAVAP_SAMPLE)
        self.assertEqual(sorted(blocks), ["net/minecraft/client/Options", "net/minecraft/client/Options$FieldAccess",
                                          "net/minecraft/world/Difficulty"])
        self.assertEqual(blocks["net/minecraft/client/Options"].splitlines(),
                         ["public class net.minecraft.client.Options {", "  public int renderDistance;",
                          "    descriptor: I", "}"])

    def test_split_javap_reads_package_info(self):
        blocks = mad.split_javap("interface io.github.x.package-info {\n}\n")
        self.assertEqual(list(blocks), ["io/github/x/package-info"])

    def test_class_statuses_flag_missing_dumps(self):
        statuses = mad.class_statuses(["a/A", "a/B", "a/C"], {"a/A": "x", "a/B": "y"}, {"a/A": "x", "a/B": "z"})
        self.assertEqual(statuses, {"a/A": "SAME", "a/B": "DIFF", "a/C": "NO DUMP"})

    def test_normalize_bytecode_strips_pool_indices(self):
        a = "  3: invokevirtual #12                 // Method net/minecraft/A.tick:()V\n  6: bipush        65"
        b = "  3: invokevirtual #40                 // Method net/minecraft/A.tick:()V\n  6: bipush        65"
        self.assertEqual(mad.normalize_bytecode(a), mad.normalize_bytecode(b))
        self.assertNotEqual(mad.normalize_bytecode(a), mad.normalize_bytecode(a.replace("65", "297")))


def empty_jar(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    zipfile.ZipFile(path, "w").close()
    return path


FABRIC_API_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <dependencies>
    <dependency><groupId>net.fabricmc.fabric-api</groupId><artifactId>fabric-api-base</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>net.fabricmc.fabric-api</groupId><artifactId>fabric-screen-api-v1</artifactId><version>2.0.0</version></dependency>
    <dependency><groupId>net.fabricmc.fabric-api</groupId><artifactId>fabric-api-deprecated</artifactId><version>0.1.0+26.9</version></dependency>
  </dependencies>
</project>
"""


DEPRECATED_POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <packaging>pom</packaging>
  <dependencies>
    <dependency><groupId>net.fabricmc.fabric-api</groupId><artifactId>fabric-resource-loader-v0</artifactId><version>3.0.0</version></dependency>
    <dependency><groupId>net.fabricmc.fabric-api</groupId><artifactId>fabric-api-base</artifactId><version>1.0.0</version></dependency>
  </dependencies>
</project>
"""


class ClasspathTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.gradle = Path(tmp.name)
        self.modules = self.gradle / "caches" / "modules-2" / "files-2.1"
        self.loom = self.gradle / "caches" / "fabric-loom"

    def populate(self):
        mc_maven = self.loom / "minecraftMaven" / "net" / "minecraft"
        jars = [empty_jar(mc_maven / f"minecraft-{side}-deobf" / "26.9" / f"minecraft-{side}-deobf-26.9.jar")
                for side in ("clientonly", "common")]
        info = {"libraries": [
            {"name": "com.mojang:brigadier:1.3.11",
             "downloads": {"artifact": {"path": "com/mojang/brigadier/1.3.11/brigadier-1.3.11.jar", "sha1": "abc"}}},
            {"name": "org.lwjgl:lwjgl:3.4.3:natives-windows",
             "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3-natives-windows.jar", "sha1": "def"}}},
            {"name": "com.example:absent:1.0",
             "downloads": {"artifact": {"path": "com/example/absent/1.0/absent-1.0.jar", "sha1": "fff"}}},
        ]}
        (self.loom / "26.9").mkdir(parents=True)
        (self.loom / "26.9" / "mojang_minecraft_info.json").write_text(json.dumps(info), encoding="utf-8")
        jars.append(empty_jar(self.modules / "com.mojang" / "brigadier" / "1.3.11" / "abc" / "brigadier-1.3.11.jar"))
        pom = self.modules / "net.fabricmc.fabric-api" / "fabric-api" / "0.1.0+26.9" / "p" / "fabric-api-0.1.0+26.9.pom"
        pom.parent.mkdir(parents=True)
        pom.write_text(FABRIC_API_POM, encoding="utf-8")
        deprecated = self.modules / "net.fabricmc.fabric-api" / "fabric-api-deprecated" / "0.1.0+26.9" / "p"
        deprecated.mkdir(parents=True)
        (deprecated / "fabric-api-deprecated-0.1.0+26.9.pom").write_text(DEPRECATED_POM, encoding="utf-8")
        for artifact, version in (("fabric-api-base", "1.0.0"), ("fabric-screen-api-v1", "2.0.0"), ("fabric-resource-loader-v0", "3.0.0")):
            jars.append(empty_jar(self.modules / "net.fabricmc.fabric-api" / artifact / version / "h" / f"{artifact}-{version}.jar"))
        jars.append(empty_jar(self.modules / "com.terraformersmc" / "modmenu" / "30.0.0" / "h" / "modmenu-30.0.0.jar"))
        jars.append(empty_jar(self.modules / "net.fabricmc" / "fabric-loader" / "0.19.5" / "h" / "fabric-loader-0.19.5.jar"))
        return jars

    def props(self):
        return {"fabric_api_version": "0.1.0+26.9", "modmenu_version": "30.0.0"}

    def test_find_classpath_from_caches(self):
        expected = self.populate()
        cp = mad.find_classpath("26.9", self.props(), "0.19.5", self.gradle)
        self.assertEqual(sorted(cp.jars), sorted(expected))
        self.assertEqual(cp.missing_libraries, ["com.example:absent:1.0"])

    def test_setup_error_names_missing_jars(self):
        with self.assertRaises(mad.SetupError) as caught:
            mad.find_classpath("26.9", self.props(), "0.19.5", self.gradle)
        message = str(caught.exception)
        self.assertIn("minecraft-clientonly-deobf-26.9.jar", message)
        self.assertIn("fabric-api-0.1.0+26.9.pom", message)
        self.assertIn("modmenu-30.0.0", message)
        self.assertIn("add_mc_version.py 26.9", message)

    def test_node_props_override(self):
        root = self.gradle / "repo"
        (root / "versions" / "26.3").mkdir(parents=True)
        (root / "versions" / "26.3" / "gradle.properties").write_text(
            "fabric_api_version=0.161.0+26.3\nmodmenu_version=21.0.0\n", encoding="utf-8")
        self.assertEqual(mad.node_props(root, "26.3")["modmenu_version"], "21.0.0")
        with self.assertRaises(mad.SetupError):
            mad.node_props(root, "26.4-snapshot-1")
        props = mad.node_props(root, "26.4-snapshot-1", fabric_api="0.161.1+26.4", modmenu="22.0.0-alpha.1")
        self.assertEqual(props, {"fabric_api_version": "0.161.1+26.4", "modmenu_version": "22.0.0-alpha.1"})


def jar_with(path, writers):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as jar:
        for w in writers:
            jar.writestr(f"{w.name}.class", w.bytes())
    return path


def fake_javap(javap, args):
    """javap -p -s -constants -cp <jar> <names>: one block per class, its text taken from the jar's class members."""
    jar = args[args.index("-cp") + 1]
    index = mad.ClassIndex([jar])
    out = []
    for dotted in args[args.index("-cp") + 2:]:
        info = index.get(dotted.replace(".", "/"))
        members = sorted(f"  {n}{d};" for n, d in list(info.fields) + list(info.methods))
        out.append("\n".join([f"public class {dotted} {{"] + members + ["}"]))
    index.close()
    return "\n".join(out) + "\n"


class MainTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name) / "repo"
        self.gradle = Path(tmp.name) / "gradle"
        (self.root / "versions" / "26.8").mkdir(parents=True)
        (self.root / "gradle.properties").write_text("loader_version=0.19.5\n", encoding="utf-8")
        (self.root / "versions" / "26.8" / "gradle.properties").write_text(
            "fabric_api_version=0.1.0+26.8\nmodmenu_version=30.0.0\n", encoding="utf-8")
        probe = ClassWriter("io/github/chaotix345/rigtune/client/Probe")
        probe.ref(*GET_DECLARED_FIELD)
        probe.ref("method", "net/minecraft/A", "tick", "()V")
        probe.ref("field", "net/minecraft/client/Options", "renderDistance", "I")
        probe.string("serverRenderDistance")
        target = self.root / "versions" / "26.8" / "build" / "classes" / "java" / "client" / "io" / "github" / "chaotix345" / "rigtune" / "client"
        target.mkdir(parents=True)
        self.probe_file = target / "Probe.class"
        self.probe_file.write_bytes(probe.bytes())
        for name, value in (("java_tool", lambda name, java_home: name), ("run_javap", fake_javap),
                            ("JdkClasses", lambda *args: jdk_index())):
            original = getattr(mad, name)
            setattr(mad, name, value)
            self.addCleanup(setattr, mad, name, original)

    def cache(self, mc, classes):
        modules = self.gradle / "caches" / "modules-2" / "files-2.1"
        mc_maven = self.gradle / "caches" / "fabric-loom" / "minecraftMaven" / "net" / "minecraft"
        jar_with(mc_maven / "minecraft-clientonly-deobf" / mc / f"minecraft-clientonly-deobf-{mc}.jar", classes)
        empty_jar(mc_maven / "minecraft-common-deobf" / mc / f"minecraft-common-deobf-{mc}.jar")
        (self.gradle / "caches" / "fabric-loom" / mc).mkdir(parents=True, exist_ok=True)
        (self.gradle / "caches" / "fabric-loom" / mc / "mojang_minecraft_info.json").write_text('{"libraries": []}', encoding="utf-8")
        pom = modules / "net.fabricmc.fabric-api" / "fabric-api" / f"0.1.0+{mc}" / "p" / f"fabric-api-0.1.0+{mc}.pom"
        pom.parent.mkdir(parents=True, exist_ok=True)
        pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><dependencies/></project>', encoding="utf-8")
        empty_jar(modules / "com.terraformersmc" / "modmenu" / "30.0.0" / "h" / "modmenu-30.0.0.jar")
        empty_jar(modules / "net.fabricmc" / "fabric-loader" / "0.19.5" / "h" / "fabric-loader-0.19.5.jar")

    def run_main(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        code = mad.main(["26.8", "26.9", "--fabric-api", "0.1.0+26.9", "--modmenu", "30.0.0",
                         "--gradle-home", str(self.gradle), *extra], root=self.root, out=out, err=err)
        return code, out.getvalue(), err.getvalue()

    def test_same_api_exits_0_and_writes_the_report(self):
        for mc in ("26.8", "26.9"):
            self.cache(mc, [ClassWriter("net/minecraft/A").method("tick", "()V"), options()])
        out_dir = self.root / "out"
        code, out, err = self.run_main("--out", str(out_dir))
        self.assertEqual(code, 0, out + err)
        self.assertIn("OK 2, MISSING 0, CHANGED 0, UNRESOLVED 0", out)
        self.assertIn("RESULT: no breaking change found", out)
        self.assertIn("not compiled, so not checked: main, gametest, e2e, e2eUndo", out)
        report = json.loads((out_dir / "report.json").read_text(encoding="utf-8"))
        self.assertEqual(report["breaking"], [])
        self.assertEqual((out_dir / "summary.txt").read_text(encoding="utf-8").splitlines(), out.splitlines())

    def test_missing_member_exits_1_and_diffs_the_class(self):
        self.cache("26.8", [ClassWriter("net/minecraft/A").method("tick", "()V"), options()])
        self.cache("26.9", [ClassWriter("net/minecraft/A").method("tick", "(F)V"), options(fields=("renderDistance",))])
        out_dir = self.root / "out"
        code, out, err = self.run_main("--out", str(out_dir))
        self.assertEqual(code, 1, out + err)
        self.assertIn("MISSING method net/minecraft/A.tick()V: not found on the new version", out)
        self.assertIn("MISSING name net/minecraft/client/Options.serverRenderDistance (a string RigTune uses)", out)
        self.assertIn("  DIFF net/minecraft/A", out)
        self.assertIn("-  tick()V;", (out_dir / "diff-net.minecraft.A.txt").read_text(encoding="utf-8"))

    def test_missing_jar_exits_2_with_how_to(self):
        self.cache("26.8", [ClassWriter("net/minecraft/A").method("tick", "()V"), options()])
        code, out, err = self.run_main()
        self.assertEqual(code, 2)
        self.assertIn("minecraft-clientonly-deobf-26.9.jar", err)
        self.assertIn("git worktree add", err)

    def test_unexpected_error_exits_2_with_one_line(self):
        for mc in ("26.8", "26.9"):
            self.cache(mc, [ClassWriter("net/minecraft/A").method("tick", "()V"), options()])
        self.probe_file.write_bytes(b"not a class file")
        code, out, err = self.run_main()
        self.assertEqual(code, 2)
        self.assertEqual(err, "mc_apidiff: unexpected error: ValueError: not a class file\n")

    def test_no_compiled_classes_exits_2(self):
        code, out, err = self.run_main("--sets", "gametest")
        self.assertEqual(code, 2)
        self.assertIn(":26.8:classes", err)


if __name__ == "__main__":
    unittest.main()
