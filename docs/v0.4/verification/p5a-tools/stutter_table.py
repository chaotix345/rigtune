# Summarises the AC5.8 runs: one row per run from the driver's JSON (stutterReport) or, for the product's teleport
# script (C1/C3), from the stutter.json it logs. Also cross-checks GC-claimed spikes against -Xlog:gc pauses when a
# GC log exists. Usage: python stutter_table.py <scratch p5a dir> <out md>
import json, os, re, sys
from datetime import datetime

P = sys.argv[1]
OUT = sys.argv[2]
LOGS = os.path.join(P, 'logs')


def load_driver(label, key):
    f = os.path.join(P, 'out', 'p5a-%s.json' % label)
    if not os.path.exists(f):
        return None
    d = json.load(open(f, encoding='utf-8'))
    return d.get('stutterReport', {}).get(key)


def load_script_session(label):
    f = os.path.join(LOGS, label + '.latest.log')
    if not os.path.exists(f):
        return None
    text = open(f, encoding='utf-8', errors='replace').read()
    m = re.search(r'Dev stutter: \S*stutter\.json:\n(\{.*?\n\})\n', text, re.S)
    if not m:
        return None
    d = json.loads(m.group(1))
    return d['sessions'][-1]


def wall(line):
    m = re.match(r'\[(\d\d):(\d\d):(\d\d)\]', line)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) if m else None


def gc_pauses(label):
    """(uptime_s, ms) of every pause in the -Xlog file, plus the JVM start in log wall-clock seconds and the capture start."""
    g = os.path.join(LOGS, label + '-gc.log')
    lg = os.path.join(LOGS, label + '.latest.log')
    if not (os.path.exists(g) and os.path.exists(lg)):
        return None
    pauses = []
    for line in open(g, encoding='utf-8', errors='replace'):
        m = re.match(r'\[(\d+\.\d+)s\].*\[gc\s*\] GC\(\d+\) (Pause [^\n]*?) (\d+\.\d+)ms', line)
        if m:
            pauses.append((float(m.group(1)), float(m.group(3)), m.group(2)))
    start = cap = None
    for line in open(lg, encoding='utf-8', errors='replace'):
        m = re.search(r'title screen at JVM uptime (\d+) ms', line)
        if m and start is None:
            start = wall(line) - int(m.group(1)) / 1000.0
        if 'Stutter Doctor: capture on' in line and cap is None:
            cap = wall(line)
    return pauses, start, cap


def row(name, what, r):
    if r is None:
        return '| %s | %s | (no data) |||||||\n' % (name, what)
    sp = r['spikes']
    total = sp['minor'] + sp['major'] + sp['severe'] + sp['freeze']
    gp = r['gameplaySeconds']
    rate = total / (gp / 60.0) if gp else 0
    causes = ', '.join('%s %d%%' % (k, round(v * 100)) for k, v in r['causes'].items()) or '-'
    tags = ', '.join('%s %s' % (k, v) for k, v in r['tags'].items()) or '-'
    f = r['facts']
    facts = 'gcOffset %s ms, explicit %s, full %s, stalls %s, liveSet %s%%' % (f.get('gcOffsetMs'), f.get('explicitGcs'), f.get('fullGcs'),
                                                                         f.get('stalls'), f.get('liveSetPct'))
    return '| %s | %s | %.0f s / %d frames / %.0f FPS / 1%% low %.0f | %d (%d hitches), %.1f/min | %.0f ms | %s | %s | %s | %s | phase %s, enough %s |\n' % (
        name, what, gp, r['frames'], r['avgFps'], r['onePercentLowFps'], total, r.get('hitches', 0), rate, r['lostMs'], causes, tags,
        ', '.join(r.get('advice', [])) or '-', facts, r['phaseTiming'], r['enoughData'])


RUNS = [
    ('A', 'stutA', 'A', 'control, G1 -Xmx4G, stand still 6 min; default options (VSync on, maxFps 120, inactivity AFK)'),
    ('A2', 'stutA2', 'A2', 'control, G1 -Xmx4G, stand still 150 s; VSync off, maxFps 260, inactivity minimized'),
    ('A3', 'stutA3', 'A3', 'control, G1 -Xmx4G, stand still 6.5 min (autosave window); VSync off, uncapped'),
    ('B', 'stutB', 'B', 'forceGcEverySec=5, G1 -Xmx4G, 150 s; default options'),
    ('C1', 'stutC1', None, 'product teleport script (-Drigtune.dev.stutterScript=teleport); default options'),
    ('C2', 'stutC2', 'C2', 'driver: 100 s still, tp, 30 s, save, 10 s; default options (AFK throttle)'),
    ('C3', 'stutC3', None, 'product teleport script; VSync off, uncapped, inactivity minimized'),
    ('C4', 'stutC4', 'C4', 'driver: 100 s still, tp, 30 s, save, 10 s; VSync off, uncapped'),
    ('D', 'stutD', 'D', 'ZGC + forceGcEverySec=5, -Xmx4G, 150 s; default options'),
    ('D2', 'stutD2', 'D2', 'ZGC + forceGcEverySec=5, -Xmx4G, 150 s; VSync off, uncapped, -Xlog:gc*'),
    ('SM1', 'stutSM1', 'SM1', 'S-M1 attempt 1 (@Redirect test mixin)'),
    ('SM1b', 'stutSM1b', 'SM1b', 'S-M1 attempt 2 (mixin-config plugin removes the INVOKE target)'),
]

out = ['| run | setup | gameplay / frames / avg / 1% low | spikes (hitches), per min | lost | causes (share of lost time) | tags | advice | facts | phase timing, enough data |\n',
       '|---|---|---|---|---|---|---|---|---|---|\n']
worst = []
for name, label, key, what in RUNS:
    r = load_driver(label, key) if key else load_script_session(label)
    out.append(row(name, what, r))
    if r:
        worst.append('\n**%s** worst spikes (t s, ms, baseline ms, notes):\n' % name)
        for w in r['worst']:
            worst.append('- %.1f s, %.1f ms (base %.1f): %s\n' % (w['t'], w['ms'], w['baseMs'], ', '.join(w['causes'])))
    g = gc_pauses(label)
    if r and g and g[1] is not None and g[2] is not None:
        pauses, start, cap = g
        cap_up = cap - start
        claimed = [w for w in r['worst'] if any(c.startswith('gc:') for c in w['causes'])]
        worst.append('- GC log: %d pauses in the JVM, longest %.1f ms; capture started at JVM uptime ~%.0f s (1 s log precision)\n' % (
            len(pauses), max([p[1] for p in pauses] or [0]), cap_up))
        for w in claimed:
            near = [p for p in pauses if abs((p[0] - cap_up) - w['t']) <= 2.0]
            worst.append('  - GC-claimed spike at %.1f s: -Xlog pauses within 2 s: %s\n' % (
                w['t'], ', '.join('%.3f s %.2f ms %s' % (p[0] - cap_up, p[1], p[2]) for p in near) or 'NONE'))
open(OUT, 'w', encoding='utf-8', newline='\n').write(''.join(out) + ''.join(worst))
print(''.join(out))
