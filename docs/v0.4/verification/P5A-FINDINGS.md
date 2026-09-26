# P5-A findings (Phase 5 real-run proofs, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6). Evidence folders are next to this file.

## P5A-F1 (low): the dev machine's real AMD Vulkan driver string parses to UNKNOWN (AC9.8)
- **Repro:** 26.3, forced Vulkan (`--graphicsBackend vulkan`), AMD Radeon RX 7800 XT, Adrenalin 26.8.1, Windows 11. MC logs
  `Using graphics backend Vulkan, using drivers: 1.4.349 AMD proprietary driver 26.8.1 (LLPC)`; RigTune's
  `GpuInfo.driverVersion` is that same string. `DriverVersionParser.parse(AMD, VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)")`
  (the RC jar's own class, `p5a-tools/ParseDriver.java`) → `family=unknown comparable=[]`. The same PC's GL string
  `3.3.0 Core Profile Context 26.8.1.260810` → `adrenalin [26, 8, 1]`.
- **Evidence:** `drivers/README.md`, `drivers/vk263-log-excerpt.txt`, `drivers/p5a-vk263.json`, `drivers/parse-results.txt`.
- **Cause:** by design so far: on Vulkan only the NVIDIA and Mesa sub-parsers read the driverInfo part, and
  DriverVersionParserTest asserts the AMD proprietary form (`1.3.296 AMD proprietary driver 24.12.1 (...)`) is UNKNOWN
  ("The API version is never the driver version"). The capture shows the number after "AMD proprietary driver" IS the
  Adrenalin version (26.8.1, identical to GL's `Context 26.8.1`), so AC9.8's "the 26.3 Vulkan capture parses to the
  expected family" is not met.
- **Impact:** low. It fails closed: a future `driverVersion {vendor: amd}` rule would stay UNKNOWN on AMD + Vulkan (no AMD
  driver rule is seeded in v0.4). The hardware-change notice still works on Vulkan (raw strings on the same backend are
  compared), but it would print the raw strings instead of "26.8.1 → 26.9.1".
- **Suggested fix / test vector (not applied; no product-code changes here):** on Vulkan, read AMD's driverInfo
  `AMD proprietary driver (\d+)\.(\d+)\.(\d+)` as adrenalin, and change the test to
  `parses(GpuVendor.AMD, GraphicsBackend.VULKAN, "1.4.349 AMD proprietary driver 26.8.1 (LLPC)", DriverVersion.ADRENALIN, 26, 8, 1);`
  (and flip the existing `1.3.296 AMD proprietary driver 24.12.1 (...)` assertion to adrenalin [24, 12, 1]).
  Or, if AMD Vulkan stays unsupported in v0.4, keep UNKNOWN and add the captured string as an UNKNOWN vector with a
  comment, and amend AC9.8's wording.
