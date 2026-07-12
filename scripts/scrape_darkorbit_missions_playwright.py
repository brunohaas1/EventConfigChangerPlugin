from playwright.sync_api import sync_playwright
import re
from html import unescape
from urllib.parse import urljoin
from pathlib import Path

BASE = 'https://darkorbitwiki.com'
INDEX = BASE + '/missions/'
OUT_PT = Path(__file__).resolve().parent.parent / 'missions_npc_map_pt.properties'

seen = set()
entries = {}


def normalize_key(s):
    s = s.lower()
    s = re.sub('[^a-z0-9 ]', ' ', s)
    s = re.sub('\s+', ' ', s).strip()
    return s


def extract_requirements_from_html(html):
    # try headings
    for heading in ['Requisitos', 'Objetivos', 'Requisito', 'Objetivo', 'Alvos', 'Targets']:
        m = re.search(rf'<h[0-6][^>]*>\s*{heading}[^<]*</h[0-6]>\s*(?:<p[^>]*>(.*?)</p>|<ul[^>]*>(.*?)</ul>)', html, re.IGNORECASE|re.DOTALL)
        if m:
            txt = (m.group(1) or m.group(2) or '').strip()
            txt = re.sub('<[^<]+?>', '', txt)
            if txt:
                return unescape(txt)
    # fallback paragraphs with destroy/kill/collect in pt
    cand = []
    for m in re.finditer(r'<p[^>]*>(.*?)</p>', html, re.IGNORECASE|re.DOTALL):
        t = re.sub('<[^<]+?>', '', m.group(1))
        if re.search(r'destruir|matar|coletar|bonus|caixa|caixas|interceptor|annihilator', t, re.IGNORECASE):
            cand.append(unescape(t.strip()))
    if cand:
        return ' '.join(cand[:3])
    return ''


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.set_default_timeout(15000)
    print('Opening index', INDEX)
    page.goto(INDEX)
    # collect candidate links
    anchors = page.query_selector_all('a[href*="/missions/"]')
    links = set()
    for a in anchors:
        try:
            href = a.get_attribute('href')
            if not href: continue
            href = urljoin(INDEX, href)
            if '/missions/' in href:
                links.add(href.split('#')[0])
        except Exception:
            pass

    links = sorted(links)
    print('Found', len(links), 'links')

    for l in links:
        if l in seen: continue
        seen.add(l)
        print('->', l)
        try:
            page.goto(l)
        except Exception as e:
            print('goto error', e)
            continue
        # try clicking Portuguese language selector if present
        try:
            # common patterns: img alt="pt" inside anchor, or link text Portuguese / Português
            el = page.query_selector('a:has(img[alt="pt"])') or page.query_selector('a:has-text("Portuguese")') or page.query_selector('a:has-text("Português")')
            if el:
                try:
                    el.click()
                    page.wait_for_timeout(800)
                    print('   -> clicked language selector')
                except Exception:
                    pass
        except Exception:
            pass

        html = page.content()
        # extract title
        title = ''
        try:
            h = page.query_selector('h1')
            if h:
                title = h.inner_text().strip()
        except Exception:
            pass
        if not title:
            title = page.title()
        if not title:
            title = l

        req = extract_requirements_from_html(html)
        key = normalize_key(title)
        if key:
            entries[key] = req or ''
            print('   ->', title, '=>', (req[:120] + '...') if len(req) > 120 else req)

    browser.close()

if entries:
    print('Writing', OUT_PT)
    with open(OUT_PT, 'w', encoding='utf-8') as f:
        f.write('# Auto-generated mission -> description mapping (PT via Playwright)\n')
        for k, desc in entries.items():
            desc_single = ' '.join(str(desc).splitlines()).strip()
            if not desc_single: desc_single = ''
            f.write(k + '=' + desc_single + '\n')
    print('Done. Wrote', len(entries), 'entries to', OUT_PT)
else:
    print('No entries extracted')
