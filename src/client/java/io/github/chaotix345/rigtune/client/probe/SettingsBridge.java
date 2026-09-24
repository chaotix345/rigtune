package io.github.chaotix345.rigtune.client.probe;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.chaotix345.rigtune.RigTune;
import io.github.chaotix345.rigtune.core.apply.SodiumConfigPatcher;
import io.github.chaotix345.rigtune.core.model.SettingsSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.util.LenientJsonParser;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class SettingsBridge {
	public static final String VANILLA_PREFIX = "vanilla.";
	public static final String SODIUM_PREFIX = "sodium.";
	private static final String PRESET_KEY = "graphicsPreset";

	public record Result(String key, boolean ok, String oldValue, String newValue, String message) {
	}

	interface Visitor {
		void option(String key, OptionInstance<?> option);

		Object field(String key, Object current, Function<Object, String> serialize, Function<String, Object> parse);
	}

	private SettingsBridge() {
	}

	public static SettingsSnapshot read(Minecraft minecraft) {
		Map<String, String> values = new LinkedHashMap<>();
		try {
			readVanilla(minecraft.options).forEach((k, v) -> values.put(VANILLA_PREFIX + k, v));
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Could not read vanilla options", e);
		}
		values.putAll(readSodium(sodiumConfig()));
		return new SettingsSnapshot(Map.copyOf(values));
	}

	public static Path sodiumConfig() {
		return FabricLoader.getInstance().getConfigDir().resolve("sodium-options.json");
	}

	public static Map<String, String> readVanilla(Options options) {
		Map<String, String> out = new LinkedHashMap<>();
		visit(options, new Visitor() {
			@Override
			public void option(String key, OptionInstance<?> option) {
				encode(option).ifPresent(v -> out.put(key, v));
			}

			@Override
			public Object field(String key, Object current, Function<Object, String> serialize, Function<String, Object> parse) {
				if (current != null) {
					out.put(key, serialize.apply(current));
				}
				return current;
			}
		});
		return out;
	}

	public static Map<String, String> captions(Options options) {
		Map<String, String> out = new LinkedHashMap<>();
		try {
			visit(options, new Visitor() {
				@Override
				public void option(String key, OptionInstance<?> option) {
					out.put(key, option.toString());
				}

				@Override
				public Object field(String key, Object current, Function<Object, String> serialize, Function<String, Object> parse) {
					return current;
				}
			});
		} catch (RuntimeException e) {
			RigTune.LOGGER.warn("Could not read option captions", e);
		}
		return out;
	}

	public static Map<String, String> readSodium(Path file) {
		if (!Files.isRegularFile(file)) {
			return Map.of();
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
			return root.isJsonObject() ? SodiumConfigPatcher.flatten(root.getAsJsonObject(), SODIUM_PREFIX) : Map.of();
		} catch (IOException | JsonParseException e) {
			RigTune.LOGGER.warn("Could not read {}", file, e);
			return Map.of();
		}
	}

	public static Map<String, Result> applyVanilla(Map<String, String> values) {
		return applyVanilla(Minecraft.getInstance().options, values);
	}

	public static Map<String, Result> applyVanilla(Options options, Map<String, String> values) {
		return applyVanilla(options, values, true);
	}

	// graphicsPreset rewrites a dozen other options when set, so it goes first and the explicit values win.
	public static Map<String, Result> applyVanilla(Options options, Map<String, String> values, boolean save) {
		Map<String, String> first = new LinkedHashMap<>();
		Map<String, String> rest = new LinkedHashMap<>();
		values.forEach((k, v) -> {
			String bare = bare(k);
			(bare.equals(PRESET_KEY) ? first : rest).put(bare, v);
		});
		Map<String, Result> byBareKey = new LinkedHashMap<>();
		boolean changed = false;
		for (Map<String, String> batch : List.of(first, rest)) {
			if (!batch.isEmpty()) {
				changed |= applyBatch(options, batch, byBareKey);
			}
		}
		if (changed && save) {
			options.save();
		}
		Map<String, Result> out = new LinkedHashMap<>();
		for (String original : values.keySet()) {
			String bare = bare(original);
			Result r = byBareKey.get(bare);
			out.put(original, r == null ? new Result(original, false, null, null, "Unknown option " + bare)
					: new Result(original, r.ok(), r.oldValue(), r.newValue(), r.message()));
		}
		return out;
	}

	private static String bare(String key) {
		return key.startsWith(VANILLA_PREFIX) ? key.substring(VANILLA_PREFIX.length()) : key;
	}

	private static boolean applyBatch(Options options, Map<String, String> wanted, Map<String, Result> byBareKey) {
		boolean[] changed = {false};
		try {
			visit(options, new Visitor() {
				@Override
				public void option(String key, OptionInstance<?> option) {
					String value = wanted.get(key);
					if (value != null) {
						Result result = setOption(key, option, value);
						byBareKey.put(key, result);
						changed[0] |= result.ok() && !Objects.equals(result.oldValue(), result.newValue());
					}
				}

				@Override
				public Object field(String key, Object current, Function<Object, String> serialize, Function<String, Object> parse) {
					String value = wanted.get(key);
					if (value == null) {
						return current;
					}
					String old = current == null ? null : serialize.apply(current);
					try {
						Object parsed = parse.apply(value);
						if (parsed == null) {
							byBareKey.put(key, new Result(key, false, old, old, "Invalid value " + value));
							return current;
						}
						byBareKey.put(key, new Result(key, true, old, serialize.apply(parsed), "Set"));
						changed[0] |= !Objects.equals(parsed, current);
						return parsed;
					} catch (RuntimeException e) {
						byBareKey.put(key, new Result(key, false, old, old, "Invalid value " + value + ": " + e.getMessage()));
						return current;
					}
				}
			});
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Could not apply vanilla settings", e);
		}
		return changed[0];
	}

	private static <T> Result setOption(String key, OptionInstance<T> option, String value) {
		String old = encode(option).orElse(null);
		DataResult<T> parsed = decode(option, value);
		Optional<T> result = parsed.result();
		if (result.isEmpty()) {
			return new Result(key, false, old, old, "Invalid value " + value + ": " + parsed.error().map(DataResult.Error::message).orElse("?"));
		}
		if (option.values().validateValue(result.get()).isEmpty()) {
			return new Result(key, false, old, old, "Value out of range: " + value);
		}
		option.set(result.get());
		String now = encode(option).orElse(null);
		boolean ok = Objects.equals(option.get(), result.get());
		return new Result(key, ok, old, now, ok ? "Set" : "Rejected by the game: " + value);
	}

	public static <T> Optional<String> encode(OptionInstance<T> option) {
		return option.codec().encodeStart(JsonOps.INSTANCE, option.get()).result().map(SettingsBridge::unquote);
	}

	static String unquote(JsonElement json) {
		if (json instanceof JsonPrimitive primitive && primitive.isString()) {
			return primitive.getAsString();
		}
		return json.toString();
	}

	// Mirrors Options.load(): lenient JSON first, then the raw text as a JSON string for unquoted enum values.
	public static <T> DataResult<T> decode(OptionInstance<T> option, String value) {
		DataResult<T> first;
		try {
			JsonElement json = LenientJsonParser.parse(value.isEmpty() ? "\"\"" : value);
			first = option.codec().parse(JsonOps.INSTANCE, json);
		} catch (JsonParseException e) {
			first = DataResult.error(e::getMessage);
		}
		if (first.result().isPresent()) {
			return first;
		}
		DataResult<T> asString = option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(value));
		return asString.result().isPresent() ? asString : first;
	}

	// Options.processOptions(FieldAccess) is private and FieldAccess is package-private, so drive it through a proxy.
	@SuppressWarnings("unchecked")
	static void visit(Options options, Visitor visitor) {
		try {
			Class<?> fieldAccess = Class.forName("net.minecraft.client.Options$FieldAccess", false, Options.class.getClassLoader());
			Method process = Options.class.getDeclaredMethod("processOptions", fieldAccess);
			process.setAccessible(true);
			Object proxy = Proxy.newProxyInstance(Options.class.getClassLoader(), new Class<?>[]{fieldAccess}, (self, method, args) -> {
				switch (method.getName()) {
					case "hashCode":
						return System.identityHashCode(self);
					case "equals":
						return self == args[0];
					case "toString":
						return "RigTuneOptionsVisitor";
					default:
						break;
				}
				String key = (String) args[0];
				if (args.length == 2 && args[1] instanceof OptionInstance<?> option) {
					visitor.option(key, option);
					return null;
				}
				if (args.length == 4) {
					Function<String, Object> parse = (Function<String, Object>) args[2];
					Function<Object, String> serialize = (Function<Object, String>) args[3];
					return visitor.field(key, args[1], serialize, parse);
				}
				Class<?> type = method.getParameterTypes()[1];
				return visitor.field(key, args[1], String::valueOf, primitiveParser(type));
			});
			process.invoke(options, proxy);
		} catch (InvocationTargetException e) {
			throw new IllegalStateException("Options visitor failed", e.getCause());
		} catch (ReflectiveOperationException | RuntimeException e) {
			throw new IllegalStateException("Options visitor unavailable", e);
		}
	}

	static Function<String, Object> primitiveParser(Class<?> type) {
		if (type == int.class) {
			return Integer::parseInt;
		}
		if (type == float.class) {
			return Float::parseFloat;
		}
		if (type == boolean.class) {
			return s -> switch (s) {
				case "true" -> Boolean.TRUE;
				case "false" -> Boolean.FALSE;
				default -> throw new IllegalArgumentException("not a boolean");
			};
		}
		return s -> s;
	}
}
