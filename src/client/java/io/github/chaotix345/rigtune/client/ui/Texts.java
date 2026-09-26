package io.github.chaotix345.rigtune.client.ui;

import io.github.chaotix345.rigtune.core.model.SafeText;
import io.github.chaotix345.rigtune.core.model.Text;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

// Shows a core Text (docs/v0.3/SPEC.md item 9): the key's translation where the language has one, else the English
// template it carries (Component.translatableWithFallback, 26.2 and 26.3), formatted as core's english() formats it.
// Literals and string arguments are data (rule text, names) and are drawn inert (review-8 SE-2: core/model/SafeText).
public final class Texts {
	private Texts() {
	}

	public static MutableComponent component(Text text) {
		return switch (text) {
			case Text.Literal literal -> SafeLiteral.of(literal.value());
			case Text.Translatable t -> Component.translatableWithFallback(t.key(), t.fallback(),
					t.args().stream().map(arg -> arg instanceof Text inner ? component(inner) : arg instanceof String s ? SafeText.clean(s) : arg).toArray());
			case Text.Joined joined -> {
				MutableComponent out = Component.empty();
				for (int i = 0; i < joined.parts().size(); i++) {
					if (i > 0) {
						out.append(joined.separator());
					}
					out.append(component(joined.parts().get(i)));
				}
				yield out;
			}
		};
	}
}
