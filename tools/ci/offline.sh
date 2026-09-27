#!/usr/bin/env bash
# Runs a command with no network but loopback (docs/v0.5/design/ws-ci.md): in a new network namespace whose only interface
# is lo, as the calling user, with the calling environment. GitHub's Linux runners (passwordless sudo) only. Loopback also
# carries multicast (a route for 224.0.0.0/4 on lo, still nothing off the machine), so vanilla's LAN discovery works
# (docs/v0.5/SPEC.md 1a; tools/ci/MulticastCheck.java checks it).
#   tools/ci/offline.sh ./gradlew --no-daemon --offline :26.2:runProductionClientGameTest
set -euo pipefail
if [ "$#" -eq 0 ]; then
	echo "usage: tools/ci/offline.sh <command> [args...]" >&2
	exit 2
fi
exec sudo --preserve-env env "PATH=$PATH" "HOME=$HOME" unshare --net -- \
	bash -c 'ip link set lo up && ip link set lo multicast on && ip route add 224.0.0.0/4 dev lo && exec setpriv --reuid="$0" --regid="$1" --init-groups -- "${@:2}"' "$(id -u)" "$(id -g)" "$@"
