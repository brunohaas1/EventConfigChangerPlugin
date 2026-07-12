import zipfile, struct

JAR = r'c:\Users\Bruno\Desktop\DarkOrbit\DarkBot.jar'
z = zipfile.ZipFile(JAR)

def parse_cp(data):
    cp = [None]
    idx = 10
    count = struct.unpack('>H', data[8:10])[0]
    i = 1
    while i < count and idx < len(data):
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
    if ci is None or ci >= len(cp) or ci < 0:
        return ''
    v = cp[ci]
    return v.decode('latin1') if isinstance(v, bytes) else ''

def list_methods(name):
    try:
        data = z.read(name)
    except KeyError:
        print('MISSING', name)
        return
    cp, cp_end = parse_cp(data)
    # super class name
    pos = cp_end
    pos += 6
    nint = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2 + nint*2
    nf = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    for _ in range(nf):
        pos += 6
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
    # super class index
    super_idx = struct.unpack('>H', data[cp_end+4:cp_end+6])[0]
    print('===', name, 'extends', utf(cp, struct.unpack('>H', cp[super_idx][2:4])[0]) if super_idx else '?')
    nm = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    for _ in range(nm):
        pos += 2
        ni = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        di = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
        print('   ', utf(cp, ni) + utf(cp, di))

for n in ['com/github/manolo8/darkbot/core/objects/Gui.class',
          'eu/darkbot/api/game/other/Gui.class']:
    list_methods(n)