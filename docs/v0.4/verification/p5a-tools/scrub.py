# Replaces the scratch-dir and user-home paths in committed evidence text files with placeholders.
import os, re, sys
root = sys.argv[1]
pats = [
    (re.compile(r'C:.Users.Admin.AppData.Local.Temp.claude.C--Dev-Minecraft-Setting-Optimisation-Mod.32b9ff53-5b6c-44d7-bc00-195e4f1a3166.scratchpad.p5a', re.I), '<scratch>'),
    (re.compile(r'C:.Users.Admin', re.I), '~'),
]
n = 0
for sub in sys.argv[2:]:
    for dp, dn, fn in os.walk(os.path.join(root, sub)):
        for f in fn:
            if not f.endswith(('.txt', '.json', '.md', '.log')):
                continue
            p = os.path.join(dp, f)
            s = open(p, encoding='utf-8', errors='replace').read()
            t = s
            for rx, rep in pats:
                t = rx.sub(rep, t)
            if t != s:
                open(p, 'w', encoding='utf-8', newline='\n').write(t)
                n += 1
print('scrubbed', n, 'files')
