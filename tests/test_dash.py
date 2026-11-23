"""
Reproduit le problème des vidéos Facebook et vérifie la correction (Playwright + Chromium + ffmpeg) :

  python tests/test_dash.py

Le faux site imite un lecteur « adaptatif » comme celui de Facebook :
  - image et son dans deux fichiers MP4 fragmentés séparés ;
  - le lecteur les charge par petits morceaux : …mp4?oh=…&bytestart=0&byteend=299999 ;
  - la vidéo est branchée sur un blob: (pas d'adresse directe dans la page).
Avant la correction, Varika Download enregistrait un seul morceau de ~300 Ko, illisible.
Attendu : un .mp4 complet de 12 s, avec image ET son, nommé d'après le titre de la page.
"""

import asyncio
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)
TMP = tempfile.mkdtemp()
os.environ["APPDATA"] = os.path.join(TMP, "appdata")
os.makedirs(os.environ["APPDATA"])

import app  # noqa: E402
import bridge  # noqa: E402
import dash  # noqa: E402
import test_hls  # noqa: E402
from engine import COMPLETED, PAUSED  # noqa: E402

PORT = 8881
FILES = {}
SLOW = {"s": 0.0}

PAGE = """<!doctype html><html><head><meta charset="utf-8"><title>(19) Facebook</title></head>
<body style="margin:0;background:#18191a;color:#e4e6eb;font-family:sans-serif">
<h3 style="padding:14px 20px;margin:0">Publication avec une vidéo</h3>
<div style="position:relative;width:640px;height:360px;margin:0 20px">
  <video id="v" muted width="640" height="360" style="background:#000"></video>
  <div style="position:absolute;inset:0"></div>
</div>
<script>
  // Comme le lecteur de Facebook : image et son chargés séparément, par petits morceaux
  (async () => {
    const get = (f, a, b) => fetch(`/v/${f}?oh=SIGNATURE&oe=6800&bytestart=${a}&byteend=${b}`).then(r => r.arrayBuffer());
    await get('video.mp4', 0, 299999); await get('audio.mp4', 0, 99999);
    await get('video.mp4', 300000, 599999); await get('audio.mp4', 100000, 199999);
    document.getElementById('v').src = URL.createObjectURL(new Blob([new Uint8Array(8)], {type: 'video/mp4'}));
  })();
</script></body></html>"""


