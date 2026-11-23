"""
Varika Download — téléchargement des vidéos en streaming HLS (.m3u8).

Comment marche une vidéo HLS :
  1. Une playlist « maître » liste plusieurs qualités (360p, 720p, 1080p…).
  2. Chaque qualité a sa playlist « média » : la vidéo découpée en centaines de
     petits morceaux (segments .ts ou .m4s) de quelques secondes.
  3. Les segments peuvent être chiffrés en AES-128 : la clé est donnée dans la
     playlist (#EXT-X-KEY). C'est un chiffrement de transport, pas un DRM.
     Les DRM (SAMPLE-AES, Widevine, FairPlay, PlayReady) sont refusés.

On télécharge donc les segments en parallèle (plusieurs connexions), on les
déchiffre si besoin, puis on les met bout à bout. Si ffmpeg est disponible, on
produit un .mp4 (et on fusionne la piste audio si elle est séparée) ; sinon on
garde le .ts, lisible par VLC.
"""

import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from engine import (COMPLETED, DOWNLOADING, ERROR, MAX_RETRIES, NET_RETRIES, PAUSED, PROBING,
                    QUEUED, UA, WAITING, NetworkDown, _safe_name, check_free_space, claim_path, http_open,
                    is_network_error, release_path)

MAX_SEGMENT = 256 * 1024 * 1024     # garde-fou mémoire : un « segment » plus gros est suspect

try:
    from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
except ImportError:  # le logiciel marche sans, seules les vidéos chiffrées échouent
    Cipher = None


class ProtectedContent(Exception):
    pass


# ------------------------------------------------------------------ playlists
_ATTR = re.compile(r'([A-Z0-9-]+)=("[^"]*"|[^,]*)')


def _attrs(s):
    return {k: v.strip('"') for k, v in _ATTR.findall(s)}


def parse_playlist(text, base_url):
    text = text.lstrip("﻿")
    lines = [l.strip() for l in text.splitlines() if l.strip()]
    if not lines or not lines[0].startswith("#EXTM3U"):
        raise ValueError("ce n'est pas une playlist HLS")
    join = lambda u: urllib.parse.urljoin(base_url, u)

    if any(l.startswith("#EXT-X-STREAM-INF") for l in lines):
        variants, audio = [], {}
        for i, l in enumerate(lines):
            if l.startswith("#EXT-X-STREAM-INF"):
                a = _attrs(l.split(":", 1)[1])
                uri = next((x for x in lines[i + 1:] if not x.startswith("#")), None)
                if uri:
                    res = a.get("RESOLUTION", "")
                    variants.append({"url": join(uri), "bandwidth": int(a.get("BANDWIDTH", "0") or 0),
                                     "resolution": res, "height": int(res.split("x")[1]) if "x" in res else 0,
                                     "audio": a.get("AUDIO")})
            elif l.startswith("#EXT-X-MEDIA") and "TYPE=AUDIO" in l:
                a = _attrs(l.split(":", 1)[1])
                if a.get("URI"):
                    gid = a.get("GROUP-ID")
                    if gid not in audio or a.get("DEFAULT") == "YES":
                        audio[gid] = join(a["URI"])
        return {"type": "master", "variants": variants, "audio": audio}

    segs, key, init, seq, byterange_next = [], None, None, 0, 0
    pending_range = None
    for l in lines:
        if l.startswith("#EXT-X-MEDIA-SEQUENCE:"):
            seq = int(l.split(":", 1)[1])
        elif l.startswith("#EXT-X-KEY:"):
            a = _attrs(l.split(":", 1)[1])
            method = a.get("METHOD", "NONE")
            fmt = a.get("KEYFORMAT", "identity")
            if method == "NONE":
                key = None
            elif method == "AES-128" and fmt == "identity":
                key = {"uri": join(a["URI"]), "iv": a.get("IV")}
            else:
                raise ProtectedContent("vidéo protégée par DRM : téléchargement impossible")
        elif l.startswith("#EXT-X-MAP:"):
            a = _attrs(l.split(":", 1)[1])
            init = {"url": join(a["URI"]), "range": _range(a.get("BYTERANGE"), 0)[0] if a.get("BYTERANGE") else None}
        elif l.startswith("#EXT-X-BYTERANGE:"):
            pending_range = l.split(":", 1)[1]
        elif not l.startswith("#"):
            rng = None
            if pending_range:
                rng, byterange_next = _range(pending_range, byterange_next)
                pending_range = None
            segs.append({"url": join(l), "key": key, "seq": seq + len(segs), "range": rng})
    if not segs:
        raise ValueError("playlist vide")
    if "#EXT-X-ENDLIST" not in lines and not any(l.startswith("#EXT-X-PLAYLIST-TYPE:VOD") for l in lines):
        raise ValueError("diffusion en direct : non prise en charge (seules les vidéos complètes se téléchargent)")
    return {"type": "media", "segments": segs, "init": init}


