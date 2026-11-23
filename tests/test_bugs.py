"""
Tests de non-régression : chaque test reproduit un bug trouvé pendant l'analyse.

  python tests/test_bugs.py            (tous)
  python tests/test_bugs.py B12 B31    (seulement certains)
"""

import hashlib
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, ROOT)
TMP = tempfile.mkdtemp()
os.environ["APPDATA"] = os.path.join(TMP, "appdata")
os.makedirs(os.environ["APPDATA"])

import engine  # noqa: E402
import hls  # noqa: E402
from engine import COMPLETED, ERROR, PAUSED, Download  # noqa: E402

PORT = 8861
DATA = os.urandom(3 * 1024 * 1024)
DATA2 = os.urandom(3 * 1024 * 1024 + 777)
STATE = {"changed": False, "cookies": [], "valid_token": "1", "slow": 0.0}


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _file(self, data, name=None, ranges=True):
        rng = self.headers.get("Range")
        start, end = 0, len(data) - 1
        if rng and ranges:
            a, b = rng.split("=")[1].split("-")
            start, end = int(a), int(b) if b else len(data) - 1
            end = min(end, len(data) - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(data)}")
        else:
            self.send_response(200)
        self.send_header("Content-Length", str(end - start + 1))
        if name:
            self.send_header("Content-Disposition", name)
        self.end_headers()
        pos = start
        while pos <= end:
            self.wfile.write(data[pos:min(end + 1, pos + 65536)])
            pos += 65536
            if STATE["slow"]:
                time.sleep(STATE["slow"])

    def do_GET(self):
        p = self.path
        if p == "/cd-utf8":
            raw = 'attachment; filename="Conférence été.pdf"'.encode("utf-8").decode("latin-1")
            return self._file(DATA, raw)
        if p == "/reserved":
            return self._file(DATA, 'attachment; filename="CON.txt"')
        if p == "/longname":
            return self._file(DATA, 'attachment; filename="' + "a" * 300 + '.zip"')
        if p == "/same":
            return self._file(DATA, 'attachment; filename="meme-nom.bin"')
        if p == "/changing":
            return self._file(DATA2 if STATE["changed"] else DATA, 'attachment; filename="change.bin"')
        if p == "/short":                       # annonce 1 Mo, n'en envoie que la moitié, pas de Range
            self.send_response(200)
            self.send_header("Content-Length", str(1024 * 1024))
            self.end_headers()
            self.wfile.write(DATA[:512 * 1024])
            self.close_connection = True
            return
        if p == "/redirect-other":              # redirection vers un autre hôte
            self.send_response(302)
            self.send_header("Location", f"http://localhost:{PORT}/echo-cookie")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if p == "/echo-cookie":
            STATE["cookies"].append(self.headers.get("Cookie"))
            return self._file(DATA[:100000], 'attachment; filename="cookie.bin"')
        if p == "/source":                      # lien « public » qui redirige vers un lien signé
            self.send_response(302)
            self.send_header("Location", f"/signed?t={STATE['valid_token']}")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if p.startswith("/signed"):
            if p != f"/signed?t={STATE['valid_token']}":
                self.send_response(403)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            return self._file(DATA, 'attachment; filename="signe.bin"')
        if p == "/live.m3u8":
            body = b"#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:2,\nseg100.ts\n#EXTINF:2,\nseg101.ts\n"
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        self.send_response(404)
        self.send_header("Content-Length", "0")
        self.end_headers()


def sha(b):
    return hashlib.sha256(b).hexdigest()


def dl(path, **kw):
    d = Download(f"http://127.0.0.1:{PORT}{path}", os.path.join(TMP, "out"), kw.pop("conns", 4), **kw)
    d.start()
    d.wait()
    return d


# ----------------------------------------------------------------- les tests
def B1():
    """Nom de fichier UTF-8 brut dans Content-Disposition -> ne doit pas être illisible."""
    d = dl("/cd-utf8")
    assert d.filename == "Conférence été.pdf", d.filename


def B2():
    """Noms réservés Windows (CON, NUL…) et noms trop longs."""
    d = dl("/reserved")
    assert d.filename.split(".")[0].upper() != "CON", d.filename
    d = dl("/longname")
    assert len(d.filename) <= 180 and d.filename.endswith(".zip"), len(d.filename)


