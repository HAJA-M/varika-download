"""
Varika Download — application de bureau.

Interface en HTML/CSS (dossier ui/) affichée dans une fenêtre native par pywebview
(moteur Edge WebView2 sous Windows). Le JavaScript appelle les méthodes publiques
de la classe Api ci-dessous via window.pywebview.api.<méthode>().
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import uuid

import webview

import bridge
from hls import HlsDownload

from engine import (COMPLETED, DOWNLOADING, ERROR, PAUSED, PROBING, QUEUED,
                    Download)

APP_NAME = "Varika Download"
APP_VERSION = "1.3.0"
STATUS_KEYS = {QUEUED: "queued", PROBING: "probing", DOWNLOADING: "downloading",
               PAUSED: "paused", COMPLETED: "completed", ERROR: "error", "assemblage": "assembling"}


def _restore(rec):
    return (HlsDownload if rec.get("kind") == "hls" else Download).from_record(rec)


def _is_hls(url):
    return urllib.parse.urlparse(url).path.lower().endswith((".m3u8", ".m3u"))


def resource(rel):
    """Chemin d'un fichier embarqué (fonctionne aussi dans l'exe PyInstaller)."""
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, rel)


def data_dir():
    root = os.environ.get("APPDATA") or os.path.join(os.path.expanduser("~"), ".config")
    path = os.path.join(root, APP_NAME)
    os.makedirs(path, exist_ok=True)
    return path


def default_downloads():
    if sys.platform == "win32":
        try:  # vrai dossier « Téléchargements » de Windows, même s'il a été déplacé
            import winreg
            key = r"Software\Microsoft\Windows\CurrentVersion\Explorer\User Shell Folders"
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, key) as k:
                val, _ = winreg.QueryValueEx(k, "{374DE290-123F-4565-9164-39C4925E467B}")
                return os.path.expandvars(val)
        except OSError:
            pass
    return os.path.join(os.path.expanduser("~"), "Downloads")


def clipboard_text():
    """Lit le presse-papiers Windows sans dépendance (ctypes)."""
    if sys.platform != "win32":
        return ""
    import ctypes
    from ctypes import wintypes
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32
    user32.OpenClipboard.argtypes = [wintypes.HWND]
    user32.GetClipboardData.restype = wintypes.HANDLE
    kernel32.GlobalLock.argtypes = [wintypes.HGLOBAL]
    kernel32.GlobalLock.restype = wintypes.LPVOID
    kernel32.GlobalUnlock.argtypes = [wintypes.HGLOBAL]
    if not user32.OpenClipboard(None):
        return ""
    try:
        h = user32.GetClipboardData(13)  # CF_UNICODETEXT
        if not h:
            return ""
        p = kernel32.GlobalLock(h)
        if not p:
            return ""
        try:
            return ctypes.wstring_at(p)
        finally:
            kernel32.GlobalUnlock(h)
    finally:
        user32.CloseClipboard()


def open_path(path):
    if sys.platform == "win32":
        os.startfile(path)  # noqa: S606
    else:
        subprocess.Popen(["open" if sys.platform == "darwin" else "xdg-open", path])


class Api:
    """Toutes les méthodes publiques sont appelables depuis le JavaScript."""

    def __init__(self):
        self._window = None
        self._lock = threading.RLock()
        self._items = {}          # id -> {"d": Download, "added": timestamp}
        self._order = []
        self._settings = {"dest": default_downloads(), "connections": 8, "max_parallel": 3,
                          "show_on_add": True}
        self._file = os.path.join(data_dir(), "downloads.json")
        self._ext_dir = None      # dossier permanent de l'extension (voir main)
        self._server_ok = False
        self._save_lock = threading.Lock()   # A22 : une seule écriture du fichier à la fois
        self._closing = False                # A43 : plus de nouveaux départs pendant la fermeture
        self._load()
        self._dirty = False
        threading.Thread(target=self._loop, daemon=True).start()

    # ------------------------------------------------------ persistance
    def _load(self):
        try:
            with open(self._file, encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return
        if not isinstance(data, dict):
            return
        if isinstance(data.get("settings"), dict):
            self._settings.update(data["settings"])
        for rec in data.get("items", []) if isinstance(data.get("items"), list) else []:
            try:                                  # A23 : une entrée abîmée est ignorée, pas fatale
                i = str(rec["id"])
                d = _restore(rec)
            except Exception:  # noqa: BLE001
                continue
            if i in self._items:
                continue
            self._items[i] = {"d": d, "added": rec.get("added", time.time())}
            self._order.append(i)

    def _save(self):
        with self._lock:
            items = []
            for i in self._order:
                it = self._items[i]
                rec = it["d"].to_record()
                rec.update(id=i, added=it["added"])
                items.append(rec)
            data = {"settings": self._settings, "items": items}
        with self._save_lock:
            tmp = self._file + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self._file)

    def _loop(self):
        """File d'attente + sauvegarde automatique."""
        last_save = 0
        while True:
            time.sleep(0.5)
            busy = False
            try:                                  # A44 : une erreur ne doit jamais arrêter la file
                with self._lock:
                    running = sum(1 for i in self._order if self._items[i]["d"].is_running)
                    for i in list(self._order):
                        if self._closing or running >= int(self._settings["max_parallel"]):
                            break
                        d = self._items[i]["d"]
                        if d.status == QUEUED and not d.is_running:
                            try:
                                d.start()
                                running += 1
                            except Exception as e:  # noqa: BLE001
                                d.status, d.error = ERROR, f"démarrage impossible : {e}"
                    busy = running > 0
            except Exception:  # noqa: BLE001
                pass
            if self._dirty or (busy and time.time() - last_save > 3):
                try:
                    self._save()
                except (OSError, ValueError, TypeError):
                    pass
                self._dirty, last_save = False, time.time()

    def _shutdown(self):
        self._closing = True
        with self._lock:
            running = [it["d"] for it in self._items.values() if it["d"].is_running]
        for d in running:
            d.pause()
        for d in running:
            d.wait()
        try:
            self._save()
        except OSError:
            pass

    # ------------------------------------------------------ appelé par le JS
    def get_state(self):
        with self._lock:
            out, total_speed = [], 0.0
            for i in self._order:
                it = self._items[i]
                d = it["d"]
                total_speed += d.speed
                segs = []
                if d.status != COMPLETED:
                    if d.kind == "hls":
                        segs = d.ui_segments()
                    elif d.total:
                        for s, e, p in d.segment_map()[:256]:
                            segs.append([s / d.total, (e + 1) / d.total, min(p, e + 1) / d.total])
                out.append({
                    "id": i, "url": d.url, "filename": d.filename, "path": d.path,
                    "total": d.total, "downloaded": d.downloaded if d.status != COMPLETED else d.total,
                    "progress": d.progress, "speed": d.speed, "eta": d.eta,
                    "status": STATUS_KEYS.get(d.status, "queued"), "error": d.error,
                    "active": d.active, "connections": d.connections,
                    "resumable": d.resumable, "segments": segs, "added": it["added"],
                    "kind": d.kind, "variant": getattr(d, "variant", None), "note": getattr(d, "note", None),
                })
            return {"items": out, "totalSpeed": total_speed, "settings": self._settings,
                    "version": APP_VERSION, "extension": self.extension_info()}

    def add(self, url, connections=None):
        return self._add(url, connections)

    def _add(self, url, connections=None, filename=None, headers=None, probe=False, kind=None, quality=None):
        url = (url or "").strip()
        if not url.lower().startswith(("http://", "https://")):
            return {"ok": False, "error": "Le lien doit commencer par http:// ou https://"}
        n = int(connections or self._settings["connections"])
        if kind == "hls" or _is_hls(url):
            d = HlsDownload(url, self._settings["dest"], n, filename=filename or None, headers=headers, quality=quality)
        else:
            d = Download(url, self._settings["dest"], n, filename=filename or None, headers=headers)
        if probe:
            # Sondage immédiat : si le lien ne marche pas hors du navigateur
            # (lien à usage unique, formulaire POST…), l'extension rend la main au navigateur.
            try:
                d.probe()
            except Exception as e:  # noqa: BLE001
                return {"ok": False, "error": str(e)}
        i = uuid.uuid4().hex[:12]
        with self._lock:
            self._items[i] = {"d": d, "added": time.time()}
            self._order.insert(0, i)
            self._dirty = True
        return {"ok": True, "id": i, "filename": d.filename}

    # ------------------------------------------------------ pont navigateur
    def _version(self):
        return APP_VERSION

    def _add_from_browser(self, data):
        url = str(data.get("url") or "")
        headers = {}
        for key, name in (("referrer", "Referer"), ("cookies", "Cookie"), ("userAgent", "User-Agent")):
            v = str(data.get(key) or "")
            if v and "\n" not in v and "\r" not in v:
                headers[name] = v[:16000]
        fname = os.path.basename(str(data.get("filename") or "").replace("\\", "/")) or None
        kind = "hls" if data.get("kind") == "hls" else None
        title = str(data.get("title") or "").strip()
        if not fname and title and (kind or data.get("kind") == "video"):
            fname = title[:120] + (".mp4" if kind else os.path.splitext(urllib.parse.urlparse(url).path)[1] or ".mp4")
        quality = data.get("quality")
        quality = int(quality) if str(quality or "").isdigit() else None
        res = self._add(url, None, fname, headers, probe=True, kind=kind, quality=quality)
        if res.get("ok") and self._settings.get("show_on_add", True):
            self._bring_to_front()
        return res

    def _extension_seen(self, ua):
        browser = ("Edge" if "Edg/" in ua else "Opera" if "OPR/" in ua else "Firefox" if "Firefox/" in ua
                   else "Chrome" if "Chrome/" in ua else "navigateur")
        first = not self._settings.get("extension_seen")
        self._settings["extension_seen"] = time.time()
        self._settings["extension_browser"] = browser
        if first:
            self._dirty = True

    def _bring_to_front(self):
        w = self._window
        if not w:
            return
        for action in ("restore", "show"):
            try:
                getattr(w, action)()
            except Exception:  # noqa: BLE001
                pass
        try:  # passe brièvement au premier plan, comme IDM
            w.on_top = True
            w.on_top = False
        except Exception:  # noqa: BLE001
            pass

    def extension_info(self):
        return {"connected": bool(self._settings.get("extension_seen")),
                "lastSeen": self._settings.get("extension_seen"),
                "browser": self._settings.get("extension_browser"),
                "folder": self._ext_dir, "server": self._server_ok, "port": bridge.PORT}

    def open_extension_folder(self):
        if self._ext_dir and os.path.isdir(self._ext_dir):
            open_path(self._ext_dir)
            return True
        return False

    def open_browser_extensions(self, browser):
        """Ouvre la page des extensions du navigateur choisi (Windows)."""
        pages = {"chrome": ("chrome", "chrome://extensions"), "edge": ("msedge", "edge://extensions"),
                 "opera": ("opera", "opera://extensions"), "brave": ("brave", "brave://extensions")}
        exe, page = pages.get(browser, pages["chrome"])
        if sys.platform != "win32":
            return page
        try:
            subprocess.Popen(["cmd", "/c", "start", "", exe, page], creationflags=0x08000000)
        except OSError:
            pass
        return page

    def _get(self, i):
        it = self._items.get(i)
        return it["d"] if it else None

    def pause(self, i):
        d = self._get(i)
        if d:
            if d.is_running:
                d.pause()
            elif d.status == QUEUED:
                d.status = PAUSED
            self._dirty = True

    def resume(self, i):
        d = self._get(i)
        if d and not d.is_running and d.status in (PAUSED, ERROR):
            d.status = QUEUED            # la file d'attente le démarrera
            self._dirty = True

    def pause_all(self):
        for i in list(self._order):
            self.pause(i)

    def resume_all(self):
        for i in list(self._order):
            self.resume(i)

    def remove(self, i, delete_files=False):
        with self._lock:
            it = self._items.pop(i, None)
            if i in self._order:
                self._order.remove(i)
            self._dirty = True
        if not it:
            return
        d = it["d"]

        def cleanup():
            d.pause()
            d.wait()
            # Sans suppression, on garde tout : le .part permet de reprendre
            # si on rajoute la même URL plus tard.
            if delete_files:
                d.cleanup_files(delete_final=True)
        threading.Thread(target=cleanup, daemon=True).start()

    def clear_completed(self):
        with self._lock:
            done = [i for i in self._order if self._items[i]["d"].status == COMPLETED]
            for i in done:
                self._order.remove(i)
                del self._items[i]
            self._dirty = True
        return len(done)

    def open_file(self, i):
        d = self._get(i)
        if d and d.path and os.path.exists(d.path):
            open_path(d.path)
            return True
        return False

    def open_folder(self, i=None):
        d = self._get(i) if i else None
        if d and d.path and sys.platform == "win32" and os.path.exists(d.path):
            subprocess.Popen(f'explorer /select,"{os.path.normpath(d.path)}"')   # A37 : chemins avec espaces
            return
        folder = os.path.dirname(d.path) if d and d.path else self._settings["dest"]
        os.makedirs(folder, exist_ok=True)
        open_path(folder)

    def choose_folder(self):
        if not self._window:
            return None
        res = self._window.create_file_dialog(webview.FOLDER_DIALOG, directory=self._settings["dest"])
        return res[0] if res else None

    def save_settings(self, s):
        if s.get("dest") and s["dest"] != self._settings["dest"]:
            dest = os.path.abspath(os.path.expanduser(str(s["dest"]).strip()))
            try:                                  # A39 : le dossier doit exister et être accessible en écriture
                os.makedirs(dest, exist_ok=True)
                with tempfile.NamedTemporaryFile(dir=dest):
                    pass
            except OSError as e:
                return dict(self._settings, error=f"Dossier inutilisable : {dest} ({e.strerror or e})")
            s = dict(s, dest=dest)
        with self._lock:
            if s.get("dest"):
                self._settings["dest"] = s["dest"]
            if s.get("connections"):
                self._settings["connections"] = max(1, min(32, int(s["connections"])))
            if s.get("max_parallel"):
                self._settings["max_parallel"] = max(1, min(10, int(s["max_parallel"])))
            if "show_on_add" in s:
                self._settings["show_on_add"] = bool(s["show_on_add"])
            self._dirty = True
        return self._settings

    def read_clipboard(self):
        try:
            text = clipboard_text().strip()
        except Exception:  # noqa: BLE001
            return ""
        return text if text.lower().startswith(("http://", "https://")) and len(text) < 4000 else ""

    def free_space(self):
        try:
            return shutil.disk_usage(self._settings["dest"]).free
        except OSError:
            return None


def main():
    links = bridge.parse_args(sys.argv[1:])     # https://… ou varika://add?url=…
    token = bridge.load_token(data_dir())
    # Instance unique : si le logiciel tourne déjà, on lui passe les liens et on quitte.
    if bridge.forward_to_running(token, links):
        return
    api = Api()
    api._ext_dir = bridge.install_extension_files(resource("extension"), data_dir())
    api._server_ok = bridge.start_server(api, token) is not None
    bridge.register_protocol()
    # A41 : le sondage d'un lien peut prendre du temps ; la fenêtre ne doit pas l'attendre
    threading.Thread(target=lambda: [api._add_from_browser(link) for link in links], daemon=True).start()
    window = webview.create_window(
        f"{APP_NAME}", resource(os.path.join("ui", "index.html")), js_api=api,
        width=1200, height=760, min_size=(920, 600), background_color="#0b0c0b",
    )
    api._window = window
    window.events.closing += api._shutdown
    webview.start(http_server=True, debug="--debug" in sys.argv)


if __name__ == "__main__":
    main()
