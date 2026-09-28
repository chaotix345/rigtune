#!/usr/bin/env bash
# Runs a command with no network but loopback (docs/v0.5/design/ws-ci.md): in a new network namespace whose only interface
# is lo, as the calling user, with the calling environment. GitHub's Linux runners (passwordless sudo) only. Loopback also
# carries multicast (a route for 224.0.0.0/4 on lo with source 127.0.0.1, still nothing off the machine), so vanilla's LAN
# discovery works and names the host 127.0.0.1 (docs/v0.5/SPEC.md 1a; tools/ci/MulticastCheck.java checks it).
# --timeout <duration>: GNU timeout, running as root inside the sudo, sends TERM to the command's whole process group
# (Gradle, xvfb-run, the game) after <duration> and KILL 15 s later, although they run as the calling user.
#   tools/ci/offline.sh [--timeout 14m] ./gradlew --no-daemon --offline :26.2:runProductionClientGameTest
set -euo pipefail
limit=()
if [ "${1:-}" = --timeout ]; then
	limit=(timeout --kill-after=15s "$2")
	shift 2
fi
if [ "$#" -eq 0 ]; then
	echo "usage: tools/ci/offline.sh [--timeout <duration>] <command> [args...]" >&2
	exit 2
fi
exec sudo --preserve-env env "PATH=$PATH" "HOME=$HOME" unshare --net -- \
	bash -c 'ip link set lo up && ip link set lo multicast on && ip route add 224.0.0.0/4 dev lo src 127.0.0.1 && exec "$@"' offline \
	"${limit[@]}" setpriv --reuid="$(id -u)" --regid="$(id -g)" --init-groups -- "$@"
