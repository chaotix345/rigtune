from sim import *
rng = random.Random(3)
print("selection: before kept only if its rate >= sel x mu (regression to the mean, STUTTER-11)")
for mu in (3, 5, 10):
    for disp in (1.5, 3):
        for sel in (1.0, 1.3, 1.6):
            for bmin in (5, 10):
                res = {'less': 0, 'same': 0, 'more': 0}; used = 0; tries = 0
                while used < 2000 and tries < 400000:
                    tries += 1
                    hb, gb, lb, bb = session(bmin, mu, disp, rng)
                    if hb < 8 or hb / bmin < sel * mu: continue
                    ha, ga, la, ba = session(bmin, mu, disp, rng)
                    k, _, _ = compare(hb, gb, lb, bb, ha, ga, la, ba); res[k] += 1; used += 1
                print(f"mu={mu:>2} disp={disp} sel>={sel} {bmin:>2}m vs {bmin:>2}m: LESS {res['less']/max(1,used):.3f} (n={used})")
