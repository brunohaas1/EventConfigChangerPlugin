import zipfile, struct

z = zipfile.ZipFile(r'c:\Users\Bruno\Desktop\DarkOrbit\DarkBot.jar')

def parse_cp(data):
    cp = [None]
    idx = 10
    count = struct.unpack('>H', data[8:10])[0]
    i = 1
    while i < count:
        if idx >= len(data):
            break
        tag = data[idx]; idx += 1
        try:
            if tag == 1:
                ln = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
                cp.append(data[idx:idx+ln]); idx += ln
            elif tag in (7, 8, 16, 19, 20):
                cp.append(data[idx:idx+2]); idx += 2
            elif tag in (3, 4, 15):
                cp.append(data[idx:idx+4]); idx += 4
            elif tag in (9, 10, 11, 12, 17, 18):
                cp.append(data[idx:idx+4]); idx += 4
            elif tag in (5, 6):
                cp.append(data[idx:idx+8]); idx += 8; i += 1
            elif tag in (21, 22):
                cp.append(data[idx:idx+2]); idx += 2
            else:
                cp.append(b''); idx += 2
        except Exception:
            cp.append(b''); idx += 2
        i += 1
    return cp, idx

def utf(cp, ci):
    try:
        v = cp[ci]
    except Exception:
        return ''
    return v.decode('latin1') if isinstance(v, bytes) else ''

def list_methods(name, only=None):
    try:
        data = z.read(name)
    except KeyError:
        print('MISSING', name); return
    cp, cp_end = parse_cp(data)
    pos = cp_end
    pos += 6
    nint = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2 + nint*2
    nf = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    for _ in range(nf):
        pos += 6
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
    nm = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    print('===', name)
    for _ in range(nm):
        pos += 2
        name_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        desc_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
        m = utf(cp, name_idx)
        d = utf(cp, desc_idx)
        if only is None or m in only:
            print('   ', m + d)

targets = [
    'eu/darkbot/api/game/other/Gui.class',
    'eu/darkbot/api/managers/GameScreenAPI.class',
    'eu/darkbot/api/DarkInput.class',
    'eu/darkbot/api/game/entities/Entity.class',
    'eu/darkbot/api/game/entities/Station.class',
    'eu/darkbot/api/managers/QuestAPI.class',
]
for t in targets:
    list_methods(t)