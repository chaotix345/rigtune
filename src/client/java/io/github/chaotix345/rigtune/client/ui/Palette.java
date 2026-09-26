package io.github.chaotix345.rigtune.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

// docs/v0.4/SPEC.md 11 (AC11.2): RigTune's own colours, switched to a high-contrast set while the game's High Contrast
// or High Contrast Block Outline option is on. Screens keep their colour constants and draw them through of(); with
// both options off every colour is returned as it is, so nothing looks different. The high-contrast set keeps each
// colour's meaning (hue) and raises its contrast against the dark menu background: greys and accents lighter, row
// highlights and the header backing stronger.
public final class Palette {
	private static final int FOCUS = 0xFFFFFFFF;
	private static final int FOCUS_HIGH_CONTRAST = 0xFFFFFF00;

	private Palette() {
	}

	public static int of(int argb) {
		return of(argb, enabled());
	}

	static int of(int argb, boolean highContrast) {
		if (!highContrast) {
			return argb;
		}
		return switch (argb) {
			// greys (labels, reasons, notes, low impact)
			case 0xFFA8A8A8 -> 0xFFE6E6E6;
			case 0xFFB8B8B8 -> 0xFFEBEBEB;
			case 0xFFC8C8C8 -> 0xFFF0F0F0;
			case 0xFFDDDDDD, 0xFFE0E0E0 -> 0xFFFFFFFF;
			case 0xFF808080, 0xFF8A8A8A -> 0xFFDCDCDC;
			case 0xFF9AA0A6 -> 0xFFE0E4E8;
			// ambers and yellows (headings, staged, warnings, notices, impact)
			case 0xFFFFD166, 0xFFE0C060 -> 0xFFFFE680;
			case 0xFFFFE08A -> 0xFFFFF0B0;
			case 0xFFFFB347 -> 0xFFFFCC80;
			// greens (applied, passed, active, launcher steps)
			case 0xFF7FE07F, 0xFF7BE07B -> 0xFFA8FFA8;
			case 0xFFA8E0B0 -> 0xFFC8FFD0;
			case 0xFFD2F7D2 -> 0xFFEFFFEF;
			// reds (failures, warnings)
			case 0xFFFF7A6B, 0xFFFF6E5E, 0xFFFF6B6B -> 0xFFFFA89E;
			case 0xFFE8B0A8 -> 0xFFFFD0C8;
			// blues (advice, files, reverted, the chart's average)
			case 0xFF7EC8FF -> 0xFFB0E0FF;
			case 0xFF5B8DD6 -> 0xFF8CB8FF;
			case 0xFFB4CDF2 -> 0xFFD8E6FF;
			// translucent fills: row highlights, divider lines and bar backgrounds, the header and table backings
			case 0x18FFFFFF -> 0x48FFFFFF;
			case 0x30FFFFFF -> 0x60FFFFFF;
			case 0x40FFFFFF -> 0x80FFFFFF;
			case 0x70000000, 0x60000000 -> 0xD0000000;
			case 0x30FFD166 -> 0x60FFD166;
			case 0x3000FF00 -> 0x6000FF00;
			default -> argb;
		};
	}

	// The frame around a keyboard-focused list row (RowFocus.outline).
	public static int focus() {
		return enabled() ? FOCUS_HIGH_CONTRAST : FOCUS;
	}

	public static boolean enabled() {
		Minecraft minecraft = Minecraft.getInstance();
		Options options = minecraft == null ? null : minecraft.options;
		return options != null && (options.highContrast().get() || options.highContrastBlockOutline().get());
	}
}
