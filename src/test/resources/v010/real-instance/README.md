# The user's real 0.1.0 `last-apply.json` (templated)

`last-apply.json` is a copy of the file the released RigTune 0.1.0 helper wrote in the user's real instance (a Modrinth
App profile on MC 26.2) after its exit on 2026-09-24 23:09 UTC, read read-only on 2026-09-26 (original sha256
`9b55af7585b044cb063ae28123a9879f179e11e7d4945709d245236c44782d83`). It holds the only real-world failure seen so far
(docs/v0.3/SPEC.md 3e, review B-M1): the Distant Horizons update group, whose `DISABLE_FILE fabric-26.2.jar` hit a
sharing violation (`FAILED`, attempts 0 before the run, so "attempt 1 of 3") and whose `ENABLE_FILE` of the new DH jar
was therefore not applied (`FAILED`). The other 15 ops are `OK`.

The only edit: the instance root (the profile folder, which holds the user name) is replaced by `${INSTANCE}` wherever
it appears, in `path`/`from`/`to` and inside the `message` text, and the rest of each such path uses `/` instead of
`\`, as in `../captured/` (see its README; `V010Fixtures.template` substitutes the token). The file was parsed and
written back with two-space indentation; field order and every other value are as 0.1.0 wrote them. The script that
made it lives in the WS-B scratch folder (`template_real_last_apply.py`); it refuses to write if any of the user's path
is left.
