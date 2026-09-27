#!/usr/bin/env bash
# Runs a command up to 3 times, 30 s then 90 s apart (docs/v0.5/design/ws-ci.md). Only for steps that download; tests are
# never retried (a flake is re-run by hand, so an intermittent regression isn't hidden).
#   tools/ci/retry.sh ./gradlew --no-daemon prefetchDependencies
set -uo pipefail
if [ "$#" -eq 0 ]; then
	echo "usage: tools/ci/retry.sh <command> [args...]" >&2
	exit 2
fi
for wait in 30 90 0; do
	"$@" && exit 0
	status=$?
	if [ "$wait" -eq 0 ]; then
		exit "$status"
	fi
	echo "::warning::'$*' failed (exit $status); retrying in $wait s"
	sleep "$wait"
done
