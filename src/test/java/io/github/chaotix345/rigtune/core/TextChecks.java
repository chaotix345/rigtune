package io.github.chaotix345.rigtune.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.github.chaotix345.rigtune.core.model.Text;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// docs/v0.3/SPEC.md item 9 (AC9.3, G-M2): checks that core-built display text reaches the UI through en_us.json keys.
public final class TextChecks {
	private static final Pattern CONVERSION = Pattern.compile("%(?:\\d+\\$)?[A-Za-z%]");
	private static Map<String, String> english;

	private TextChecks() {
	}

	// en_us.json, key -> English.
	public static synchronized Map<String, String> english() {
		if (english == null) {
			try {
				Map<String, String> out = new LinkedHashMap<>();
				for (Map.Entry<String, JsonElement> e : JsonParser.parseString(Files.readString(RepoFiles.resolve("src/main/resources/assets/rigtune/lang/en_us.json")))
						.getAsJsonObject().entrySet()) {
					out.put(e.getKey(), e.getValue().getAsString());
				}
				english = Map.copyOf(out);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return english;
	}

	// The pseudo-locale: every en_us template uppercased, its %s/%n$s/%% kept.
	public static String upper(String template) {
		StringBuilder out = new StringBuilder();
		Matcher m = CONVERSION.matcher(template);
		int at = 0;
		while (m.find()) {
			out.append(template.substring(at, m.start()).toUpperCase(java.util.Locale.ROOT)).append(m.group());
			at = m.end();
		}
		return out.append(template.substring(at).toUpperCase(java.util.Locale.ROOT)).toString();
	}

	// Rendered through the uppercasing translator. A key missing from en_us.json fails.
	public static String pseudo(Text text) {
		return text.render(key -> {
			String template = english().get(key);
			if (template == null) {
				fail("key not in en_us.json: " + key);
			}
			return upper(template);
		});
	}

	// Every key is in en_us.json with the fallback as its English; a literal outside an argument is blank or data from
	// `data` (rule text); arguments may be any data. Returns the argument values (for the uppercase check).
	public static List<String> assertTranslated(Text text, Set<String> data, String context) {
		List<String> arguments = new ArrayList<>();
		walk(text, false, data, context, arguments);
		return arguments;
	}

	private static void walk(Text text, boolean argument, Set<String> data, String context, List<String> arguments) {
		switch (text) {
			case Text.Literal literal -> {
				if (argument) {
					arguments.add(literal.value());
				} else {
					assertTrue(literal.value().isBlank() || data.contains(literal.value()), context + ": literal outside an argument: " + literal);
				}
			}
			case Text.Translatable t -> {
				assertTrue(english().containsKey(t.key()), context + ": key not in en_us.json: " + t.key());
				assertEquals(english().get(t.key()), t.fallback(), context + ": the fallback of " + t.key() + " isn't its en_us.json value");
				for (Object arg : t.args()) {
					if (arg instanceof Text inner) {
						walk(inner, true, data, context, arguments);
					} else {
						arguments.add(String.valueOf(arg));
					}
				}
			}
			case Text.Joined joined -> joined.parts().forEach(part -> walk(part, argument, data, context, arguments));
		}
	}

	// AC9.3: through the uppercasing translator nothing but argument values (and rule data) is lower case. The arguments
	// and data are rendered as a placeholder, so only the text around them is checked.
	public static void assertPseudoLocalised(Text text, Set<String> data, String context) {
		assertTranslated(text, data, context);
		String rest = pseudo(masked(text, false, data));
		assertTrue(rest.chars().noneMatch(Character::isLowerCase), context + ": untranslated text reaches the UI: \"" + pseudo(text) + "\" (without its arguments: \""
				+ rest.replace(HOLE, "_") + "\")");
	}

	private static final String HOLE = "\u0000";

	private static Text masked(Text text, boolean argument, Set<String> data) {
		return switch (text) {
			case Text.Literal literal -> argument || data.contains(literal.value()) ? Text.literal(HOLE) : literal;
			case Text.Translatable t -> Text.of(t.key(), t.fallback(),
					t.args().stream().map(arg -> arg instanceof Text inner ? masked(inner, true, data) : HOLE).toArray());
			case Text.Joined joined -> new Text.Joined(joined.separator(), joined.parts().stream().map(part -> masked(part, argument, data)).toList());
		};
	}
}
