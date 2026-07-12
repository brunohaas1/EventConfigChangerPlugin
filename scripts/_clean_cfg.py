import os, json

needle = b'com.eventchanger.quest.QuestModule'
root = 'c:/Users/Bruno/Desktop/DarkOrbit'
keys = ('listItemX', 'listItemY', 'acceptButtonX', 'acceptButtonY')
changed = []

for dirpath, dirs, files in os.walk(root):
    if 'build' in dirpath or 'jar_extract' in dirpath or '.git' in dirpath:
        continue
    for f in files:
        if not f.lower().endswith('.json'):
            continue
        p = os.path.join(dirpath, f)
        try:
            raw = open(p, 'rb').read()
        except Exception:
            continue
        if needle not in raw:
            continue
        try:
            data = json.loads(raw)
        except Exception as e:
            print('SKIP (invalid json)', p, e)
            continue

        mod = False

        def clean(o):
            global mod
            if isinstance(o, dict):
                for k in list(o.keys()):
                    if k in keys:
                        del o[k]
                        mod = True
                        print('  removed', k, 'from', p)
                    else:
                        clean(o[k])
            elif isinstance(o, list):
                for v in o:
                    clean(v)

        clean(data)
        if mod:
            with open(p, 'w', encoding='utf-8') as fh:
                json.dump(data, fh, indent=2, ensure_ascii=False)
            changed.append(p)

print('Files modified:', changed)