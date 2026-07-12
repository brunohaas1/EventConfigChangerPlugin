import zipfile, struct, sys

z = zipfile.ZipFile(r'c:\Users\Bruno\Desktop\DarkOrbit\DarkBot.jar')

def parse(name):
    data = z.read(name)
    cp = [None]
    idx = 10
    count = struct.unpack('>H', data[8:10])[0]
    i = 1
    while i < count:
        tag = data[idx]; idx += 1
        if tag in (7, 8, 9, 10, 11, 12):
            sz = 4 if tag in (9, 10, 11, 12) else 2
            cp.append(data[idx:idx+sz]); idx += sz
        elif tag in (3, 4):
            cp.append(data[idx:idx+4]); idx += 4
        elif tag in (5, 6):
            cp.append(data[idx:idx+8]); idx += 8; i += 1
        elif tag == 1:
            ln = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
            cp.append(data[idx:idx+ln]); idx += ln
        else:
            cp.append(b'')
        i += 1
    pos = idx + 6
    nint = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2 + nint*2
    nf = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    for _ in range(nf):
        pos += 6
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
    nm = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    out = []
    for _ in range(nm):
        pos += 2
        name_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        desc_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
        n = cp[name_idx].decode('latin1') if isinstance(cp[name_idx], bytes) else ''
        d = cp[desc_idx].decode('latin1') if isinstance(cp[desc_idx], bytes) else ''
        out.append(n + d)
    return out

def strings(name):
    data = z.read(name)
    out = []
    i = 0
    while i < len(data) - 2:
        if data[i] == 1:
            ln = struct.unpack('>H', data[i+1:i+3])[0]
            s = data[i+3:i+3+ln]
            try:
                t = s.decode('latin1')
                if any(c.isalpha() for c in t) and len(t) > 2:
                    out.append(t)
            except: pass
            i += 3 + ln
        else:
            i += 1
    return out

targets = [
    'eu/darkbot/api/game/other/Gui.class',
    'com/github/manolo8/darkbot/core/objects/Gui.class',
    'com/github/manolo8/darkbot/core/objects/facades/QuestProxy$QuestListItem.class',
    'com/github/manolo8/darkbot/core/objects/facades/QuestProxy$Quest.class',
]
for n in targets:
    print('=== METHODS', n)
    try:
        for m in parse(n):
            print('  ', m)
    except Exception as e:
        print('  ERR', e)
    print('=== STRINGS', n)
    try:
        for s in strings(n)[:60]:
            print('  ', repr(s))
    except Exception as e:
