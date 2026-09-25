package io.github.chaotix345.rigtune.e2e;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/**
 * Shows that JVM properties alone send a client's traffic for the Modrinth (and rules) hosts to the fake server, as the
 * self-update test needs for the unmodified v0.1.0 jar. Run it with the game's properties:
 * {@code java -Djdk.net.hosts.file=<hosts> -Djavax.net.ssl.trustStore=<p12> -Djavax.net.ssl.trustStorePassword=<pw>
 * RedirectProbe.java [--unresolved <host>]... <url>...}. Exits 1 unless every URL resolves to a loopback address and
 * answers 200 over a trusted connection, and every --unresolved host fails to resolve.
 */
public final class RedirectProbe {
	private RedirectProbe() {
	}

	public static void main(String[] args) throws Exception {
		List<String> urls = new ArrayList<>();
		List<String> unresolved = new ArrayList<>();
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("--unresolved") && i + 1 < args.length) {
				unresolved.add(args[++i]);
			} else {
				urls.add(args[i]);
			}
		}
		System.out.println("java.version=" + System.getProperty("java.version"));
		for (String property : List.of("jdk.net.hosts.file", "javax.net.ssl.trustStore", "javax.net.ssl.trustStoreType")) {
			System.out.println(property + "=" + System.getProperty(property));
		}
		System.out.println("javax.net.ssl.trustStorePassword " + (System.getProperty("javax.net.ssl.trustStorePassword") == null ? "unset" : "set"));
		boolean ok = true;
		try {
			System.out.println("getLocalHost: " + InetAddress.getLocalHost());
		} catch (UnknownHostException e) {
			System.out.println("getLocalHost FAILED: " + e);
		}
		try {
			System.out.println("localhost: " + Arrays.toString(InetAddress.getAllByName("localhost")));
		} catch (UnknownHostException e) {
			System.out.println("localhost FAILED: " + e);
		}
		for (String host : unresolved) {
			try {
				System.out.println("UNEXPECTED: " + host + " resolved to " + Arrays.toString(InetAddress.getAllByName(host)));
				ok = false;
			} catch (UnknownHostException e) {
				System.out.println("unresolved as expected: " + host);
			}
		}
		HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		for (String url : urls) {
			URI uri = URI.create(url);
			try {
				InetAddress[] addresses = InetAddress.getAllByName(uri.getHost());
				boolean loopback = Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
				HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
						HttpResponse.BodyHandlers.ofByteArray());
				String peer = response.sslSession().map(s -> {
					try {
						return s.getProtocol() + " " + s.getPeerPrincipal().getName();
					} catch (Exception e) {
						return s.getProtocol() + " (no peer: " + e + ")";
					}
				}).orElse("no TLS");
				String sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(response.body()));
				System.out.println(url + " -> " + Arrays.toString(addresses) + " | " + peer + " | HTTP " + response.statusCode()
						+ " | " + response.body().length + " bytes | sha256 " + sha256);
				ok &= loopback && response.statusCode() == 200;
			} catch (Exception e) {
				System.out.println(url + " FAILED: " + e);
				ok = false;
			}
		}
		System.out.println(ok ? "PROBE OK" : "PROBE FAILED");
		System.exit(ok ? 0 : 1);
	}
}
