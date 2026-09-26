# AC9.8: real driver strings + the hardware-change notice (P5-A, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6). Jars (`./gradlew :26.2:jar :26.3:jar`):
`rigtune-0.4.0-dev+mc26.2.jar` sha256 `149f20f9…dd11`, `rigtune-0.4.0-dev+mc26.3.jar` sha256 `ada3925d…ea15`.
Dev machine: AMD Radeon RX 7800 XT, Adrenalin 26.8.1, Ryzen 7 7800X3D, 32 GB, Windows 11, Temurin 25.0.4.1.
Clients: production clients through Loom's `e2eClient` on scratch instances (RC jar + fabric-api [+ Sodium]) with the
test-only driver mod (`../p5a-tools/driver`); one client at a time under the lock (`../p5a-tools/run.sh`).

| criterion | evidence | result |
|---|---|---|
| The real GL driver string parses to the expected family/version | 26.2 (and 26.3 GL) log: `Using graphics backend OpenGL, using drivers: 3.3.0 Core Profile Context 26.8.1.260810`; the RC's `DriverVersionParser.parse(AMD, OPENGL, …)` → **adrenalin [26, 8, 1]** (`parse-results.txt`, both jars) | **PASS** (Adrenalin 26.8.1 as expected) |
| ONE 26.3 Vulkan launch captures the real driverInfo string | 3rd attempt (`vk263-log-excerpt.txt`): `Using graphics backend Vulkan, using drivers: 1.4.349 AMD proprietary driver 26.8.1 (LLPC)`; RigTune's GpuInfo = the same string, backend VULKAN (`p5a-vk263.json`, `p5a-vk263-rigtune-vulkan.png`) | **captured** |
| …and it parses to the expected family | `parse(AMD, VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)")` → **family unknown** (expected adrenalin 26.8.1) | **FAIL on the RC → P5A-F1 (low)**; fixed on feat/v0.4.0 (c7a2e28): the merged jar parses it to **adrenalin [26, 8, 1]** (`parse-results.txt`) |
| Hardware-change notice silent on a first run | fresh instance, first start: `notices []`, awareness.json seeded with the real fingerprint (`awareness-first-run.json`, `aware1-log-excerpt.txt`, `p5a-aware1-rigtune-first.png`) | **PASS** |
| …fires after editing awareness.json's fingerprint (scratch copy, never the user's instance) | `../p5a-tools/fpedit.py` changed `gpuDriverRaw` to `…Context 26.5.1.260501`; next start: notice `hardware-changed:…` "Your GPU driver changed since last time (26.5.1 → 26.8.1)", detail "Re-scan to refresh the recommendations, or re-benchmark to measure again.", actions Re-scan / Re-benchmark (`aware2-log-excerpt.txt`, `p5a-aware2-rigtune-hwchange.png`, `p5a-aware2-noticescreen.png`); once shown, the fingerprint was committed back to 26.8.1 (`awareness-after-second-run.json`) | **PASS** |
| GL → Vulkan switch alone is not a hardware change (extra) | the Vulkan start showed no notice and the stored fingerprint moved on to the Vulkan values (`awareness-vk263-fingerprint.json`, screenshot). Caveat: that instance's two earlier 26.3 GL starts crashed natively ~15 s in, so it isn't recorded whether they had stored a GL fingerprint first | PASS (weak) |

## Commands
- `./gradlew -I p5a-init.gradle :26.2:e2eClient -Pe2e.instance=<scratch>/inst/p5a-inst-aware262 -Pe2e.driver=undo -Pe2e.jvmArgsFile=<args>`
  (driver scripts in `p5a-aware1.json` / `p5a-aware2.json` under `script`); `python fpedit.py` between the two starts.
- Vulkan: `:26.3:e2eClient … -Pp5aProgramArgs=--graphicsBackend,vulkan` (the init script `../p5a-tools/p5a-init.gradle`
  appends MC's own `--graphicsBackend vulkan` launch argument). Setting `preferredGraphicsBackend:"vulkan"` in
  options.txt did not stick: attempts 1-2 used OpenGL and then crashed natively (0xC0000005, the known vanilla 26.3
  startup crash), and after a crashed startup 26.3 logs "Detected unexpected shutdown during last game startup:
  resetting preferred graphics API to Default". Attempt 3 with the launch argument ran on Vulkan and exited normally.
- Parsing: `java -cp <rigtune jar>;gson-2.14.0.jar;jspecify-1.0.0.jar ../p5a-tools/ParseDriver.java AMD VULKAN "<raw>"`.

## Test-vector suggestion (applied by the fix in c7a2e28, lines 69-71 of DriverVersionParserTest)
```java
// A real 26.3 Vulkan string from the dev PC (RX 7800 XT, Adrenalin 26.8.1; Phase 5 P5-A):
parses(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)", DriverVersion.ADRENALIN, 26, 8, 1);
```
This needs the parser change in P5A-F1. If AMD on Vulkan stays unsupported in v0.4, add it instead as
`assertFalse(DriverVersionParser.parse(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)").known());`
with a comment that the number is the Adrenalin version, and amend AC9.8.
