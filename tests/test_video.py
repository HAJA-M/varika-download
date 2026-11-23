"""
Test de bout en bout de la détection vidéo (Playwright + Chromium + ffmpeg) :

  python tests/test_video.py

1. Page avec une vidéo MP4 : survol -> bouton « Télécharger cette vidéo » -> clic
   -> la vidéo arrive dans Varika Download et se télécharge.
2. Page qui lit un flux HLS comme hls.js (playlists chargées en XHR, vidéo en blob:)
   -> l'extension détecte la playlist maître et ses qualités -> choix 720p -> mp4 complet.
3. Les playlists filles et les segments .ts ne sont pas proposés en double.
"""

import asyncio
import functools
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)
TMP = tempfile.mkdtemp()
os.environ["APPDATA"] = os.path.join(TMP, "appdata")
os.makedirs(os.environ["APPDATA"])

import app  # noqa: E402
import bridge  # noqa: E402
import test_hls  # noqa: E402

PORT = 8852
SHOTS = os.environ.get("VARIKA_SHOTS")       # dossier où enregistrer des captures (facultatif)

PAGE_MP4 = """<!doctype html><html><head><meta charset="utf-8"><title>Démo vidéo MP4</title></head>
<body style="margin:0;background:#222;font-family:sans-serif;color:#eee">
<h2 style="padding:16px 24px;margin:0">Démo : vidéo MP4</h2>
<video id="v" src="/clip.mp4" controls muted width="720" height="405" style="margin:0 24px;background:#000"></video>
</body></html>"""

PAGE_HLS = """<!doctype html><html><head><meta charset="utf-8"><title>Conférence Tech Madagascar</title></head>
<body style="margin:0;background:#222;font-family:sans-serif;color:#eee">
<h2 style="padding:16px 24px;margin:0">Démo : lecteur HLS (comme hls.js)</h2>
<div style="position:relative;width:720px;height:405px;margin:0 24px">
  <video id="v" muted width="720" height="405" style="background:#000"></video>
  <div style="position:absolute;inset:0" title="couche du lecteur qui recouvre la vidéo"></div>
</div>
<script>
  // Comme hls.js : playlists et segments chargés en XHR, vidéo branchée sur un blob (MediaSource)
  (async () => {
    const master = await (await fetch('/master.m3u8')).text();
    await (await fetch('/v720/index.m3u8')).text();
    await (await fetch('/v720/index0.ts')).arrayBuffer();
    document.getElementById('v').src = URL.createObjectURL(new Blob([new Uint8Array(8)], {type: 'video/mp4'}));
    document.title = 'Conférence Tech Madagascar';
  })();
</script></body></html>"""


class Handler(SimpleHTTPRequestHandler):
    extensions_map = {**SimpleHTTPRequestHandler.extensions_map, ".m3u8": "application/vnd.apple.mpegurl",
                      ".ts": "video/mp2t", ".mp4": "video/mp4"}

    def log_message(self, *a):
        pass


async def wait_items(api, n, timeout=60):
    for _ in range(int(timeout * 4)):
        items = api.get_state()["items"]
        if len(items) >= n and items[0]["status"] in ("completed", "error"):
            return items
        await asyncio.sleep(0.25)
    raise AssertionError(api.get_state()["items"])


async def hover_and_click(page, x, y, shot=None):
    await page.mouse.move(x - 200, y + 100)
    await page.mouse.move(x - 60, y + 40, steps=5)
    await page.wait_for_timeout(400)
    if shot:
        await page.screenshot(path=shot)
    await page.mouse.click(x, y)


