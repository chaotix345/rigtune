package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.apply.ModJars;
import io.github.chaotix345.rigtune.core.model.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

// docs/v0.4/SPEC.md 2o, H2: whether the mods folder a download batch leaves behind would start, as far as fabric.mod.json
// version ranges go: Fabric refuses to start over a `depends` range the present version doesn't match, or a `breaks` range
// it does. Iris 1.11.4 declares sodium: ["0.9.x"], so a Sodium 0.10 update before Iris supports it stops the game from
// starting; Nvidium 0.4.4 declares sodium "0.9.2", so installing it next to a Sodium 0.9.3 update does too. Both
// directions are checked over the whole batch, with what earlier Applies staged: the loaded mods' declarations on the
// batch's jars, and the jars' own declarations on what will be present. The client matches versions with Fabric's own
// predicates (client/probe/FabricPins); this is the decision, free of Fabric.
public final class VersionPins {
	public enum Kind {
		DEPENDS, BREAKS
	}

	// A loaded mod's declaration. by/byName: the declaring mod (byName null = its id). top: the id of the top-level jar it's
	// in (by, unless it's nested). target/targetName: the mod it's about (targetName null = its id). declared: the ranges as
	// the mod wrote them, read only for a refusal. satisfiedBy: whether that version of the target is fine for this
	// declaration (a depends range matches it, a breaks range doesn't; a version Fabric can't parse isn't fine).
	public record Pin(String by, String byName, String top, String target, String targetName, Kind kind, Supplier<String> declared,
			Predicate<String> satisfiedBy) {
	}

	// A loaded mod id, or an id a loaded mod provides, with its name, version and the id of the top-level jar it's in.
	public record Loaded(String id, String name, String version, String top) {
	}

	// Whether a version matches any of a fabric.mod.json entry's ranges, as Fabric reads them; false when it can't say.
	public interface Matcher {
		boolean matches(List<String> ranges, String version);
	}

	// A jar that would be put in: its mod id, name (null = its id), version (null = unknown: nothing is judged against it),
	// the loaded top-level mod it takes the place of (an update's; null for an addition), the ids it provides or nests
	// (JarInfo), and its own fabric.mod.json depends and breaks (mod id -> ranges).
	public record Jar(String modId, String name, String version, String replaces, Set<String> provides, Map<String, List<String>> depends,
			Map<String, List<String>> breaks) {
		public Jar {
			provides = provides == null ? Set.of() : provides;
			depends = depends == null ? Map.of() : depends;
			breaks = breaks == null ? Map.of() : breaks;
		}

		public Jar(String modId, String name, String version, String replaces, Map<String, List<String>> depends, Map<String, List<String>> breaks) {
			this(modId, name, version, replaces, Set.of(), depends, breaks);
		}
	}

	// refused: index in the batch's jars -> why it can't go in (a pair of the batch that can't go in together: both, each
	// naming the other). reliances: {a, b}: batch jar a is only fine because batch jar b goes in too (one all-or-nothing
	// group for them).
	public record Outcome(Map<Integer, Text> refused, List<int[]> reliances) {
	}

	private static final String SEPARATOR = " || ";

	public static final VersionPins NONE = new VersionPins(List.of());

	private final List<Pin> pins;
	private final Map<String, Loaded> loaded = new HashMap<>();
	private final Matcher matcher;

	public VersionPins(List<Pin> pins) {
		this(pins, List.of(), (ranges, version) -> true);
	}

	public VersionPins(List<Pin> pins, List<Loaded> loaded, Matcher matcher) {
		this.pins = pins.stream().sorted(Comparator.comparing(Pin::by).thenComparing(Pin::target)).toList();
		loaded.forEach(mod -> this.loaded.putIfAbsent(mod.id(), mod));
		this.matcher = matcher;
	}

	public boolean isEmpty() {
		return pins.isEmpty() && loaded.isEmpty();
	}

