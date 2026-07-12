from playwright.sync_api import sync_playwright
from urllib.parse import urljoin
from pathlib import Path
import re

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


def extract_from_table(html):
    # crude: find <table> blocks and extract rows
    rows = []
    for match in re.finditer(r'<table[^>]*>(.*?)</table>', html, re.DOTALL|re.IGNORECASE):
        tbl = match.group(1)
        for tr in re.finditer(r'<tr[^>]*>(.*?)</tr>', tbl, re.DOTALL|re.IGNORECASE):
            cells = [re.sub('<[^<]+?>','',c).strip() for c in re.findall(r'<t[dh][^>]*>(.*?)</t[dh]>', tr.group(1), re.DOTALL|re.IGNORECASE)]
            if len(cells) >= 3:
                name = cells[1]
                desc = cells[2]
                rows.append((name, desc))
    return rows


with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page()
    page.set_default_timeout(20000)
    page.goto(INDEX)
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
        # Try to invoke the translation widget via JS regardless of visibility
        try:
            clicked = page.evaluate("""
                (function(){
                    // prefer plugin link with data-gt-lang
                    var el = document.querySelector('a[data-gt-lang="pt"]') || Array.from(document.querySelectorAll('a')).find(a=>/Portuguese|Português/i.test(a.innerText));
                    if (el) { try { el.click(); return true; } catch(e) { /* fallback: dispatch event */ var ev = document.createEvent('MouseEvents'); ev.initEvent('click', true, true); el.dispatchEvent(ev); return true; } }
                    // try opening gt_switcher
                    var sw = document.querySelector('.gt_switcher');
                    if (sw) {
                        try { var s = sw.querySelector('.gt_selected a'); if (s) { s.click(); } } catch(e) {}
                        var option = Array.from(sw.querySelectorAll('a')).find(a=>/Portuguese|Português|pt/i.test(a.innerText) || a.getAttribute('data-gt-lang')=='pt');
                        if (option) { try { option.click(); return true; } catch(e){ var ev=document.createEvent('MouseEvents'); ev.initEvent('click', true, true); option.dispatchEvent(ev); return true;} }
                    }
                    return false;
                })()
            """)
            print('translation click attempted:', clicked)
            page.wait_for_timeout(1000)
        except Exception as e:
            print('translation attempt error', e)

        html = page.content()
        # first try to extract from tables
        rows = extract_from_table(html)
        if rows:
            for name, desc in rows:
                key = normalize_key(name)
                if key:
                    entries[key] = ' '.join(desc.splitlines()).strip()
                    print('   -> (table) ', name, '=>', entries[key][:120])
            continue
        # fallback: title + paragraphs
        try:
            title = page.query_selector('h1').inner_text().strip() if page.query_selector('h1') else page.title()
        except Exception:
            title = page.title() or l
        # try to find req-like paragraph
        paras = page.query_selector_all('p')
        reqtxt = ''
        for pnode in paras:
            try:
                t = pnode.inner_text()
                if re.search(r'destruir|matar|coletar|coletar|bonus|caixa|interceptor|annihilator', t, re.IGNORECASE):
                    reqtxt += ' ' + t.strip()
            except Exception:
                pass
        key = normalize_key(title)
        if key:
            entries[key] = reqtxt.strip()
            print('   ->', title, '=>', entries[key][:120])

    browser.close()

if entries:
    with open(OUT_PT, 'w', encoding='utf-8') as f:
        f.write('# Auto-generated mission -> description mapping (PT via Playwright fixed)\n')
        for k, desc in entries.items():
            f.write(k + '=' + desc + '\n')
    print('Wrote', len(entries), 'entries to', OUT_PT)
else:
    print('No entries extracted')
