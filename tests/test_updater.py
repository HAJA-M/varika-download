"""
Mises à jour automatiques : faux « GitHub » local.

  python tests/test_updater.py

1. Une version plus récente est détectée ; une version égale ou plus ancienne ne l'est pas.
2. Le fichier est téléchargé et son empreinte SHA-256 vérifiée.
3. Un fichier modifié (empreinte différente) est REFUSÉ et supprimé.
4. Une release sans empreintes est refusée.
5. Comparaison des numéros de version (1.10.0 > 1.9.3).
"""

import hashlib
import json
import os
import shutil
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import updater  # noqa: E402

PORT = 8895
BASE = f"http://127.0.0.1:{PORT}"
SETUP = os.urandom(3 * 1024 * 1024)
FILES = {"Setup-VarikaDownload-9.9.9.exe": SETUP, "VarikaDownload.exe": os.urandom(1024 * 1024)}
STATE = {"tag": "v9.9.9", "tamper": False, "sums": True}


class FakeGitHub(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, body, ctype="application/octet-stream"):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/repos/"):
            assets = [{"name": n, "browser_download_url": f"{BASE}/dl/{n}"} for n in FILES]
            if STATE["sums"]:
                assets.append({"name": "SHA256SUMS.txt", "browser_download_url": f"{BASE}/dl/SHA256SUMS.txt"})
            return self._send(json.dumps({"tag_name": STATE["tag"], "body": "Corrections", "assets": assets}).encode(),
                              "application/json")
        name = self.path.rsplit("/", 1)[-1]
        if name == "SHA256SUMS.txt":
            return self._send("".join(f"{hashlib.sha256(d).hexdigest()}  {n}\n" for n, d in FILES.items()).encode())
        data = FILES[name]
        if STATE["tamper"]:
            data = data[:-1] + bytes([data[-1] ^ 1])       # un seul octet modifié
        self._send(data)


def main():
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), FakeGitHub)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    tmp = tempfile.mkdtemp()
    api = BASE + "/repos/{repo}/releases/latest"
    try:
        assert updater.is_newer("v1.10.0", "1.9.3") and not updater.is_newer("1.3.1", "1.3.1") \
            and not updater.is_newer("v1.2.0", "1.3.1")
        print("[ok] comparaison des versions (1.10.0 > 1.9.3, 1.2.0 < 1.3.1)")

        u = updater.Updater("1.3.1", os.path.join(tmp, "a"), repo="x/y", api=api)
        st = u.check()
        assert st["available"] and st["version"] == "9.9.9", st
        old = updater.Updater("9.9.9", os.path.join(tmp, "b"), repo="x/y", api=api).check()
        assert not old["available"], old
        print("[ok] nouvelle version 9.9.9 détectée ; aucune proposition si on est déjà à jour")

        path = u.download(installed=True)
        assert open(path, "rb").read() == SETUP
        print(f"[ok] installateur téléchargé et empreinte SHA-256 vérifiée ({os.path.basename(path)})")

        STATE["tamper"] = True
        u2 = updater.Updater("1.3.1", os.path.join(tmp, "c"), repo="x/y", api=api)
        u2.check()
        try:
            u2.download(installed=True)
            raise AssertionError("un fichier modifié a été accepté !")
        except RuntimeError as e:
            assert "empreinte" in str(e), e
        assert not os.path.exists(os.path.join(tmp, "c", "Setup-VarikaDownload-9.9.9.exe"))
        print("[ok] fichier modifié d'un seul octet : refusé et supprimé")

        STATE.update(tamper=False, sums=False)
        u3 = updater.Updater("1.3.1", os.path.join(tmp, "d"), repo="x/y", api=api)
        u3.check()
        try:
            u3.download(installed=False)
            raise AssertionError("release sans empreintes acceptée !")
        except RuntimeError as e:
            assert "SHA-256" in str(e), e
        print("[ok] release sans fichier d'empreintes : mise à jour refusée")
        print("Tous les tests de mise à jour passent.")
    finally:
        srv.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
