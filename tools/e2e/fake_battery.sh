#!/usr/bin/env bash
# A fake laptop battery for e2e.yml's battery OSHI leg (docs/v0.5/SPEC.md 3e, AC3e.2; research: verification-gaps.md
# §3.2b). The runner's kernel has no test_power module, so a tmpfs goes over /sys/class/power_supply with a hand-written
# BAT0 (and an AC adapter). OSHI reads these files only with -Doshi.os.linux.allowudev=false (its udev enumeration
# ignores the tmpfs). World-writable, so the game test (BatteryFlowGameTest) rewrites POWER_SUPPLY_STATUS as the runner
# user. Linux CI runners only (passwordless sudo).
#   tools/e2e/fake_battery.sh Discharging|Charging
set -eu
status=${1:?usage: fake_battery.sh Discharging|Charging}
ps=/sys/class/power_supply
sudo mount -t tmpfs -o mode=0777 tmpfs "$ps"
mkdir -p "$ps/AC" "$ps/BAT0"
printf 'POWER_SUPPLY_NAME=AC\nPOWER_SUPPLY_TYPE=Mains\nPOWER_SUPPLY_ONLINE=1\n' > "$ps/AC/uevent"
printf '%s\n' POWER_SUPPLY_NAME=BAT0 POWER_SUPPLY_TYPE=Battery "POWER_SUPPLY_STATUS=$status" POWER_SUPPLY_PRESENT=1 \
  POWER_SUPPLY_TECHNOLOGY=Li-ion POWER_SUPPLY_CYCLE_COUNT=12 POWER_SUPPLY_VOLTAGE_NOW=12000000 POWER_SUPPLY_CURRENT_NOW=1500000 \
  POWER_SUPPLY_CHARGE_FULL_DESIGN=5000000 POWER_SUPPLY_CHARGE_FULL=4000000 POWER_SUPPLY_CHARGE_NOW=2000000 \
  POWER_SUPPLY_CAPACITY=50 "POWER_SUPPLY_MODEL_NAME=RigTune fake battery" POWER_SUPPLY_MANUFACTURER=RigTune \
  POWER_SUPPLY_SERIAL_NUMBER=0 > "$ps/BAT0/uevent"
chmod 0666 "$ps/AC/uevent" "$ps/BAT0/uevent"
cat "$ps/BAT0/uevent"