def B3():
    """Deux téléchargements du même nom lancés en même temps -> deux fichiers distincts et intacts."""
    a = Download(f"http://127.0.0.1:{PORT}/same", os.path.join(TMP, "same"), 4)
    b = Download(f"http://127.0.0.1:{PORT}/same", os.path.join(TMP, "same"), 4)
    a.start(); b.start(); a.wait(); b.wait()
    assert a.status == COMPLETED and b.status == COMPLETED, (a.error, b.error)
    assert a.path != b.path, a.path
    assert sha(open(a.path, "rb").read()) == sha(DATA) == sha(open(b.path, "rb").read())


def B5():
    """Reprise après redémarrage alors que le lien signé (après redirection) a expiré."""
    STATE["slow"] = 0.02
    d = Download(f"http://127.0.0.1:{PORT}/source", os.path.join(TMP, "signed"), 2)
    d.start()
    while d.downloaded == 0 and d.is_running:
        time.sleep(0.02)
    d.pause(); d.wait()
    STATE["slow"] = 0.0
    assert d.status == PAUSED, d.status
    rec = d.to_record()
    STATE["valid_token"] = "2"                  # l'ancien lien signé ne marche plus
    d2 = Download.from_record(json.loads(json.dumps(rec)))
    d2.start(); d2.wait()
    assert d2.status == COMPLETED, d2.error
    assert sha(open(d2.path, "rb").read()) == sha(DATA)


def B12():
    """Le fichier a changé sur le serveur entre la pause et la reprise -> pas de fichier corrompu."""
    STATE["changed"], STATE["slow"] = False, 0.02
    d = Download(f"http://127.0.0.1:{PORT}/changing", os.path.join(TMP, "chg"), 2)
    d.start()
    while d.downloaded == 0 and d.is_running:
        time.sleep(0.02)
    d.pause(); d.wait()
    STATE["slow"] = 0.0
    STATE["changed"] = True
    d2 = Download.from_record(d.to_record())
    d2.start(); d2.wait()
    STATE["changed"] = False
    ok = d2.status == COMPLETED and sha(open(d2.path, "rb").read()) == sha(DATA2)
    assert ok or (d2.status == ERROR and "changé" in (d2.error or "")), (d2.status, d2.error)
    if d2.status == COMPLETED:
        assert sha(open(d2.path, "rb").read()) == sha(DATA2), "fichier mélangé ancien/nouveau"


def B13():
    """Connexion coupée en plein téléchargement sans reprise possible -> erreur, pas « en pause »."""
    d = dl("/short")
    assert d.status == ERROR, (d.status, d.error)


def B14():
    """Un fichier du même nom apparaît pendant le téléchargement -> il ne doit pas être écrasé."""
    out = os.path.join(TMP, "ecrase")
    STATE["slow"] = 0.01
    d = Download(f"http://127.0.0.1:{PORT}/same", out, 2)
    d.start()
    while not d.path and d.is_running:
        time.sleep(0.01)
    with open(d.path, "wb") as f:               # l'utilisateur (ou le navigateur) crée ce fichier
        f.write(b"MON FICHIER")
    d.wait()
    STATE["slow"] = 0.0
    assert open(os.path.join(out, "meme-nom.bin"), "rb").read() == b"MON FICHIER"
    assert d.status == COMPLETED and sha(open(d.path, "rb").read()) == sha(DATA)


def B19():
    """Pas assez d'espace disque -> erreur claire tout de suite."""
    real = shutil.disk_usage
    shutil.disk_usage = lambda p: real(p)._replace(free=1000)
    try:
        d = dl("/same")
    finally:
        shutil.disk_usage = real
    assert d.status == ERROR and "espace" in (d.error or "").lower(), (d.status, d.error)


def B31():
    """Les cookies d'un site ne doivent pas être envoyés à un autre site après une redirection."""
    STATE["cookies"].clear()
    d = dl("/redirect-other", headers={"Cookie": "session=SECRET"})
    assert d.status == COMPLETED, d.error
    assert STATE["cookies"] and all(c is None for c in STATE["cookies"]), STATE["cookies"]


