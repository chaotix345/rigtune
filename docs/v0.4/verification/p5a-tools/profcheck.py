# AC4.13: for each switch, the Preview rows logged just before it (key: old -> new) against the settings read after the
# next restart (SettingsBridge.read). Usage: python profcheck.py <scratch p5a dir> <out md>
import json, os, re, sys

P, OUT = sys.argv[1], sys.argv[2]


def load(label):
    f = os.path.join(P, 'out', 'p5a-%s.json' % label)
    return json.load(open(f, encoding='utf-8')) if os.path.exists(f) else None


def rows(preview):
    out = {}
    for part in ('now', 'atRestart'):
        for line in preview.get(part, []):
            m = re.match(r'(\S+): (.*?) -> (.*)$', line)
            if m:
                out[m.group(1)] = (m.group(2), m.group(3), part)
    return out


STEPS = [  # (template, run that previewed + switched, preview label, run that read the settings after the restart, settings label)
    ('Max FPS', 'profB', 'Max FPS', 'profC', 'after-maxfps'),
    ('Battery', 'profC', 'Battery', 'profD', 'after-battery'),
    ('Recording', 'profD', 'Recording', 'profE', 'after-recording'),
    ('My settings', 'profE', 'My settings', 'profF', 'after-mysettings'),
]
md = []
for name, prun, plabel, srun, slabel in STEPS:
    p, s = load(prun), load(srun)
    if not p or not s:
        md.append('## %s\n(missing data: %s / %s)\n\n' % (name, prun, srun))
        continue
    pr = rows(p['preview'][plabel])
    settings = s['settings'][slabel]
    ok = bad = 0
    lines = []
    for key, (old, new, part) in sorted(pr.items()):
        got = settings.get(key)
        match = got is not None and str(got) == str(new)
        ok += match
        bad += not match
        lines.append('| %s | %s | %s | %s | %s | %s |\n' % (key, part, old, new, got, 'ok' if match else '**MISMATCH**'))
    md.append('## %s: switch in %s, checked after the restart in %s: %d of %d keys match%s\n\n' % (name, prun, srun, ok, ok + bad,
                                                                                                  '' if bad == 0 else ', %d MISMATCH' % bad))
    md.append('switch message: %s\n\n' % p['switch'][plabel])
    md.append('| key | applied | before | template value | after restart | |\n|---|---|---|---|---|---|\n')
    md.extend(lines)
    md.append('\n')
open(OUT, 'w', encoding='utf-8', newline='\n').write(''.join(md))
print(''.join(md))
