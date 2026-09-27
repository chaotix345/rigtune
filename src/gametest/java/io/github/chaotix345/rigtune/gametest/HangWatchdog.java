package io.github.chaotix345.rigtune.gametest;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// RESEARCH ONLY (research/v05-ci, r-ci): with -Drigtune.test.watchdogMinutes=N, dumps every thread (with locks) to stdout
// and footprint/watchdog-threads.txt after N minutes, then halts the JVM with exit code 3, so a hang fails fast with evidence.
public class HangWatchdog implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		int minutes = Integer.getInteger("rigtune.test.watchdogMinutes", 0);
		if (minutes <= 0) {
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				Thread.sleep(minutes * 60_000L);
			} catch (InterruptedException e) {
				return;
			}
			StringBuilder dump = new StringBuilder("HangWatchdog: no exit after " + minutes + " min; all threads:\n");
			for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
				dump.append('"').append(info.getThreadName()).append("\" ").append(info.getThreadState());
				if (info.getLockName() != null) {
					dump.append(" on ").append(info.getLockName());
				}
				if (info.getLockOwnerName() != null) {
					dump.append(" owned by \"").append(info.getLockOwnerName()).append('"');
				}
				dump.append('\n');
				for (StackTraceElement frame : info.getStackTrace()) {
					dump.append("\tat ").append(frame).append('\n');
				}
				dump.append('\n');
			}
			System.out.println(dump);
			try {
				Path dir = FabricLoader.getInstance().getGameDir().resolve("footprint");
				Files.createDirectories(dir);
				Files.writeString(dir.resolve("watchdog-threads.txt"), dump, StandardCharsets.UTF_8);
			} catch (Exception ignored) {
			}
			Runtime.getRuntime().halt(3);
		}, "rigtune-gametest watchdog");
		thread.setDaemon(true);
		thread.start();
	}
}
