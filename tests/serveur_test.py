"""
Petit serveur local pour tester Varika Download sans Internet.

  python tests/serveur_test.py            -> http://127.0.0.1:8765/fichier.bin (40 Mo)

- Gère les requêtes Range (réponses 206), comme un vrai serveur de fichiers.
- Limite le débit PAR connexion (1 Mo/s par défaut) : c'est ce que font beaucoup
  de serveurs, et ça montre pourquoi plusieurs connexions vont plus vite.
- /sans-range.bin : même fichier, mais le serveur ignore les Range (réponse 200).
"""

import argparse
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DATA = b""
RATE = 1024 * 1024


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path not in ("/fichier.bin", "/sans-range.bin"):
            self.send_error(404)
            return
        total = len(DATA)
        rng = self.headers.get("Range")
        m = re.match(r"bytes=(\d+)-(\d*)$", rng or "")
        if m and self.path == "/fichier.bin":
            start = int(m.group(1))
            end = int(m.group(2)) if m.group(2) else total - 1
            if start >= total:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{total}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, total - 1)
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{total}")
        else:
            start, end = 0, total - 1
            self.send_response(200)
        self.send_header("Content-Length", str(end - start + 1))
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Disposition", 'attachment; filename="exemple.bin"')
        self.send_header("Accept-Ranges", "bytes" if self.path == "/fichier.bin" else "none")
        self.end_headers()

        pos, step = start, 16 * 1024
        try:
            while pos <= end:
                chunk = DATA[pos:min(end + 1, pos + step)]
                self.wfile.write(chunk)
                pos += len(chunk)
                time.sleep(len(chunk) / RATE)     # bridage par connexion
        except (BrokenPipeError, ConnectionResetError):
            pass


def make_server(size_mb=40, rate=1024 * 1024, port=8765):
    global DATA, RATE
    DATA = os.urandom(size_mb * 1024 * 1024)
    RATE = rate
    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--taille", type=int, default=40, help="taille du fichier en Mo")
    p.add_argument("--debit", type=float, default=1.0, help="Mo/s par connexion")
    p.add_argument("--port", type=int, default=8765)
    a = p.parse_args()
    srv = make_server(a.taille, int(a.debit * 1024 * 1024), a.port)
    print(f"Serveur prêt : http://127.0.0.1:{a.port}/fichier.bin  ({a.taille} Mo, "
          f"{a.debit} Mo/s par connexion) — Ctrl+C pour arrêter")
    srv.serve_forever()
