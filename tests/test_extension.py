"""
Test de bout en bout de l'extension (nécessite : pip install playwright && playwright install chromium)

  python tests/test_extension.py

Lance le moteur + serveur local de Varika Download (sans fenêtre), charge l'extension
dans Chromium, puis vérifie :
  1. la sécurité du serveur local (origines refusées / acceptées) ;
  2. qu'un téléchargement lancé dans le navigateur est capturé par Varika Download,
     avec les cookies du site, et retiré de la liste du navigateur ;
  3. qu'un lien que le logiciel ne peut pas prendre reste au navigateur.
"""

import asyncio
import hashlib
import http.server
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

TMP = tempfile.mkdtemp()
os.environ["APPDATA"] = os.path.join(TMP, "appdata")
os.makedirs(os.environ["APPDATA"], exist_ok=True)

import app  # noqa: E402
import bridge  # noqa: E402
import serveur_test  # noqa: E402

FILE_PORT = 8833
seen_cookies = []


class Files(serveur_test.Handler):
    """Le serveur de test, avec en plus : cookie de session exigé sur /protege.bin."""

    def do_GET(self):
        if self.path == "/login":
            self.send_response(200)
            self.send_header("Set-Cookie", "session=abc123; Path=/")
            self.send_header("Content-Type", "text/html")
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")
            return
        if self.path == "/protege.bin":
            seen_cookies.append(self.headers.get("Cookie", ""))
            if "session=abc123" not in (self.headers.get("Cookie") or ""):
                self.send_error(403)
                return
            self.path = "/fichier.bin"
        if self.path == "/unique.bin":          # lien qui refuse tout client hors navigateur
            if "Varika" in self.headers.get("User-Agent", "") or "python" in self.headers.get("User-Agent", "").lower() \
                    or self.headers.get("Range"):
                self.send_error(410)
                return
            self.path = "/sans-range.bin"
        super().do_GET()


def http_get(url, headers=None):
    req = urllib.request.Request(url, headers=headers or {})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=5) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code


async def main():
    from playwright.async_api import async_playwright

    srv = serveur_test.make_server(size_mb=6, rate=3 * 1024 * 1024, port=FILE_PORT)
    srv.RequestHandlerClass = Files
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    api = app.Api()
    api.save_settings({"dest": os.path.join(TMP, "telechargements")})
    token = bridge.load_token(app.data_dir())
    assert bridge.start_server(api, token), "port 47821 occupé"
    expected = hashlib.sha256(serveur_test.DATA).hexdigest()

    # 1) sécurité du serveur local
    ping = f"http://127.0.0.1:{bridge.PORT}/ping"
    assert http_get(ping, {"Origin": "https://site-malveillant.com"}) == 403
    assert http_get(ping) == 403                                    # ni origine ni jeton
    assert http_get(ping, {"X-Varika-Token": "faux"}) == 403
    assert http_get(ping, {"X-Varika-Token": token}) == 200
    assert http_get(ping, {"Origin": "chrome-extension://abcdef"}) == 200
    assert http_get(ping, {"X-Varika-Client": "extension"}) == 200
    assert http_get(ping, {"Origin": "https://site-malveillant.com", "X-Varika-Client": "extension"}) == 403
    print("[ok] serveur local : pages web refusées, extension et jeton acceptés")

    profile = os.path.join(TMP, "profil")
    ext = os.path.join(ROOT, "extension")
    dl_dir = os.path.join(TMP, "navigateur")
    async with async_playwright() as p:
        # Chromium lancé « normalement » (Playwright piloterait sinon lui-même les téléchargements)
        proc = subprocess.Popen(
            [p.chromium.executable_path, "--headless=new", "--no-sandbox", "--no-first-run",
             f"--user-data-dir={profile}", "--remote-debugging-port=9339",
             f"--disable-extensions-except={ext}", f"--load-extension={ext}", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            await run_browser_tests(p, api, expected, dl_dir)
        finally:
            os.killpg(proc.pid, signal.SIGKILL)

    print("Info extension côté logiciel :", json.dumps(api.extension_info()))
    srv.shutdown()
    shutil.rmtree(TMP, ignore_errors=True)
    print("Tous les tests de l'extension passent.")


async def run_browser_tests(p, api, expected, dl_dir):
    for _ in range(40):
        try:
            browser = await p.chromium.connect_over_cdp("http://127.0.0.1:9339")
            break
        except Exception:
            await asyncio.sleep(0.25)
    ctx = browser.contexts[0]
    cdp = await browser.new_browser_cdp_session()
    await cdp.send("Browser.setDownloadBehavior", {"behavior": "allow", "downloadPath": dl_dir})
    sw = None
    for _ in range(40):
        for w in ctx.service_workers:
            try:
                if await w.evaluate("chrome.runtime.getManifest().name") == "Varika Download":
                    sw = w
            except Exception:
                pass
        if sw:
            break
        await asyncio.sleep(0.25)
    assert sw, "extension non chargée"
    if True:
        await sw.evaluate("chrome.storage.local.set({allFiles: true, minSizeMB: 0})")

        page = await ctx.new_page()
        await page.goto(f"http://127.0.0.1:{FILE_PORT}/login")       # pose le cookie de session

        # 2) téléchargement capturé, avec cookies
        await page.set_content(f'<a id="l" href="http://127.0.0.1:{FILE_PORT}/protege.bin">fichier</a>')
        await page.click("#l")
        item = None
        for _ in range(80):
            await asyncio.sleep(0.25)
            items = api.get_state()["items"]
            if items and items[0]["status"] == "completed":
                item = items[0]
                break
        assert item, f"non capturé : {api.get_state()['items']}"
        assert item["filename"] == "exemple.bin", item["filename"]   # nom lu dans Content-Disposition
        assert hashlib.sha256(open(item["path"], "rb").read()).hexdigest() == expected
        assert any("session=abc123" in c for c in seen_cookies)
        await asyncio.sleep(1)
        left = await sw.evaluate("chrome.downloads.search({}).then(l => l.map(d => d.state + ':' + d.url))")
        assert not any("protege.bin" in x for x in left), left
        print(f"[ok] clic dans le navigateur -> capturé par Varika Download ({item['filename']}), "
              "cookie transmis, retiré de la liste du navigateur")

        # 3) lien refusé par le logiciel -> le navigateur garde le téléchargement
        before = len(api.get_state()["items"])
        await page.set_content(f'<a id="u" href="http://127.0.0.1:{FILE_PORT}/unique.bin">unique</a>')
        await page.click("#u")
        state = ""
        for _ in range(80):
            await asyncio.sleep(0.25)
            l = await sw.evaluate("chrome.downloads.search({}).then(l => l.filter(d => d.url.includes('unique.bin')))")
            if l and l[0]["state"] == "complete":
                state, path = "complete", l[0]["filename"]
                break
        assert state == "complete", l
        assert hashlib.sha256(open(path, "rb").read()).hexdigest() == expected
        assert len(api.get_state()["items"]) == before
        print("[ok] lien refusé par le logiciel -> reste téléchargé par le navigateur, fichier intact")
        print("Journal de l'extension :", (await sw.evaluate("chrome.storage.session.get('vdlog')")).get("vdlog"))


if __name__ == "__main__":
    asyncio.run(main())
