package io.github.chaotix345.rigtune.client.notice;

import io.github.chaotix345.rigtune.client.RealController;
import io.github.chaotix345.rigtune.client.compat.OptionalMods;
import io.github.chaotix345.rigtune.client.server.ServerLimitsTracker;
import io.github.chaotix345.rigtune.core.model.ServerLimits;
import io.github.chaotix345.rigtune.core.model.Text;
import io.github.chaotix345.rigtune.core.notice.Notice;
import io.github.chaotix345.rigtune.core.notice.NoticePriority;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

// NoticePriority.SERVER_LIMIT (docs/v0.4/SPEC.md 8, plan review W-H1): while connected to a server that isn't the
// player's own world, "The server limits view distance to N chunks" (+ "you set M" when M > N). The detail explains what
// the player sees, gives the simulation distance (the server decides it), says when this server's limit changed since
// last time, and with Distant Horizons loaded adds the (unverified, hence "may") note about terrain beyond the limit. The
// key names only the limit, never the server, so a dismissal stores nothing about where the player plays.
public final class ServerLimitNoticeSource implements NoticeSource {
	public static final String KEY_PREFIX = "server-limit:";
	private final RealController controller;

	public ServerLimitNoticeSource(RealController controller) {
		this.controller = controller;
	}

	@Override
	public @Nullable Notice current() {
		ServerLimitsTracker.Live live = controller.serverLimitsTracker().liveState();
		Minecraft minecraft = controller.minecraft();
		if (live == null || minecraft == null) {
			return null;
		}
		return notice(live.limits(), live.wasViewDistance(), minecraft.options.renderDistance().get(), OptionalMods.dhLoaded());
	}

	@Override
	public void act(String actionId) {
	}

	static @Nullable Notice notice(ServerLimits limits, @Nullable Integer was, int renderDistance, boolean distantHorizons) {
		int view = limits.viewDistance();
		if (limits.kind() == ServerLimits.Kind.SINGLEPLAYER || view <= 0) {
			return null;
		}
		boolean above = renderDistance > view;
		Text message = above ? Text.of("rigtune.server.notice.you_set", "The server limits view distance to %s chunks (you set %s)", view, renderDistance)
				: Text.of("rigtune.server.notice", "The server limits view distance to %s chunks", view);
		List<Text> detail = new ArrayList<>();
		if (above) {
			detail.add(Text.of("rigtune.server.detail.you_set", "You set %1$s; the server sends %2$s, so %2$s is what you see.", renderDistance, view));
		}
		if (limits.simulationDistance() > 0) {
			detail.add(Text.of("rigtune.server.detail.simulation", "Simulation distance on this server: %s chunks (the server decides it).",
					limits.simulationDistance()));
		}
		if (was != null && was != view) {
			detail.add(Text.of("rigtune.server.detail.changed", "This server's limit changed since last time (was %s).", was));
		}
		if (distantHorizons) {
			detail.add(Text.of("rigtune.server.detail.dh", "Distant Horizons may still show terrain you've already explored beyond it; "
					+ "generating new distant terrain may need Distant Horizons on the server."));
		}
		return new Notice(KEY_PREFIX + view + (above ? ":above" : ""), NoticePriority.SERVER_LIMIT, message,
				detail.isEmpty() ? null : Text.join(" ", detail), List.of(), true);
	}
}
