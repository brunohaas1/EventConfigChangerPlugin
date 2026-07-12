import re
from pathlib import Path

root = Path(__file__).resolve().parent
infile = root / '..' / 'missions_npc_map.properties'
outfile = root / '..' / 'missions_npc_map_pt.properties'

replacements = [
    (r'\bComplete in order:?\b', 'Completar na ordem:'),
    (r'\bComplete in less than\b', 'Completar em menos de'),
    (r'\bTravel to\b', 'Vá para'),
    (r'\bTravel the target distance\b', 'Viajar a distância alvo'),
    (r'\bDestroy\b', 'Destruir'),
    (r'\bDamage\b', 'Danos em'),
    (r'\bBoss\b', 'Chefe'),
    (r'\bCollect\b', 'Coletar'),
    (r'\bCollect Cargo Boxes\b', 'Coletar Caixas de Carga'),
    (r'\bCollect Bonus Boxes\b', 'Coletar Caixas Bônus'),
    (r'\bSell\b', 'Vender'),
    (r'\bUse\b', 'Use'),
    (r'\bUpgrade\b', 'Atualizar'),
    (r'\bEnemy Players\b', 'Jogadores Inimigos'),
    (r'\bDestroy Enemy Players\b', 'Destruir Jogadores Inimigos'),
    (r'\bTravel to X-','Vá para X-'),
]

text = ''
if not infile.exists():
    print('Input file not found:', infile)
    raise SystemExit(1)

with infile.open('r', encoding='utf-8') as f:
    lines = f.readlines()

out = []
for ln in lines:
    if ln.strip().startswith('#') or '=' not in ln:
        out.append(ln)
        continue
    k,v = ln.split('=',1)
    s = v.strip()
    for pat, repl in replacements:
        s = re.sub(pat, repl, s, flags=re.IGNORECASE)
    out_line = k + '=' + s + '\n'
    out.append(out_line)

with outfile.open('w', encoding='utf-8') as f:
    f.writelines(out)

print('Wrote', outfile)
