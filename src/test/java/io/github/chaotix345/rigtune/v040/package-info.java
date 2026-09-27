// RigTune 0.4.0's own rules parser, pinned for the v0.5 compatibility tests (docs/v0.5/SPEC.md "Compatibility promise",
// AC5.2; docs/v0.5/design/ws-k.md). Every file under v040/core/ is `git show v0.4.0:src/main/java/io/github/chaotix345/
// rigtune/core/<pkg>/<Name>.java` with only the package line changed and the imports of other pinned classes pointed at
// v040 (like the v010, v020 and v030 copies). Never edit them. Pinned: core/rules RulesDocument, LenientSection,
// ConditionAdapterFactory, Condition, BudgetedChars and core/model Impact, the classes 0.4.0's RulesLoader.parse needs to
// turn rules-v2.json into a RulesDocument (feature-stutter-fixes.md §1.4's harness).
// The copies compile against these CURRENT classes, which they don't pin: io.github.chaotix345.rigtune.RigTune (logger),
// Gson and jspecify.
package io.github.chaotix345.rigtune.v040;
