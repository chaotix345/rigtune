// RigTune 0.4.0's own share-code decoder, pinned for the v0.5 compatibility tests (docs/v0.5/SPEC.md "Compatibility
// promise": every code 0.5 emits decodes in 0.4.0's decoder to the same values; AC2P.4, PF-5, Latent 1;
// docs/v0.5/design/ws-p.md). Every file here is `git show v0.4.0:src/main/java/io/github/chaotix345/rigtune/core/profile/
// <Name>.java` with only the package line changed and the import of another pinned class pointed at v040. Never edit them.
// Pinned: ShareCode, ShareKeys, ShareCodeException, ProfileNames. They compile against these CURRENT classes, which they
// don't pin: io.github.chaotix345.rigtune.core.recommend.SettingValues (refreshRateCap, unchanged since v0.4.0) and
// io.github.chaotix345.rigtune.core.model.Text, plus jspecify.
package io.github.chaotix345.rigtune.v040.core.profile;
