# AC9.8 notice check: copy awareness.json aside, then make the stored fingerprint's driver an older Adrenalin build.
import json, re, shutil
p = 'C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a/inst/p5a-inst-aware262/config/rigtune/awareness.json'
shutil.copy(p, p + '.before-edit')
d = json.load(open(p, encoding='utf-8'))
raw = d['fingerprint']['gpuDriverRaw']
new = re.sub(r'Context \d+\.\d+\.\d+\.\d+', 'Context 26.5.1.260501', raw)
if new == raw:
    new = raw + ' EDITED'
d['fingerprint']['gpuDriverRaw'] = new
open(p, 'w', encoding='utf-8', newline='\n').write(json.dumps(d, indent=2))
print('gpuDriverRaw:', raw, '->', new)
