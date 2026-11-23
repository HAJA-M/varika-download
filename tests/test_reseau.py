"""
Coupures d'Internet et très gros fichiers.

  python tests/test_reseau.py            (5 Go par défaut)
  VARIKA_BIG_GB=1 python tests/test_reseau.py

1. Le réseau tombe en plein téléchargement -> « en attente du réseau » (pas d'erreur),
   puis reprise là où on s'était arrêté quand il revient. Fichier identique, rien de re-téléchargé.
2. Même chose sans rien toucher : le logiciel reprend TOUT SEUL quand le serveur revient.
3. Fermeture du logiciel pendant un téléchargement -> au lancement suivant, il reprend tout seul.
4. Fichier de 5 Go, avec une coupure au milieu : contenu vérifié octet par octet (SHA-256).
"""

import hashlib
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
TMP = tempfile.mkdtemp()
os.environ["APPDATA"] = os.path.join(TMP, "appdata")
os.makedirs(os.environ["APPDATA"])

import app  # noqa: E402
import engine  # noqa: E402
from engine import COMPLETED, WAITING, Download  # noqa: E402

PORT = 8891
BLOCK = 65536
STATE = {"down": False, "served": 0, "rate": 0.0}


def block(k):
    """Contenu déterministe, généré à la volée (pas besoin de stocker 5 Go)."""
    return hashlib.sha256(k.to_bytes(8, "big")).digest() * (BLOCK // 32)


def expected_sha(size):
    h = hashlib.sha256()
    for k in range(size // BLOCK):
        h.update(block(k))
    rest = size % BLOCK
    if rest:
        h.update(block(size // BLOCK)[:rest])
    return h.hexdigest()


class Gen(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    size = 0

    def log_message(self, *a):
        pass

    def do_GET(self):
        if STATE["down"]:
            self.close_connection = True
            return
        size = int(re.search(r"/(\d+)\.bin", self.path).group(1))
        a, b = 0, size - 1
        m = re.match(r"bytes=(\d+)-(\d*)", self.headers.get("Range", ""))
        if m:
            a = int(m.group(1))
            b = min(int(m.group(2)) if m.group(2) else size - 1, size - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {a}-{b}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(b - a + 1))
        self.send_header("Content-Disposition", f'attachment; filename="gros-{size}.bin"')
        self.end_headers()
        pos = a
        try:
            while pos <= b:
                if STATE["down"]:                       # coupure brutale : on ferme la connexion
                    self.connection.shutdown(2)
                    return
                k, off = divmod(pos, BLOCK)
                chunk = block(k)[off:off + (b - pos + 1)]
                self.wfile.write(chunk)
                pos += len(chunk)
                STATE["served"] += len(chunk)
                if STATE["rate"]:
                    time.sleep(len(chunk) / STATE["rate"])
        except OSError:
            pass


class Server:
    def __init__(self):
        self.srv = None

    def up(self):
        STATE["down"] = False
        for _ in range(50):
            try:
                self.srv = ThreadingHTTPServer(("127.0.0.1", PORT), Gen)
                break
            except OSError:
                time.sleep(0.2)
        self.srv.daemon_threads = True
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()

    def down(self):
        """Comme une coupure d'Internet : connexions coupées et plus personne au bout du fil."""
        STATE["down"] = True
        self.srv.shutdown()
        self.srv.server_close()


def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def wait_for(cond, timeout, what):
    end = time.time() + timeout
    while time.time() < end:
        if cond():
            return
        time.sleep(0.1)
    raise AssertionError("délai dépassé : " + what)


def main():
    engine.NET_RETRIES = 1          # en test, on passe vite en « attente réseau »
    app.NET_CHECK_EVERY = 1
    S = Server()
    S.up()
    url = lambda size: f"http://127.0.0.1:{PORT}/{size}.bin"
    out = os.path.join(TMP, "dl")
    try:
        # 1) coupure puis reprise manuelle
        size = 40 * 1024 * 1024
        STATE.update(rate=8 * 1024 * 1024, served=0)
        d = Download(url(size), out, 4)
        d.start()
        wait_for(lambda: d.progress > 0.3, 30, "30 %")
        S.down()
        wait_for(lambda: d.status == WAITING and not d.is_running, 30, "attente réseau")
        got = d.downloaded
        print(f"[ok] coupure à {d.progress * 100:.0f} % -> statut « {d.status} », message : {d.error}")
        S.up()
        STATE.update(served=0, rate=0)
        d.status = engine.QUEUED
        d.start()
        d.wait()
        assert d.status == COMPLETED, (d.status, d.error)
        assert file_sha(d.path) == expected_sha(size)
        assert STATE["served"] <= size - got + 4 * 1024 * 1024, (STATE["served"], size - got)
        print(f"[ok] reprise : seuls {STATE['served'] / 1048576:.0f} Mo re-téléchargés sur {size / 1048576:.0f} Mo, fichier identique")

        # 2) reprise automatique par le logiciel
        api = app.Api()
        api.save_settings({"dest": os.path.join(TMP, "auto")})
        STATE.update(rate=8 * 1024 * 1024)
        r = api.add(url(size + 1))
        dd = lambda: api._items[r["id"]]["d"]
        wait_for(lambda: dd().progress > 0.3, 30, "30 %")
        S.down()
        wait_for(lambda: dd().status == WAITING, 30, "attente réseau")
        time.sleep(2)                                   # le réseau reste coupé un moment
        assert dd().status == WAITING
        S.up()
        STATE.update(rate=0)
        wait_for(lambda: dd().status == COMPLETED, 60, "reprise automatique")
        assert file_sha(dd().path) == expected_sha(size + 1)
        print("[ok] reprise automatique : le téléchargement repart seul quand le réseau revient")

        # 3) fermeture du logiciel en plein téléchargement -> reprise au lancement suivant
        STATE.update(rate=8 * 1024 * 1024)
        r = api.add(url(size + 2))
        wait_for(lambda: api._items[r["id"]]["d"].progress > 0.2, 30, "20 %")
        api._shutdown()
        STATE.update(rate=0)
        api2 = app.Api()
        d3 = api2._items[r["id"]]["d"]
        wait_for(lambda: d3.status == COMPLETED, 60, "reprise au lancement")
        assert file_sha(d3.path) == expected_sha(size + 2)
        print("[ok] logiciel fermé pendant un téléchargement -> il reprend tout seul au lancement suivant")
        api2._shutdown()

        # 4) très gros fichier, avec une coupure au milieu
        gb = float(os.environ.get("VARIKA_BIG_GB", "5"))
        big = int(gb * 1024 ** 3)
        STATE.update(rate=0, served=0)
        t0 = time.time()
        d = Download(url(big), os.path.join(TMP, "gros"), 8)
        d.start()
        wait_for(lambda: d.progress > 0.5, 1800, "50 %")
        S.down()
        wait_for(lambda: d.status == WAITING and not d.is_running, 60, "attente réseau")
        half = d.downloaded
        S.up()
        d.status = engine.QUEUED
        d.start()
        d.wait()
        dt = time.time() - t0
        assert d.status == COMPLETED, (d.status, d.error)
        assert os.path.getsize(d.path) == big
        assert file_sha(d.path) == expected_sha(big), "contenu différent !"
        print(f"[ok] fichier de {gb:g} Go : coupure à {half / big * 100:.0f} %, reprise, contenu vérifié "
              f"(SHA-256 identique) en {dt:.0f} s, {len(d.segments)} segments")
        print("Tous les tests réseau passent.")
    finally:
        try:
            S.down()
        except Exception:
            pass
        shutil.rmtree(TMP, ignore_errors=True)


if __name__ == "__main__":
    main()