class FakeCdn(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        u = urlparse(self.path)
        if u.path == "/fb.html":
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        name = u.path.rsplit("/", 1)[-1]
        if name not in FILES:
            self.send_error(404)
            return
        data, q = FILES[name], parse_qs(u.query)
        if "bytestart" in q:                      # morceau demandé dans l'adresse (réponse 200)
            a, b = int(q["bytestart"][0]), min(int(q["byteend"][0]), len(data) - 1)
            self.send_response(200)
        elif self.headers.get("Range"):
            m = re.match(r"bytes=(\d+)-(\d*)", self.headers["Range"])
            a = int(m.group(1))
            b = int(m.group(2)) if m.group(2) else len(data) - 1
            b = min(b, len(data) - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {a}-{b}/{len(data)}")
        else:
            a, b = 0, len(data) - 1
            self.send_response(200)
        self.send_header("Content-Type", "video/mp4")       # Facebook envoie aussi le son en video/mp4
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(b - a + 1))
        self.end_headers()
        pos = a
        try:
            while pos <= b:
                self.wfile.write(data[pos:min(b + 1, pos + 65536)])
                pos += 65536
                if SLOW["s"]:
                    time.sleep(SLOW["s"])
        except (BrokenPipeError, ConnectionResetError):
            pass


def make_media(site):
    frag = ["-movflags", "frag_keyframe+empty_moov+default_base_moof"]
    test_hls.ff("-f", "lavfi", "-i", "testsrc=size=854x480:rate=25", "-t", "12", "-c:v", "libx264", "-g", "25",
                "-b:v", "1500k", "-an", *frag, f"{site}/video.mp4")
    test_hls.ff("-f", "lavfi", "-i", "sine=frequency=440", "-t", "12", "-c:a", "aac", "-b:a", "192k", *frag, f"{site}/audio.mp4")
    for n in ("video.mp4", "audio.mp4"):
        FILES[n] = open(f"{site}/{n}", "rb").read()


async def browser_test(api):
    from playwright.async_api import async_playwright
    ext = os.path.join(ROOT, "extension")
    async with async_playwright() as p:
        proc = subprocess.Popen(
            [p.chromium.executable_path, "--headless=new", "--no-sandbox", "--no-first-run", "--window-size=900,700",
             f"--user-data-dir={TMP}/profil", "--remote-debugging-port=9351",
             f"--disable-extensions-except={ext}", f"--load-extension={ext}", "about:blank"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        try:
            for _ in range(40):
                try:
                    browser = await p.chromium.connect_over_cdp("http://127.0.0.1:9351")
                    break
                except Exception:
                    await asyncio.sleep(0.25)
            ctx = browser.contexts[0]
            page = await ctx.new_page()
            await page.set_viewport_size({"width": 900, "height": 600})
            await page.goto(f"http://127.0.0.1:{PORT}/fb.html")
            await page.wait_for_timeout(2500)                # détection + lecture du début des pistes
            r = await page.eval_on_selector("#v", "v => { const r = v.getBoundingClientRect(); return [r.right, r.top]; }")
            x, y = r[0] - 10 - 80, r[1] + 10 + 16
            await page.mouse.move(x - 200, y + 100)
            await page.mouse.move(x - 60, y + 40, steps=5)
            await page.wait_for_timeout(400)
            await page.mouse.click(x, y)
            await page.wait_for_timeout(600)
            if os.environ.get("VARIKA_SHOTS"):
                await page.screenshot(path=os.environ["VARIKA_SHOTS"] + "/facebook.png")
            for _ in range(160):
                items = api.get_state()["items"]
                if items and items[0]["status"] in ("completed", "error"):
                    return items[0]
                await asyncio.sleep(0.25)
            raise AssertionError(api.get_state()["items"])
        finally:
            os.killpg(proc.pid, signal.SIGKILL)


def main():
    site = os.path.join(TMP, "site")
    os.makedirs(site)
    make_media(site)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), FakeCdn)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{PORT}/v"
    try:
        # 1) moteur : image + son, fusion, reprise après redémarrage
        SLOW["s"] = 0.01
        d = dash.DashDownload(f"{base}/video.mp4?oh=SIG", f"{base}/audio.mp4?oh=SIG", os.path.join(TMP, "out"), 4,
                              filename="Ma vidéo.mp4")
        d.start()
        while d.downloaded == 0 and d.status != "erreur":
            time.sleep(0.02)
        d.pause()
        d.wait()
        assert d.status == PAUSED, (d.status, d.error)
        mid = d.progress
        SLOW["s"] = 0.0
        d2 = dash.DashDownload.from_record(json.loads(json.dumps(d.to_record())))
        d2.start()
        d2.wait()
        assert d2.status == COMPLETED, d2.error
        kinds, h, dur = test_hls.probe(d2.path)
        assert kinds == ["audio", "video"] and h == 480 and 11 < dur < 13, (kinds, h, dur)
        print(f"[ok] image + son : pause à {mid * 100:.0f} %, reprise après redémarrage, fusion -> "
              f"« {os.path.basename(d2.path)} » ({dur:.0f} s, audio + vidéo)")

        # 2) de bout en bout : page « façon Facebook » -> bouton de l'extension -> fichier lisible
        api = app.Api()
        api.save_settings({"dest": os.path.join(TMP, "dl")})
        assert bridge.start_server(api, bridge.load_token(app.data_dir()))
        it = asyncio.run(browser_test(api))
        assert it["status"] == "completed", it
        assert it["kind"] == "dash", it["kind"]
        kinds, h, dur = test_hls.probe(it["path"])
        assert kinds == ["audio", "video"] and 11 < dur < 13, (kinds, dur)
        size = os.path.getsize(it["path"])
        assert size > len(FILES["video.mp4"]) * 0.9, size
        print(f"[ok] page façon Facebook -> « {it['filename']} », {size / 1048576:.1f} Mo, {dur:.0f} s, image + son "
              f"(avant : un morceau de 300 Ko illisible)")
        print("Tous les tests « image + son » passent.")
    finally:
        srv.shutdown()
        shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    main()
