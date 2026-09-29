# The laptop run (KIT.md), 2026-09-29

The user's laptop: Lenovo IdeaPad Pro 5i Gen 10 (Intel Core Ultra 5 225H, 14 threads; integrated Intel Arc 130T; 31 GB
RAM; 2880x1800 at 120 Hz; Windows 11). It used the Modrinth App 0.21.6 and a fresh instance with Fabric API and the 0.5.0
release jar. Screenshots are kept locally.

| step | result |
|---|---|
| 2 first start | CPU "Intel Core Ultra 5 225H · 14 threads"; GPU "Intel(R) Arc(TM) 130T GPU (16GB) · 2.0 GB VRAM · OpenGL · GPU tier 2"; "Estimated tier 2/5 · lowest estimated component: GPU · Display 2880x1800". The "New? History… lets you undo each Apply." notice and the How Apply works page are correct. With the charger in: Max Framerate 120 → **110**. Mod suggestions are Modrinth App advice. PASS (the header has no separate CPU tier line; KIT.md's wording). |
| 3 one Apply | "Your first Apply" listed every change as Applied. PASS, with a count mismatch filed: "13 settings" vs "Applied 12 setting(s)"; the 13th is "Preset: fancy → custom". |
| 4 benchmark | 381 FPS average, 179 FPS 1 % low, P99 4.6 ms, 2 measurements, "noisy (10 % spread)", no stutter spikes, left out of the trend as the first run in a new benchmark world. PASS. The network toast showed a second time over the results (filed). |
| 5 battery | **PASS on real hardware.** Unplugged: after about 30-45 s, "Power source changed" and "You're on battery power. Switch to the Battery profile…" (Switch to Battery / Don't offer again). Switch → "Switched to Battery. Undo it in History."; Profiles: Battery Active. Plugged in: after about 30-45 s, "You're plugged in again. Switch back to My settings?" (no Don't offer again). Switch back → My settings Active. |
| 6 restart | The "New?" notice and "Your first Apply" didn't come back; the network toast didn't repeat. PASS. |
| 7 26.3 Vulkan | 26.3 starts on this laptop. "Prefer Vulkan (Experimental)" in Video Settings, then a restart → "… · 2.0 GB VRAM · **Vulkan** · GPU tier 2". PASS. |

Follow-ups filed as issues: the iGPU's "2.0 GB VRAM" (its name says 16GB of shared memory); KIT.md's wording (CPU tier
line, "preferred graphics API" is "Prefer Vulkan (Experimental)").
