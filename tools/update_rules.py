#!/usr/bin/env python3
"""Regenerates rules/rules-v2.json (plus its bundled copy) and its v1 projection
rules/rules-v1.json from rules/source/knowledge.json plus live data from the
Fabulously Optimized / Additive packwiz repos and the Modrinth API. See
docs/RULES_SCHEMA.md for the output contract and tools/README.md for the
pipeline and the v1 projection rules.
"""

import argparse
import copy
import hashlib
import json
import os
import re
import sys
import time
import tomllib
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from pathlib import Path

USER_AGENT = "chaotix345/rigtune-updater/1.0 (github.com/chaotix345/rigtune)"
MODRINTH_API = "https://api.modrinth.com/v2"
GITHUB_API = "https://api.github.com"
RETRYABLE_STATUSES = {429, 500, 502, 503, 504}
# Modrinth's own API cap is 300 req/min; 0.25s between calls keeps us at 240/min.
MODRINTH_MIN_INTERVAL = 0.25
# Gson (the client's JSON lib) reads revision into a Java int; stay clear of overflow.
MAX_SAFE_REVISION = 2**31 - 2

FO_OWNER_REPO = "Fabulously-Optimized/fabulously-optimized"
ADDITIVE_OWNER_REPO = "skywardmc/additive"
# Statuses Modrinth treats as "still a working, reachable project" per docs.modrinth.com.
OK_PROJECT_STATUSES = {"approved", "unlisted"}
MENTION_RE = re.compile(r"@(?=\w)")


class UpdateRulesError(Exception):
    pass


class KnowledgeError(UpdateRulesError):
    pass


class ProjectionError(KnowledgeError):
    pass


class HttpStatusError(UpdateRulesError):
    def __init__(self, status, url):
        super().__init__(f"HTTP {status} for {url}")
        self.status = status
        self.url = url


# --- Schema: what 0.1.x (schemaVersion 1) and 0.2 (schemaVersion 2) understand -------------------------------

V1_CONDITION_KEYS = frozenset({
    "always", "tierAtLeast", "tierAtMost", "rawTierAtLeast", "rawTierAtMost", "gpuVendor", "gpuIntegrated",
    "gpuTierAtLeast", "gpuTierAtMost", "cpuTierAtLeast", "cpuTierAtMost", "hasBattery", "onBattery",
    "heapMbAtLeast", "heapMbAtMost", "ramMbAtLeast", "ramMbAtMost", "vramMbAtLeast", "vramMbAtMost",
    "refreshRateAtLeast", "backend", "os", "goal", "mcVersion", "modPresent", "modAbsent", "flags", "anyOf", "not",
})
# What 0.2.0 and 0.3.0 understand (their Condition is byte-identical; SchemaConsistencyTest checks this against the pinned
# v030 copy). A key or value outside it is newer than them and poisons the condition there, so the rule doesn't fire: safe
# for advice and value entries, not for rules whose firing is the protection (plan review R-L1, restrictive_problems).
LEGACY_V2_CONDITION_KEYS = V1_CONDITION_KEYS | {
    "gpuModelMatches", "displayPixelsAtLeast", "displayPixelsAtMost", "modVersion", "mcVersionRange", "settingIs",
}
V2_CONDITION_KEYS = LEGACY_V2_CONDITION_KEYS | {"driverVersion"}
# v0.4 Stutter Doctor keys (docs/v0.4/SPEC.md 5): Condition fields, but allowed only inside `stutterAdvice`.
STUTTER_CONDITION_KEYS = frozenset({
    "stutterShareAtLeast", "stutterTaggedShareAtLeast", "gcFullPausesAtLeast", "gcStallsAtLeast", "gcExplicitPausesAtLeast",
    "liveSetPercentAtLeast", "heapRaiseRoomMbAtLeast", "cpuContentionShareAtLeast", "spikesPerMinuteAtLeast", "gcCollector",
})
# The share maps' keys (core/stutter/StutterFacts): causes that claim lost milliseconds, and tags that only count spikes.
# Values are whole percent (0-100), as numbers or digit strings (plan review K-M1).
STUTTER_MAP_KEYS = {
    "stutterShareAtLeast": frozenset({"gc", "chunkLoad", "chunkBuild", "tick", "render", "unknown"}),
    "stutterTaggedShareAtLeast": frozenset({"worldSave", "dh", "cpuContention", "afterTeleport", "movingFast"}),
}
STUTTER_PERCENT_KEYS = frozenset({"liveSetPercentAtLeast", "cpuContentionShareAtLeast"})
# docs/v0.4/SPEC.md 9: {"vendor": <gpuVendor>, "atLeast": "526.47", "atMost": "536.22"}, compared on the parsed ints.
DRIVER_VERSION_FIELDS = frozenset({"vendor", "atLeast", "atMost"})
DRIVER_VERSION_RE = re.compile(r"[0-9]{1,9}(?:\.[0-9]{1,9})*")
# docs/v0.4/SPEC.md 6: the JVM facts rules may test under `flags` (WS-J's core/jvm/JvmFacts.RULE_FLAGS). 0.4 evaluates
# them only for rules with `requires` naming JVM_FEATURE, which 0.2.0/0.3.0 skip. jvm-probed is set by the client (the
# check ran; plan review J-M1) and isn't rule vocabulary; any other jvm- flag is refused as a typo.
JVM_FLAG_PREFIX = "jvm-"
JVM_FLAGS = frozenset({
    "jvm-gc-g1", "jvm-gc-zgc", "jvm-gc-shenandoah", "jvm-gc-parallel", "jvm-gc-serial", "jvm-gc-epsilon", "jvm-gc-other",
    "jvm-gc-typed", "jvm-ignored-flags", "jvm-young-gen-fixed", "jvm-server-flags", "jvm-explicit-gc-disabled",
    "jvm-xmx-duplicate",
})
JVM_PROBED_FLAG = "jvm-probed"
# Client features a rule's `requires` may name (docs/RULES_SCHEMA.md "requires"): jvm-flags is the main Recommender's in
# 0.4 (Recommender.SUPPORTED_FEATURES); stutter-doctor only the Stutter Doctor's (every stutterAdvice entry needs it).
JVM_FEATURE = "jvm-flags"
STUTTER_FEATURE = "stutter-doctor"
BOOLEAN_CONDITION_KEYS = frozenset({"always", "gpuIntegrated", "hasBattery", "onBattery"})
LIST_CONDITION_KEYS = frozenset({"gpuVendor", "backend", "os", "goal", "mcVersion", "modPresent", "modAbsent", "flags",
                                 "gcCollector"})
STRING_CONDITION_KEYS = frozenset({"gpuModelMatches", "mcVersionRange"})
# Java Integer fields; every other numeric key is a Java Long.
INT32_CONDITION_KEYS = frozenset({"tierAtLeast", "tierAtMost", "rawTierAtLeast", "rawTierAtMost", "gpuTierAtLeast",
                                  "gpuTierAtMost", "cpuTierAtLeast", "cpuTierAtMost", "refreshRateAtLeast",
                                  "gcFullPausesAtLeast", "gcStallsAtLeast", "gcExplicitPausesAtLeast", "liveSetPercentAtLeast",
                                  "cpuContentionShareAtLeast", "spikesPerMinuteAtLeast"})
MAX_PATTERN_LENGTH = 200

