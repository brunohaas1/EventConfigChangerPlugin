#!/usr/bin/env python3
# Simple scraper to collect mission titles and kill targets from darkorbitwiki.com/missions/
# Writes entries to missions_npc_map.properties in project root.

import re
import sys
import urllib.request
import urllib.parse
from html import unescape

BASE = 'https://darkorbitwiki.com'
INDEX = BASE + '/missions/'
OUT = 'missions_npc_map.properties'
OUT_PT = 'missions_npc_map_pt.properties'

# runtime language flag for fetch
LANG = 'en'

seen = set()
entries = {}

def fetch(url):
    try:
        headers = {'User-Agent': 'Mozilla/5.0'}
        if LANG and LANG.startswith('pt'):
            headers['Accept-Language'] = 'pt'
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.read().decode('utf-8', errors='ignore')
    except Exception as e:
        print('fetch error', url, e)
        return ''

def find_links(html):
    links = set()
    for m in re.finditer(r'href=["\']([^"\']+)["\']', html, re.IGNORECASE):
        href = m.group(1)
        if href.startswith('/missions') or href.startswith(BASE + '/missions'):
            if href.startswith('/'): href = BASE + href
            links.add(href.split('#')[0])
    return links

def extract_title(html):
    m = re.search(r'<h1[^>]*>(.*?)</h1>', html, re.IGNORECASE|re.DOTALL)
    if m:
        return unescape(re.sub('<[^<]+?>', '', m.group(1))).strip()
    m = re.search(r'<title[^>]*>(.*?)</title>', html, re.IGNORECASE|re.DOTALL)
    if m:
        return unescape(re.sub('<[^<]+?>', '', m.group(1))).strip()
    return None

def extract_requirements(html):
    # Try headings named Requirement/Requirements/Objectives
    for heading in [r'Requirements', r'Requirement', r'Objectives', r'Objective', r'Targets', r'Target']:
        m = re.search(r'<h[0-6][^>]*>\s*'+heading+r'\s*</h[0-6]>\s*(?:<p[^>]*>(.*?)</p>|<ul[^>]*>(.*?)</ul>)', html, re.IGNORECASE|re.DOTALL)
        if m:
            txt = (m.group(1) or m.group(2) or '').strip()
            txt = re.sub('<[^<]+?>', '', txt)
            if txt:
                return unescape(txt)
    # Fallback: find paragraphs with words like kill, destroy, collect, destroy
    cand = []
    for m in re.finditer(r'<p[^>]*>(.*?)</p>', html, re.IGNORECASE|re.DOTALL):
        t = re.sub('<[^<]+?>', '', m.group(1))
        if re.search(r'kill|destroy|collect|destrói|destruir|matar|matem', t, re.IGNORECASE):
            cand.append(unescape(t.strip()))
    if cand:
        return ' '.join(cand[:3])
    return ''


def extract_missions_from_table_text(html):
    results = []
    # look for Markdown-style table rows that contain mission entries
    for line in html.splitlines():
        line = line.strip()
        if not line.startswith('|'): continue
        parts = [p.strip() for p in line.split('|')]
        # Expect rows like: | Level X | Mission Name | Description | Reward |
        if len(parts) >= 4:
            # skip header separator lines
            if re.match(r'^-+$', parts[1].replace(' ', '')): continue
            name = parts[2]
            desc = parts[3] if len(parts) > 3 else ''
            # crude filter: name shouldn't be 'NAME' or empty
            if name and name.upper() != 'NAME' and not name.lower().startswith('requirements'):
                results.append((name, desc))
    return results


