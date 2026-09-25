package io.github.chaotix345.rigtune.core.rules;

import io.github.chaotix345.rigtune.RigTune;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Where a 0.2 client gets its rules (docs/RULES_SCHEMA.md "How the mod picks a copy"): the bundled rules-v2.json, the
// v2 cache, 0.1.0's cache (read only), then remote rules-v2.json, falling back to remote rules-v1.json.
public final class RulesSources {
	public static final String BASE_URL_PROPERTY = "rigtune.rules.baseUrl";
	public static final URI DEFAULT_BASE_URL = URI.create("https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/");
	public static final String V2_FILE = "rules-v2.json";
	public static final String V1_FILE = "rules-v1.json";
	public static final String V2_CACHE = "rules-v2-cache.json";
	// Written by 0.1.x only; 0.2 reads it as a candidate and never writes it.
	public static final String LEGACY_CACHE = "rules-cache.json";

	public interface Listener {
		void loaded(RulesDocument rules, boolean remote);
	}

	private final Path dir;
	private final URI baseUrl;
	private final String modVersion;

	public RulesSources(Path configDir, URI baseUrl, String modVersion) {
		this.dir = configDir.resolve("rigtune");
		this.baseUrl = baseUrl;
		this.modVersion = modVersion;
	}

	// The value of -Drigtune.rules.baseUrl (a folder URL that holds rules-v2.json and rules-v1.json), or the default.
	public static URI baseUrl(String override) {
		if (override == null || override.isBlank()) {
			return DEFAULT_BASE_URL;
		}
		String text = override.trim();
		try {
			URI uri = new URI(text.endsWith("/") ? text : text + "/");
			String scheme = uri.getScheme();
			if (uri.getHost() != null && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
				return uri;
			}
		} catch (URISyntaxException e) {
			// Falls through to the warning.
		}
		RigTune.LOGGER.warn("Ignoring -D{}={}: not an http(s) URL", BASE_URL_PROPERTY, override);
		return DEFAULT_BASE_URL;
	}

	public List<RulesLoader.Candidate> local() {
		List<RulesLoader.Candidate> candidates = new ArrayList<>();
		try {
			candidates.add(new RulesLoader.Candidate(RulesLoader.SOURCE_BUNDLED, RulesLoader.loadBundled()));
		} catch (RuntimeException e) {
			RigTune.LOGGER.error("Bundled rules are unusable", e);
		}
		for (String cache : List.of(V2_CACHE, LEGACY_CACHE)) {
			RulesLoader.loadCache(dir.resolve(cache)).ifPresent(doc -> candidates.add(new RulesLoader.Candidate(RulesLoader.SOURCE_CACHE, doc)));
		}
		return candidates;
	}

	// Remote rules-v2.json (cached when it is a v2 document), or remote rules-v1.json when that fails (kept in memory
	// only, so the v2 cache always holds a v2 document).
	public Optional<RulesLoader.Candidate> remote() {
		Optional<RulesDocument> v2 = new RemoteRulesFetcher(baseUrl.resolve(V2_FILE), modVersion, dir.resolve(V2_CACHE)).fetch();
		Optional<RulesDocument> doc = v2.isPresent() ? v2 : new RemoteRulesFetcher(baseUrl.resolve(V1_FILE), modVersion, null).fetch();
		return doc.map(d -> new RulesLoader.Candidate(RulesLoader.SOURCE_REMOTE, d));
	}

	// Hands the best local rules to the listener, then, only if remoteAllowed, fetches the remote rules and hands them
	// over too if they win. With remoteAllowed false no request is made at all.
	public void load(boolean remoteAllowed, Listener listener) {
		List<RulesLoader.Candidate> candidates = local();
		RulesDocument local = RulesLoader.pickNewest(candidates).orElse(null);
		if (local != null) {
			listener.loaded(local, false);
		}
		if (!remoteAllowed) {
			return;
		}
		remote().ifPresent(remote -> {
			candidates.add(remote);
			RulesDocument best = RulesLoader.pickNewest(candidates).orElse(null);
			if (best != null && best != local) {
				listener.loaded(best, true);
			}
		});
	}
}