# Enumerated condition values. V1 is what 0.1.0 understands and is frozen: 0.1.x evaluates two-valued, so a value it
# can't detect would turn TRUE under `not` there. V2 is what 0.2's ConditionEvaluator knows (SchemaConsistencyTest
# checks both against the Java code). A value only V2 knows makes a condition v2-only, like a v2 key.
V1_VOCABULARIES = {
    "gpuVendor": frozenset({"nvidia", "amd", "intel", "apple", "qualcomm", "software", "other", "unknown"}),
    "backend": frozenset({"opengl", "vulkan"}),
    "os": ("windows", "macos", "linux"),
    "goal": frozenset({"performance", "balanced", "quality"}),
    "flags": frozenset({"backend-vulkan", "shaders-enabled"}),
}
V2_VOCABULARIES = {
    "gpuVendor": frozenset({"nvidia", "amd", "intel", "apple", "qualcomm", "software", "other", "unknown"}),
    "backend": frozenset({"opengl", "vulkan"}),
    "os": ("windows", "macos", "linux"),
    "goal": frozenset({"performance", "balanced", "quality"}),
    "flags": frozenset({"backend-vulkan", "shaders-enabled"}),
    # v0.4, not in ConditionEvaluator.FLAGS: the jvm- facts (WS-J evaluates them separately) and the Stutter Doctor's
    # collector names (stutterAdvice only).
    "jvmFlags": JVM_FLAGS,
    "gcCollector": frozenset({"g1", "zgc", "shenandoah", "parallel", "serial"}),
}
# 0.2.0/0.3.0's vocabularies (ConditionEvaluator, byte-identical in both; SchemaConsistencyTest checks the pinned copy).
LEGACY_V2_VOCABULARIES = {k: v for k, v in V2_VOCABULARIES.items() if k not in ("jvmFlags", "gcCollector")}
SODIUM_WORKAROUND_FLAG = "sodium-workaround:"
KNOWLEDGE_TOP_LEVEL = frozenset({
    "minModVersion", "gpuTiers", "gpuVendorFallback", "cpuTiers", "heapTiers", "mods", "obsolete", "settings", "advice",
    "settingLabels", "profileTemplates", "stutterAdvice", "reviewIgnore",
})
# v0.4 rules-v2 sections (docs/v0.4/SPEC.md C2): never written to rules-v1.json.
V2_ONLY_SECTIONS = ("settingLabels", "profileTemplates", "stutterAdvice")
# profileTemplates (docs/v0.4/SPEC.md 4, docs/research/v0.4/profiles.md §4.2).
PROFILE_TEMPLATE_IDS = ("max_fps", "balanced", "quality", "battery", "recording")
PROFILE_TEMPLATE_FIELDS = frozenset({"requires", "id", "goal", "facts", "settings"})
PROFILE_TEMPLATE_FACTS = frozenset({"onBattery", "hasBattery"})
# The keys a profile manages: the share-code table plus the two local-only thread counts (profiles.md §5.2). Never
# vanilla.graphicsPreset or iris.shaderPack.
MANAGED_PROFILE_KEYS = frozenset({
    "vanilla.renderDistance", "vanilla.simulationDistance", "vanilla.entityDistanceScaling", "vanilla.maxFps",
    "vanilla.enableVsync", "vanilla.inactivityFpsLimit", "vanilla.particles", "vanilla.biomeBlendRadius",
    "vanilla.weatherRadius", "vanilla.textureFiltering", "vanilla.renderClouds", "vanilla.prioritizeChunkUpdates",
    "vanilla.improvedTransparency", "vanilla.entityShadows", "vanilla.cutoutLeaves",
    "sodium.performance.use_fog_occlusion", "sodium.performance.use_block_face_culling",
    "sodium.performance.use_entity_culling", "sodium.performance.animate_only_visible_textures",
    "sodium.performance.chunk_build_defer_mode", "sodium.performance.quad_splitting_mode",
    "sodium.performance.chunk_builder_threads",
    "iris.enableShaders", "iris.maxShadowRenderDistance",
    "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", "dh.client.advanced.graphics.quality.verticalQuality",
    "dh.client.advanced.graphics.quality.horizontalQuality", "dh.client.advanced.graphics.quality.maxHorizontalResolution",
    "dh.client.advanced.debugging.rendererMode", "dh.common.multiThreading.numberOfThreads",
})
ADVICE_KINDS = frozenset({"info", "warning", "critical"})
IMPACTS = frozenset({"high", "medium", "low"})

RULE_KINDS = ("mods", "obsolete", "settings", "advice")
TIER_KINDS = ("gpuTiers", "cpuTiers", "heapTiers")
V1_RULE_FIELDS = {
    "mods": frozenset({"slug", "projectId", "title", "modIds", "category", "impact", "stability", "reason",
                       "recommendWhen", "avoidWhen", "avoidReason", "conflictsWith", "defaultSelected", "upstream"}),
    "obsolete": frozenset({"modIds", "title", "reason", "replacement"}),
    "settings": frozenset({"key", "value", "min", "max", "when", "reason", "impact", "defaultSelected"}),
    "advice": frozenset({"id", "when", "impact", "title", "text", "kind"}),
    "gpuTiers": frozenset({"pattern", "vendor", "integrated", "tier"}),
    "cpuTiers": frozenset({"pattern", "tier"}),
    "heapTiers": frozenset({"atLeastMb", "tier"}),
}
V2_ONLY_RULE_FIELDS = {
    "mods": frozenset({"requires", "avoidSelected", "skipUpdateWhen"}),
    "obsolete": frozenset({"requires"}),
    "settings": frozenset({"requires"}),
    "advice": frozenset({"requires"}),
}
SOURCE_ONLY_TIER_FIELDS = {"gpuTiers": frozenset({"v1"}), "cpuTiers": frozenset({"v1"})}
CONDITION_FIELDS = {"mods": ("recommendWhen", "avoidWhen", "skipUpdateWhen"), "obsolete": (), "settings": ("when",), "advice": ("when",)}
V1_SETTING_PREFIXES = ("vanilla.", "sodium.")
# Computed setting values both 0.1.0 and 0.2 resolve. A new token needs a new client, so it must come with `requires`.
VALUE_TOKENS = frozenset({"$refreshRate", "$refreshRateCap"})
# Resolved only in 0.4's template layer (docs/v0.4/SPEC.md 4), so allowed only inside profileTemplates.
TEMPLATE_VALUE_TOKENS = VALUE_TOKENS | {"$recordingFps"}
NEVER = {"always": False}
MISSING = object()


def known_value(field, value, vocabularies=None):
    vocabularies = V2_VOCABULARIES if vocabularies is None else vocabularies
    if not isinstance(value, str):
        return False
    if field not in vocabularies:
        return True
    lower = value.lower()
    if field == "os":
        return lower != "" and any(family.startswith(lower) for family in vocabularies["os"])
    if field == "flags":
        return (value in vocabularies["flags"] or value in vocabularies.get("jvmFlags", ())
                or (value.startswith(SODIUM_WORKAROUND_FLAG) and len(value) > len(SODIUM_WORKAROUND_FLAG)))
    return lower in vocabularies[field]


def is_integer(value):
    return isinstance(value, int) and not isinstance(value, bool)


def whole_percent(value):
    """A share threshold inside a stutter map: 0-100 as a JSON integer or a digit string (the Java field is a
    Map<String, String>, its numbers parsed by the evaluator; plan review K-M1)."""
    if is_integer(value):
        return 0 <= value <= 100
    return isinstance(value, str) and value.isascii() and value.isdigit() and int(value) <= 100


def driver_version_parts(text):
    return [int(p) for p in text.split(".")] if isinstance(text, str) and DRIVER_VERSION_RE.fullmatch(text) else None


def driver_version_problems(value, where):
    if not isinstance(value, dict):
        return [f'{where} must be an object like {{"vendor": "nvidia", "atLeast": "526.47", "atMost": "536.22"}}']
    problems = [f"{where}.{k}: unknown field (only vendor, atLeast, atMost)" for k in sorted(set(value) - DRIVER_VERSION_FIELDS)]
    vendor = value.get("vendor")
    if not isinstance(vendor, str) or not known_value("gpuVendor", vendor) or vendor != vendor.lower():
        problems.append(f"{where}.vendor must be a lower-case gpuVendor value")
    bounds = {}
    for key in ("atLeast", "atMost"):
        if key in value:
            parts = driver_version_parts(value[key])
            if parts is None:
                problems.append(f'{where}.{key} must be a dotted version string like "526.47"')
            else:
                bounds[key] = parts
    if "atLeast" not in value and "atMost" not in value:
        problems.append(f"{where} needs atLeast or atMost")
    if len(bounds) == 2:
        width = max(len(bounds["atLeast"]), len(bounds["atMost"]))
        pad = lambda parts: parts + [0] * (width - len(parts))
        if pad(bounds["atLeast"]) > pad(bounds["atMost"]):
            problems.append(f"{where}: atLeast is above atMost")
    return problems


