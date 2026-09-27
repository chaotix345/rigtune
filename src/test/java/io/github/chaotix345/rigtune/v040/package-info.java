// RigTune 0.4.0's own rules parser, pinned for the v0.5 compatibility tests (docs/v0.5/SPEC.md "Compatibility promise",
// AC5.2; docs/v0.5/design/ws-k.md). Every file under v040/core/ is `git show v0.4.0:src/main/java/io/github/chaotix345/
// rigtune/core/<pkg>/<Name>.java` with only the package line changed and the imports of other pinned classes pointed at
// v040 (like the v010, v020 and v030 copies). Never edit them. Pinned: core/rules RulesDocument, LenientSection,
// ConditionAdapterFactory, Condition, BudgetedChars and core/model Impact, the classes 0.4.0's RulesLoader.parse needs to
// turn rules-v2.json into a RulesDocument (feature-stutter-fixes.md §1.4's harness).
// For the 4i reach test (docs/v0.5/SPEC.md AC4i.1, docs/v0.5/design/ws-r.md), also pinned the same way: core/rules
// ConditionEvaluator, EvalContext and Truth, and client/probe/ModScanner (`git show v0.4.0:src/client/java/.../client/
// probe/ModScanner.java`; besides the package line it gains one import, of the current client.probe.Probes, which the
// original reached as a same-package class). Exceptions, not copies: v040/core/recommend/Recommender.java (a stub with only
// SUPPORTED_FEATURES and supported(), like v030's) and v040/core/rules/V040Parser.java (a test helper that parses as
// 0.4.0's RulesLoader.parse does).
// The copies compile against these CURRENT classes, which they don't pin: io.github.chaotix345.rigtune.RigTune (logger),
// Gson and jspecify; ConditionEvaluator and EvalContext also core.model.* (unchanged since v0.4.0 when pinned),
// core.hardware.DriverVersionParser and GpuClassifier, core.jvm.JvmFacts, core.recommend.SettingValues, core.stutter
// Attributor and StutterFacts, and Fabric Loader's version API; ModScanner core.apply InstanceDirs, ModJars and
// SafeFileNames, client.probe.Probes and Fabric Loader's API.
package io.github.chaotix345.rigtune.v040;
