package io.github.chaotix345.rigtune.core.apply;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;

// A helper (ApplyExecutor) for tests outside this package whose renames of some files fail every time, as a jar another
// program keeps open does; no real waiting.
public final class TestExecutors {
	private TestExecutors() {
	}

	// A helper killed (PC shutdown, Task Manager) just before it renames a matching file: nothing after the throw runs.
	public static final class Killed extends Error {
	}

	public static ApplyExecutor killedAt(Predicate<Path> at) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			if (at.test(from)) {
				throw new Killed();
			}
			Files.move(from, to);
		}, millis -> true);
	}

	// Killed just after it renames a matching file (review 11 APPLY-1): the rename is done, nothing after it runs.
	public static ApplyExecutor killedAfter(Predicate<Path> after) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			Files.move(from, to);
			if (after.test(from)) {
				throw new Killed();
			}
		}, millis -> true);
	}

	// Killed once unfinished-groups.json durably says `opId`'s rename happened (review 12: the helper that finished a group's
	// renames and died before last-apply.json).
	public static ApplyExecutor killedAfterMarking(String opId) {
		return new ApplyExecutor(2, 1, Files::move, millis -> true, ModJars::readModId, (file, content) -> {
			UnfinishedGroups.DURABLE.write(file, content);
			for (JsonElement group : JsonParser.parseString(content).getAsJsonObject().getAsJsonArray("groups")) {
				for (JsonElement rename : group.getAsJsonObject().getAsJsonArray("renames")) {
					JsonObject r = rename.getAsJsonObject();
					if (opId.equals(r.get("op").getAsString()) && r.has("done") && r.get("done").getAsBoolean()) {
						throw new Killed();
					}
				}
			}
		});
	}

	public static ApplyExecutor failingMovesOf(Predicate<Path> fails) {
		return new ApplyExecutor(2, 1, (from, to) -> {
			if (fails.test(from)) {
				throw new FileSystemException(from.toString(), null, "The process cannot access the file because it is being used by another process");
			}
			Files.move(from, to);
		}, millis -> true);
	}
}
