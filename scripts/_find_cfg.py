import os, json

needle = b'com.eventchanger.quest.QuestModule'
roots = [
    'c:/Users/Bruno/Desktop/DarkOrbit',
]
hits = []
for root in roots:
    for dirpath, dirs, files in os.walk(root):
        # skip build/classes and jar internals
        if 'build' in dirpath or 'jar_extract' in dirpath or '.git' in dirpath:
            continue
        for f in files:
            if f.lower().endswith('.json'):
                p = os.path.join(dirpath, f)
                try:
                    c = open(p, 'rb').read()
                except Exception:
                    continue
                if needle in c:
                    hits.append(p)

for p in hits:
    print('===', p)
    try:
        c = json.load(open(p, encoding='utf-8'))
    except Exception as e:
        print('  (not valid json:', e, ')'); continue
    # navigate to plugin config
    node = c
    key = 'com.eventchanger.quest.QuestModule'
    if key in c:
        qf = c[key].get('questFlow', {})
        print('  questFlow.listItemX =', qf.get('listItemX'))
        print('  questFlow.listItemY =', qf.get('listItemY'))