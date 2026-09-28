import math, random
from decimal import Decimal, ROUND_HALF_UP

def cdf(k, n, logp, logq):
    if k < 0: return 0.0
    if k >= n: return 1.0
    # log pmf recursion
    lp = n * logq
    terms = [lp]
    for i in range(k):
        lp = lp + math.log((n - i) / (i + 1)) + logp - logq
        terms.append(lp)
    m = max(terms)
    return min(1.0, math.exp(m) * sum(math.exp(t - m) for t in terms))

def side(bins):
    n = len(bins); s = sum(bins); mean = s / n
    var = sum((b - mean) ** 2 for b in bins) / (n - 1) if n > 1 else 0
    return n, mean, var

def phi(sb, sa):
    m2 = 0; df = 0; tot = 0; nb = 0
    for n, mean, var in (sb, sa):
        if n >= 3:
            m2 += var * (n - 1); df += n - 1; tot += mean * n; nb += n
    if df == 0 or tot <= 0: return 1.0
    return max(1.0, m2 / df / (tot / nb))

def eff(h, ph):
    return int((Decimal(h) / Decimal(ph)).quantize(Decimal(1), rounding=ROUND_HALF_UP))

def compare(hb, gb, lb, bb, ha, ga, la, ba):
    ph = phi(side(bb), side(ba))
    e0, e1 = eff(hb, ph), eff(ha, ph)
    n = e0 + e1
    lp = math.log(ga / (gb + ga)); lq = math.log(gb / (gb + ga))
    pl = cdf(e1, n, lp, lq)
    pm = cdf(n - e1, n, lq, lp)
    rb, ra = hb * 60 / gb, ha * 60 / ga
    lrb, lra = lb * 60 / gb, la * 60 / ga
    if ra * 3 <= rb * 2 and pl <= 0.05 and lra <= lrb: return 'less', pl, ph
    if ra * 2 >= rb * 3 and pm <= 0.05: return 'more', pm, ph
    return 'same', pl, ph

def nb(mu, disp, rng):
    # negative binomial with mean mu, variance disp*mu (disp>1), via gamma-poisson
    if disp <= 1.0001:
        return poisson(mu, rng)
    k = mu / (disp - 1)
    lam = rng.gammavariate(k, (disp - 1))
    return poisson(lam, rng)

def poisson(lam, rng):
    if lam < 30:
        L = math.exp(-lam); k = 0; p = 1.0
        while True:
            p *= rng.random()
            if p <= L: return k
            k += 1
    return max(0, int(round(rng.gauss(lam, math.sqrt(lam)))))

def session(minutes, mu, disp, rng, burst=None):
    bins = [nb(mu, disp, rng) for _ in range(minutes)]
    if burst:
        for i, extra in enumerate(burst):
            if i < minutes: bins[i] += poisson(extra, rng)
    h = sum(bins)
    lost = sum(rng.expovariate(1 / 60.0) for _ in range(h))  # ~60 ms lost per hitch
    return h, minutes * 60.0, lost, bins

def run(trials, bmin, amin, mu, disp, rng, burst_before=None, burst_after=None, floor=True):
    res = {'less': 0, 'same': 0, 'more': 0}; used = 0
    for _ in range(trials):
        hb, gb, lb, bb = session(bmin, mu, disp, rng, burst_before)
        if floor and hb < 8: continue
        ha, ga, la, ba = session(amin, mu, disp, rng, burst_after)
        k, _, _ = compare(hb, gb, lb, bb, ha, ga, la, ba)
        res[k] += 1; used += 1
    return {k: round(v / max(1, used), 4) for k, v in res.items()}, used

if __name__ == '__main__':
    rng = random.Random(12)
    print("H0 (no burst), false LESS/MORE rates")
    for mu in (2, 5, 10, 30):
        for disp in (1, 2, 4):
            for bmin, amin in ((5, 5), (10, 10), (20, 20), (5, 20)):
                r, used = run(3000, bmin, amin, mu, disp, rng)
                print(f"mu={mu:>3} disp={disp} before={bmin:>2}m after={amin:>2}m -> {r} (n={used})")
