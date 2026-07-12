import zipfile, subprocess, os, struct

DARKBOT = r'c:\Users\Bruno\Desktop\DarkOrbit\DarkBot.jar'
CFR = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\DmPlugin-2.1.10\tools\cfr-0.152.jar'
OUT = r'c:\Users\Bruno\Desktop\DarkOrbit\plugins\EventConfigChangerPlugin\core_decompiled'

z = zipfile.ZipFile(DARKBOT)

# Read raw bytes of Gui to find superclass name via simple constant pool scan
data = z.read('com/github/manolo8/darkbot/core/objects/Gui.class')
# Find all UTF8 strings (tag 1)
i = 10
count = struct.unpack('>H', data[8:10])[0]
strings = []
idx = 10
n = 1
while n < count and idx < len(data):
    tag = data[idx]; idx += 1
    if tag == 1:
        ln = struct.unpack('>H', data[idx:idx+2])[0]; idx += 2
        s = data[idx:idx+ln].decode('latin1', errors='ignore'); idx += ln
        strings.append(s)
    elif tag in (7,8,16,19,20):
        idx += 2
    elif tag in (3,4,15):
        idx += 4
    elif tag in (9,10,11,12,17,18):
        idx += 4
    elif tag in (5,6):
        idx += 8; n += 1
    elif tag in (21,22):
        idx += 2
    else:
        idx += 2
    n += 1

# superclass is the 2nd class entry in constant pool (after this_class)
# Find class entries (tag 7) - first is this_class, second is super_class
class_entries = [s for s in strings]  # not precise; just print candidates
print('Candidate class names in constant pool:')
for s in strings:
    if '.' in s and ('/' in s or s.startswith('com') or s.startswith('eu') or s.startswith('java') or s.startswith('org')):
        print('  ', s)

# Just decompile the whole core objects package to be safe
targets = ['com/github/manolo8/darkbot/core/objects/Gui.class']
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
for root, dirs, files in os.walk(OUT):
    for fn in files:
        if fn.endswith('.java'):
            print(os.path.join(root, fn))