package io.github.chaotix345.rigtune.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

// What the RigTune logger writes while this is open: each event's message plus its stack trace, as a log file shows it.
public final class LogCapture implements AutoCloseable {
	private final List<String> lines = new CopyOnWriteArrayList<>();
	private final AbstractAppender appender;

	public LogCapture() {
		appender = new AbstractAppender("rigtune-test-capture-" + System.identityHashCode(this), null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				StringWriter out = new StringWriter();
				out.append(event.getMessage().getFormattedMessage());
				if (event.getThrown() != null) {
					event.getThrown().printStackTrace(new PrintWriter(out));
				}
				lines.add(out.toString());
			}
		};
		appender.start();
		((Logger) LogManager.getLogger("RigTune")).addAppender(appender);
	}

	public List<String> lines() {
		return List.copyOf(lines);
	}

	@Override
	public void close() {
		((Logger) LogManager.getLogger("RigTune")).removeAppender(appender);
		appender.stop();
	}
}