async def main():
    from playwright.async_api import async_playwright
    site = os.path.join(TMP, "site")
    os.makedirs(site)
    test_hls.make_fixtures(site)
    test_hls.ff("-f", "lavfi", "-i", "testsrc=size=640x360:rate=25", "-f", "lavfi", "-i", "sine", "-t", "8",
                "-c:v", "libx264", "-c:a", "aac", "-movflags", "+faststart", f"{site}/clip.mp4")
    open(f"{site}/mp4.html", "w").write(PAGE_MP4)
    open(f"{site}/hls.html", "w").write(PAGE_HLS)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), functools.partial(Handler, directory=site))
    threading.Thread(target=srv.serve_forever, daemon=True).start()

    api = app.Api()
    api.save_settings({"dest": os.path.join(TMP, "dl")})
    assert bridge.start_server(api, bridge.load_token(app.data_dir()))
    ext = os.path.join(ROOT, "extension")

    async with async_playwright() as p:
        proc = subprocess.Popen(
            [p.chromium.executable_path, "--headless=new", "--no-sandbox", "--no-first-run", "--window-size=1100,800",
             "--autoplay-policy=no-user-gesture-required", f"--user-data-dir={TMP}/profil", "--remote-debugging-port=9343",
             f"--disable-extensions-except={ext}", f"--load-extension={ext}", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            for _ in range(40):
                try:
                    browser = await p.chromium.connect_over_cdp("http://127.0.0.1:9343")
                    break
                except Exception:
                    await asyncio.sleep(0.25)
            ctx = browser.contexts[0]
            page = await ctx.new_page()
            await page.set_viewport_size({"width": 1000, "height": 620})

            # 1) vidéo MP4 directe
            await page.goto(f"http://127.0.0.1:{PORT}/mp4.html")
            await page.wait_for_timeout(800)
            r = await page.eval_on_selector("#v", "v => { const r = v.getBoundingClientRect(); return [r.right, r.top]; }")
            await hover_and_click(page, r[0] - 10 - 80, r[1] + 10 + 16, SHOTS and f"{SHOTS}/bouton-video.png")
            items = await wait_items(api, 1)
            it = items[0]
            assert it["status"] == "completed", it
            assert open(it["path"], "rb").read() == open(f"{site}/clip.mp4", "rb").read()
            print(f"[ok] vidéo MP4 : survol -> bouton -> « {it['filename']} » téléchargée, identique à l'original")

            # 2) flux HLS lu comme par hls.js
            await page.goto(f"http://127.0.0.1:{PORT}/hls.html")
            await page.wait_for_timeout(1500)
            r = await page.eval_on_selector("#v", "v => { const r = v.getBoundingClientRect(); return [r.right, r.top]; }")
            btn_x, btn_y = r[0] - 10 - 80, r[1] + 10 + 16
            await hover_and_click(page, btn_x, btn_y)
            await page.wait_for_timeout(700)
            if SHOTS:
                await page.screenshot(path=f"{SHOTS}/choix-qualite.png")
            # 1re ligne du panneau = playlist maître ; bouton « Télécharger » à droite
            panel_top, panel_right = r[1] + 10 + 40, r[0] - 10
            await page.mouse.click(panel_right - 8 - 8 - 38, panel_top + 8 + 30 + 22)
            items = await wait_items(api, 2, timeout=90)
            it = items[0]
            assert it["status"] == "completed", it
            assert it["kind"] == "hls" and it["variant"] == "1280x720", it
            assert it["filename"].startswith("Conférence Tech Madagascar"), it["filename"]
            kinds, height, dur = test_hls.probe(it["path"])
            assert kinds == ["audio", "video"] and height == 720 and 11 < dur < 13, (kinds, height, dur)
            print(f"[ok] lecteur HLS : playlist maître détectée, 720p choisie -> « {it['filename']} » ({dur:.0f} s, audio + vidéo)")

            sw = None
            for w in ctx.service_workers:
                try:
                    if await w.evaluate("chrome.runtime.getManifest().name") == "Varika Download":
                        sw = w
                except Exception:
                    pass
            tab_media = await sw.evaluate("chrome.storage.session.get(null)")
            urls = [m["url"] for k, v in tab_media.items() if k.startswith("media:") for m in v]
            assert any(u.endswith("/master.m3u8") for u in urls)
            assert not any(u.endswith(".ts") for u in urls), urls
            print("[ok] segments .ts ignorés, playlist fille masquée derrière la playlist maître")
            print("Journal :", [l for l in (await sw.evaluate("chrome.storage.session.get('vdlog')")).get("vdlog", [])][-6:])
        finally:
            os.killpg(proc.pid, signal.SIGKILL)
            srv.shutdown()
    shutil.rmtree(TMP, ignore_errors=True)
    print("Tous les tests vidéo passent.")


if __name__ == "__main__":
    asyncio.run(main())
