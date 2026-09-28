# The battery flow with a simulated battery: evidence (WS-E, SPEC 3e)

Game test: `src/gametest/java/io/github/chaotix345/rigtune/gametest/BatteryFlowGameTest.java`. Design: docs/v0.5/design/ws-e.md.

## AC3e.1: the real PowerWatcher over a fake battery (every game-test leg)

A fake `PowerWatcher.Battery` feeds the real `PowerWatcher.start(List.of(fake), false, service::powerChanged, executor, 1)`: the watcher's own thread, its debounce, and polls every second. Each variant uses a fresh `profiles.json`.

- **Offer, take it, come back:**
  - Max FPS active, then unplugged. The Battery notice [switch, snooze] and the toast (`getToast(SystemToast.class, BATTERY_TOAST_ID)`) come within 5 s, and nothing switches.
  - Taking the offer makes Battery active (60 FPS cap).
  - Plugged in, the back-offer is "Switch back to Max FPS?" [switch] (PF-2: no snooze on it). Taking it restores Max FPS.
- **A wiggle:** one poll on battery, then AC. No edge, no notice, no toast.
- **No active profile (PF-1):** taking the offer saves My settings first and remembers it. The back-offer targets My settings, and taking it makes My settings active.
- **Snooze:** the next unplug, past the cooldown, is an edge but offers nothing.
- **The 10-minute cooldown** from a `lastPromptAt` fixture: 5 minutes ago → no offer; 11 minutes ago → the offer.
- **A running benchmark:** no offer.
- **A listener that throws on every edge:** the watcher keeps polling and delivers the next edge.

| run | where | result | the offer and its toast after each unplug |
|---|---|---|---|
| 2026-09-28 local | Windows 11, 26.2 | PASS, 32.6 s | 2021 / 2046 / 2055 / 1846 ms |
| [36363179808](https://github.com/chaotix345/rigtune/actions/runs/36363179808) | CI 26.2 OpenGL | PASS, 32.6 s | 2041 / 2048 / 2049 / 1849 ms |
| 36363179808 | CI 26.3 OpenGL | PASS, 32.7 s | 2042 / 2048 / 2049 / 1850 ms |
| 36363179808 | CI 26.3 Vulkan | PASS, 32.7 s | 2028 / 2048 / 2049 / 1849 ms |

The margin to the 5 s budget is about 3 s on every leg: two 1 s polls, then the render thread's toast.

## AC3e.2: the game's own OSHI on a tmpfs battery (e2e.yml release tier, `battery-oshi`)

`tools/e2e/fake_battery.sh Discharging` mounts a tmpfs over `/sys/class/power_supply` with an `AC` and a discharging `BAT0` uevent. The runner's kernel has no `test_power` module.

The production client runs:
- with `-Doshi.os.linux.allowudev=false`: OSHI's udev enumeration ignores the tmpfs, so OSHI reads the files only with udev off;
- with `-Drigtune.gametest.fakeBattery=<BAT0/uevent>`;
- on the caller's RigTune jar (`-PgametestModJar`, review M2);
- inside offline.sh's namespace.

The test:
- checks the startup probe;
- waits for `PowerWatcher.isRunning()`;
- rewrites `POWER_SUPPLY_STATUS` and waits for the game's watcher (30 s polls, two in a row) to report the change.

| run | node | result | startup probe | Charging seen | Discharging seen (offer + toast) | Charging seen |
|---|---|---|---|---|---|---|
| [36362848495](https://github.com/chaotix345/rigtune/actions/runs/36362848495) (scratch branch, review M3) | 26.2 | PASS, 175 s | `hasBattery=true, onBattery=true` | after 54 s | after 60 s | after 59 s |
| 36362848495 | 26.3 | PASS, 177 s | `hasBattery=true, onBattery=true` | after 56 s | after 60 s | after 59 s |

What the 26.2 row shows:
- `allowudev=false` took effect: the startup probe found the tmpfs battery, which OSHI can't see through udev.
- OSHI read the uevent again on each poll (`updateAttributes`): every rewritten STATUS reached the product.

## AC3e.3 (unit)

- PowerWatcherTest:
  - polls that read no battery aren't AC;
  - one of two batteries discharging is on battery;
  - a wiggle gives no edge;
  - `stop()` before the startup probe finishes starts nothing. The battery source is a seam, so this is red without the stop guard (review M1).
- BatteryPromptTest:
  - the PF-1 case (WS-P);
  - the cooldown at the 10-minute and the 1-minute clock-skew boundaries.

## Stays UNVERIFIED

- **Windows OSHI on a real laptop battery:** the user's laptop run (3g).
- **A real Linux laptop's udev enumeration:** `allowudev=false` moves OSHI's reads to sysfs files. The OSHI `PowerSource` fields are the same, but the enumeration path differs.
