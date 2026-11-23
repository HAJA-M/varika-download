"""
Tests de bout en bout : python tests/test_moteur.py

1. 1 connexion vs 8 connexions (même fichier, débit bridé par connexion)
2. Pause en plein milieu puis reprise -> le fichier final doit être identique
3. Serveur sans Range -> repli sur une seule connexion
"""

import hashlib
import os
import shutil
import sys
import tempfile
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
sys.path.insert(0, HERE)

import serveur_test  # noqa: E402
from engine import COMPLETED, PAUSED, Download  # noqa: E402

PORT = 8799
URL = f"http://127.0.0.1:{PORT}/fichier.bin"


def sha(b):
    return hashlib.sha256(b).hexdigest()


def run(url, conns, out):
    d = Download(url, out, conns)
    t = time.time()
    d.start()
    d.wait()
    return d, time.time() - t


def main():
    srv = serveur_test.make_server(size_mb=8, rate=1024 * 1024, port=PORT)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    expected = sha(serveur_test.DATA)
    tmp = tempfile.mkdtemp()
    try:
        # 1) vitesse
        d1, t1 = run(URL, 1, os.path.join(tmp, "a"))
        d8, t8 = run(URL, 8, os.path.join(tmp, "b"))
        assert d1.status == COMPLETED and d8.status == COMPLETED, (d1.error, d8.error)
        assert sha(open(d1.path, "rb").read()) == expected
        assert sha(open(d8.path, "rb").read()) == expected
        print(f"[ok] 1 connexion : {t1:.1f} s   8 connexions : {t8:.1f} s   "
              f"(x{t1 / t8:.1f})")

        # 2) pause / reprise
        out = os.path.join(tmp, "c")
        d = Download(URL, out, 4)
        d.start()
        time.sleep(1.2)
        d.pause()
        d.wait()
        assert d.status == PAUSED, d.status
        part = d.downloaded
        assert os.path.exists(d.state_file)
        # Nouvel objet, comme après un redémarrage du programme
        d2 = Download(URL, out, 4)
        d2.start()
        d2.wait()
        assert d2.status == COMPLETED, d2.error
        assert d2.path == d.path
        assert sha(open(d2.path, "rb").read()) == expected
        assert not os.path.exists(d2.state_file)
        print(f"[ok] pause à {part / 1024 / 1024:.1f} Mo puis reprise : fichier identique")

        # 3) sans Range
        d3, _ = run(f"http://127.0.0.1:{PORT}/sans-range.bin", 8, os.path.join(tmp, "d"))
        assert d3.status == COMPLETED and not d3.resumable, d3.error
        assert sha(open(d3.path, "rb").read()) == expected
        print(f"[ok] serveur sans Range : repli sur 1 connexion, nom « {d3.filename} »")
        print("Tous les tests passent.")
    finally:
        srv.shutdown()
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
