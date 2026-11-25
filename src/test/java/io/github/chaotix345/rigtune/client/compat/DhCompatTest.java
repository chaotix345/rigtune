package io.github.chaotix345.rigtune.client.compat;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue;
import com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// review-11 PERF-7: DH's graphics().renderingEnabled() builds a new DhApiConfigValue and converter on every call (DH core
// main, DhApiGraphicsConfig), and SettingsWatch asks once a second. The wrapper reads DH's config entry on each getValue,
// so DhCompat keeps one per DhApi.Delayed.configs instance.
class DhCompatTest {
	@AfterEach
	void reset() {
		DhApi.Delayed.configs = null;
		DhCompat.forgetRendering();
	}

	@Test
	void theRenderingValueIsBuiltOncePerConfigs() {
		AtomicInteger built = new AtomicInteger();
		AtomicBoolean on = new AtomicBoolean(true);
		DhApi.Delayed.configs = configs(built, on);
		assertTrue(DhCompat.renderingEnabled());
		on.set(false);
		assertFalse(DhCompat.renderingEnabled(), "the kept value still reads DH's setting");
		assertEquals(1, built.get());

		DhApi.Delayed.configs = configs(built, on);
		assertFalse(DhCompat.renderingEnabled());
		assertEquals(2, built.get(), "a new configs instance gets its own");
	}

	@SuppressWarnings("unchecked")
	private static IDhApiConfig configs(AtomicInteger built, AtomicBoolean on) {
		IDhApiConfigValue<Boolean> value = (IDhApiConfigValue<Boolean>) Proxy.newProxyInstance(DhCompatTest.class.getClassLoader(),
				new Class<?>[]{IDhApiConfigValue.class}, (proxy, method, args) -> switch (method.getName()) {
					case "getValue" -> on.get();
					case "getApiValue" -> null;
					default -> throw new UnsupportedOperationException(method.getName());
				});
		IDhApiGraphicsConfig graphics = (IDhApiGraphicsConfig) Proxy.newProxyInstance(DhCompatTest.class.getClassLoader(),
				new Class<?>[]{IDhApiGraphicsConfig.class}, (proxy, method, args) -> {
					if (method.getName().equals("renderingEnabled")) {
						built.incrementAndGet();
						return value;
					}
					throw new UnsupportedOperationException(method.getName());
				});
		return (IDhApiConfig) Proxy.newProxyInstance(DhCompatTest.class.getClassLoader(), new Class<?>[]{IDhApiConfig.class},
				(proxy, method, args) -> {
					if (method.getName().equals("graphics")) {
						return graphics;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
