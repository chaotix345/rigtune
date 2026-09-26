package io.github.chaotix345.rigtune.core.modrinth;

import io.github.chaotix345.rigtune.core.model.Text;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

// docs/v0.4/SPEC.md 2o, H2: what the loaded mods' fabric.mod.json says about other mods' versions, in the two kinds Fabric
// refuses to start over (a `depends` range the present version doesn't match, a `breaks` range it does). Iris 1.11.4
// declares sodium: ["0.9.x"], so a Sodium 0.10 update before Iris supports it stops the game from starting. The client
// matches versions with Fabric's own predicates (client/probe/FabricPins); this is the decision, free of Fabric.
public final class VersionPins {
	public enum Kind {
		DEPENDS, BREAKS
	}

	// by/byName: the declaring mod (byName null = its id). top: the id of the top-level jar it's in (by, unless it's nested).
	// target/targetName: the mod it's about (targetName null = its id). declared: the ranges as the mod wrote them, read
	// only for a refusal. satisfiedBy: whether that version of the target is fine for this declaration (a depends range
	// matches it, a breaks range doesn't; a version Fabric can't parse isn't fine).
	public record Pin(String by, String byName, String top, String target, String targetName, Kind kind, Supplier<String> declared,
			Predicate<String> satisfiedBy) {
	}

	public static final VersionPins NONE = new VersionPins(List.of());

	private final List<Pin> pins;

	public VersionPins(List<Pin> pins) {
		this.pins = pins.stream().sorted(Comparator.comparing(Pin::by).thenComparing(Pin::target)).toList();
	}

	// The refusal when `version` of mod `modId` would take the place of what's loaded now (an update, or an addition next
	// to a nested copy) while an installed mod declares it can't work with it, naming that mod; null when every declaration
	// is fine, or the version isn't known. The declarations of the jar being replaced (top = modId: the mod itself and the
	// mods nested in it) go with it.
	public Text problem(String modId, String version) {
		if (modId == null || version == null) {
			return null;
		}
		for (Pin pin : pins) {
			if (!modId.equals(pin.target()) || modId.equals(pin.top()) || pin.satisfiedBy().test(version)) {
				continue;
			}
			String by = pin.byName() != null ? pin.byName() : pin.by();
			String target = pin.targetName() != null ? pin.targetName() : modId;
			return pin.kind() == Kind.DEPENDS
					? Text.of("rigtune.download.pinned", "%s, which is installed, needs %s %s, not %s", by, target, String.valueOf(pin.declared().get()), version)
					: Text.of("rigtune.download.pinned_breaks", "%s, which is installed, doesn't work with %s %s", by, target, version);
		}
		return null;
	}
}
