"""
Varika Download — pont avec le navigateur.

Comment l'extension parle au logiciel (même principe qu'IDM, en plus simple) :

  Extension  --HTTP-->  http://127.0.0.1:47821  (serveur local, uniquement sur la machine)
                          GET  /ping   -> le logiciel est-il lancé ?
                          POST /add    -> {url, filename, referrer, cookies, userAgent}

Sécurité : n'importe quelle page web peut tenter d'appeler 127.0.0.1. On n'accepte
donc que les requêtes dont l'en-tête Origin est celui d'une extension
(chrome-extension://, moz-extension://…), qu'une page web ne peut pas falsifier,
ou celles qui portent l'en-tête X-Varika-Client (impossible à ajouter depuis une page web
sans pré-vérification CORS, que l'on refuse) ou le jeton secret local (2e instance).

Si le logiciel est fermé, l'extension ouvre un lien varika://add?url=… :
Windows lance alors Varika Download grâce au protocole enregistré dans le registre.
"""

import hashlib
import json
import os
import secrets
import shutil
import sys
import threading
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 47821
EXT_ORIGINS = ("chrome-extension://", "moz-extension://", "extension://", "safari-web-extension://")
PROTOCOL = "varika"


# ------------------------------------------------------------------ jeton local
def load_token(data_dir):
    path = os.path.join(data_dir, "local.token")
    try:
        with open(path, encoding="utf-8") as f:
            tok = f.read().strip()
            if len(tok) >= 32:
                return tok
    except OSError:
        pass
    tok = secrets.token_hex(24)
    with open(path, "w", encoding="utf-8") as f:
        f.write(tok)
    return tok


# --------------------------------------------------------------- serveur local
class _Handler(BaseHTTPRequestHandler):
    server_version = "VarikaDownload"
    api = None
    token = ""

    def log_message(self, *a):
        pass

    def _origin(self):
        return self.headers.get("Origin") or ""

    def _allowed(self):
        """
        - Origin présent (toute requête venant d'une page web) : il doit être une extension.
        - Pas d'Origin : Chrome ne l'envoie pas toujours pour une extension qui a la
          permission sur l'hôte. On exige alors l'en-tête X-Varika-Client (une page web
          ne peut pas ajouter d'en-tête personnalisé sans pré-vérification CORS, que l'on
          refuse) ou le jeton local (2e instance du logiciel).
        """
        # X47 : protection contre le « DNS rebinding » (un site qui se fait passer pour 127.0.0.1)
        if (self.headers.get("Host") or "").lower() not in (f"127.0.0.1:{PORT}", f"localhost:{PORT}"):
            return False
        o = self._origin()
        if o:
            return o.startswith(EXT_ORIGINS)
        if self.headers.get("X-Varika-Client") == "extension":
            return True
        return secrets.compare_digest(self.headers.get("X-Varika-Token", ""), self.token)

    def _from_extension(self):
        return self._origin().startswith(EXT_ORIGINS) or self.headers.get("X-Varika-Client") == "extension"

    def _send(self, code, obj=None):
        body = json.dumps(obj or {}).encode("utf-8")
        self.send_response(code)
        o = self._origin()
        if o.startswith(EXT_ORIGINS):
            self.send_header("Access-Control-Allow-Origin", o)
            self.send_header("Vary", "Origin")
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):  # pré-vérification CORS
        if not self._origin().startswith(EXT_ORIGINS):
            self._send(403, {"error": "origine refusée"})
            return
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", self._origin())
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Varika-Client")
        self.send_header("Access-Control-Max-Age", "600")
        self.send_header("Vary", "Origin")
        self.end_headers()

    def do_GET(self):
        if not self._allowed():
            self._send(403, {"error": "origine refusée"})
            return
        if self.path == "/ping":
            if self._from_extension():
                self.api._extension_seen(self.headers.get("User-Agent", ""))
            self._send(200, {"app": "Varika Download", "version": self.api._version()})
        else:
            self._send(404, {"error": "inconnu"})

    def do_POST(self):
        if not self._allowed():
            self._send(403, {"error": "origine refusée"})
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
            if n > 1_000_000:
                raise ValueError("requête trop grande")
            data = json.loads(self.rfile.read(n) or b"{}")
            if not isinstance(data, dict):
                raise ValueError("format invalide")
        except ValueError as e:
            self._send(400, {"ok": False, "error": str(e)})
            return
        if self.path == "/add":
            if self._from_extension():
                self.api._extension_seen(self.headers.get("User-Agent", ""))
            self._send(200, self.api._add_from_browser(data))
        elif self.path == "/show":
            self.api._bring_to_front()
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "inconnu"})