def condition_problems(cond, allowed_keys=V2_CONDITION_KEYS, path="condition", vocabularies=None):
    """Everything wrong with a condition for a client that knows allowed_keys and vocabularies (default: 0.2's):
    unknown keys, nulls, wrong types and values outside the vocabularies, recursively through not/anyOf."""
    if not isinstance(cond, dict):
        return [f"{path} must be an object"]
    problems = []
    for key, value in cond.items():
        where = f"{path}.{key}"
        if key not in allowed_keys:
            if key in STUTTER_CONDITION_KEYS and allowed_keys is V2_CONDITION_KEYS:
                problems.append(f"{where}: a Stutter Doctor key, allowed only inside stutterAdvice")
            else:
                problems.append(f"{where}: unknown condition key")
        elif value is None:
            problems.append(f"{where}: null")
        elif key == "not":
            problems += condition_problems(value, allowed_keys, where, vocabularies)
        elif key == "anyOf":
            if not isinstance(value, list):
                problems.append(f"{where} must be an array")
            else:
                for i, sub in enumerate(value):
                    problems += condition_problems(sub, allowed_keys, f"{where}[{i}]", vocabularies)
        elif key in BOOLEAN_CONDITION_KEYS:
            if not isinstance(value, bool):
                problems.append(f"{where} must be true or false")
        elif key in LIST_CONDITION_KEYS:
            if not isinstance(value, list) or not all(isinstance(v, str) for v in value):
                problems.append(f"{where} must be an array of strings")
            else:
                for v in value:
                    if key == "flags" and v == JVM_PROBED_FLAG:
                        problems.append(f"{where}: {v!r} is set by RigTune itself when its JVM check ran; rules can't test it")
                    elif not known_value(key, v, vocabularies):
                        problems.append(f"{where}: {v!r} is outside the known values")
                    elif key == "gcCollector" and v != v.lower():
                        problems.append(f"{where}: {v!r} must be lower case")
        elif key == "driverVersion":
            problems += driver_version_problems(value, where)
        elif key in STUTTER_MAP_KEYS:
            if not isinstance(value, dict) or not value:
                problems.append(f"{where} must map {'causes' if key == 'stutterShareAtLeast' else 'tags'} to whole percentages")
            else:
                for name, share in value.items():
                    if name not in STUTTER_MAP_KEYS[key]:
                        problems.append(f"{where}.{name}: not one of {', '.join(sorted(STUTTER_MAP_KEYS[key]))}")
                    elif not whole_percent(share):
                        problems.append(f"{where}.{name} must be a whole percentage (0-100)")
        elif key in STUTTER_PERCENT_KEYS and is_integer(value) and not 0 <= value <= 100:
            problems.append(f"{where} must be a whole percentage (0-100)")
        elif key in STUTTER_CONDITION_KEYS and is_integer(value) and value < 0:
            problems.append(f"{where} can't be negative")
        elif key in STRING_CONDITION_KEYS:
            if not isinstance(value, str) or not value.strip():
                problems.append(f"{where} must be a non-empty string")
            elif key == "gpuModelMatches" and len(value) > MAX_PATTERN_LENGTH:
                problems.append(f"{where} is longer than {MAX_PATTERN_LENGTH} characters")
        elif key == "modVersion":
            if not isinstance(value, dict) or not all(isinstance(v, str) and v.strip() for v in value.values()):
                problems.append(f"{where} must map mod ids to version predicates")
        elif key == "settingIs":
            if not isinstance(value, dict) or not all(isinstance(k, str) and k.strip() for k in value)                     or not all(isinstance(v, (str, bool, int, float)) for v in value.values()):
                problems.append(f"{where} must map settings keys to strings, numbers or booleans")
        elif not is_integer(value):
            problems.append(f"{where} must be an integer")
        elif not -(2 ** bits(key) - 1) - 1 <= value <= 2 ** bits(key) - 1:
            problems.append(f"{where} is outside the range of a {bits(key) + 1}-bit integer")
    return problems


def bits(key):
    return 31 if key in INT32_CONDITION_KEYS else 63


def is_v1_condition(cond):
    return not condition_problems(cond, V1_CONDITION_KEYS, vocabularies=V1_VOCABULARIES)


def is_legacy_v2_condition(cond):
    """Whether 0.2.0 and 0.3.0 understand every key and value (so the condition doesn't poison there)."""
    return not condition_problems(cond, LEGACY_V2_CONDITION_KEYS, vocabularies=LEGACY_V2_VOCABULARIES)


def condition_nodes(cond):
    """Every object of a condition tree (itself, then through not/anyOf)."""
    if not isinstance(cond, dict):
        return
    yield cond
    yield from condition_nodes(cond.get("not"))
    any_of = cond.get("anyOf")
    for sub in any_of if isinstance(any_of, list) else []:
        yield from condition_nodes(sub)


def uses_jvm_flag(cond):
    return any(isinstance(node.get("flags"), list) and any(isinstance(f, str) and f.startswith(JVM_FLAG_PREFIX) for f in node["flags"])
               for node in condition_nodes(cond))


def feature_problems(label, conditions, requires):
    """A condition testing a jvm- fact needs `requires` naming jvm-flags, so 0.2.0/0.3.0 skip the whole rule (SPEC 6)."""
    requires = requires if isinstance(requires, list) else []
    if any(uses_jvm_flag(c) for c in conditions) and JVM_FEATURE not in requires:
        return [f'{label}: a rule that tests a jvm- flag needs "requires": ["{JVM_FEATURE}"]']
    return []


def restrictive_problems(kind, rule, label):
    """Plan review R-L1. A key or value 0.2.0/0.3.0 don't know makes a condition UNKNOWN there, and the rule doesn't fire.
    Where firing is the protection (a clamp, an avoidWhen, a skipUpdateWhen), not firing loses it, so such a rule must
    carry `requires` (then older clients skip it knowingly, and the maintainer keeps a legacy rule next to it)."""
    if rule.get("requires"):
        return []
    if kind == "settings":
        fields = ["when"] if "min" in rule or "max" in rule else []
    elif kind == "mods":
        fields = ["avoidWhen", "skipUpdateWhen"]
    else:
        fields = []
    problems = []
    for field in fields:
        cond = rule.get(field)
        if isinstance(cond, dict) and not condition_problems(cond, V2_CONDITION_KEYS, field) and not is_legacy_v2_condition(cond):
            problems.append(f"{label}: {field} uses a condition 0.2.0/0.3.0 don't know, so they'd silently drop this "
                            f"restriction; add \"requires\" (and keep a rule they understand next to it)")
    return problems


def value_or_clamp_problems(label, rule):
    """Review-8 CR-2: a settings rule is a value entry or a clamp, never both (clients read only `value` then, so the
    min/max restriction would be silently lost) and never neither (it would do nothing)."""
    has_value = "value" in rule
    has_clamp = "min" in rule or "max" in rule
    if has_value and has_clamp:
        return [f"{label}: sets both value and min/max; clients read only value, so the min/max would be ignored; use one"]
    if not has_value and not has_clamp:
        return [f"{label}: needs value or min/max"]
    return []


def contains_null(value):
    if value is None:
        return True
    if isinstance(value, dict):
        return any(contains_null(v) for v in value.values())
    if isinstance(value, list):
        return any(contains_null(v) for v in value)
    return False


def rule_label(kind, rule, index=None):
    if kind == "mods":
        return f"mods[{rule.get('slug')}]"
    if kind == "advice":
        return f"advice[{rule.get('id')}]"
    if kind == "obsolete":
        return f"obsolete[{rule.get('title') or ','.join(rule.get('modIds', []))}]"
    position = "" if index is None else str(index)
    if kind == "settings":
        return f"settings[{position}] {rule.get('key')}"
    return f"{kind}[{position}]"


def unknown_token(value):
    return isinstance(value, str) and value.strip().startswith("$") and value.strip() not in VALUE_TOKENS


def droppable(field, value, merged):
    """Whether leaving a v2-only field out of the v1 output is exactly as safe as keeping it."""
    if field == "requires":
        return value == []
    if field == "avoidSelected":
        return value is True or "avoidWhen" not in merged
    # 0.1.x offers every available update whatever its rules say; this field only ever takes one away.
    if field == "skipUpdateWhen":
        return True
    return False


