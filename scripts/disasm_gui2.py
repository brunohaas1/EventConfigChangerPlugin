import zipfile, struct

z = zipfile.ZipFile(r'c:\Users\Bruno\Desktop\DarkOrbit\DarkBot.jar')

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

def find_method(data, cp, cp_end, target_name, target_desc):
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
    for _ in range(nm):
        pos += 2
        name_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        desc_idx = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        nattr = struct.unpack('>H', data[pos:pos+2])[0]; pos += 2
        code = None
        for _ in range(nattr):
            alen = struct.unpack('>I', data[pos+2:pos+6])[0]
            aname = utf(cp, struct.unpack('>H', data[pos:pos+2])[0])
            if aname == 'Code':
                code = data[pos+6:pos+6+alen]
            pos += 6 + alen
        if utf(cp, name_idx) == target_name and utf(cp, desc_idx) == target_desc:
            return code
    return None

def disasm(code, cp):
    if code is None:
        print("  no code")
        return
    clen = struct.unpack('>I', code[4:8])[0]
    bc = code[8:8+clen]
    pc = 0
    while pc < len(bc):
        op = bc[pc]
        if op == 0xb4:
            ref = struct.unpack('>H', bc[pc+1:pc+3])[0]
            fr = cp[ref]; nat = struct.unpack('>H', fr[2:4])[0]; nt = cp[nat]
            name = utf(cp, struct.unpack('>H', nt[0:2])[0])
            desc = utf(cp, struct.unpack('>H', nt[2:4])[0])
            print(f"  {pc:4d}: getfield {name}:{desc}")
            pc += 3
        elif op == 0xb6 or op == 0xb7:
            ref = struct.unpack('>H', bc[pc+1:pc+3])[0]
            mr = cp[ref]; nat = struct.unpack('>H', mr[2:4])[0]; nt = cp[nat]
            name = utf(cp, struct.unpack('>H', nt[0:2])[0])
            desc = utf(cp, struct.unpack('>H', nt[2:4])[0])
            print(f"  {pc:4d}: invoke {name}{desc}")
            pc += 3
        elif op == 0x2a:
            print(f"  {pc:4d}: aload_0"); pc += 1
        elif op in (0x1a, 0x1b, 0x1c, 0x1d):
            print(f"  {pc:4d}: iload_{op-0x1a}"); pc += 1
        elif op == 0x15:
            print(f"  {pc:4d}: iload_{bc[pc+1]}"); pc += 2
        elif op == 0x16:
            print(f"  {pc:4d}: lload_{bc[pc+1]}"); pc += 2
        elif op == 0x60:
            print(f"  {pc:4d}: iadd"); pc += 1
        elif op == 0x61:
            print(f"  {pc:4d}: ladd"); pc += 1
        elif op == 0xac:
            print(f"  {pc:4d}: ireturn"); pc += 1
        elif op == 0xad:
            print(f"  {pc:4d}: lreturn"); pc += 1
        elif op == 0xb1:
            print(f"  {pc:4d}: return"); pc += 1
        else:
            pc += 1

for n in ['com/github/manolo8/darkbot/core/objects/Gui.class']:
    data = z.read(n)
    cp, cp_end = parse_cp(data)
    print('===', n)
    for tname, tdesc in [('click', '(II)V'), ('getX', '()D'), ('getY', '()D'),
                         ('getWidth', '()D'), ('getHeight', '()D'),
                         ('getX2', '()D'), ('getY2', '()D')]:
        code = find_method(data, cp, cp_end, tname, tdesc)
        if code is not None:
            print(f'--- {tname}{tdesc}')
            disasm(code, cp)
        else:
            print(f'--- {tname}{tdesc} NOT FOUND')