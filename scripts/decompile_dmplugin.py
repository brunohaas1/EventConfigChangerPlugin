import zipfile, os, subprocess, shutil

DMPLUGIN = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\DmPlugin.jar'
CFR = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\DmPlugin-2.1.10\tools\cfr-0.152.jar'
OUT = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\EventConfigChangerPlugin\dmplugin_decompiled'

targets = [
    'com/deemeplus/modules/quest/QuestGiverModule.class',
    'com/deemeplus/modules/quest/QuestGiverMediator.class',
    'com/deemeplus/modules/quest/NormalQuestManager.class',
]

os.makedirs(OUT, exist_ok=True)
# extract only targets
with zipfile.ZipFile(DMPLUGIN) as z:
    for t in targets:
        try:
            data = z.read(t)
        except KeyError:
            print('MISSING', t)
            continue
        dest = os.path.join(OUT, t)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, 'wb') as f:
            f.write(data)
        print('extracted', t)

# run cfr on the extracted dir
cmd = ['java', '-jar', CFR, OUT, '--outputdir', OUT]
print('Running:', ' '.join(cmd))
r = subprocess.run(cmd, capture_output=True, text=True)
print('STDOUT:', r.stdout[:3000])
print('STDERR:', r.stderr[:3000])