def project_rule(kind, rule, index=None):
    """The v1 form of one knowledge rule (None = omitted from rules-v1.json) and notes for REVIEW.md (d).
    Raises ProjectionError when the rule can't be projected safely without a maintainer's decision."""
    label = rule_label(kind, rule, index)

    def fail(message):
        raise ProjectionError(f"{label}: {message}")

    override = rule.get("v1", MISSING)
    if override is False:
        return None, ['omitted ("v1": false)']
    if override is not MISSING and not isinstance(override, dict):
        fail('"v1" must be false or an object of v1 fields')
    explicit = isinstance(override, dict)
    merged = {k: copy.deepcopy(v) for k, v in rule.items() if k != "v1"}
    notes = []
    if explicit:
        if contains_null(override):
            fail('null in the "v1" override (0.1.x reads a null condition as "always")')
        not_v1 = sorted(set(override) - V1_RULE_FIELDS[kind])
        if not_v1:
            fail(f'the "v1" override may only set fields 0.1.x understands, not {", ".join(not_v1)}')
        for field in CONDITION_FIELDS[kind]:
            if field in override and not is_v1_condition(override[field]):
                fail(f'the "v1" override\'s {field} uses fields or values 0.1.x doesn\'t know')
        merged.update(copy.deepcopy(override))
        notes.append(f"v1 override: {', '.join(sorted(override))}")

    if kind == "settings":
        key = merged.get("key")
        if not isinstance(key, str) or not key.startswith(V1_SETTING_PREFIXES):
            fail('the key is outside vanilla./sodium., which 0.1.x can\'t apply; add "v1": false')
        if "when" in merged and not is_v1_condition(merged["when"]):
            fail('"when" uses v2 condition features; add "v1": false or a "v1" override with a v1 "when"')
        if unknown_token(merged.get("value")):
            fail(f"the value {merged['value']!r} is a token 0.1.x doesn't know; add \"v1\": false")
    elif kind == "advice":
        if "when" in merged and not is_v1_condition(merged["when"]):
            if str(merged.get("kind", "info")).lower() in ("warning", "critical"):
                fail('a warning whose "when" uses v2 condition features would silently disappear for 0.1.x; add "v1": false '
                     'or a "v1" override with a v1 "when"')
            merged["when"] = dict(NEVER)
            notes.append("when uses v2 condition features: never shown to 0.1.x")
    elif kind == "mods":
        if "recommendWhen" in merged and not is_v1_condition(merged["recommendWhen"]):
            merged["recommendWhen"] = dict(NEVER)
            notes.append("recommendWhen uses v2 condition features: never recommended to 0.1.x")
        if "avoidWhen" in merged and not is_v1_condition(merged["avoidWhen"]):
            if merged.get("recommendWhen") != NEVER:
                fail("avoidWhen uses v2 condition features and would be dropped for 0.1.x while recommendWhen can "
                     "still fire, so 0.1.x would offer the mod where 0.2 avoids it; add a v1 avoidWhen override "
                     '(or a v1 recommendWhen of {"always": false})')
            del merged["avoidWhen"]
            merged.pop("avoidReason", None)
            notes.append("avoidWhen uses v2 condition features: dropped (0.1.x gets no disable suggestion)")

    for field in sorted(set(merged) - V1_RULE_FIELDS[kind]):
        if field not in V2_ONLY_RULE_FIELDS.get(kind, ()):
            fail(f"unknown field {field!r}")
        if not explicit:
            fail(f'{field!r} isn\'t understood by 0.1.x; add "v1": false or a "v1" override')
        if not droppable(field, merged[field], merged):
            fail(f'{field!r} can\'t be left out of rules-v1.json safely; use "v1": false')
        del merged[field]
        notes.append(f"{field} left out")
    return merged, notes


def setting_label_problems(labels):
    """settingLabels must be {key: {"name": str, "values": {str: str}}} (both optional), or Gson rejects rules-v2.json."""
    if not isinstance(labels, dict):
        return ["settingLabels must be an object"]
    problems = []
    for key, label in labels.items():
        where = f"settingLabels[{key}]"
        if not isinstance(label, dict):
            problems.append(f'{where} must be an object like {{"name": ..., "values": {{...}}}}')
            continue
        unknown = sorted(set(label) - {"name", "values"})
        if unknown:
            problems.append(f"{where}: unknown field(s) {', '.join(unknown)}")
        if "name" in label and not isinstance(label["name"], str):
            problems.append(f"{where}.name must be a string")
        values = label.get("values", {})
        if not isinstance(values, dict) or not all(isinstance(v, str) for v in values.values()):
            problems.append(f"{where}.values must map setting values to strings")
    return problems


def validate_knowledge(knowledge):
    """Rejects knowledge the updater can't turn into safe v2 and v1 outputs (typos, unknown condition keys, nulls,
    values outside the vocabularies, rules that need a v1 decision). Raises KnowledgeError listing every problem."""
    problems = []
    for kind in TIER_KINDS:
        rows = knowledge.get(kind, [])
        if not isinstance(rows, list):
            problems.append(f"'{kind}' must be an array")
            continue
        for i, rule in enumerate(rows):
            if not isinstance(rule, dict):
                problems.append(f"{kind}[{i}] must be an object")
                continue
            unknown = sorted(set(rule) - V1_RULE_FIELDS[kind] - SOURCE_ONLY_TIER_FIELDS.get(kind, frozenset()) - {"v1"})
            if unknown:
                problems.append(f"{kind}[{i}]: unknown field(s) {', '.join(unknown)} (tier-rule changes need a new schemaVersion)")
            if "v1" in rule and kind not in SOURCE_ONLY_TIER_FIELDS:
                problems.append(f'{kind}[{i}]: "v1" is only allowed on gpuTiers and cpuTiers rows')
            elif "v1" in rule and rule["v1"] is not False:
                problems.append(f'{kind}[{i}]: "v1" on a tier row may only be false (the row is left out of rules-v1.json)')
    for kind in RULE_KINDS:
        rules = knowledge.get(kind, [])
        if not isinstance(rules, list):
            problems.append(f"'{kind}' must be an array")
            continue
        for i, rule in enumerate(rules):
            if not isinstance(rule, dict):
                problems.append(f"{kind}[{i}] must be an object")
                continue
            label = rule_label(kind, rule, i)
            unknown = sorted(set(rule) - V1_RULE_FIELDS[kind] - V2_ONLY_RULE_FIELDS[kind] - {"v1"})
            if unknown:
                problems.append(f"{label}: unknown field(s) {', '.join(unknown)}")
            if any(contains_null(v) for k, v in rule.items() if k != "v1"):
                problems.append(f"{label}: null isn't allowed in a rule")
            for field in CONDITION_FIELDS[kind]:
                if field in rule:
                    problems += [f"{label}: {p}" for p in condition_problems(rule[field], V2_CONDITION_KEYS, field)]
            if "requires" in rule and (not isinstance(rule["requires"], list) or not all(isinstance(r, str) for r in rule["requires"])):
                problems.append(f"{label}: requires must be an array of strings")
            if kind == "settings":
                problems += value_or_clamp_problems(label, rule)
            if kind == "settings" and unknown_token(rule.get("value")) and not rule.get("requires"):
                problems.append(f"{label}: the value {rule['value']!r} isn't a known token ({', '.join(sorted(VALUE_TOKENS))}); "
                                "a new token needs \"requires\" naming the client feature that resolves it")
            if "avoidSelected" in rule and not isinstance(rule["avoidSelected"], bool):
                problems.append(f"{label}: avoidSelected must be true or false")
            problems += feature_problems(label, [rule[f] for f in CONDITION_FIELDS[kind] if f in rule], rule.get("requires"))
            problems += restrictive_problems(kind, rule, label)
            if string_list(rule.get("requires")) and STUTTER_FEATURE in rule["requires"]:
                problems.append(f"{label}: \"{STUTTER_FEATURE}\" is only the Stutter Doctor's feature; the main list would skip this rule")
            if not unknown:
                try:
                    project_rule(kind, rule, i)
                except ProjectionError as e:
                    problems.append(str(e))
    unknown_top = sorted(set(knowledge) - KNOWLEDGE_TOP_LEVEL)
    if unknown_top:
        problems.append(f"unknown top-level field(s) {', '.join(unknown_top)}")
    problems += setting_label_problems(knowledge.get("settingLabels", {}))
    if "profileTemplates" in knowledge:
        problems += profile_template_problems(knowledge["profileTemplates"])
    if "stutterAdvice" in knowledge:
        problems += stutter_advice_problems(knowledge["stutterAdvice"])
    if problems:
        raise KnowledgeError("invalid knowledge:\n  " + "\n  ".join(problems))


def string_list(value):
    return isinstance(value, list) and all(isinstance(v, str) for v in value)