	// One jar: the refusal when `version` of mod `modId` took the place of what's loaded now, naming the installed mod whose
	// declaration it breaks; null when every declaration is fine, or the version isn't known.
	public Text problem(String modId, String version) {
		return check(List.of(new Jar(modId, null, version, modId, Map.of(), Map.of()))).refused().get(0);
	}

	public Outcome check(List<Jar> jars) {
		return check(jars, List.of());
	}

	// staged: the jars earlier Applies staged (pending.json), minus any a jar of this batch replaces. They count as present
	// and never get refused: a conflict between one of them and the loaded mods isn't this batch's doing.
	public Outcome check(List<Jar> jars, List<Jar> staged) {
		int n = jars.size();
		List<Jar> all = new ArrayList<>(jars);
		all.addAll(staged);
		Map<String, Integer> jarOf = new HashMap<>();
		Set<String> replaced = new HashSet<>();
		for (int i = 0; i < all.size(); i++) {
			jarOf.putIfAbsent(all.get(i).modId(), i);
			if (all.get(i).replaces() != null) {
				replaced.add(all.get(i).replaces());
			}
		}
		Map<Integer, Text> refused = new LinkedHashMap<>();
		List<int[]> reliances = new ArrayList<>();
		// The loaded mods' declarations on the batch's jars. A declaration of a jar that's replaced (the mod itself, or a
		// mod nested in it) goes with it: the replacement's own declarations count instead, and a batch jar that's only
		// fine because of a replacement in the batch relies on it.
		for (Pin pin : pins) {
			Integer t = jarOf.get(pin.target());
			if (t == null || t >= n || all.get(t).version() == null) {
				continue;
			}
			Jar target = all.get(t);
			boolean fine = pin.satisfiedBy().test(target.version());
			if (replaced.contains(pin.top())) {
				Integer by = replacer(all, pin.top());
				if (!fine && by != null && by < n && !by.equals(t)) {
					reliances.add(new int[]{t, by});
				}
			} else if (!fine) {
				String by = pin.byName() != null ? pin.byName() : pin.by();
				String name = target.name() != null ? target.name() : pin.targetName() != null ? pin.targetName() : shown(target.modId());
				refused.putIfAbsent(t, pin.kind() == Kind.DEPENDS
						? Text.of("rigtune.download.pinned", "%s, which is installed, needs %s %s, not %s", by, name, shown(pin.declared().get()), shown(target.version()))
						: Text.of("rigtune.download.pinned_breaks", "%s, which is installed, doesn't work with %s %s", by, name, shown(target.version())));
			}
		}
		// The jars' own declarations, on what will be present.
		for (int i = 0; i < all.size(); i++) {
			Jar jar = all.get(i);
			for (Map.Entry<String, List<String>> e : jar.depends().entrySet()) {
				declared(all, n, jarOf, replaced, i, e.getKey(), e.getValue(), Kind.DEPENDS, refused, reliances);
			}
			for (Map.Entry<String, List<String>> e : jar.breaks().entrySet()) {
				declared(all, n, jarOf, replaced, i, e.getKey(), e.getValue(), Kind.BREAKS, refused, reliances);
			}
		}
		return new Outcome(refused, reliances);
	}