def extract_missions_from_html_tables(html):
    results = []
    # Find all table blocks
    for table in re.finditer(r'<table[^>]*>(.*?)</table>', html, re.IGNORECASE|re.DOTALL):
        tbl = table.group(1)
        # find rows
        for tr in re.finditer(r'<tr[^>]*>(.*?)</tr>', tbl, re.IGNORECASE|re.DOTALL):
            row = tr.group(1)
            # extract td cells
            cells = [re.sub('<[^<]+?>', '', c).strip() for c in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', row, re.IGNORECASE|re.DOTALL)]
            if len(cells) >= 3:
                name = cells[1]
                desc = cells[2]
                if name and name.upper() != 'NAME':
                    results.append((name, desc))
    return results

def extract_candidates_from_text(txt):
    # look for Capitalized tokens (NPC names) and known patterns
    tokens = re.findall(r"\b([A-Z][a-z]{2,}|Annihilator|Interceptor|Barracuda|Saboteur)\b", txt)
    # filter
    common = set(['The','A','An','To','For','And','Or','Of','In','On','By','From'])
    cleaned = []
    for t in tokens:
        if t in common: continue
        if len(t) < 3: continue
        if t.lower() in ('kill','destroy','collect','collecte','destrói','matar','matem'): continue
        if t not in cleaned:
            cleaned.append(t)
    return cleaned


def normalize_key(s):
    s = s.lower()
    s = re.sub('[^a-z0-9 ]', ' ', s)
    s = re.sub('\s+', ' ', s).strip()
    return s


def main():
    global LANG
    lang = 'en'
    if len(sys.argv) > 1 and sys.argv[1].lower() in ('pt','pt-br','pt_br'):
        lang = 'pt'
    LANG = lang

    if lang == 'pt':
        # Prefer Portuguese subdomain, fallback to /pt/ path
        possible = ['https://pt.darkorbitwiki.com', 'https://darkorbitwiki.com/pt']
        chosen = None
        for base_try in possible:
            idx_url = base_try + '/missions/'
            print('Trying Portuguese index:', idx_url)
            idx_html = fetch(idx_url)
            if idx_html and 'mission' in idx_html.lower():
                BASE_LOCAL = base_try
                INDEX_LOCAL = idx_url
                chosen = (BASE_LOCAL, INDEX_LOCAL)
                break
        if chosen is None:
            print('Portuguese index not found, falling back to English index')
            idx = fetch(INDEX)
        else:
            BASE = chosen[0]
            INDEX = chosen[1]
            idx = fetch(INDEX)
    else:
        print('Fetching index...')
        idx = fetch(INDEX)
    links = find_links(idx)
    print('Found', len(links), 'links (may include category pages).')
    # do one level expansion: fetch each category page and collect its links
    pages = set(links)
    for l in list(links):
        html = fetch(l)
        if not html: continue
        sub = find_links(html)
        for s in sub:
            pages.add(s)
    pages = [l for l in pages if '/missions/' in l]
    pages = sorted(set(pages))
    print('Processing', len(pages), 'pages...')
    for p in pages:
        if p in seen: continue
        seen.add(p)
        print('->', p)
        html = fetch(p)
        # If we're in Portuguese mode, try to find a PT variant link on the page (language selector)
        if len(sys.argv) > 1 and sys.argv[1].lower() in ('pt','pt-br','pt_br'):
            # look for hreflang or language links
            m = re.search(r'href=["\']([^"\']+)["\'][^>]*hreflang=["\']pt["\']', html, re.IGNORECASE)
            if not m:
                # look for links with Portuguese label
                m = re.search(r'<a[^>]+href=["\']([^"\']+)["\'][^>]*>(?:\s*<img[^>]*>\s*)?\s*Portugu[eê]s\b', html, re.IGNORECASE)
            if not m:
                # look for /pt/ path links
                m2 = re.search(r'href=["\']([^"\']*/pt/[^"\']+)["\']', html, re.IGNORECASE)
                if m2:
                    m = m2
            if m:
                pt_href = m.group(1)
                if pt_href.startswith('/'):
                    # make absolute
                    parsed_base = urllib.parse.urljoin(p, pt_href)
                    pt_href = parsed_base
                print('   -> Found PT variant, fetching:', pt_href)
                pt_html = fetch(pt_href)
                if pt_html:
                    html = pt_html
        if not html: continue
        # attempt to extract mission rows from HTML tables or markdown-style tables first
        rows = extract_missions_from_html_tables(html)
        if not rows:
            rows = extract_missions_from_table_text(html)
        if rows:
            for name, desc in rows:
                title = name
                req = desc or ''
                key = normalize_key(title)
                if key:
                    desc_text = req.strip()
                    if not desc_text:
                        candidates = extract_candidates_from_text(title)
                        desc_text = ','.join(candidates)
                    entries[key] = desc_text
                    print('   -> (table) ', title, '=>', desc_text)
            continue
        title = extract_title(html) or p
        req = extract_requirements(html) or ''
        key = normalize_key(title)
        if key:
            desc_text = req.strip()
            if not desc_text:
                candidates = extract_candidates_from_text(title)
                desc_text = ','.join(candidates)
            entries[key] = desc_text
            print('   ->', title, '=>', desc_text)
    # write to file
    if not entries:
        print('No entries found.')
        return
    out_file = OUT if lang == 'en' else OUT_PT
    print('Writing', out_file)
    with open(out_file, 'w', encoding='utf-8') as f:
        f.write('# Auto-generated mission -> description mapping\n')
        for k, desc in entries.items():
            if desc is None: continue
            desc_single = ' '.join(str(desc).splitlines()).strip()
            if not desc_single: continue
            f.write(k + '=' + desc_single + '\n')
    print('Done. Wrote', len(entries), 'entries to', out_file)

if __name__ == '__main__':
    main()
