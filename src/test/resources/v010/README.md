# Pinned v0.1.0 test fixtures

- `rules-v1-baseline.json`: the rules-v1.json that 0.1.0 shipped (`git show v0.1.0:rules/rules-v1.json`). `RulesV1DifferentialTest` checks that the repository's `rules/rules-v1.json` gives 0.1.x users no ticked action this baseline didn't. If a new ticked action is intended, review the test's output, then replace this file with `rules/rules-v1.json` in the same commit.
- `src/test/java/io/github/chaotix345/rigtune/v010/`: the v0.1.0 recommender, condition evaluator, rules parser and model classes, copied from tag v0.1.0 with only the package renamed (`io.github.chaotix345.rigtune` → `io.github.chaotix345.rigtune.v010`). Never edit them: they stand for what a 0.1.x client does with rules-v1.json.