	private void declared(List<Jar> all, int n, Map<String, Integer> jarOf, Set<String> replaced, int i, String target, List<String> ranges, Kind kind,
			Map<Integer, Text> refused, List<int[]> reliances) {
		Jar jar = all.get(i);
		if (target.equals(jar.modId()) || ranges == null || ranges.isEmpty()) {
			return;
		}
		String shown = shown(String.join(SEPARATOR, ranges));
		// Another jar with that id goes in as a top-level jar: that version it is.
		Integer t = jarOf.get(target);
		if (t != null && t != i) {
			Jar other = all.get(t);
			if (other.version() == null || i >= n && t >= n) {
				return;
			}
			if (!fine(kind, ranges, other.version())) {
				String version = shown(other.version());
				if (i < n) {
					refused.putIfAbsent(i, t < n
							? kind == Kind.DEPENDS
							? Text.of("rigtune.download.needs_version_batch", "%s needs %s %s, not the %s that would be installed too", name(jar), name(other), shown, version)
							: Text.of("rigtune.download.breaks_version_batch", "%s doesn't work with %s %s, which would be installed too", name(jar), name(other), version)
							: kind == Kind.DEPENDS
							? Text.of("rigtune.download.needs_version_staged", "%s needs %s %s, not the %s that's waiting for a restart", name(jar), name(other), shown,
							version)
							: Text.of("rigtune.download.breaks_version_staged", "%s doesn't work with %s %s, which is waiting for a restart", name(jar), name(other),
							version));
				}
				if (t < n) {
					refused.putIfAbsent(t, i < n
							? kind == Kind.DEPENDS
							? Text.of("rigtune.download.pinned_batch", "%s, which would be installed too, needs %s %s, not %s", name(jar), name(other), shown, version)
							: Text.of("rigtune.download.pinned_breaks_batch", "%s, which would be installed too, doesn't work with %s %s", name(jar), name(other), version)
							: kind == Kind.DEPENDS
							? Text.of("rigtune.download.pinned_staged", "%s, which is waiting for a restart, needs %s %s, not %s", name(jar), name(other), shown, version)
							: Text.of("rigtune.download.pinned_breaks_staged", "%s, which is waiting for a restart, doesn't work with %s %s", name(jar), name(other),
							version));
				}
				return;
			}
			Loaded before = loaded.get(target);
			boolean fineWithout = before == null ? kind == Kind.BREAKS : fine(kind, ranges, before.version());
			if (!fineWithout && i < n && t < n) {
				reliances.add(new int[]{i, t});
			}
			return;
		}
		// A staged jar on a loaded mod isn't this batch's doing.
		if (i >= n) {
			return;
		}
		Loaded present = loaded.get(target);
		boolean gone = present == null || replaced.contains(present.top()) || present.version() == null;
		boolean topLevel = !gone && target.equals(present.top());
		// Not a top-level jar: a copy one of these jars provides or nests is a candidate too, and Fabric picks the one that
		// fits (a jar bundling the library it needs, next to an older copy nested in another mod), so it isn't judged here.
		if (!topLevel && all.stream().anyMatch(other -> other.provides().contains(target))) {
			return;
		}
		if (gone) {
			return;
		}
		if (!fine(kind, ranges, present.version())) {
			String name = present.name() != null ? present.name() : target;
			refused.putIfAbsent(i, kind == Kind.DEPENDS
					? Text.of("rigtune.download.needs_version", "%s needs %s %s, not the installed %s", name(jar), name, shown, shown(present.version()))
					: Text.of("rigtune.download.breaks_version", "%s doesn't work with the installed %s %s", name(jar), name, shown(present.version())));
		}
	}

	private boolean fine(Kind kind, List<String> ranges, String version) {
		boolean matches = matcher.matches(ranges, version);
		return kind == Kind.DEPENDS ? matches : !matches;
	}

	private static Integer replacer(List<Jar> jars, String top) {
		for (int i = 0; i < jars.size(); i++) {
			if (top.equals(jars.get(i).replaces())) {
				return i;
			}
		}
		return null;
	}

	// The jar's own name, else the loaded mod's with that id (an update's), else its id.
	private String name(Jar jar) {
		if (jar.name() != null) {
			return jar.name();
		}
		Loaded same = loaded.get(jar.modId());
		return same != null && same.name() != null ? same.name() : shown(jar.modId());
	}

	// Text from a jar's fabric.mod.json as the UI shows it (plan review P-L1: no formatting codes or control characters,
	// bounded); "?" when nothing is left.
	public static String shown(String raw) {
		String out = ModJars.sanitizeName(raw);
		return out != null ? out : "?";
	}
}
