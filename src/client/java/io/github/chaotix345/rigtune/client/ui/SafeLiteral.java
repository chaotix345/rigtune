package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.model.SafeText;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

// Outside text (rules feed titles and texts, setting labels, mod, file, GPU, CPU and JVM names) as a literal Component
// with its formatting codes and bidi/control characters removed (review-8 SE-2; core/model/SafeText). Every screen shows
// such text through this or Texts.component, never through a bare Component.literal.
public final class SafeLiteral {
	private SafeLiteral() {
	}

	public static MutableComponent of(@Nullable String text) {
		return Component.literal(SafeText.clean(text));
	}
}