def H1():
    """Une diffusion en direct (sans #EXT-X-ENDLIST) doit être refusée, pas tronquée en silence."""
    d = hls.HlsDownload(f"http://127.0.0.1:{PORT}/live.m3u8", os.path.join(TMP, "live"), 2)
    d.start(); d.wait()
    assert d.status == ERROR and "direct" in (d.error or ""), (d.status, d.error)


def A22():
    """Sauvegardes simultanées de la liste (fermeture + sauvegarde automatique) sans erreur."""
    import app
    api = app.Api()
    errors = []

    def hammer():
        for _ in range(200):
            try:
                api._save()
            except Exception as e:  # noqa: BLE001
                errors.append(e)
    ts = [threading.Thread(target=hammer) for _ in range(4)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    assert not errors, errors[:2]


def A23():
    """Une liste sauvegardée abîmée ne doit pas empêcher le logiciel de démarrer."""
    import app
    path = os.path.join(app.data_dir(), "downloads.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"settings": {}, "items": [{"url": "http://x/a"}, {"id": "ok1", "url": "http://x/b", "status": "terminé"}]}, f)
    api = app.Api()
    assert [i["id"] for i in api.get_state()["items"]] == ["ok1"]


def A39():
    """Un dossier de destination impossible doit être refusé dans les paramètres."""
    import app
    api = app.Api()
    bad = "/proc/impossible/dossier" if sys.platform != "win32" else "Q:\\nexiste\\pas"
    res = api.save_settings({"dest": bad})
    assert res.get("error") and api._settings["dest"] != bad, res


def A44():
    """La file d'attente doit survivre à une erreur inattendue."""
    import app
    api = app.Api()

    class Boom:
        status, is_running, kind = engine.QUEUED, False, "file"
        def start(self):
            raise RuntimeError("boum")
    api._items["boom"] = {"d": Boom(), "added": 0}
    api._order.insert(0, "boom")
    time.sleep(1.2)
    del api._items["boom"]
    api._order.remove("boom")
    r = api.add(f"http://127.0.0.1:{PORT}/same")
    for _ in range(40):
        time.sleep(0.25)
        if api._items[r["id"]]["d"].status == COMPLETED:
            break
    assert api._items[r["id"]]["d"].status == COMPLETED, "la file d'attente s'est arrêtée"


def X47():
    """Le serveur local doit refuser un en-tête Host étranger (attaque par DNS rebinding)."""
    import app
    import bridge
    api = app.Api()
    tok = bridge.load_token(app.data_dir())
    srv = bridge.start_server(api, tok)
    assert srv, "port occupé"
    try:
        s = socket.create_connection(("127.0.0.1", bridge.PORT), timeout=3)
        s.sendall(b"GET /ping HTTP/1.1\r\nHost: evil.example:47821\r\nX-Varika-Client: extension\r\n\r\n")
        resp = s.recv(200).decode("latin-1")
        s.close()
        assert " 403 " in resp.split("\r\n")[0], resp.split("\r\n")[0]
    finally:
        srv.shutdown()
        srv.server_close()


def C1():
    """La ligne de commande doit accepter un lien .m3u8."""
    import cli
    assert hasattr(cli, "make_download"), "cli ne sait pas créer un téléchargement HLS"
    d = cli.make_download(f"http://127.0.0.1:{PORT}/x/index.m3u8", TMP, 4)
    assert isinstance(d, hls.HlsDownload)


TESTS = [B1, B2, B3, B5, B12, B13, B14, B19, B31, H1, A22, A23, A39, A44, X47, C1]

if __name__ == "__main__":
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), H)
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    wanted = set(sys.argv[1:])
    failed = 0
    for t in TESTS:
        if wanted and t.__name__ not in wanted:
            continue
        try:
            t()
            print(f"  ok    {t.__name__:4} {t.__doc__.strip()}")
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"  ÉCHEC {t.__name__:4} {t.__doc__.strip()}\n         -> {type(e).__name__}: {str(e)[:160]}")
    srv.shutdown()
    shutil.rmtree(TMP, ignore_errors=True)
    print(f"\n{failed} échec(s)")
    sys.exit(1 if failed else 0)
