#!/usr/bin/env python3
import re
from pathlib import Path

texto_path = Path('c:/Users/Bruno/Desktop/texto.txt')
output_file = Path('c:/Users/Bruno/Desktop/DarkOrbit/plugins/missions_npc_map_pt.properties')

if not texto_path.exists():
    print(f"Error: {texto_path} not found")
    exit(1)

with open(texto_path, 'r', encoding='utf-8') as f:
    content = f.read()

missions = {}

# Extract NAME \t DESCRIPTION patterns where DESCRIPTION starts with action verbs
pattern = r'([A-ZÀ-ÿ][A-Za-z0-9À-ÿ\s\-\.\!\?\'`]+?)\t((?:Destruir|Destrua|Colete|Coletar|Complete|Permaneça|Viaje|Cause|Deixe|Use|Aguente|Roube|Percorra|Venda|Elimine|Danif|Conclua|Artesanato|Você)[^\t]*(?:\n(?!\w)[^\t\n]*)*)'

for match in re.finditer(pattern, content, re.MULTILINE):
    nome_raw = match.group(1).strip()
    desc_raw = match.group(2).strip()
    
    # Filter out header rows and invalid entries
    if (len(nome_raw) > 2 and 
        len(desc_raw) > 5 and
        not any(x in nome_raw for x in ['REQUISITOS', 'NOME', 'DESCRIÇÃO', 'RECOMPENSA', 'Duration', '11 months', 'DIFICULDADE', '–']) and
        not nome_raw[0].isdigit() and
        'EXP' not in desc_raw[:30]):
        
        # Normalize key
        key = nome_raw.lower()
        key = re.sub('[^a-z0-9 ]', ' ', key)
        key = re.sub(r'\s+', ' ', key).strip()
        
        # Normalize description
        desc = ' '.join(desc_raw.split())
        desc = desc[:600]  # Limit length
        
        if key and key not in missions:
            missions[key] = desc

print(f"Extracted {len(missions)} missions")

# Write output
with open(output_file, 'w', encoding='utf-8') as f:
    f.write('# Auto-generated mission -> description mapping (PT manual - full extraction)\n')
    for k in sorted(missions.keys()):
        f.write(f'{k}={missions[k]}\n')

print(f"Wrote {len(missions)} entries to {output_file}")
