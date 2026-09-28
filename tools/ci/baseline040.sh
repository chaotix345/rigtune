#!/usr/bin/env bash
# SCRATCH (scratch/baseline-040, never merged): RigTune's startup footprint, the released 0.4.0 against this build, each with
# an empty config/rigtune and with the returning player's seed (tools/gametest/returning_seed.py), launched the same way
# (the E2E harness's e2eClient on a scratch instance: RigTune and fabric-api in its mods folder, the inert E2E driver). Each
# launch runs until RigTune's own "RigTune startup footprint" line (5 s after the client started), then its game is ended.
set -u
MC=$1
OLD="$PWD/build/baseline/rigtune-0.4.0+mc$MC.jar"
NEW=$(ls "versions/$MC/build/libs/"rigtune-*+mc"$MC".jar | grep -v sources | head -1)
FAPI=$(find ~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api -maxdepth 3 -name "fabric-api-*+$MC.jar" | grep -v sources | head -1)
echo "old $OLD; new $NEW; fabric-api $FAPI"
python3 tools/gametest/returning_seed.py --out build/returning-seed
summary=build/baseline/summary.txt

end_game() {
	for p in $(pgrep -x java || true); do
		if tr '\0' '\n' < "/proc/$p/cmdline" 2>/dev/null | grep -qxF net.fabricmc.loader.impl.launch.knot.KnotClient; then kill "$p" || true; fi
	done
}

run_one() {
	label=$1 jar=$2 seed=$3
	inst="$PWD/build/baseline/$label"
	rm -rf "$inst"
	mkdir -p "$inst/mods" "$inst/config/rigtune"
	cp "$jar" "$FAPI" "$inst/mods/"
	if [ "$seed" = yes ]; then
		python3 - "$inst" <<'EOF'
import pathlib, sys
inst = sys.argv[1]
for f in pathlib.Path("build/returning-seed").glob("*.json"):
    (pathlib.Path(inst) / "config" / "rigtune" / f.name).write_text(f.read_text(encoding="utf-8").replace("${INSTANCE}", inst), encoding="utf-8")
EOF
	fi
	./gradlew --no-daemon ":$MC:e2eClient" "-Pe2e.instance=$inst" "-Pe2e.oldJar=$OLD" > "build/baseline/$label.gradle.log" 2>&1 &
	g=$!
	for _ in $(seq 1 240); do
		grep -q "RigTune startup footprint" "$inst/logs/latest.log" 2>/dev/null && break
		sleep 1
	done
	line=$(grep -h "RigTune startup footprint" "$inst/logs/latest.log" 2>/dev/null | head -1)
	echo "$label: ${line:-no footprint line}" | tee -a "$summary"
	end_game
	wait "$g" 2>/dev/null || true
	sleep 2
}

for rep in 1 2 3; do
	run_one "040-fresh-$rep" "$OLD" no
	run_one "040-returning-$rep" "$OLD" yes
	run_one "050-fresh-$rep" "$NEW" no
	run_one "050-returning-$rep" "$NEW" yes
done
