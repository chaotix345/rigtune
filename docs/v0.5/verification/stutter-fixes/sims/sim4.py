"""R12STUTTER fixes, simulated with the reviewer's own FixComparison model (sim.py):
- R12STUTTER-1: each capture's first SETTLE minutes are left out on BOTH sides (a join burst lives there);
- R12STUTTER-6: the before side is never the session that triggered the offer: it is a fresh session measured after the
  player chose to try the fix (baseline first), with no hitch floor of its own (only >= 5 minutes of compared play).
Acceptance: false LESS (no real change) at most about 5 % in every scenario of sim.py, sim2.py and sim3.py."""
from sim import *

SETTLE = 3  # minutes, SessionOutcome's settle span


def compared(minutes, mu, disp, rng, burst=None):
    """A session of `minutes` of compared play: SETTLE more minutes are played first (with the join burst, if any) and cut."""
    h, g, lost, bins = session(minutes + SETTLE, mu, disp, rng, burst)
    kept = bins[SETTLE:]
    hk = sum(kept)
    return hk, minutes * 60.0, lost * hk / h if h else 0.0, kept


def trial(bmin, amin, mu, disp, rng, burst_base=None, burst_after=None):
    hb, gb, lb, bb = compared(bmin, mu, disp, rng, burst_base)
    ha, ga, la, ba = compared(amin, mu, disp, rng, burst_after)
    return compare(hb, gb, lb, bb, ha, ga, la, ba)[0]


def rate(trials, *args, **kw):
    res = {'less': 0, 'same': 0, 'more': 0}
    for _ in range(trials):
        res[trial(*args, **kw)] += 1
    return {k: v / trials for k, v in res.items()}


if __name__ == '__main__':
    worst = {}
    rng = random.Random(12)
    print("H0 (sim.py's grid; the before side a fresh baseline, both sides cut by SETTLE)")
    for mu in (2, 5, 10, 30):
        for disp in (1, 2, 4):
            for bmin, amin in ((5, 5), (10, 10), (20, 20), (5, 20)):
                r = rate(3000, bmin, amin, mu, disp, rng)
                worst['H0'] = max(worst.get('H0', 0), r['less'])
                print(f"mu={mu:>3} disp={disp} before={bmin:>2}m after={amin:>2}m -> LESS {r['less']:.3f} MORE {r['more']:.3f}")
    rng = random.Random(7)
    print("sim2's grid: the baseline has its join burst, the after side none (a restarted or wrapped session) or its own")
    for mu in (2, 3, 5):
        for burst in ((8, 8, 6), (12, 10, 6), (20, 10, 5), (20, 10, 5, 3)):
            for bmin, amin in ((5, 5), (8, 8), (10, 10), (15, 15), (20, 20)):
                r = rate(3000, bmin, amin, mu, 1.5, rng, burst_base=burst)
                r2 = rate(3000, bmin, amin, mu, 1.5, rng, burst_base=burst, burst_after=burst)
                worst['sim2'] = max(worst.get('sim2', 0), r['less'], r2['less'])
                print(f"mu={mu} burst={burst} before={bmin:>2}m after={amin:>2}m  after w/o start: LESS {r['less']:.3f}   with its own: LESS {r2['less']:.3f}")
    rng = random.Random(3)
    print("sim3's grid: the trigger is selected (rate >= sel x mu, >= 8 hitches) and only decides the offer; the before side is the fresh baseline")
    for mu in (3, 5, 10):
        for disp in (1.5, 3):
            for sel in (1.0, 1.3, 1.6):
                for bmin in (5, 10):
                    res = {'less': 0, 'same': 0, 'more': 0}
                    used = 0
                    tries = 0
                    while used < 2000 and tries < 400000:
                        tries += 1
                        ht, gt, lt, bt = session(bmin + SETTLE, mu, disp, rng)
                        kept = bt[SETTLE:]
                        if sum(kept) < 8 or sum(kept) / bmin < sel * mu:
                            continue
                        res[trial(bmin, bmin, mu, disp, rng)] += 1
                        used += 1
                    less = res['less'] / max(1, used)
                    worst['sim3'] = max(worst.get('sim3', 0), less)
                    print(f"mu={mu:>2} disp={disp} sel>={sel} {bmin:>2}m vs {bmin:>2}m: LESS {less:.3f} (n={used})")
    print("worst false LESS per scenario:", {k: round(v, 3) for k, v in worst.items()})
