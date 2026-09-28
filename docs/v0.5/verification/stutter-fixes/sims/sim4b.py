"""sim4's selection grid (sim3's cells) with more samples: the trigger only decides the offer, the before side is a fresh
baseline, so false LESS should match the unselected rate. used up to 4000 per cell, tries up to 4,000,000."""
from sim4 import *
rng = random.Random(33)
worst = 0
for mu in (3, 5, 10):
    for disp in (1.5, 3):
        for sel in (1.0, 1.3, 1.6):
            for bmin in (5, 10):
                res = {'less': 0, 'same': 0, 'more': 0}; used = 0; tries = 0
                while used < 4000 and tries < 4000000:
                    tries += 1
                    ht, gt, lt, bt = session(bmin + SETTLE, mu, disp, rng)
                    kept = bt[SETTLE:]
                    if sum(kept) < 8 or sum(kept) / bmin < sel * mu:
                        continue
                    res[trial(bmin, bmin, mu, disp, rng)] += 1; used += 1
                less = res['less'] / max(1, used); worst = max(worst, less)
                print(f"mu={mu:>2} disp={disp} sel>={sel} {bmin:>2}m vs {bmin:>2}m: LESS {less:.3f} (n={used})", flush=True)
print("worst", round(worst, 3))