def _range(spec, next_offset):
    length, _, offset = spec.partition("@")
    start = int(offset) if offset else next_offset
    end = start + int(length) - 1
    return f"bytes={start}-{end}", end + 1


def find_ffmpeg():
    exe = "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg"
    here = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else os.path.abspath(__file__))
    for p in (os.path.join(here, exe), os.path.join(here, "ffmpeg", exe), shutil.which("ffmpeg")):
        if p and os.path.isfile(p):
            return p
    return None


# ------------------------------------------------------------------ téléchargement
class HlsDownload:
    kind = "hls"

    def __init__(self, url, dest_dir=".", connections=8, filename=None, headers=None, quality=None):
        self.url = url                    # playlist (maître ou média)
        self.dest_dir = dest_dir
        self.connections = max(1, min(32, int(connections)))
        self.forced_name = filename
        self.headers = {k: v for k, v in (headers or {}).items() if v}
        self.quality = quality            # hauteur voulue (ex. 720) ; None = la meilleure
        self.filename = None
        self.path = None
        self.total = None                 # estimation en octets
        self.resumable = True
        self.status = QUEUED
        self.error = None
        self.note = None
        self.speed = 0.0
        self.active = 0
        self.variant = None               # ex. "1280x720"
        self.tracks = {}                  # "v" / "a" -> {"segments": [...], "init": ...}
        self._done = {}                   # (piste, index) -> taille
        self._bytes = 0
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._fatal = None
        self._runner = None
        self._keys = {}
        self._probed = False
        self._waiting = None
        self._user_paused = False

    # -------------------------------------------------------- interface commune
    @property
    def part(self):
        return self.path + ".parts"

    @property
    def state_file(self):
        return self.path + ".parts"

    @property
    def is_running(self):
        return self._runner is not None and self._runner.is_alive()

    def _count(self):
        return sum(len(t["segments"]) for t in self.tracks.values())

    @property
    def downloaded(self):
        return self._bytes

    @property
    def progress(self):
        if self.status == COMPLETED:
            return 1.0
        n = self._count()
        return len(self._done) / n if n else 0.0

    @property
    def eta(self):
        n, d = self._count(), len(self._done)
        if not n or not d or self.speed < 1:
            return None
        return (self._bytes / d) * (n - d) / self.speed

    def ui_segments(self):
        """Carte de progression : jusqu'à 48 blocs, remplis selon les segments finis."""
        v = self.tracks.get("v")
        if not v:
            return []
        n = len(v["segments"])
        blocks = min(48, n)
        out = []
        with self._lock:
            for b in range(blocks):
                s, e = b * n // blocks, (b + 1) * n // blocks
                done = sum(1 for i in range(s, e) if ("v", i) in self._done)
                out.append([s / n, e / n, s / n + (done / max(1, e - s)) * (e - s) / n])
        return out

    def start(self):
        if self.is_running or self.status == COMPLETED:
            return
        self._stop.clear()
        self._fatal = None
        self.error = None
        self._waiting = None
        self._user_paused = False
        self._runner = threading.Thread(target=self._run, daemon=True)
        self._runner.start()

    def pause(self):
        self._user_paused = True
        self._stop.set()

    def wait(self):
        if self._runner:
            self._runner.join()

    def to_record(self):
        return {"kind": "hls", "url": self.url, "dest_dir": self.dest_dir, "connections": self.connections,
                "filename": self.filename, "path": self.path, "total": self.total, "status": self.status,
                "error": self.error, "quality": self.quality, "variant": self.variant,
                "headers": {k: v for k, v in self.headers.items() if k.lower() != "cookie"}}

    @classmethod
    def from_record(cls, r):
        d = cls(r["url"], r.get("dest_dir", "."), r.get("connections", 8), headers=r.get("headers"),
                quality=r.get("quality"))
        d.filename, d.path, d.total, d.variant = r.get("filename"), r.get("path"), r.get("total"), r.get("variant")
        d.error = r.get("error")
        if r.get("status") == COMPLETED and d.path and os.path.exists(d.path):
            d.status = COMPLETED
        else:
            d.status = ERROR if r.get("status") == ERROR else WAITING if r.get("status") == WAITING else PAUSED
        return d

    def cleanup_files(self, delete_final=False):
        if not self.path:
            return
        shutil.rmtree(self.part, ignore_errors=True)
        if delete_final and os.path.isfile(self.path):
            os.remove(self.path)
        release_path(self.path)

    # -------------------------------------------------------- réseau
    def _h(self, extra=None, url=None):
        h = {"User-Agent": UA}
        h.update(self.headers)
        # les cookies ne partent que vers le site de la playlist, jamais vers un autre
        if urllib.parse.urlparse(url or self.url).hostname != urllib.parse.urlparse(self.url).hostname:
            h = {k: v for k, v in h.items() if k.lower() not in ("cookie", "authorization")}
        h.update(extra or {})
        return h

    def _get(self, url, extra=None, limit=MAX_SEGMENT):
        req = urllib.request.Request(url, headers=self._h(extra, url))
        with http_open(req) as r:
            data = r.read(limit + 1)
            if len(data) > limit:
                raise ValueError("réponse anormalement grosse : téléchargement arrêté")
            return data, r.geturl()

    def _playlist(self, url):
        data, final = self._get(url, limit=8 * 1024 * 1024)
        return parse_playlist(data.decode("utf-8", "replace"), final)

    # -------------------------------------------------------- sondage
    def probe(self):
        pl = self._playlist(self.url)
        audio_url = None
        if pl["type"] == "master":
            vs = sorted(pl["variants"], key=lambda v: (v["height"], v["bandwidth"]))
            if not vs:
                raise ValueError("aucune qualité trouvée dans la playlist")
            chosen = vs[-1]
            if self.quality:
                fit = [v for v in vs if v["height"] and v["height"] <= int(self.quality)]
                chosen = fit[-1] if fit else vs[0]
            self.variant = chosen["resolution"] or f"{chosen['bandwidth'] // 1000} kb/s"
            audio_url = pl["audio"].get(chosen["audio"]) if chosen["audio"] else None
            pl = self._playlist(chosen["url"])
            if pl["type"] != "media":
                raise ValueError("playlist imbriquée non prise en charge")
        self.tracks = {"v": pl}
        if audio_url:
            self.tracks["a"] = self._playlist(audio_url)
        if any(s["key"] for t in self.tracks.values() for s in t["segments"]) and Cipher is None:
            raise ValueError("vidéo chiffrée : module cryptography manquant")
        if not self.filename:
            if self.forced_name:
                base = os.path.splitext(self.forced_name)[0]
            else:
                p = urllib.parse.urlparse(self.url).path
                base = os.path.splitext(os.path.basename(p))[0] or "video"
                if base.lower() in ("index", "master", "playlist", "manifest", "video", "chunklist"):
                    base = "video-" + time.strftime("%Y%m%d-%H%M%S")
            self.filename = _safe_name(base) + self._final_ext()
        self._probed = True

    def _final_ext(self):
        if find_ffmpeg() or self.tracks["v"].get("init"):
            return ".mp4"
        return ".ts"

    # -------------------------------------------------------- déroulement
    def _run(self):
        try:
            if not self._probed:          # aussi après une reprise : les liens des segments expirent
                self.status = PROBING
                self.probe()
            if not self.path:
                os.makedirs(self.dest_dir, exist_ok=True)
                self.path = claim_path(os.path.join(self.dest_dir, self.filename), self)
                self.filename = os.path.basename(self.path)
            os.makedirs(self.part, exist_ok=True)
            self._scan_done()
            if self.total and self._done:
                check_free_space(self.dest_dir, (self.total - self._bytes) * 2)   # segments + fichier final
            self.status = DOWNLOADING
            self._run_workers()
            if self._fatal:
                raise RuntimeError(self._fatal)
            if self._waiting:
                raise NetworkDown(self._waiting)
            if len(self._done) == self._count():
                self._assemble()
                self.status = COMPLETED
            else:
                self.status = PAUSED
        except ProtectedContent as e:
            self.error, self.status = str(e), ERROR
        except Exception as e:  # noqa: BLE001
            if is_network_error(e) and not self._user_paused:
                self.error, self.status = f"en attente du réseau ({e})", WAITING   # reprise automatique
            else:
                self.error, self.status = str(e), ERROR
        finally:
            self.speed, self.active = 0.0, 0
            self._probed = False           # au prochain départ, on relit la playlist
            if self.status in (COMPLETED, ERROR):
                release_path(self.path)

    def _seg_file(self, track, i):
        return os.path.join(self.part, f"{track}{i:06d}.seg")

    def _scan_done(self):
        self._done, self._bytes = {}, 0
        for t, pl in self.tracks.items():
            for i in range(len(pl["segments"])):
                f = self._seg_file(t, i)
                if os.path.isfile(f):
                    n = os.path.getsize(f)
                    self._done[(t, i)] = n
                    self._bytes += n
        self._estimate()

    def _estimate(self):
        n, d = self._count(), len(self._done)
        if d:
            self.total = int(self._bytes / d * n)

    def _run_workers(self):
        todo = [(t, i) for t, pl in self.tracks.items() for i in range(len(pl["segments"])) if (t, i) not in self._done]
        it = iter(todo)
        lock = threading.Lock()

        def worker():
            while not self._stop.is_set():
                with lock:
                    job = next(it, None)
                if job is None:
                    return
                self._fetch_segment(*job)

        ws = [threading.Thread(target=worker, daemon=True) for _ in range(self.connections)]
        for w in ws:
            w.start()
        last_t, last_b = time.time(), self._bytes
        while any(w.is_alive() for w in ws):
            time.sleep(0.5)
            now, b = time.time(), self._bytes
            inst = (b - last_b) / max(1e-6, now - last_t)
            self.speed = inst if self.speed == 0 else 0.7 * self.speed + 0.3 * inst
            last_t, last_b = now, b
        for w in ws:
            w.join()

    def _key(self, uri):
        with self._lock:
            if uri in self._keys:
                return self._keys[uri]
        data, _ = self._get(uri, limit=64)
        if len(data) != 16:
            raise ProtectedContent("clé de chiffrement invalide (contenu probablement protégé)")
        with self._lock:
            self._keys[uri] = data
        return data

    def _decrypt(self, data, seg):
        k = seg["key"]
        key = self._key(k["uri"])
        iv = bytes.fromhex(k["iv"][2:].rjust(32, "0")) if k.get("iv") else seg["seq"].to_bytes(16, "big")
        dec = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        out = dec.update(data) + dec.finalize()
        pad = out[-1] if out else 0
        return out[:-pad] if 1 <= pad <= 16 and out.endswith(bytes([pad]) * pad) else out

    def _fetch_segment(self, track, i):
        seg = self.tracks[track]["segments"][i]
        retries = 0
        while not self._stop.is_set():
            try:
                with self._lock:
                    self.active += 1
                try:
                    data, _ = self._get(seg["url"], {"Range": seg["range"]} if seg["range"] else None)
                finally:
                    with self._lock:
                        self.active -= 1
                if seg["key"]:
                    data = self._decrypt(data, seg)
                tmp = self._seg_file(track, i) + ".tmp"
                with open(tmp, "wb") as f:
                    f.write(data)
                    f.flush()
                    os.fsync(f.fileno())          # un segment « fini » doit vraiment être sur le disque
                os.replace(tmp, self._seg_file(track, i))
                with self._lock:
                    self._done[(track, i)] = len(data)
                    self._bytes += len(data)
                self._estimate()
                return
            except ProtectedContent as e:
                self._fatal = str(e)
                self._stop.set()
                return
            except urllib.error.HTTPError as e:
                if e.code not in (408, 429, 500, 502, 503, 504):
                    self._fatal = f"segment {i + 1} : HTTP {e.code} {e.reason} (lien expiré ? relancez depuis la page)"
                    self._stop.set()
                    return
                why = f"HTTP {e.code}"
            except (OSError, ValueError) as e:
                why = str(e)
                if is_network_error(e) and retries + 1 > NET_RETRIES:
                    self._waiting = why               # coupure durable : attente du réseau
                    self._stop.set()
                    return
            retries += 1
            if retries > MAX_RETRIES:
                self._fatal = f"segment {i + 1} : abandon après {MAX_RETRIES} essais ({why})"
                self._stop.set()
                return
            self._stop.wait(min(30, 2 ** retries))

    # -------------------------------------------------------- assemblage
    def _concat(self, track, out_path):
        pl = self.tracks[track]
        with open(out_path, "wb") as out:
            if pl.get("init"):
                data, _ = self._get(pl["init"]["url"], {"Range": pl["init"]["range"]} if pl["init"]["range"] else None)
                out.write(data)
            for i in range(len(pl["segments"])):
                with open(self._seg_file(track, i), "rb") as f:
                    shutil.copyfileobj(f, out, 1024 * 1024)

    def _assemble(self):
        self.status = "assemblage"
        ffmpeg = find_ffmpeg()
        vext = ".mp4" if self.tracks["v"].get("init") else ".ts"
        video = os.path.join(self.part, "video" + vext)
        self._concat("v", video)
        audio = None
        if "a" in self.tracks:
            aext = ".mp4" if self.tracks["a"].get("init") else ".ts"
            audio = os.path.join(self.part, "audio" + aext)
            self._concat("a", audio)

        if os.path.exists(self.path):        # un fichier de ce nom est apparu entre-temps : on ne l'écrase pas
            self.path = claim_path(self.path, self)
            self.filename = os.path.basename(self.path)
        if ffmpeg:
            cmd = [ffmpeg, "-y", "-loglevel", "error", "-i", video]
            if audio:
                cmd += ["-i", audio, "-map", "0:v:0?", "-map", "1:a:0?"]
            cmd += ["-c", "copy", "-movflags", "+faststart", self.path]
            flags = 0x08000000 if sys.platform == "win32" else 0      # pas de fenêtre console
            r = subprocess.run(cmd, capture_output=True, creationflags=flags)
            if r.returncode == 0 and os.path.isfile(self.path):
                shutil.rmtree(self.part, ignore_errors=True)
                return
            self.note = "conversion mp4 impossible, fichier .ts conservé"
        # sans ffmpeg (ou échec) : on garde le flux tel quel
        base = os.path.splitext(self.path)[0]
        final = base + vext
        if final != self.path:
            final = claim_path(final, self)
        os.replace(video, final)
        if audio:
            os.replace(audio, claim_path(base + ".audio" + os.path.splitext(audio)[1], self))
            self.note = "piste audio séparée enregistrée à côté (installez ffmpeg pour les fusionner)"
        self.path, self.filename = final, os.path.basename(final)
        shutil.rmtree(self.part, ignore_errors=True)
