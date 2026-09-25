package io.github.chaotix345.rigtune.core.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Display text built in core (docs/v0.3/SPEC.md item 9): a translation key with its English template and arguments, a
// literal (data: rule text, mod names, versions, file names), or parts joined by a separator. The client shows a key's
// translation when the language has one, else the English (client/ui/Texts); english() is what the String fields next
// to a Text, the share report and the logs keep showing.
public sealed interface Text {
	// Data shown as it is: never formatted or translated.
	record Literal(String value) implements Text {
		public Literal {
			value = value == null ? "" : value;
		}
	}

	// fallback: the English template (Minecraft's %s, %n$s and %% only). args: String, Number, Boolean or Text.
	record Translatable(String key, String fallback, List<Object> args) implements Text {
		public Translatable {
			args = Collections.unmodifiableList(new ArrayList<>(args));
		}
	}

	// The parts with the separator between them. join() and sentences() leave out blank parts; the constructor keeps them.
	record Joined(String separator, List<Text> parts) implements Text {
		public Joined {
			parts = List.copyOf(parts);
		}
	}

	static Text literal(String value) {
		return new Literal(value);
	}

	// A null argument reads "null", as Minecraft and string concatenation show it.
	static Text of(String key, String fallback, Object... args) {
		List<Object> list = new ArrayList<>(args.length);
		for (Object arg : args) {
			list.add(arg == null ? "null" : arg);
		}
		return new Translatable(key, fallback, list);
	}

	static Text join(String separator, List<Text> parts) {
		return new Joined(separator, parts.stream().filter(part -> part != null && !part.isBlank()).toList());
	}

	static Text join(String separator, Text... parts) {
		return join(separator, Arrays.asList(parts));
	}

	// Sentences of one paragraph.
	static Text sentences(Text... parts) {
		return join(" ", parts);
	}

	default String english() {
		return render(key -> null);
	}

	// templates: a key's template in the language shown, or null to use the English fallback.
	default String render(Function<String, String> templates) {
		return switch (this) {
			case Literal literal -> literal.value();
			case Translatable t -> {
				String template = templates.apply(t.key());
				yield Format.format(template != null ? template : t.fallback(), t.args(), templates);
			}
			case Joined joined -> String.join(joined.separator(), joined.parts().stream().map(part -> part.render(templates)).toList());
		};
	}

	default boolean isBlank() {
		return switch (this) {
			case Literal literal -> literal.value().isBlank();
			case Translatable t -> false;
			case Joined joined -> joined.parts().isEmpty();
		};
	}

	// As net.minecraft.network.chat.contents.TranslatableContents.decomposeTemplate (26.2 and 26.3): a template it can't
	// format is shown as it is.
	final class Format {
		private static final Pattern PATTERN = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

		private Format() {
		}

		static String format(String template, List<Object> args, Function<String, String> templates) {
			StringBuilder out = new StringBuilder();
			Matcher matcher = PATTERN.matcher(template);
			int next = 0;
			int at = 0;
			while (matcher.find(at)) {
				String plain = template.substring(at, matcher.start());
				if (plain.indexOf('%') != -1) {
					return template;
				}
				out.append(plain);
				String type = matcher.group(2);
				if ("%".equals(type) && "%%".equals(matcher.group())) {
					out.append('%');
				} else if ("s".equals(type)) {
					int index;
					try {
						index = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) - 1 : next++;
					} catch (NumberFormatException e) {
						return template;
					}
					if (index < 0 || index >= args.size()) {
						return template;
					}
					Object arg = args.get(index);
					out.append(arg instanceof Text text ? text.render(templates) : String.valueOf(arg));
				} else {
					return template;
				}
				at = matcher.end();
			}
			String rest = template.substring(at);
			if (rest.indexOf('%') != -1) {
				return template;
			}
			return out.append(rest).toString();
		}
	}
}