def profile_template_problems(section):
    """profileTemplates (v0.4, rules-v2 only): {"templates": [{"id", "goal", "facts"?, "settings"?, "requires"?}]}.
    Settings entries are SettingRules over the managed keyset; `$recordingFps` is allowed here only."""
    if not isinstance(section, dict):
        return ['profileTemplates must be an object like {"templates": [...]}']
    problems = [f"profileTemplates: unknown field(s) {', '.join(sorted(set(section) - {'templates'}))}"] if set(section) - {"templates"} else []
    templates = section.get("templates")
    if not isinstance(templates, list):
        return problems + ["profileTemplates.templates must be an array"]
    seen = set()
    for i, template in enumerate(templates):
        if not isinstance(template, dict):
            problems.append(f"profileTemplates.templates[{i}] must be an object")
            continue
        label = f"profileTemplates[{template.get('id', i)}]"
        unknown = sorted(set(template) - PROFILE_TEMPLATE_FIELDS)
        if unknown:
            problems.append(f"{label}: unknown field(s) {', '.join(unknown)} (the section never reaches rules-v1.json, so no v1)")
        if contains_null(template):
            problems.append(f"{label}: null isn't allowed in a template")
        template_id = template.get("id")
        if template_id not in PROFILE_TEMPLATE_IDS:
            problems.append(f"{label}: id must be one of {', '.join(PROFILE_TEMPLATE_IDS)}")
        elif template_id in seen:
            problems.append(f"{label}: duplicate id")
        else:
            seen.add(template_id)
        if not isinstance(template.get("goal"), str) or not known_value("goal", template["goal"]) or template["goal"] != template["goal"].lower():
            problems.append(f"{label}: goal must be one of {', '.join(sorted(V2_VOCABULARIES['goal']))}")
        facts = template.get("facts", {})
        if not isinstance(facts, dict) or not all(k in PROFILE_TEMPLATE_FACTS and isinstance(v, bool) for k, v in facts.items()):
            problems.append(f"{label}: facts may only set {', '.join(sorted(PROFILE_TEMPLATE_FACTS))} to true or false")
        if "requires" in template and not string_list(template["requires"]):
            problems.append(f"{label}: requires must be an array of strings")
        settings = template.get("settings", [])
        if not isinstance(settings, list):
            problems.append(f"{label}: settings must be an array")
            continue
        for j, entry in enumerate(settings):
            problems += template_setting_problems(f"{label}.settings[{j}]", entry, template.get("requires"))
    return problems


def template_setting_problems(label, entry, template_requires):
    if not isinstance(entry, dict):
        return [f"{label} must be an object"]
    label = f"{label} {entry.get('key')}"
    problems = []
    unknown = sorted(set(entry) - V1_RULE_FIELDS["settings"] - V2_ONLY_RULE_FIELDS["settings"])
    if unknown:
        problems.append(f"{label}: unknown field(s) {', '.join(unknown)}")
    if not isinstance(entry.get("key"), str) or entry["key"] not in MANAGED_PROFILE_KEYS:
        problems.append(f"{label}: not a key profiles manage (docs/RULES_SCHEMA.md \"profileTemplates\")")
    has_value = "value" in entry
    has_clamp = "min" in entry or "max" in entry
    if has_value == has_clamp:
        problems.append(f"{label}: needs either value or min/max")
    for bound in ("min", "max"):
        if bound in entry and (isinstance(entry[bound], bool) or not isinstance(entry[bound], (int, float))):
            problems.append(f"{label}: {bound} must be a number")
    if has_value and not isinstance(entry["value"], (str, int, float, bool)):
        problems.append(f"{label}: value must be a string, number or boolean")
    requires = [f for group in (entry.get("requires"), template_requires) if string_list(group) for f in group]
    value = entry.get("value")
    if isinstance(value, str) and value.strip().startswith("$") and value.strip() not in TEMPLATE_VALUE_TOKENS and not requires:
        problems.append(f"{label}: the value {value!r} isn't a known token ({', '.join(sorted(TEMPLATE_VALUE_TOKENS))})")
    if "requires" in entry and not string_list(entry["requires"]):
        problems.append(f"{label}: requires must be an array of strings")
    if "when" in entry:
        problems += [f"{label}: {p}" for p in condition_problems(entry["when"], V2_CONDITION_KEYS, "when")]
        problems += feature_problems(label, [entry["when"]], requires)
    return problems


def stutter_advice_problems(section):
    """stutterAdvice (v0.4, rules-v2 only): AdviceRules for the Stutter Doctor. Each needs `requires` naming
    stutter-doctor (the main Recommender doesn't know it) and may use the stutter condition keys."""
    if not isinstance(section, list):
        return ["stutterAdvice must be an array"]
    problems = []
    seen = set()
    allowed = V2_CONDITION_KEYS | STUTTER_CONDITION_KEYS
    for i, rule in enumerate(section):
        if not isinstance(rule, dict):
            problems.append(f"stutterAdvice[{i}] must be an object")
            continue
        label = f"stutterAdvice[{rule.get('id', i)}]"
        unknown = sorted(set(rule) - V1_RULE_FIELDS["advice"] - V2_ONLY_RULE_FIELDS["advice"])
        if unknown:
            problems.append(f"{label}: unknown field(s) {', '.join(unknown)} (the section never reaches rules-v1.json, so no v1)")
        if contains_null(rule):
            problems.append(f"{label}: null isn't allowed in a rule")
        if not isinstance(rule.get("id"), str) or not rule["id"].strip():
            problems.append(f"{label}: needs an id")
        elif rule["id"] in seen:
            problems.append(f"{label}: duplicate id")
        else:
            seen.add(rule["id"])
        if not string_list(rule.get("requires")) or STUTTER_FEATURE not in rule["requires"]:
            problems.append(f'{label}: needs "requires": ["{STUTTER_FEATURE}"]')
        if not isinstance(rule.get("kind"), str) or rule["kind"] not in ADVICE_KINDS:
            problems.append(f"{label}: kind must be one of {', '.join(sorted(ADVICE_KINDS))}")
        if not isinstance(rule.get("impact"), str) or rule["impact"] not in IMPACTS:
            problems.append(f"{label}: impact must be one of {', '.join(sorted(IMPACTS))}")
        for field in ("title", "text"):
            if not isinstance(rule.get(field), str) or not rule[field].strip():
                problems.append(f"{label}: needs a {field}")
        if "when" in rule:
            problems += [f"{label}: {p}" for p in condition_problems(rule["when"], allowed, "when")]
            if uses_jvm_flag(rule["when"]):
                problems.append(f"{label}: the Stutter Doctor doesn't evaluate jvm- flags (the main list's {JVM_FEATURE} feature)")
    return problems


def fo_contents_url(mc_version):
    return f"{GITHUB_API}/repos/{FO_OWNER_REPO}/contents/Packwiz/{mc_version}/mods?ref=main"


def fo_raw_url(mc_version, filename):
    return f"https://raw.githubusercontent.com/{FO_OWNER_REPO}/main/Packwiz/{mc_version}/mods/{filename}"


def additive_contents_url(mc_version):
    return f"{GITHUB_API}/repos/{ADDITIVE_OWNER_REPO}/contents/versions/fabric/{mc_version}/mods?ref=main"


def additive_raw_url(mc_version, filename):
    return f"https://raw.githubusercontent.com/{ADDITIVE_OWNER_REPO}/main/versions/fabric/{mc_version}/mods/{filename}"


PACKS = {
    "fabulouslyOptimized": {"contents_url": fo_contents_url, "raw_url": fo_raw_url},
    "additive": {"contents_url": additive_contents_url, "raw_url": additive_raw_url},
}


PRERELEASE_RANK = {"snapshot": 0, "pre": 1, "rc": 2}


def version_sort_key(version):
    core, _, prerelease = version.partition("-")
    parts = tuple((int(p), "") if p.isdigit() else (-1, p) for p in core.split("."))
    pre = tuple((0, int(p), "") if p.isdigit() else (1, PRERELEASE_RANK.get(p, len(PRERELEASE_RANK)), p)
                for p in re.split(r"[-.]", prerelease)) if prerelease else ()
    return parts, prerelease == "", pre


GROOVY_COMMENT_RE = re.compile(r"""("(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*')|//[^\n]*|/\*.*?\*/""", re.S)
STONECUTTER_VERSIONS_RE = re.compile(r"""^[ \t]*versions\s*\(?\s*((?:["'][^"'\n]+["']\s*,?\s*)+)""", re.M)


def stonecutter_nodes(settings_path):
    try:
        text = Path(settings_path).read_text(encoding="utf-8")
    except OSError as e:
        raise UpdateRulesError(f"can't read the Stonecutter version list from {settings_path}: {e}")
    code = GROOVY_COMMENT_RE.sub(lambda m: m.group(1) or "\n" * m.group(0).count("\n"), text)
    lists = STONECUTTER_VERSIONS_RE.findall(code)
    if len(lists) != 1:
        found = "no" if not lists else f"{len(lists)}"
        raise UpdateRulesError(f"{found} Stonecutter `versions` lists in {settings_path} (expected one); pass --mc-versions")
    return re.findall(r"""["']([^"']+)["']""", lists[0])


def default_opener(request):
    try:
        with urllib.request.urlopen(request, timeout=30) as resp:
            return resp.status, resp.read(), {k.lower(): v for k, v in resp.headers.items()}
    except urllib.error.HTTPError as e:
        headers = {k.lower(): v for k, v in (e.headers or {}).items()}
        return e.code, e.read(), headers


def fixture_key(url):
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


