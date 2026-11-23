"""
Varika Download — vidéos dont l'image et le son sont séparés (Facebook, Instagram, nombreux lecteurs).

Ces sites envoient deux fichiers MP4 : l'un ne contient que l'image, l'autre que le son. On
télécharge les deux (chacun en plusieurs connexions, avec reprise), puis ffmpeg les réunit
dans un seul .mp4, sans réencodage : c'est instantané et sans perte de qualité.
Sans ffmpeg, on garde la vidéo (muette) et le son à côté, en le signalant.
"""

import os
import shutil
import subprocess
import sys
import threading
import time

from engine import (COMPLETED, DOWNLOADING, ERROR, PAUSED, PROBING, QUEUED, WAITING, Download,
                    NetworkDown, _safe_name, claim_path, is_network_error, release_path)
from hls import find_ffmpeg


class DashDownload:
    kind = "dash"
    resumable = True

    def __init__(self, url, audio_url, dest_dir=".", connections=8, filename=None, headers=None):
        self.url = url
        self.audio_url = audio_url
        self.dest_dir = dest_dir
        self.connections = max(1, min(32, int(connections)))
        self.forced_name = filename
        self.headers = {k: v for k, v in (headers or {}).items() if v}
        self.filename = None
        self.path = None
        self.status = QUEUED
        self.error = None
        self.note = None
        self.variant = "image + son"
        self.video = None
        self.audio = None
        self._stop = threading.Event()
        self._runner = None
        self._probed = False

    # -------------------------------------------------------- les deux pistes
    def _make_parts(self):
        n = max(1, self.connections // 2)
        self.video = Download(self.url, self.part, max(2, self.connections - n), filename="video.mp4", headers=self.headers)
        self.audio = Download(self.audio_url, self.part, max(2, n), filename="audio.m4a", headers=self.headers)

    @property
    def part(self):
        return self.path + ".parts" if self.path else os.path.join(self.dest_dir, ".varika-parts")

    @property
    def state_file(self):
        return self.part

    def _tracks(self):
        return [t for t in (self.video, self.audio) if t is not None]

    @property
    def total(self):
        ts = [t.total for t in self._tracks()]
        return sum(ts) if ts and all(ts) else None

    @property
    def downloaded(self):
        return sum(t.downloaded for t in self._tracks() if t.status != COMPLETED) + \
            sum(t.total or 0 for t in self._tracks() if t.status == COMPLETED)

    @property
    def progress(self):
        if self.status == COMPLETED:
            return 1.0
        tot = self.total
        return min(1.0, self.downloaded / tot) if tot else 0.0

    @property
    def speed(self):
        return sum(t.speed for t in self._tracks())

    @speed.setter
    def speed(self, v):
        pass

    @property
    def active(self):
        return sum(t.active for t in self._tracks())

    @property
    def eta(self):
        tot, sp = self.total, self.speed
        return (tot - self.downloaded) / sp if tot and sp >= 1 else None

    @property
    def is_running(self):
        return self._runner is not None and self._runner.is_alive()

    def ui_segments(self):
        """Barre : la partie gauche montre l'image, la droite le son, au prorata de leur taille."""
        tot = self.total
        if not tot or self.status == COMPLETED:
            return []
        out, start = [], 0.0
        for t in self._tracks():
            share = (t.total or 0) / tot
            done = 1.0 if t.status == COMPLETED else t.progress
            out.append([start, start + share, start + share * done])
            start += share
        return out

    # -------------------------------------------------------- déroulement
    def probe(self):
        """Vérifie les deux liens tout de suite (l'extension rend la main au navigateur en cas d'échec)."""
        self._make_parts()
        self.video.probe()
        self.audio.probe()
        base = os.path.splitext(self.forced_name)[0] if self.forced_name else "video-" + time.strftime("%Y%m%d-%H%M%S")
        self.filename = _safe_name(base) + ".mp4"
        self._probed = True

    def start(self):
        if self.is_running or self.status == COMPLETED:
            return
        self._stop.clear()
        self.error = None
        self._runner = threading.Thread(target=self._run, daemon=True)
        self._runner.start()

    def pause(self):
        self._stop.set()
        for t in self._tracks():
            t.pause()

    def wait(self):
        if self._runner:
            self._runner.join()

    def _run(self):
        try:
            if not self._probed and self.video is None:
                self.status = PROBING
                self.probe()
            if not self.path:
                os.makedirs(self.dest_dir, exist_ok=True)
                self.path = claim_path(os.path.join(self.dest_dir, self.filename), self)
                self.filename = os.path.basename(self.path)
                # les deux pistes vont dans le dossier de travail propre à ce téléchargement
                for t in self._tracks():
                    t.dest_dir = self.part
            os.makedirs(self.part, exist_ok=True)
            self.status = DOWNLOADING
            for t in self._tracks():
                if t.status != COMPLETED:
                    if t.status in (PAUSED, ERROR, WAITING):
                        t.status = QUEUED
                    t.start()
            for t in self._tracks():
                t.wait()
            waiting = [t for t in self._tracks() if t.status == WAITING]
            if waiting and not self._stop.is_set():
                raise NetworkDown(waiting[0].error)
            failed = [t for t in self._tracks() if t.status == ERROR]
            if failed:
                which = "l'image" if failed[0] is self.video else "le son"
                raise RuntimeError(f"{which} : {failed[0].error}")
            if all(t.status == COMPLETED for t in self._tracks()):
                self._merge()
                self.status = COMPLETED
            else:
                self.status = PAUSED
        except Exception as e:  # noqa: BLE001
            if is_network_error(e) and not self._stop.is_set():
                self.error, self.status = str(e), WAITING          # reprise automatique au retour du réseau
            else:
                self.error, self.status = str(e), ERROR
        finally:
            if self.status in (COMPLETED, ERROR):
                release_path(self.path)

    def _merge(self):
        self.status = "assemblage"
        v, a = self.video.path, self.audio.path
        if os.path.exists(self.path):          # un fichier de ce nom est apparu entre-temps
            self.path = claim_path(self.path, self)
            self.filename = os.path.basename(self.path)
        ffmpeg = find_ffmpeg()
        if ffmpeg:
            flags = 0x08000000 if sys.platform == "win32" else 0
            r = subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", v, "-i", a, "-map", "0:v:0", "-map", "1:a:0",
                                "-c", "copy", "-movflags", "+faststart", self.path], capture_output=True, creationflags=flags)
            if r.returncode == 0 and os.path.isfile(self.path):
                shutil.rmtree(self.part, ignore_errors=True)
                return
            self.note = "fusion impossible : image et son enregistrés séparément"
        else:
            self.note = "image et son enregistrés séparément : installez ffmpeg pour les réunir automatiquement"
        os.replace(v, self.path)
        base = os.path.splitext(self.path)[0]
        apath = claim_path(base + ".son.m4a", self)
        os.replace(a, apath)
        release_path(apath)
        shutil.rmtree(self.part, ignore_errors=True)

    # -------------------------------------------------------- sauvegarde
    def to_record(self):
        return {"kind": "dash", "url": self.url, "audio_url": self.audio_url, "dest_dir": self.dest_dir,
                "connections": self.connections, "filename": self.filename, "path": self.path,
                "status": self.status, "error": self.error,
                "headers": {k: v for k, v in self.headers.items() if k.lower() != "cookie"},
                "video": self.video.to_record() if self.video else None,
                "audio": self.audio.to_record() if self.audio else None}

    @classmethod
    def from_record(cls, r):
        d = cls(r["url"], r["audio_url"], r.get("dest_dir", "."), r.get("connections", 8), headers=r.get("headers"))
        d.filename, d.path, d.error = r.get("filename"), r.get("path"), r.get("error")
        if r.get("status") == COMPLETED and d.path and os.path.exists(d.path):
            d.status = COMPLETED
            return d
        d.status = ERROR if r.get("status") == ERROR else WAITING if r.get("status") == WAITING else PAUSED
        if r.get("video") and r.get("audio"):
            d.video, d.audio = Download.from_record(r["video"]), Download.from_record(r["audio"])
            d._probed = True
        return d

    def cleanup_files(self, delete_final=False):
        if not self.path:
            return
        shutil.rmtree(self.part, ignore_errors=True)
        if delete_final and os.path.isfile(self.path):
            os.remove(self.path)
        release_path(self.path)
