import zipfile, subprocess, os

DARKBOT = r'c:\Users\Bruno\Desktop\DarkOrbit\DarkBot.jar'
CFR = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\DmPlugin-2.1.10\tools\cfr-0.152.jar'
OUT = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\EventConfigChangerPlugin\core_decompiled'

# First find superclass of core Gui
import struct
z = zipfile.ZipFile(DARKBOT)
data = z.read('com/github/manolo8/darkbot/core/objects/Gui.class')

def parse_cp(d):
    cp = [None]; idx = 10; count = struct.unpack('>H', d[8:10])[0]; i = 1
    while i < count and idx < len(d):
        tag = d[idx]; idx += 1
        try:
            if tag == 1:
                ln = struct.unpack('>H', d[idx:idx+2])[0]; idx += 2; cp.append(d[idx:idx+ln]); idx += ln
            elif tag in (7,8,16,19,20): cp.append(d[idx:idx+2]); idx += 2
            elif tag in (3,4,15): cp.append(d[idx:idx+4]); idx += 4
            elif tag in (9,10,11,12,17,18): cp.append(d[idx:idx+4]); idx += 4
            elif tag in (5,6): cp.append(d[idx:idx+8]); idx += 8; i += 1
            elif tag in (21,22): cp.append(d[idx:idx+2]); idx += 2
            else: cp.append(b''); idx += 2
        except Exception: cp.append(b''); idx += 2
        i += 1
    return cp

cp = parse_cp(data)
# this_class at 2, super at 4
this_idx = struct.unpack('>H', data[2:4])[0]
super_idx = struct.unpack('>H', data[4:6])[0]
def utf(ci):
    v = cp[ci]; return v.decode('latin1') if isinstance(v, bytes) else ''
print('Gui this:', utf(this_idx))
print('Gui super:', utf(super_idx))

# Decompile the Gui class and its superclass
targets = ['com/github/manolo8/darkbot/core/objects/Gui.class']
# also need superclass - find its path
super_name = utf(super_idx).replace('.', '/') + '.class'
print('Super class file:', super_name)
if super_name in z.namelist():
    targets.append(super_name)

os.makedirs(OUT, exist_ok=True)
for t in targets:
    d = z.read(t)
    dest = os.path.join(OUT, t)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    with open(dest, 'wb') as f:
        f.write(d)

cmd = ['java', '-jar', CFR, OUT, '--outputdir', OUT, '--silent', 'true']
print('Running CFR...')
r = subprocess.run(cmd, capture_output=True, text=True)
print('STDERR:', r.stderr[:2000])
# list output
for root, dirs, files in os.walk(OUT):
    for fn in files:
        if fn.endswith('.java'):
            print(os.path.join(root, fn))