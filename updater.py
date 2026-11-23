"""
Varika Download — mises à jour automatiques (Windows).

Chaque version publiée par l'intégration continue (GitHub Actions) est une « Release » GitHub
qui contient l'installateur, l'exe portable, l'APK, l'extension et un fichier SHA256SUMS.txt.

Le logiciel :
  1. demande à l'API GitHub la dernière version (au démarrage, puis toutes les 12 h) ;
  2. si elle est plus récente, propose de l'installer (bandeau dans l'interface) ;
  3. télécharge le fichier avec son propre moteur (reprise en cas de coupure) ;
  4. VÉRIFIE SON EMPREINTE SHA-256 avant de l'exécuter : un fichier modifié ou abîmé est refusé ;
  5. lance l'installateur en mode silencieux puis se ferme ; l'installateur relance la nouvelle version.

Le dépôt interrogé est UPDATE_REPO (voir README : « Mises à jour »).
"""

import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
import urllib.request

from engine import COMPLETED, Download

UPDATE_REPO = os.environ.get("VARIKA_UPDATE_REPO", "HAJA-M/varika-download-releases")
API = "https://api.github.com/repos/{repo}/releases/latest"
CHECK_EVERY = 12 * 3600


def parse_version(v):
    nums = re.findall(r"\d+", v or "")[:3]
    return tuple(int(x) for x in nums) + (0,) * (3 - len(nums))


def is_newer(remote, local):
    return parse_version(remote) > parse_version(local)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def parse_sums(text):
    """SHA256SUMS.txt : « <empreinte>  <nom du fichier> » par ligne."""
    out = {}
    for line in text.splitlines():
        m = re.match(r"^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$", line)
        if m:
            out[m.group(2)] = m.group(1).lower()
    return out


class Updater:
    def __init__(self, current_version, work_dir, repo=UPDATE_REPO, api=API):
        self.current = current_version
        self.work_dir = work_dir
        self.repo = repo
        self.api = api
        self.state = {"status": "idle", "available": False, "version": None, "notes": "", "progress": 0.0,
                      "error": None, "checked": None, "current": current_version}
        self._release = None
        self._dl = None
        self._lock = threading.Lock()

    # ------------------------------------------------------------------ vérification
    def _get(self, url, accept="application/vnd.github+json", limit=2_000_000):
        req = urllib.request.Request(url, headers={"Accept": accept, "User-Agent": "Varika-Download-Updater"})
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.read(limit)

    def check(self):
        """Interroge GitHub. Renvoie l'état (sans rien télécharger)."""
        try:
            rel = json.loads(self._get(self.api.format(repo=self.repo)))
            version = rel.get("tag_name") or rel.get("name") or ""
            with self._lock:
                self._release = rel
                self.state.update(checked=time.time(), error=None, version=version.lstrip("v"),
                                  notes=(rel.get("body") or "")[:2000], available=is_newer(version, self.current))
                if self.state["status"] == "error":
                    self.state["status"] = "idle"
        except Exception as e:  # noqa: BLE001 — hors connexion, dépôt privé… : on réessaiera plus tard
            with self._lock:
                self.state.update(checked=time.time(), error=f"vérification impossible : {e}")
        return dict(self.state)

    def start_background(self, enabled=lambda: True):
        def loop():
            time.sleep(10)                         # ne ralentit pas le démarrage
            while True:
                if enabled():
                    self.check()
                time.sleep(CHECK_EVERY)
        threading.Thread(target=loop, daemon=True).start()

    # ------------------------------------------------------------------ téléchargement
    def _pick_asset(self, installed):
        assets = (self._release or {}).get("assets", [])
        names = {a["name"]: a["browser_download_url"] for a in assets}
        if installed:
            name = next((n for n in names if re.match(r"Setup-VarikaDownload-.*\.exe$", n)), None)
        else:
            name = next((n for n in names if n == "VarikaDownload.exe"), None)
        sums = next((names[n] for n in names if n.upper().startswith("SHA256SUMS")), None)
        return name, names.get(name), sums

    def download(self, installed):
        """Télécharge la mise à jour puis vérifie son empreinte. Renvoie le chemin du fichier vérifié."""
        name, url, sums_url = self._pick_asset(installed)
        if not url:
            raise RuntimeError("cette version ne contient pas de fichier pour Windows")
        if not sums_url:
            raise RuntimeError("empreintes SHA-256 absentes : mise à jour refusée par sécurité")
        expected = parse_sums(self._get(sums_url, accept="application/octet-stream").decode("utf-8", "replace")).get(name)
        if not expected:
            raise RuntimeError(f"empreinte de {name} introuvable : mise à jour refusée par sécurité")
        os.makedirs(self.work_dir, exist_ok=True)
        target = os.path.join(self.work_dir, name)
        if os.path.exists(target) and sha256_of(target) == expected:
            return target                                   # déjà téléchargé et vérifié
        for p in (target, target + ".part", target + ".part.json"):
            if os.path.exists(p):
                os.remove(p)
        d = Download(url, self.work_dir, 4, filename=name)
        self._dl = d
        d.start()
        while d.is_running:
            self.state["progress"] = d.progress
            time.sleep(0.3)
        self._dl = None
        if d.status != COMPLETED:
            raise RuntimeError(f"téléchargement de la mise à jour : {d.error or d.status}")
        if sha256_of(d.path) != expected:
            os.remove(d.path)
            raise RuntimeError("le fichier téléchargé ne correspond pas à l'empreinte publiée : mise à jour refusée")
        if d.path != target:
            os.replace(d.path, target)
        return target

    # ------------------------------------------------------------------ installation
    def install(self, quit_app):
        """Télécharge, vérifie et lance la mise à jour (en arrière-plan). quit_app() ferme le logiciel."""
        if self.state["status"] in ("downloading", "installing"):
            return dict(self.state)
        if not self._release:
            self.check()
        if not self.state["available"]:
            return dict(self.state)

        def run():
            try:
                installed = getattr(sys, "frozen", False) and os.path.exists(
                    os.path.join(os.path.dirname(sys.executable), "unins000.exe"))
                self.state.update(status="downloading", progress=0.0, error=None)
                path = self.download(installed)
                self.state.update(status="installing", progress=1.0)
                if sys.platform != "win32" or not getattr(sys, "frozen", False):
                    self.state.update(status="ready", error="mode développement : fichier vérifié mais non installé")
                    return
                flags = 0x00000008 | 0x00000200          # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP
                if installed:
                    subprocess.Popen([path, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/CLOSEAPPLICATIONS"],
                                     creationflags=flags, close_fds=True)
                else:
                    # exe portable : on ne peut pas remplacer un exe en cours d'exécution ; un petit script
                    # attend la fermeture, remplace le fichier puis relance le logiciel.
                    exe = sys.executable
                    bat = os.path.join(self.work_dir, "maj.bat")
                    with open(bat, "w", encoding="utf-8") as f:
                        f.write(f'@echo off\r\nchcp 65001 >nul\r\n:attente\r\ntimeout /t 1 /nobreak >nul\r\n'
                                f'move /y "{path}" "{exe}" >nul 2>&1 || goto attente\r\nstart "" "{exe}"\r\ndel "%~f0"\r\n')
                    subprocess.Popen(["cmd", "/c", bat], creationflags=flags | 0x08000000, close_fds=True)
                time.sleep(1)
                quit_app()
            except Exception as e:  # noqa: BLE001
                self.state.update(status="error", error=str(e))

        threading.Thread(target=run, daemon=True).start()
        return dict(self.state)
