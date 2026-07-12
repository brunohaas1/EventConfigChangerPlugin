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
        else:
            cp.append(b''); print("UNKNOWN TAG", tag)
        i += 1

    def utf(ci):
        v = cp[ci]
        return v.decode('latin1') if isinstance(v, bytes) else ''

    pos = idx
    # access, this, super
    pos += 6
    nint = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2 + nint*2
    nf = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    for _ in range(nf):
        pos += 6
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]; pos += 6 + alen
    nm = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
    methods = []
    for _ in range(nm):
        pos += 2
        name_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        desc_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        code = None
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]
            aname = utf(struct.unpack('>H', data[pos:pos+2])[0])
            if aname == 'Code':
                code = data[pos+6:pos+6+alen]
            pos += 6 + alen
        methods.append((utf(name_idx), utf(desc_idx), code))
    return methods

for n in ['eu/darkbot/api/game/other/Gui.class',
          'com/github/manolo8/darkbot/core/objects/Gui.class']:
    print('===', n)
    try:
        for m, d, code in parse(n):
            print('  ', m + d, ('  [has code %d bytes]' % len(code)) if code else '')
    except Exception as e:
        print('  ERR', e)