def fixture_opener(fixtures_dir):
    fixtures_dir = Path(fixtures_dir)

    def opener(request):
        url = request.full_url
        path = fixtures_dir / f"{fixture_key(url)}.json"
        if not path.exists():
            raise UpdateRulesError(f"no offline fixture for {url} (expected {path})")
        payload = json.loads(path.read_text(encoding="utf-8"))
        body = payload["body"]
        body_bytes = json.dumps(body).encode("utf-8") if isinstance(body, (dict, list)) else str(body).encode("utf-8")
        headers = {k.lower(): v for k, v in payload.get("headers", {}).items()}
        return payload.get("status", 200), body_bytes, headers

    return opener


def parse_retry_after(value):
    """Parse a Retry-After header value, per RFC 9110: either a number of
    seconds, or an HTTP-date. Returns None if it's neither (caller should
    fall back to its own backoff)."""
    if not value:
        return None
    value = value.strip()
    try:
        return float(value)
    except ValueError:
        pass
    try:
        when = parsedate_to_datetime(value)
    except (TypeError, ValueError, IndexError):
        return None
    if when.tzinfo is None:
        when = when.replace(tzinfo=timezone.utc)
    return max(0.0, (when - datetime.now(timezone.utc)).total_seconds())


def http_get(url, *, headers=None, opener=default_opener, sleeper=time.sleep, max_attempts=6):
    delay = 1.0
    for attempt in range(1, max_attempts + 1):
        request = urllib.request.Request(url, headers=headers or {})
        try:
            status, body, resp_headers = opener(request)
        except OSError:
            if attempt >= max_attempts:
                raise
            sleeper(delay)
            delay = min(delay * 2, 30)
            continue
        if status == 200:
            return body
        github_rate_limited = status == 403 and resp_headers.get("x-ratelimit-remaining") == "0"
        if (status in RETRYABLE_STATUSES or github_rate_limited) and attempt < max_attempts:
            retry_after = parse_retry_after(resp_headers.get("retry-after"))
            if retry_after is not None:
                wait = retry_after
            elif github_rate_limited and resp_headers.get("x-ratelimit-reset"):
                wait = max(0.0, float(resp_headers["x-ratelimit-reset"]) - time.time())
            else:
                wait = delay
            sleeper(min(wait, 60))
            delay = min(delay * 2, 30)
            continue
        raise HttpStatusError(status, url)
    raise HttpStatusError(status, url)


class Client:
    def __init__(self, opener=default_opener, sleeper=time.sleep, github_token=None):
        self.opener = opener
        self.sleeper = sleeper
        self.github_token = github_token
        self._last_modrinth_call = None

    def _throttle_modrinth(self):
        if self._last_modrinth_call is not None:
            wait = MODRINTH_MIN_INTERVAL - (time.monotonic() - self._last_modrinth_call)
            if wait > 0:
                self.sleeper(wait)
        self._last_modrinth_call = time.monotonic()

    def modrinth_json(self, url):
        self._throttle_modrinth()
        headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
        return json.loads(http_get(url, headers=headers, opener=self.opener, sleeper=self.sleeper))

    def github_json(self, url):
        headers = {"User-Agent": USER_AGENT, "Accept": "application/vnd.github+json"}
        if self.github_token:
            headers["Authorization"] = f"Bearer {self.github_token}"
        return json.loads(http_get(url, headers=headers, opener=self.opener, sleeper=self.sleeper))

    def raw_text(self, url):
        headers = {"User-Agent": USER_AGENT}
        return http_get(url, headers=headers, opener=self.opener, sleeper=self.sleeper).decode("utf-8")


def resolve_target_versions(client, override, nodes=None):
    if override:
        versions = [v.strip() for v in override.split(",") if v.strip()]
    else:
        if not nodes:
            raise UpdateRulesError("no target MC versions: the Stonecutter version list is empty and --mc-versions isn't set")
        tags = client.modrinth_json(f"{MODRINTH_API}/tag/game_version")
        releases = [t["version"] for t in tags if t.get("version_type") == "release" and isinstance(t.get("version"), str)]
        versions = list(nodes)
        for node in nodes:
            hotfix = re.compile(re.escape(node) + r"\.\d+")
            versions += [v for v in releases if hotfix.fullmatch(v)]
    return sorted(set(versions), key=version_sort_key, reverse=True)


def list_pw_toml_filenames(client, contents_url_fn, mc_version):
    try:
        entries = client.github_json(contents_url_fn(mc_version))
    except HttpStatusError as e:
        if e.status == 404:
            return None
        raise
    return sorted(
        e["name"] for e in entries
        if isinstance(e, dict) and e.get("type") == "file" and e.get("name", "").endswith(".pw.toml")
    )


def collect_project_ids(client, raw_url_fn, mc_version, filenames):
    ids = set()
    for name in filenames:
        text = client.raw_text(raw_url_fn(mc_version, name))
        try:
            data = tomllib.loads(text)
        except tomllib.TOMLDecodeError as e:
            print(f"warning: skipping unparsable {name} ({mc_version}): {e}", file=sys.stderr)
            continue
        mod_id = data.get("update", {}).get("modrinth", {}).get("mod-id")
        if mod_id:
            ids.add(mod_id)
    return ids


def pack_ids_by_version(client, pack, mc_versions):
    result = {}
    for v in mc_versions:
        filenames = list_pw_toml_filenames(client, pack["contents_url"], v)
        result[v] = None if filenames is None else collect_project_ids(client, pack["raw_url"], v, filenames)
    return result


def newest_available_version(mc_versions_sorted_desc, ids_by_version):
    for v in mc_versions_sorted_desc:
        if ids_by_version.get(v) is not None:
            return v
    return None


def batch_fetch_projects(client, project_ids, batch_size=100):
    ids = sorted(project_ids)
    projects = {}
    for i in range(0, len(ids), batch_size):
        chunk = ids[i:i + batch_size]
        url = f"{MODRINTH_API}/projects?ids={urllib.parse.quote(json.dumps(chunk), safe='')}"
        for project in client.modrinth_json(url):
            projects[project["id"]] = project
    return projects


def slugs_for_version(ids_by_version, version, projects_by_id):
    if version is None:
        return set()
    return {projects_by_id[i]["slug"] for i in ids_by_version[version] if i in projects_by_id}


def mods_with_upstream(rule_mods, fo_slugs, additive_slugs):
    merged = []
    for mod in rule_mods:
        m = dict(mod)
        m["upstream"] = {
            "fabulouslyOptimized": mod["slug"] in fo_slugs,
            "additive": mod["slug"] in additive_slugs,
        }
        merged.append(m)
    return merged


def modrinth_version_list_url(project_id, mc_version):
    loaders = urllib.parse.quote(json.dumps(["fabric"]), safe="")
    game_versions = urllib.parse.quote(json.dumps([mc_version]), safe="")
    return f"{MODRINTH_API}/project/{project_id}/version?loaders={loaders}&game_versions={game_versions}"


def is_version_available(client, project_id, mc_version):
    versions = client.modrinth_json(modrinth_version_list_url(project_id, mc_version))
    return bool(versions)


def compute_availability(client, rule_mods, projects_by_id, mc_versions):
    """Availability must be version-level: a project's `game_versions`/`loaders`
    fields are unions across every version it has ever published, so a mod with
    Fabric only for one target and NeoForge for another would otherwise look
    available for both. The project-level fields are still used as a cheap
    pre-filter -- they can only produce false positives, never false negatives,
    so when they already rule a (mod, version) pair out there's no need to spend
    a request confirming it."""
    availability = {}
    for v in mc_versions:
        slugs = []
        for m in rule_mods:
            project = projects_by_id.get(m["projectId"])
            if not project:
                continue
            if v not in project.get("game_versions", []) or "fabric" not in project.get("loaders", []):
                continue
            if is_version_available(client, m["projectId"], v):
                slugs.append(m["slug"])
        availability[v] = sorted(slugs)
    return availability


def top_level_upstream(fo_version, fo_slugs, additive_version, additive_slugs):
    return {
        "fabulouslyOptimized": {"mcVersion": fo_version, "slugs": sorted(fo_slugs)},
        "additive": {"mcVersion": additive_version, "slugs": sorted(additive_slugs)},
    }


