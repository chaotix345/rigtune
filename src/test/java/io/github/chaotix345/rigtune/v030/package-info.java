// RigTune 0.3.0's own code, pinned for the v0.4 compatibility tests (docs/v0.4/SPEC.md "Compatibility promise",
// docs/v0.4/design/ws-k.md). Every file under v030/core/ is `git show v0.3.0:src/main/java/io/github/chaotix345/rigtune/
// core/<pkg>/<Name>.java` with only the package line changed and the imports of other pinned classes pointed at v030
// (like the v010 and v020 copies). Never edit them. Exception: v030/core/recommend/Recommender.java is a stub holding only
// SUPPORTED_FEATURES and supported() (see that file).
// core/rules is byte-identical in v0.2.0 and v0.3.0 (`git diff v0.2.0 v0.3.0 -- src/main/java/io/github/chaotix345/rigtune/
// core/rules` is empty), so these copies stand for 0.2.0's condition evaluator and rules parser too.
// The copies compile against these CURRENT classes, which they don't pin: io.github.chaotix345.rigtune.RigTune (logger),
// core.model.* records and enums (HardwareProfile, GpuInfo, GpuClass, GpuVendor, GraphicsBackend, DisplayInfo, Goal,
// SettingsSnapshot, TierResult, Impact, SettingKeys, Text, BenchmarkSummary) and Fabric Loader's version API.
package io.github.chaotix345.rigtune.v030;
