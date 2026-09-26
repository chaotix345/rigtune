# Table of benchmark (Measure) runs: RigTune's own result from benchmarks.json (by the run's record id) plus the driver's
# pooled raw frames (frames > 8 ms). Usage: python benchtable.py <scratch p5a dir> <instance> <measure key> <label>...
import json, os, statistics, sys

P, inst, key = sys.argv[1], sys.argv[2], sys.argv[3]
labels = sys.argv[4:]
bench = json.load(open(os.path.join(P, 'inst', 'p5a-inst-' + inst, 'config', 'rigtune', 'benchmarks.json'), encoding='utf-8'))
runs = {r['id']: r for r in bench['runs']}
print('| run | record id | RD | avg FPS | 1% low FPS | p99 ms | cv | pooled frames | frames > 8 ms | > 16.7 ms | max ms |')
print('|---|---|---|---|---|---|---|---|---|---|---|')
rows = []
for l in labels:
    f = os.path.join(P, 'out', 'p5a-%s.json' % l)
    if not os.path.exists(f):
        print('| %s | (missing) |||||||||' % l)
        continue
    m = json.load(open(f, encoding='utf-8'))['measure'][key]
    r = runs.get(m['recordId'], {})
    res = r.get('result') or {}
    raw = m['rawFrames']
    rd = r.get('knobs', {}).get('renderDistance', {}).get('value')
    rows.append((l, res.get('avgFps'), res.get('onePercentLowFps'), res.get('cv'), raw['over8ms']))
    print('| %s | %s | %s | %.0f | %.0f | %.2f | %.1f %% | %d | %d | %d | %.1f |' % (l, m['recordId'], rd, res.get('avgFps', 0), res.get('onePercentLowFps', 0),
                                                                         res.get('p99FrameMs', 0), 100 * (res.get('cv') or 0), raw['frames'], raw['over8ms'],
                                                                         raw['over16_7ms'], raw['maxMs']))
groups = {}
for l, avg, low, cv, o8 in rows:
    g = l.rsplit('-', 1)[0]
    groups.setdefault(g, []).append((avg, low, cv, o8))
print()
for g, v in groups.items():
    avgs = [x[0] for x in v]
    lows = [x[1] for x in v]
    print('%s: n=%d, avg FPS mean %.0f (min %.0f, max %.0f), 1%% low mean %.0f (min %.0f, max %.0f), frames>8ms %s, cv %s' % (
        g, len(v), statistics.mean(avgs), min(avgs), max(avgs), statistics.mean(lows), min(lows), max(lows), [x[3] for x in v],
        ['%.1f%%' % (100 * (x[2] or 0)) for x in v]))
