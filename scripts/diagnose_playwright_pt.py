from playwright.sync_api import sync_playwright
from urllib.parse import urljoin

P = 'https://darkorbitwiki.com/missions/'
TEST_PAGE = 'https://darkorbitwiki.com/missions/blacklight-missions/'

with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    page = b.new_page()
    page.goto(TEST_PAGE)
    print('Page loaded:', TEST_PAGE)
    # dump possible language switcher containers
    elems = page.query_selector_all('*')
    candidates = []
    for e in elems:
        try:
            txt = e.inner_text().strip()
            if 'Portugu' in txt or 'pt' in txt or 'gt_switcher' in e.get_attribute('class') or 'gtranslate' in (e.get_attribute('class') or ''):
                candidates.append((e.evaluate('el => el.outerHTML')[:1000].replace('\n',' '), txt[:200].replace('\n',' ')))
        except Exception:
            pass
    print('Found', len(candidates), 'candidate elements that may relate to language switcher:')
    for i, c in enumerate(candidates[:20]):
        print('--- element', i)
        print(c[0])
        print('inner text snippet:', c[1])
    # try specific selectors
    sels = ["a:has(img[alt='pt'])", "a:has-text('Portuguese')", "a:has-text('Português')", ".gt_switcher a", "#gtranslate_wrapper a", "[data-lang='pt']"]
    for s in sels:
        try:
            el = page.query_selector(s)
            print('Selector', s, '->', 'found' if el else 'not found')
            if el:
                print('outerHTML:', el.evaluate('el => el.outerHTML')[:500])
        except Exception as e:
            print('Selector', s, 'error', e)
    # attempt click on any element with text Portuguese
    found = page.query_selector("text=/Portugu(e|ê)s/i")
    print('text=/Portugu(e|ê)s/i ->', 'found' if found else 'not found')
    if found:
        try:
            found.click()
            page.wait_for_timeout(1000)
            print('Clicked Portuguese element; new h1:', page.query_selector('h1').inner_text())
        except Exception as e:
            print('Click failed:', e)
    b.close()

print('done')