def start_server(api, token):
    """Démarre le serveur local. Retourne None si le port est déjà pris."""
    handler = type("Handler", (_Handler,), {"api": api, "token": token})
    try:
        srv = ThreadingHTTPServer(("127.0.0.1", PORT), handler)
    except OSError:
        return None
    srv.daemon_threads = True
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def forward_to_running(token, urls):
    """Instance unique : si Varika Download tourne déjà, lui transmettre les liens."""
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # jamais via un proxy

    def post(path, data, timeout=40):
        req = urllib.request.Request(
            f"http://127.0.0.1:{PORT}{path}", data=json.dumps(data).encode(),
            headers={"Content-Type": "application/json", "X-Varika-Token": token}, method="POST")
        with opener.open(req, timeout=timeout) as r:
            return json.loads(r.read() or b"{}")
    try:
        # A40 : vérification rapide d'abord, pour ne pas bloquer le démarrage si le port
        # est occupé par un autre logiciel qui ne répond pas
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/ping", headers={"X-Varika-Token": token})
        with opener.open(req, timeout=2) as r:
            if json.loads(r.read() or b"{}").get("app") != "Varika Download":
                return False
    except (OSError, ValueError):
        return False
    try:
        for u in urls:
            post("/add", u)
        post("/show", {})
        return True
    except OSError:
        return False


# ----------------------------------------------------------------- varika://
def parse_args(argv):
    """Liens passés en argument : https://… ou varika://add?url=…&referrer=…"""
    out = []
    for a in argv:
        if a.lower().startswith(("http://", "https://")):
            out.append({"url": a})
        elif a.lower().startswith(PROTOCOL + "://"):
            p = urllib.parse.urlparse(a)
            q = urllib.parse.parse_qs(p.query)
            if p.netloc == "add" and q.get("url"):
                out.append({k: (q.get(k) or [""])[0] for k in ("referrer", "filename", "kind", "title", "quality")}
                           | {"url": q["url"][0]})
    return out


def register_protocol():
    """Enregistre varika:// pour l'utilisateur courant (sans droits administrateur)."""
    if sys.platform != "win32" or not getattr(sys, "frozen", False):
        return False
    import winreg
    exe = sys.executable
    base = rf"Software\Classes\{PROTOCOL}"
    try:
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, base) as k:
            winreg.SetValueEx(k, "", 0, winreg.REG_SZ, "URL:Varika Download")
            winreg.SetValueEx(k, "URL Protocol", 0, winreg.REG_SZ, "")
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, base + r"\DefaultIcon") as k:
            winreg.SetValueEx(k, "", 0, winreg.REG_SZ, f'"{exe}",0')
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, base + r"\shell\open\command") as k:
            winreg.SetValueEx(k, "", 0, winreg.REG_SZ, f'"{exe}" "%1"')
        return True
    except OSError:
        return False


# ------------------------------------------------------- fichiers de l'extension
def _tree_hash(folder):
    h = hashlib.sha256()
    for root, _, files in sorted(os.walk(folder)):
        for f in sorted(files):
            p = os.path.join(root, f)
            h.update(os.path.relpath(p, folder).encode())
            with open(p, "rb") as fh:
                h.update(fh.read())
    return h.hexdigest()


def install_extension_files(src, data_dir):
    """
    Copie l'extension dans un dossier permanent (%APPDATA%\\Varika Download\\extension).
    Nécessaire car l'exe « un seul fichier » se décompresse dans un dossier temporaire
    qui disparaît à la fermeture, alors que le navigateur garde le chemin du dossier.
    """
    dst = os.path.join(data_dir, "extension")
    if not os.path.isdir(src):
        return dst if os.path.isdir(dst) else None
    try:
        if os.path.isdir(dst) and _tree_hash(dst) == _tree_hash(src):
            return dst
        if os.path.isdir(dst):
            shutil.rmtree(dst)
        shutil.copytree(src, dst)
    except OSError:
        pass
    return dst