def build_review(rule_mods, mc_versions, newest_version, fo_slugs, additive_slugs,
                  projects_by_id, availability, old_mods_by_slug, review_ignore_slugs=frozenset(), projection_notes=()):
    known_slugs = {m["slug"] for m in rule_mods}
    slug_to_project = {p["slug"]: p for p in projects_by_id.values() if "slug" in p}

    new_upstream = []
    for slug in sorted(fo_slugs | additive_slugs):
        if slug in known_slugs or slug in review_ignore_slugs:
            continue
        project = slug_to_project.get(slug)
        packs = []
        if slug in fo_slugs:
            packs.append("Fabulously Optimized")
        if slug in additive_slugs:
            packs.append("Additive")
        new_upstream.append({
            "slug": slug,
            "title": project.get("title", slug) if project else slug,
            "packs": packs,
            "optimization": bool(project and "optimization" in project.get("categories", [])),
        })

    status_issues = []
    for m in rule_mods:
        project = projects_by_id.get(m["projectId"])
        if project is None:
            status_issues.append((m["slug"], m.get("title", m["slug"]), "not found on Modrinth (project id returned nothing)"))
            continue
        status = project.get("status")
        if status not in OK_PROJECT_STATUSES:
            status_issues.append((m["slug"], project.get("title", m["slug"]), f"status: {status}"))

    removed_issues = []
    if old_mods_by_slug is not None:
        for m in rule_mods:
            old_upstream = old_mods_by_slug.get(m["slug"])
            if not old_upstream:
                continue
            was_upstream = old_upstream.get("fabulouslyOptimized") or old_upstream.get("additive")
            now_upstream = m["slug"] in fo_slugs or m["slug"] in additive_slugs
            if was_upstream and not now_upstream:
                removed_issues.append((m["slug"], m.get("title", m["slug"]), "removed from both Fabulously Optimized and Additive"))

    newest_slugs = set(availability.get(newest_version, [])) if newest_version else set()
    missing_fabric = [
        (m["slug"], m.get("title", m["slug"]))
        for m in rule_mods
        if m["slug"] not in newest_slugs
    ]

    markdown = render_review_markdown(
        mc_versions, newest_version, new_upstream, status_issues, removed_issues,
        missing_fabric, old_mods_by_slug is None, projection_notes=projection_notes,
    )
    counts = {
        "new_upstream": len(new_upstream),
        "status_or_removed": len(status_issues) + len(removed_issues),
        "missing_fabric": len(missing_fabric),
        "v1_projection": len(projection_notes),
    }
    return markdown, counts


def sanitize_cell(value):
    """Modrinth titles/slugs (and the status string they carry) are untrusted
    text that ends up in a markdown table inside a PR body. Escape pipes so
    they can't break the table, collapse newlines so they can't inject extra
    rows, and defang @mentions so a crafted title can't ping someone."""
    text = str(value).replace("\r\n", " ").replace("\r", " ").replace("\n", " ")
    text = text.replace("|", "\\|")
    text = MENTION_RE.sub("@" + chr(0x200D), text)
    return text


def render_review_markdown(mc_versions, newest_version, new_upstream, status_issues, removed_issues, missing_fabric, no_history,
                           projection_notes=()):
    lines = ["# RigTune rules update review", ""]
    lines.append(f"Target MC versions: {', '.join(mc_versions)}. Newest: {newest_version or 'unknown'}.")
    lines.append("")
    lines.append("## Summary")
    lines.append(f"- New upstream mods to triage: {len(new_upstream)}")
    lines.append(f"- Rule mods with a status or removal concern: {len(status_issues) + len(removed_issues)}")
    lines.append(f"- Rule mods missing a Fabric build for {newest_version or 'the newest target version'}: {len(missing_fabric)}")
    lines.append(f"- Rules changed or omitted in rules-v1.json: {len(projection_notes)}")
    lines.append("")

    lines.append("## (a) Upstream mods not yet tracked in knowledge.json")
    if new_upstream:
        lines.append("| slug | title | pack(s) | optimization category |")
        lines.append("|---|---|---|---|")
        for item in new_upstream:
            lines.append(f"| {sanitize_cell(item['slug'])} | {sanitize_cell(item['title'])} | {', '.join(item['packs'])} | {'yes' if item['optimization'] else 'no'} |")
    else:
        lines.append("None found.")
    lines.append("")

    lines.append("## (b) Rule mods needing a status check")
    if no_history:
        lines.append('_No previous rules-v1.json was found, so "removed from both packs" could not be checked this run._')
        lines.append("")
    combined = [(s, t, r) for s, t, r in status_issues] + [(s, t, r) for s, t, r in removed_issues]
    if combined:
        lines.append("| slug | title | reason |")
        lines.append("|---|---|---|")
        for slug, title, reason in combined:
            lines.append(f"| {sanitize_cell(slug)} | {sanitize_cell(title)} | {sanitize_cell(reason)} |")
    else:
        lines.append("None found.")
    lines.append("")

    lines.append(f"## (c) Rule mods with no Fabric release for {newest_version or 'the newest target version'}")
    if missing_fabric:
        lines.append("| slug | title |")
        lines.append("|---|---|")
        for slug, title in missing_fabric:
            lines.append(f"| {sanitize_cell(slug)} | {sanitize_cell(title)} |")
    else:
        lines.append("None found.")
    lines.append("")

    lines.append("## (d) Omitted from rules-v1.json")
    lines.append("0.1.x clients read rules-v1.json, the v1 projection of these rules. Check that nothing below makes 0.1.x "
                 "less safe, in particular that an omitted setting entry doesn't change which entry wins for a key.")
    lines.append("")
    if projection_notes:
        lines.append("| rule | change |")
        lines.append("|---|---|")
        for label, note in projection_notes:
            lines.append(f"| {sanitize_cell(label)} | {sanitize_cell(note)} |")
    else:
        lines.append("None.")
    lines.append("")

    return "\n".join(lines)


