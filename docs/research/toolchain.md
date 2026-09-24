# RigTune Toolchain Research — Minecraft 26.2 (Fabric, unobfuscated)

Research date: 2026-09-24. All facts below were pulled live from the sources cited per section (fabricmc.net, docs.fabricmc.net, GitHub FabricMC repos, maven.fabricmc.net metadata XML, maven.terraformersmc.com, Modrinth). Do not treat any of this as memorized/assumed — re-verify before relying on it if much time has passed since the research date.

---

## 1. `fabric-example-mod` template for 26.2

**Branch:** [`26.2`](https://github.com/FabricMC/fabric-example-mod/tree/26.2) on `github.com/FabricMC/fabric-example-mod` (last updated 2026-09-19). Note the repo's **default branch is `26.3`**, not `26.2` — you must explicitly check out/browse the `26.2` branch. Other active branches: `26.1.2`, `1.21.10`, `1.17.1`, `1.21.9`.

Source: https://github.com/FabricMC/fabric-example-mod/branches

### Versions in the template (from `gradle.properties`, verified against maven-metadata.xml)

| Component | Version |
|---|---|
| Minecraft | `26.2` |
| Fabric Loader | `0.19.5` (also the current `<latest>`/`<release>` on maven.fabricmc.net) |
| Loom | `1.17-SNAPSHOT` |
| Fabric API | `0.161.0+26.2` (highest `+26.2`-tagged build currently on maven.fabricmc.net; newer builds like `0.161.1+26.4` exist but target later MC versions) |
| Gradle (wrapper) | `9.5.1` |
| Java | 25 (`sourceCompatibility`/`targetCompatibility` = `VERSION_25`, `JavaCompile.options.release = 25`; `fabric.mod.json` requires `"java": ">=25"`) |

Loom note: maven.fabricmc.net's `net.fabricmc.fabric-loom.gradle.plugin` metadata currently reports `<latest>1.18-SNAPSHOT</latest>` / `<release>1.18.2</release>`, and the latest stable 1.17.x point release is `1.17.21`. The template nonetheless pins the floating `1.17-SNAPSHOT` — 1.18 is presumably being developed against 26.3+. Reproduce the template as-is (`1.17-SNAPSHOT`) unless you have a reason to pin a specific stable version.

Sources:
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle.properties
- https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml
- https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml
- https://maven.fabricmc.net/net/fabricmc/fabric-loom/net.fabricmc.fabric-loom.gradle.plugin/maven-metadata.xml

### Loom plugin ID for non-obfuscated versions

Confirmed on the Fabric docs "Loom" reference page — Loom uses different plugin IDs depending on whether the target MC version is obfuscated:

> - `net.fabricmc.fabric-loom`, for non-obfuscated versions (Minecraft 26.1 or newer)
> - `net.fabricmc.fabric-loom-remap`, for obfuscated versions (Minecraft 1.21.11 or older)
> - `fabric-loom` (legacy), only supported for backwards compatibility with obfuscated versions. Use `net.fabricmc.fabric-loom-remap` instead
> - `net.fabricmc.fabric-loom-companion`, in advanced multi-project setups.

For 26.2 (unobfuscated), use `net.fabricmc.fabric-loom` — this is exactly what the template's `build.gradle` uses.

Source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/develop/loom/index.md (rendered at https://docs.fabricmc.net/develop/loom/)

### Verbatim template files (branch `26.2`)

**`settings.gradle`**
```gradle
pluginManagement {
	repositories {
		maven {
			name = 'Fabric'
			url = 'https://maven.fabricmc.net/'
		}
		mavenCentral()
		gradlePluginPortal()
	}
}

// Should match your modid
rootProject.name = 'modid'
```

**`build.gradle`**
```gradle
plugins {
	id 'net.fabricmc.fabric-loom' version "${loom_version}"
	id 'maven-publish'
}

repositories {
	// Add repositories to retrieve artifacts from in here.
	// You should only use this when depending on other mods because
	// Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
	// See https://docs.gradle.org/current/userguide/declaring_repositories.html
	// for more information about repositories.
}

loom {
	splitEnvironmentSourceSets()

	mods {
		"modid" {
			sourceSet sourceSets.main
			sourceSet sourceSets.client
		}
	}
}

dependencies {
	// To change the versions see the gradle.properties file
	minecraft "com.mojang:minecraft:${project.minecraft_version}"
	implementation "net.fabricmc:fabric-loader:${project.loader_version}"

	// Fabric API. This is technically optional, but you probably want it anyway.
	implementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_api_version}"
}

processResources {
	def version = project.version
	inputs.property "version", version

	filesMatching("fabric.mod.json") {
		expand "version": version
	}
}

tasks.withType(JavaCompile).configureEach {
	it.options.release = 25
}

java {
	// Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
	// if it is present.
	// If you remove this line, sources will not be generated.
	withSourcesJar()

	sourceCompatibility = JavaVersion.VERSION_25
	targetCompatibility = JavaVersion.VERSION_25
}

jar {
	def projectName = project.name
	inputs.property "projectName", projectName

	from("LICENSE") {
		rename { "${it}_$projectName"}
	}
}

// configure the maven publication
publishing {
	publications {
		create("mavenJava", MavenPublication) {
			from components.java
		}
	}

	// See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
	repositories {
		// Add repositories to publish to here.
		// Notice: This block does NOT have the same function as the block in the top level.
		// The repositories here will be used for publishing your artifact, not for
		// retrieving dependencies.
	}
}
```

**Important — no mappings line.** There is no `mappings loom.officialMojangMappings()` (or any `mappings ...` line) anywhere in this `build.gradle`. This is expected and correct: Minecraft 26.1+ ships unobfuscated (real Mojang names baked into the game jar directly), so Loom has nothing to remap against and the `mappings` dependency configuration is unused. This is a deliberate confirmation of your assumption, not an omission.

**`gradle.properties`**
```properties
# Done to increase the memory available to gradle.
org.gradle.jvmargs=-Xmx1G
org.gradle.parallel=true

# IntelliJ IDEA is not yet fully compatible with configuration cache, see: https://github.com/FabricMC/fabric-loom/issues/1349
org.gradle.configuration-cache=false

# Fabric Properties
# check these on https://fabricmc.net/develop
minecraft_version=26.2
loader_version=0.19.5
loom_version=1.17-SNAPSHOT

# Mod Properties
version=1.0.0
group=com.example

# Dependencies
fabric_api_version=0.161.0+26.2
```

**`gradle/wrapper/gradle-wrapper.properties`**
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.5.1-bin.zip
networkTimeout=10000
retries=0
retryBackOffMs=500
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

**`src/main/resources/fabric.mod.json`**
```json
{
	"schemaVersion": 1,
	"id": "modid",
	"version": "${version}",
	"name": "Example Mod",
	"description": "This is an example description! Tell everyone what your mod is about!",
	"authors": [
		"Me!"
	],
	"contact": {
		"homepage": "https://fabricmc.net/",
		"sources": "https://github.com/FabricMC/fabric-example-mod"
	},
	"license": "CC0-1.0",
	"icon": "assets/modid/icon.png",
	"environment": "*",
	"entrypoints": {
		"main": [
			"com.example.ExampleMod"
		],
		"client": [
			"com.example.client.ExampleModClient"
		]
	},
	"mixins": [
		"modid.mixins.json",
		{
			"config": "modid.client.mixins.json",
			"environment": "client"
		}
	],
	"depends": {
		"fabricloader": ">=0.19.5",
		"minecraft": "~26.2",
		"java": ">=25",
		"fabric-api": "*"
	}
}
```

Sources (raw, fetched directly):
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/settings.gradle
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/build.gradle
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle.properties
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/gradle/wrapper/gradle-wrapper.properties
- https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.2/src/main/resources/fabric.mod.json

### Split source sets

**Yes.** The template calls `loom { splitEnvironmentSourceSets() }` and declares the mod's classpath grouping via `mods { "modid" { sourceSet sourceSets.main; sourceSet sourceSets.client } }`. This gives you `src/main/java` (common/logic code, works on both sides even though this is a client-only mod concept-wise) and `src/client/java` (client-only code, e.g. rendering/options-screen code), each with matching `resources` folders. `fabric.mod.json` declares two entrypoints: `main` → `com.example.ExampleMod`, `client` → `com.example.client.ExampleModClient`.

Source: same `build.gradle`/`fabric.mod.json` above.

---

## 2. Client game tests (`fabric-client-gametest-api-v1`)

Fabric's docs page for this is **not** a standalone "Client Game Tests" page — it's a section titled "Game Tests" inside **`docs.fabricmc.net/develop/automatic-testing`** ("Automated Testing"). (The task description's guess at a dedicated URL doesn't exist; this is the actual page.)

Source: https://docs.fabricmc.net/develop/automatic-testing (source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/develop/automatic-testing.md)

### `fabric.mod.json` entrypoint key

Client game tests are declared with the `fabric-client-gametest` entrypoint key (server/common game tests use `fabric-gametest`). When you enable `createSourceSet = true` (see below), Loom generates a **separate** gametest source set (default name `gametest`) with its own `fabric.mod.json`:

```json
{
  "schemaVersion": 1,
  "id": "example-mod-test",
  "version": "1.0.0",
  "name": "Example mod",
  "icon": "assets/example-mod/icon.png",
  "environment": "*",
  "entrypoints": {
    "fabric-gametest": [
      "com.example.docs.ExampleModGameTest",
      "com.example.docs.entity.EntityAttributesGameTest"
    ],
    "fabric-client-gametest": ["com.example.docs.ExampleModClientGameTest"]
  }
}
```
Source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/reference/latest/src/gametest/resources/fabric.mod.json

### `build.gradle` — Loom/Fabric API DSL

```gradle
fabricApi {
	configureTests {
		createSourceSet = true
		modId = "example-mod-test-${project.name}"
		enableGameTests = true // Default is true
		enableClientGameTests = true // Default is true
		eula = true // By setting this to true, you agree to the Minecraft EULA.
	}
}
```
`createSourceSet = true` is what generates the separate `src/gametest/java` (+ `src/gametest/resources/fabric.mod.json`) source set shown above. `eula = true` is required because running a client game test launches an actual Minecraft client, which requires accepting the Minecraft EULA.

To see all options, Fabric's docs point to the Loom documentation on tests: https://docs.fabricmc.net/develop/loom/fabric-api#tests

Source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/reference/latest/build.gradle (region `automatic_testing_game_test_1`)

### Gradle task

Server/common game tests run automatically as part of the `build` task. **Client game tests run via the `runClientGameTest` Gradle task.**

For CI (e.g. GitHub Actions), there's also a "production" variant registered manually:
```gradle
tasks.register("runProductionClientGameTest", net.fabricmc.loom.task.prod.ClientProductionRunTask) {
	jvmArgs.add("-Dfabric.client.gametest")
	useXVFB = true // use a virtual framebuffer on headless CI (Linux)
}
```
There's a known CI caveat: "game test may fail on GitHub Actions due to an error in the network synchronizer" — workaround is `-Dfabric.client.gametest.disableNetworkSynchronizer=true` as a JVM arg.

Source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/develop/automatic-testing.md

### Minimal example — create/join singleplayer world, wait for chunks, screenshot

```java
package com.example.docs;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

public class ExampleModClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			singleplayer.getClientLevel().waitForChunksRender();
			context.takeScreenshot("example-mod-singleplayer-test");
		}
	}
}
```
Source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/reference/latest/src/gametest/java/com/example/docs/ExampleModClientGameTest.java

For **player position/rotation**, the example above doesn't set it, but the API exposes what you need: `TestSingleplayerContext.getConnection()` returns a `TestServerConnection`, which has `getClientPlayer()` (returns the real `net.minecraft.client.player.LocalPlayer`, callable only on the render/client thread) and `getServerPlayer()` (returns `net.minecraft.server.level.ServerPlayer`, callable only on the server thread). From either of those you use standard vanilla Entity methods (e.g. `setPos(x, y, z)` / rotation setters) or route a teleport through `TestServerContext.runCommand("tp ...")` / `runOnServer(server -> ...)` for a deterministic server-authoritative move. Source (verified against fabric-api's `26.2` branch): https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/TestServerConnection.java

### Key API classes/methods (from `fabric-client-gametest-api-v1`, verified against the `26.2` branch of `FabricMC/fabric-api`)

- `net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest` — implement this, override `runTest(ClientGameTestContext context)`.
- `net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext` — main context. Key methods: `waitTick()`, `waitTicks(int)`, `waitFor(Predicate<Minecraft>)` / `waitFor(Predicate<Minecraft>, int timeout)`, `waitForScreen(Class<? extends Screen>)`, `setScreen(Supplier<Screen>)`, `clickScreenButton(String translationKey)`, `tryClickScreenButton(String translationKey)`, `takeScreenshot(String name)` / `takeScreenshot(TestScreenshotOptions)`, `assertScreenshotEquals(...)`, and `worldBuilder()` (→ `TestWorldBuilder`, used to create/join a world).
- `net.fabricmc.fabric.api.client.gametest.v1.context.TestWorldBuilder` — `.create()` returns a `TestSingleplayerContext` (singleplayer world).
- `net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext` (`AutoCloseable`) — `getWorldSave()`, `getConnection()` (→ `TestServerConnection`), `getServer()` (→ `TestServerContext`), `close()`.
- `net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection` — chunk/packet sync waits: `waitForChunksDownload()`/`waitForChunksDownload(int timeout)`, **`waitForChunksRender()`** / `waitForChunksRender(int timeout)` / `waitForChunksRender(boolean waitForDownload, int timeout)`, `waitForClientboundPackets()`, `waitForServerboundPackets()`, `waitForClientboundEntityUpdates(...)`; plus `getClientPlayer()` → `LocalPlayer`, `getServerPlayer()` → `ServerPlayer`, `getClientLevel()` → `ClientLevel`, `getServerLevel()` → `ServerLevel`.
- `net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext` — `runCommand(String)`, `runOnServer(FailableConsumer<MinecraftServer, E>)`, `computeOnServer(FailableFunction<MinecraftServer, T, E>)`, `waitFor(Predicate<MinecraftServer>)`.

Sources:
- https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/ClientGameTestContext.java
- https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/TestSingleplayerContext.java
- https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/TestServerConnection.java
- https://raw.githubusercontent.com/FabricMC/fabric-api/26.2/fabric-client-gametest-api-v1/src/client/java/net/fabricmc/fabric/api/client/gametest/v1/context/TestServerContext.java

(Note: the fabric-api repo used to live at `github.com/FabricMC/fabric` and was renamed/moved — `github.com/FabricMC/fabric-api` is correct as of 2026-09-24, with branches per MC version, e.g. `26.2`, `26.3`, `26.4`.)

---

## 3. Unit tests (JUnit 5 / `fabric-loader-junit`)

Also documented on https://docs.fabricmc.net/develop/automatic-testing, "Unit Testing" section.

Plain JUnit doesn't work out of the box in a Loom project because Minecraft modding relies on runtime bytecode changes (Mixin). Fabric provides **Fabric Loader JUnit** (`net.fabricmc:fabric-loader-junit`) as a JUnit 5 platform integration that runs your tests inside a Fabric Loader environment.

### `build.gradle`
```gradle
dependencies {
	testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}"
}

test {
	useJUnitPlatform()
}
```
`fabric-loader-junit` is versioned in lockstep with `fabric-loader` (use the same version, e.g. `0.19.5`). Its POM shows it transitively pulls in `org.junit.jupiter:junit-jupiter-engine` and `org.junit.platform:junit-platform-launcher`, pinned via `org.junit:junit-bom:5.10.0` — so you get JUnit 5.10.0 for free; you don't need to separately declare `org.junit.jupiter:junit-jupiter`.

Source: https://maven.fabricmc.net/net/fabricmc/fabric-loader-junit/0.19.5/fabric-loader-junit-0.19.5.pom

### Pure-Java logic tests (no Minecraft needed)

Tests live in `src/test/java`, mirroring the package of the class under test (e.g. testing `src/main/java/com/example/docs/codec/BeanType.java` → `src/test/java/com/example/docs/codec/BeanTypeTest.java`), and are written exactly like normal JUnit 5 tests (`@Test`, `org.junit.jupiter.api.Assertions`, etc.). For code that doesn't touch any registry-backed or Minecraft-bootstrapped class, no special setup is needed at all beyond the two `build.gradle` lines above — this is the "pure-Java logic" case you asked about (e.g. testing a pure settings-calculation/heuristics class in RigTune with no `Item`/`Block`/registry references).

### When you *do* touch registry-dependent classes (e.g. `ItemStack`)

You'll hit an initialization crash unless you bootstrap Minecraft's registries first. Add this to a `@BeforeAll` static method:
```java
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

@BeforeAll
static void beforeAll() {
	SharedConstants.tryDetectVersion();
	Bootstrap.bootStrap();
	// ... your own registration/setup here
}
```
Source (full worked example): https://raw.githubusercontent.com/FabricMC/fabric-docs/main/reference/latest/src/test/java/com/example/docs/codec/BeanTypeTest.java

Test reports for CI (e.g. GitHub Actions) live under `**/build/reports/` and `**/build/test-results/`; tests run automatically on `./gradlew build`.

Source: https://raw.githubusercontent.com/FabricMC/fabric-docs/main/develop/automatic-testing.md

---

## 4. Mod Menu for 26.2

**Maven repository:** `https://maven.terraformersmc.com/releases/`
**Coordinates:** `com.terraformersmc:modmenu:<version>`

### Latest version for 26.2

Per Modrinth's version API filtered to `game_versions=["26.2"]`, `loaders=["fabric"]`, the latest release is **`20.0.2`** (published 2026-09-11; changelog: "Add uzbek localization... Update Chinese translations"). This also matches the highest `26.2`-targeted entry on `maven.terraformersmc.com`'s `maven-metadata.xml` (`20.0.2`, `20.0.1`, `20.0.0` all exist; the overall repo `<latest>`/`<release>` is `21.0.0`, but that build targets a newer MC version — Mod Menu's own `26.2` branch `fabric.mod.json` constrains `"minecraft": ">=26.2 <26.3-alpha.3"`, confirming `20.0.2` is the correct pin for 26.2, not `21.0.0`).

Gradle:
```gradle
repositories {
	maven {
		name = "TerraformersMC"
		url = "https://maven.terraformersmc.com/releases/"
	}
}

dependencies {
	modImplementation "com.terraformersmc:modmenu:20.0.2"
	// or, per §5 below, plain `implementation`/`localRuntime` may be more correct
	// on unobfuscated MC — see note there.
}
```

Sources:
- https://maven.terraformersmc.com/releases/com/terraformersmc/modmenu/maven-metadata.xml
- https://api.modrinth.com/v2/project/modmenu/version?game_versions=%5B%2226.2%22%5D&loaders=%5B%22fabric%22%5D
- https://raw.githubusercontent.com/TerraformersMC/ModMenu/26.2/src/main/resources/fabric.mod.json

### Entrypoint key and interface for a custom config screen

`fabric.mod.json` entrypoint key: **`modmenu`**, pointing at a class implementing `com.terraformersmc.modmenu.api.ModMenuApi`:
```json
{
  "entrypoints": {
    "modmenu": [ "com.example.mod.ExampleModMenuApiImpl" ]
  }
}
```

To provide a custom config screen (e.g. RigTune's settings UI), override `getModConfigScreenFactory()`:
```java
public interface ModMenuApi {
    default ConfigScreenFactory<?> getModConfigScreenFactory() {
        return new NullScreenFactory<>();
    }
    // ...
}
```
`ConfigScreenFactory<?>` is a functional interface — implement it to construct your `Screen` given the "previous" screen (the active Mod Menu screen), e.g. `screen -> new MyConfigScreen(screen)`. The mod ID shown for the config button is auto-detected from the source mod container the `modmenu` entrypoint came from — you don't set it separately.

Mod Menu also exposes static helpers on `ModMenuApi` itself: `static Screen createModsScreen(Screen previous)` and `static Component createModsButtonText()`.

Sources:
- https://raw.githubusercontent.com/TerraformersMC/ModMenu/26.2/src/main/java/com/terraformersmc/modmenu/api/ModMenuApi.java
- ModMenu README (`develop`/API docs section), fetched via GitHub API from branch `26.2`: https://github.com/TerraformersMC/ModMenu/blob/26.2/README.md

---

## 5. Sodium for dev runtime (Modrinth Maven)

**Latest Sodium build for MC 26.2 (Fabric):** `0.9.2+mc26.2` (file `sodium-fabric-0.9.2+mc26.2.jar`, published 2026-09-11). Confirmed via Modrinth's version-list API filtered to `game_versions=["26.2"]`, `loaders=["fabric"]` — `mc26.2-0.9.2-fabric` is the newest non-alpha/beta entry (alpha/beta pre-releases for `0.9.2+mc26.2` exist but are older).

Source: https://api.modrinth.com/v2/project/sodium/version?game_versions=%5B%2226.2%22%5D&loaders=%5B%22fabric%22%5D

### Modrinth Maven coordinates

```
groupId:    maven.modrinth
artifactId: sodium
version:    mc26.2-0.9.2-fabric
```
i.e. `maven.modrinth:sodium:mc26.2-0.9.2-fabric`. Verified directly against `https://api.modrinth.com/maven/maven/modrinth/sodium/maven-metadata.xml`, which lists `mc26.2-0.9.2-fabric` among Sodium's published versions (note: Modrinth's own metadata XML happens to report the `<artifactId>` as Sodium's internal project ID `AANobbMI` rather than the slug — both the slug `sodium` and the ID `AANobbMI` work as the Maven artifact path/coordinate against `api.modrinth.com/maven`).

```gradle
repositories {
	maven {
		name = "Modrinth"
		url = "https://api.modrinth.com/maven"
		content {
			includeGroup "maven.modrinth"
		}
	}
}
```

### `modLocalRuntime` vs `localRuntime` on unobfuscated (Loom 1.17) — verified against Loom source

Checked directly against `fabric-loom`'s `dev/1.17` branch source (`LoomConfigurations.java` and `RemapConfigurations.java`):

- **`localRuntime`** (`Constants.Configurations.LOCAL_RUNTIME`) is registered **unconditionally**, regardless of obfuscation mode, and always extends the plugin's `runtimeClasspath`:
  ```java
  register(Constants.Configurations.LOCAL_RUNTIME, Role.RESOLVABLE);
  extendsFrom(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME, Constants.Configurations.LOCAL_RUNTIME);
  ```
- **All `mod*`-prefixed configurations** (`modImplementation`, `modApi`, `modRuntimeOnly`, `modCompileOnly`, `modCompileOnlyApi`, and — critically — **`modLocalRuntime`**) are only created inside:
  ```java
  if (!extension.disableObfuscation()) {
      // ...
      extension.createRemapConfigurations(SourceSetHelper.getMainSourceSet(getProject()));
  }
  ```
  i.e. **only when the project is targeting an obfuscated Minecraft version.** On unobfuscated Minecraft (26.1+, using the `net.fabricmc.fabric-loom` plugin ID), `disableObfuscation()` is true, this block never runs, and **the `mod*` configurations simply do not exist on the project** — there is nothing to remap, because mod jars for unobfuscated MC are already compiled against the real (Mojang) names.

**Conclusion:** on RigTune's 26.2 unobfuscated setup, declare Sodium as plain **`localRuntime`**, not `modLocalRuntime` (which won't exist as a configuration at all and will fail the build with "Could not find method modLocalRuntime()"). This matches what the official `fabric-example-mod` 26.2 template itself does for Fabric API — it uses plain `implementation`, not `modImplementation`.

```gradle
dependencies {
	localRuntime "maven.modrinth:sodium:mc26.2-0.9.2-fabric"
}
```

Sources:
- https://raw.githubusercontent.com/FabricMC/fabric-loom/dev/1.17/src/main/java/net/fabricmc/loom/configuration/LoomConfigurations.java
- https://raw.githubusercontent.com/FabricMC/fabric-loom/dev/1.17/src/main/java/net/fabricmc/loom/configuration/RemapConfigurations.java
- https://raw.githubusercontent.com/FabricMC/fabric-docs/main/develop/loom/index.md (generic "Dependency Configurations" doc section still lists `modImplementation`/`modApi`/`modRuntime` — this text is written generically across obfuscated and unobfuscated versions and does not call out that these configurations disappear on unobfuscated MC; the Loom source above is the authoritative, version-specific answer)

---

## 6. Other 26.2/26.3 porting notes relevant to a client mod (options screen, rendering, GPU info)

### Graphics API toggle (OpenGL / Vulkan)

26.2 introduces an experimental Vulkan rendering backend alongside OpenGL:

> 26.2 introduces the ability for the backend to be changed between the default OpenGL backend and an experimental Vulkan backend, with OpenGL planned to be removed once the Vulkan backend is stable.

In-game, this is exposed as a new **"Graphics API"** entry in Video Settings with three values:
- **Default** — currently behaves the same as "Prefer Vulkan".
- **Prefer Vulkan** — attempts Vulkan, falls back to OpenGL if unavailable.
- **Prefer OpenGL** — attempts OpenGL, falls back to Vulkan if unavailable.

Requirements/behavior: Vulkan 1.2 with dynamic rendering and push descriptors (subject to change); Vulkan prefers the dedicated GPU over integrated graphics (a change from OpenGL's behavior); on macOS, MoltenVK translates Vulkan to Metal. **The active backend is visible in the F3 debug overlay, in the `system_specs` section.**

Sources:
- https://fabricmc.net/2026/06/15/262.html
- https://minecraft.wiki/w/Java_Edition_26.2-snapshot-1 (Options / "Vulkan support" sections)

### Does Blaze3D expose GPU vendor/renderer/device name? Yes.

`com.mojang.blaze3d.systems.GpuDevice` (obtained via `RenderSystem.getDevice()`) is the abstraction over both the OpenGL and Vulkan backends and exposes, among others:
- `String getVendor()`
- `String getRenderer()`
- `String getVersion()`
- `String getBackendName()`
- `String getImplementationInformation()`
- `List<String> getEnabledExtensions()`
- `boolean isDebuggingEnabled()`
- `int getMaxTextureSize()`, `int getMaxSupportedAnisotropy()`, `int getUniformOffsetAlignment()`

This is exactly what RigTune would call to surface GPU vendor/renderer/backend info on a custom options/diagnostics screen, e.g. `RenderSystem.getDevice().getVendor()` / `.getRenderer()` / `.getBackendName()`.

Verified against the last obfuscated-version Yarn javadoc (1.21.11, immediately pre-unobfuscation) — note the mapping table there shows `named == intermediary == official == com/mojang/blaze3d/systems/GpuDevice`, i.e. this class already used its real Mojang name even under the old obfuscated mapping scheme, so it is effectively guaranteed to keep this same fully-qualified name in the unobfuscated 26.2/26.3 jar. (Not independently re-verified against a decompiled 26.2 jar — recommended to double check the exact method set against your actual JDK 25 + 26.2 dev environment once set up, since GpuDevice is Vulkan-toggle-era API and could have gained members between 1.21.11 and 26.2.)

Sources:
- https://maven.fabricmc.net/docs/yarn-1.21.11+build.1/com/mojang/blaze3d/systems/GpuDevice.html
- https://maven.fabricmc.net/docs/yarn-1.21.11+build.1/com/mojang/blaze3d/systems/RenderSystem.html (`getDevice()` accessor)

### Other porting notes worth knowing for an options/rendering-touching client mod

- **Raw OpenGL calls break under Vulkan.** "Developers making use of raw OpenGL calls rather than going through the Blaze3D API will need to migrate for their mods to work." — go through `RenderSystem`/Blaze3D, never call GL functions directly, or the mod will fail under the Vulkan backend. Source: https://fabricmc.net/2026/06/15/262.html
- **GUI/HUD methods moved off `Minecraft`.** Screen get/set moved to a dedicated `Gui` class, itself accessible from `Minecraft`:
  ```java
  // before
  Minecraft.getInstance().setScreen()
  // after (26.2+)
  Minecraft.getInstance().gui.setScreen()
  ```
  This directly affects any mod that opens/closes screens (e.g. RigTune's own settings screen, or Mod Menu integration). Source: https://fabricmc.net/2026/06/15/262.html
- **Block/Item IDs restructured** into separate `BlockIds`, `BlockItemIds`, `ItemIds` classes for data generation purposes (not directly relevant to a purely client-side settings mod, but relevant if RigTune ever registers anything). Source: https://fabricmc.net/2026/06/15/262.html
- **26.3: GLFW replaced by SDL** for window/input management. `InputConstants` should be used instead of hardcoding GLFW input constants (some constants, including all mouse buttons, changed values). Custom text-input widgets must notify `Minecraft.getInstance().textInputManager().onTextInputFocusChange(boolean)` (or `Minecraft.getInstance().onTextInputFocusChange(GuiEventListener, boolean)`) on focus change or the SDL backend desyncs and character input breaks — relevant if RigTune ever builds a custom text field in its options screen. Source: https://fabricmc.net/2026/09/15/263.html
- **Loom/Gradle for 26.3** (if/when RigTune tracks it later): Loom 1.17 still, but Gradle bumps to **9.6.0** (vs 9.5.1 for 26.2); Fabric Loader latest stable is still 0.19.5 as of both blog posts. Source: https://fabricmc.net/2026/09/15/263.html
- NeoForged maintains a more exhaustive cross-loader "porting primer" for 26.2/26.3 changes not covered in Fabric's own blog posts — referenced but not linked with a stable URL by Fabric's posts as of this research date; worth checking NeoForged's own site/GitHub if a change isn't covered here.

---

## Summary of pinned versions for RigTune (26.2, unobfuscated)

| Item | Value | Source |
|---|---|---|
| MC version | `26.2` | fabric-example-mod `26.2` branch |
| Loom plugin ID | `net.fabricmc.fabric-loom` | docs.fabricmc.net/develop/loom |
| Loom version | `1.17-SNAPSHOT` | fabric-example-mod `26.2` gradle.properties |
| Gradle | `9.5.1` | fabric-example-mod `26.2` gradle-wrapper.properties |
| Fabric Loader | `0.19.5` | fabric-example-mod `26.2` gradle.properties + maven-metadata.xml |
| Fabric API | `0.161.0+26.2` | fabric-example-mod `26.2` gradle.properties + maven-metadata.xml |
| Java | 25 | fabric-example-mod `26.2` build.gradle / fabric.mod.json |
| fabric-loader-junit | `0.19.5` (matches loader) | maven.fabricmc.net POM |
| Mod Menu | `com.terraformersmc:modmenu:20.0.2` | maven.terraformersmc.com + Modrinth API |
| Sodium | `maven.modrinth:sodium:mc26.2-0.9.2-fabric` | Modrinth maven-metadata.xml |
