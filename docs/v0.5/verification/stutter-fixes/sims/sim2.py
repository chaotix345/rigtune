from sim import *
rng = random.Random(7)
print("(b) session-start burst: before unwrapped includes a join burst over its first 3 min; after is a wrapped tail (no start) or a restarted session")
for mu in (2, 3, 5):
    for burst in ((8, 8, 6), (12, 10, 6), (20, 10, 5)):
        for bmin, amin in ((5, 5), (8, 8), (10, 10), (15, 15), (20, 20)):
            r, used = run(3000, bmin, amin, mu, 1.5, rng, burst_before=burst)
            r2, _ = run(3000, bmin, amin, mu, 1.5, rng, burst_before=burst, burst_after=burst)
            print(f"mu={mu} burst={burst} before={bmin:>2}m after={amin:>2}m  after w/o start: LESS {r['less']:.3f}   after with its own start: LESS {r2['less']:.3f}")
