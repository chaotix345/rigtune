import re, sys, collections
probe, gclog, offset = sys.argv[1], sys.argv[2], float(sys.argv[3])
# gc log: for each GC(n), the times and phase kinds; each phase line carries the phase's end time, and its duration
phases = collections.defaultdict(list)
for line in open(gclog, encoding='utf-8'):
    m = re.match(r'\[(\d+\.\d+)s\] GC\((\d+)\) (.*)', line)
    if not m:
        continue
    t, n, rest = float(m.group(1)) * 1000, int(m.group(2)), m.group(3)
    d = re.search(r'(\d+\.\d+)ms\s*$', rest)
    dur = float(d.group(1)) if d else 0
    k = re.search(r'\((Young|Old|Global)\)', rest)
    kind = k.group(1) if k else ('Old' if 'old' in rest.lower() else None)
    phases[n].append((t - dur, t, kind, rest))
rows = []
for line in open(probe, encoding='utf-8'):
    if line.startswith('#') or '| Shenandoah Cycles' not in line:
        continue
    f = [x.strip() for x in line.split('|')]
    m = re.search(r'id=(\d+) start=(\d+) end=(\d+)', line)
    cid, start, end = int(m.group(1)), int(m.group(2)) + offset, int(m.group(3)) + offset
    old = re.search(r'Shenandoah Old Gen (\d+)->(\d+) MB', line)
    young = re.search(r'Shenandoah Young Gen (\d+)->(\d+) MB', line)
    # the log phases that ran inside this cycle's [start, end]
    kinds = collections.Counter()
    gcs = set()
    for n, ps in phases.items():
        for (s, e, kind, rest) in ps:
            if kind and s >= start - 5 and e <= end + 5:
                kinds[kind] += 1
                gcs.add(n)
    kind = '+'.join(sorted(kinds)) or '?'
    rows.append((cid, f[1] + ' | ' + f[2] + ' | ' + f[3], kind, sorted(gcs), old.groups() if old else None, young.groups() if young else None))
for r in rows:
    print(f"id={r[0]:3d} | {r[1]} | log: {r[2]:12s} GC{r[3]} | old {r[4][0]}->{r[4][1]} MB | young {r[5][0]}->{r[5][1]} MB")
print()
summary = collections.defaultdict(collections.Counter)
for r in rows:
    summary[r[2]][r[1]] += 1
for kind, c in summary.items():
    print(f"{kind}: " + '; '.join(f"{k} x{v}" for k, v in c.items()))
