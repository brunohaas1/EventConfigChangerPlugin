import os, json

needle = b'com.eventchanger.quest.QuestModule'
root = 'c:/Users/Bruno/Desktop/DarkOrbit'
keys = ('listItemX', 'listItemY', 'acceptButtonX', 'acceptButtonY')
for dirpath, dirs, files in os.walk(root):
    if 'build' in dirpath or 'jar_extract' in dirpath or '.git' in dirpath:
        continue
    for f in files:
        if not f.lower().endswith('.json'):
            continue
        p = os.path.join(dirpath, f)
        try:
            c = open(p, 'rb').read()
        except Exception:
            continue
        if needle not in c:
            continue
        try:
            data = json.loads(c)
        except Exception as e:
            print(p, '-> invalid json', e)
            continue

        def walk(o, path=''):
            if isinstance(o, dict):
                for k, v in o.items():
                    if k in keys:
                        print(p, path + '/' + k, '=', v)
                    walk(v, path + '/' + k)
            elif isinstance(o, list):
                for i, v in enumerate(o):
                    walk(v, path + '[' + str(i) + ']')

        walk(data)