def load_knowledge(path):
    if not path.exists():
        raise KnowledgeError(f"knowledge file not found: {path}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as e:
        raise KnowledgeError(f"knowledge file at {path} is not valid JSON: {e}")
    if not isinstance(data, dict):
        raise KnowledgeError(f"knowledge file at {path} must contain a JSON object")
    if not isinstance(data.get("mods"), list):
        raise KnowledgeError(f"knowledge file at {path} must have a 'mods' array")
    for mod in data["mods"]:
        if "slug" not in mod or "projectId" not in mod:
            raise KnowledgeError(f"knowledge file at {path}: every mod needs 'slug' and 'projectId'")
    if "reviewIgnore" in data:
        if not isinstance(data["reviewIgnore"], list):
            raise KnowledgeError(f"knowledge file at {path}: 'reviewIgnore' must be an array")
        for entry in data["reviewIgnore"]:
            if "slug" not in entry or "reason" not in entry:
                raise KnowledgeError(f"knowledge file at {path}: every reviewIgnore entry needs 'slug' and 'reason'")
    validate_knowledge(data)
    return data


def assemble_content(knowledge, mods, availability, upstream):
    """The full rules content: schemaVersion 2, with each rule's "v1" override still inside (see v2_content and
    v1_projection for the two outputs)."""
    content = {"schemaVersion": 2}
    if "minModVersion" in knowledge:
        content["minModVersion"] = knowledge["minModVersion"]
    content["gpuTiers"] = knowledge.get("gpuTiers", [])
    content["gpuVendorFallback"] = knowledge.get("gpuVendorFallback", {})
    content["cpuTiers"] = knowledge.get("cpuTiers", [])
    content["heapTiers"] = knowledge.get("heapTiers", [])
    content["mods"] = mods
    content["obsolete"] = knowledge.get("obsolete", [])
    content["settings"] = knowledge.get("settings", [])
    content["advice"] = knowledge.get("advice", [])
    for section in V2_ONLY_SECTIONS:
        if section in knowledge:
            content[section] = knowledge[section]
    content["availability"] = availability
    content["upstream"] = upstream
    return content


def v2_content(content):
    """rules-v2.json: the content without the source-only "v1" overrides."""
    out = copy.deepcopy(content)
    for kind in RULE_KINDS:
        out[kind] = [{k: v for k, v in rule.items() if k != "v1"} for rule in out.get(kind, [])]
    for kind, source_only in SOURCE_ONLY_TIER_FIELDS.items():
        if kind in out:
            out[kind] = [{k: v for k, v in row.items() if k not in source_only} for row in out[kind]]
    return out


def v1_projection(content):
    """rules-v1.json: what 0.1.x understands, never less safe. Returns (document, [(rule label, note)])."""
    out = {"schemaVersion": 1}
    notes = []
    omitted_mods = {}
    for key, value in content.items():
        if key == "schemaVersion" or key in V2_ONLY_SECTIONS:
            continue
        if key in RULE_KINDS:
            projected = []
            for i, rule in enumerate(value):
                rule_v1, rule_notes = project_rule(key, rule, i)
                notes += [(rule_label(key, rule, i), note) for note in rule_notes]
                if rule_v1 is not None:
                    projected.append(rule_v1)
                elif key == "mods":
                    omitted_mods[rule["slug"]] = list(rule.get("modIds", []))
            out[key] = projected
        elif key in SOURCE_ONLY_TIER_FIELDS:
            rows = []
            for i, row in enumerate(value):
                if row.get("v1") is False:
                    notes.append((f"{key}[{i}] {row.get('pattern')}", 'omitted ("v1": false)'))
                else:
                    rows.append({k: copy.deepcopy(v) for k, v in row.items() if k not in SOURCE_ONLY_TIER_FIELDS[key]})
            out[key] = rows
        else:
            out[key] = copy.deepcopy(value)
    if omitted_mods:
        notes += rewrite_conflict_references(out.get("mods", []), omitted_mods)
    return out, notes


def rewrite_conflict_references(mods, omitted_mods):
    """0.1.x resolves a conflictsWith slug only through a rule it has. When a rule is left out of rules-v1.json, the
    other rules' references to its slug would stop matching the installed mod (slugs often differ from mod ids:
    moonrise-opt vs moonrise), so 0.1.x could offer a mod that conflicts with one already installed. Such references
    become the omitted rule's mod ids, which 0.1.x matches directly."""
    notes = []
    for mod in mods:
        refs = mod.get("conflictsWith")
        if not refs or not any(ref in omitted_mods for ref in refs):
            continue
        rewritten = []
        for ref in refs:
            for replacement in omitted_mods.get(ref, [ref]):
                if replacement not in rewritten:
                    rewritten.append(replacement)
            if ref in omitted_mods:
                notes.append((f"mods[{mod.get('slug')}]", f"conflictsWith: {ref} (left out of rules-v1.json) -> its mod ids "
                              f"{', '.join(omitted_mods[ref])}"))
        mod["conflictsWith"] = rewritten
    return notes


def deep_equal(a, b):
    return json.dumps(a, sort_keys=True) == json.dumps(b, sort_keys=True)


def strip_meta(doc):
    return {k: v for k, v in doc.items() if k not in ("revision", "generatedAt")}


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def finalize_documents(pairs):
    """pairs: [(content, old_doc)]. If any content differs from its old document (ignoring revision and
    generatedAt), every document gets revision max(old revisions) + 1 and one generatedAt; otherwise None."""
    changed = any(old is None or not deep_equal(strip_meta(old), content) for content, old in pairs)
    if not changed:
        return None
    new_revision = max((old.get("revision", 0) if old else 0) for _, old in pairs) + 1
    if new_revision >= MAX_SAFE_REVISION:
        raise UpdateRulesError(
            f"revision {new_revision} would be at or above {MAX_SAFE_REVISION} (2**31-2); "
            "refusing to write rules the client can't parse"
        )
    stamp = now_iso()
    finals = []
    for content, _ in pairs:
        final = {"schemaVersion": content["schemaVersion"], "revision": new_revision, "generatedAt": stamp}
        for key, value in content.items():
            if key != "schemaVersion":
                final[key] = value
        finals.append(final)
    return finals


def finalize_document(content, old_doc):
    finals = finalize_documents([(content, old_doc)])
    return None if finals is None else finals[0]


def load_json_if_exists(path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def write_json(path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes((json.dumps(doc, indent=2, ensure_ascii=False) + "\n").encode("utf-8"))


def write_text(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not text.endswith("\n"):
        text += "\n"
    path.write_bytes(text.encode("utf-8"))


def run_pipeline(knowledge, client, mc_versions_override, old_doc, nodes=None):
    mc_versions = resolve_target_versions(client, mc_versions_override, nodes)
    newest_version = mc_versions[0]

    ids_by_version = {name: pack_ids_by_version(client, pack, mc_versions) for name, pack in PACKS.items()}
    newest_by_pack = {
        name: newest_available_version(mc_versions, ids_by_version[name])
        for name in PACKS
    }

    rule_mods = knowledge.get("mods", [])
    all_ids = {m["projectId"] for m in rule_mods}
    for name in PACKS:
        version = newest_by_pack[name]
        if version is not None:
            all_ids |= ids_by_version[name][version]
    projects_by_id = batch_fetch_projects(client, all_ids)

    fo_slugs = slugs_for_version(ids_by_version["fabulouslyOptimized"], newest_by_pack["fabulouslyOptimized"], projects_by_id)
    additive_slugs = slugs_for_version(ids_by_version["additive"], newest_by_pack["additive"], projects_by_id)

    mods = mods_with_upstream(rule_mods, fo_slugs, additive_slugs)
    availability = compute_availability(client, rule_mods, projects_by_id, mc_versions)
    upstream = top_level_upstream(newest_by_pack["fabulouslyOptimized"], fo_slugs, newest_by_pack["additive"], additive_slugs)

    content = assemble_content(knowledge, mods, availability, upstream)

    old_mods_by_slug = None
    if old_doc is not None:
        old_mods_by_slug = {m["slug"]: m.get("upstream", {}) for m in old_doc.get("mods", [])}
    review_ignore_slugs = {entry["slug"] for entry in knowledge.get("reviewIgnore", [])}
    _, projection_notes = v1_projection(content)
    review_md, review_counts = build_review(
        rule_mods, mc_versions, newest_version, fo_slugs, additive_slugs,
        projects_by_id, availability, old_mods_by_slug, review_ignore_slugs, projection_notes,
    )

    return content, review_md, review_counts, mc_versions, newest_by_pack, fo_slugs, additive_slugs


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--knowledge", help="Path to knowledge.json (default: rules/source/knowledge.json)")
    parser.add_argument("--out-dir", help="Root directory under which rules/ and src/main/resources/rigtune/ are written (default: repo root)")
    parser.add_argument("--mc-versions", help="Comma-separated MC versions, overriding the Stonecutter nodes and their hotfix releases")
    parser.add_argument("--dry-run", action="store_true", help="Compute everything and print a summary, without writing files")
    parser.add_argument("--offline-fixtures", help="Directory of canned HTTP responses keyed by sha256(url).json, for offline runs")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    repo_root = Path(__file__).resolve().parent.parent
    knowledge_path = Path(args.knowledge) if args.knowledge else repo_root / "rules" / "source" / "knowledge.json"
    out_root = Path(args.out_dir) if args.out_dir else repo_root

    try:
        knowledge = load_knowledge(knowledge_path)
    except KnowledgeError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    nodes = None
    if not args.mc_versions:
        try:
            nodes = stonecutter_nodes(repo_root / "settings.gradle")
        except UpdateRulesError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1

    v2_path = out_root / "rules" / "rules-v2.json"
    bundled_v2_path = out_root / "src" / "main" / "resources" / "rigtune" / "rules-v2.json"
    v1_path = out_root / "rules" / "rules-v1.json"
    review_path = out_root / "rules" / "REVIEW.md"
    old_v2 = load_json_if_exists(v2_path)
    old_v1 = load_json_if_exists(v1_path)
    old_doc = old_v2 if old_v2 is not None else old_v1

    opener = fixture_opener(args.offline_fixtures) if args.offline_fixtures else default_opener
    client = Client(opener=opener, sleeper=time.sleep, github_token=os.environ.get("GITHUB_TOKEN"))

    try:
        content, review_md, review_counts, mc_versions, newest_by_pack, fo_slugs, additive_slugs = run_pipeline(
            knowledge, client, args.mc_versions, old_doc, nodes=nodes,
        )
        v1_content, _ = v1_projection(content)
        finals = finalize_documents([(v2_content(content), old_v2), (v1_content, old_v1)])
    except UpdateRulesError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    print(f"target MC versions: {', '.join(mc_versions)}")
    print(f"Fabulously Optimized: newest available = {newest_by_pack['fabulouslyOptimized']}, {len(fo_slugs)} mods")
    print(f"Additive: newest available = {newest_by_pack['additive']}, {len(additive_slugs)} mods")
    print(f"REVIEW.md: {review_counts}")
    if finals is None:
        print(f"no content change; keeping revision {old_doc.get('revision') if old_doc else 'n/a'}")
    else:
        print(f"revision {old_doc.get('revision', 0) if old_doc else 0} -> {finals[0]['revision']}")

    if args.dry_run:
        print("dry run: no files written")
        return 0

    if finals is not None:
        final_v2, final_v1 = finals
        write_json(v2_path, final_v2)
        write_json(bundled_v2_path, final_v2)
        write_json(v1_path, final_v1)
    write_text(review_path, review_md)
    return 0


if __name__ == "__main__":
    sys.exit(main())
