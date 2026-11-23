"""
Varika Download — moteur de téléchargement multi-connexions (version pédagogique).

Le principe d'un gestionnaire comme IDM tient en 4 idées :

1. SONDER le serveur : on envoie une requête avec l'en-tête « Range: bytes=0-0 ».
   - Réponse 206 (Partial Content) + « Content-Range: bytes 0-0/TAILLE »
     => le serveur accepte les plages d'octets : on peut découper.
   - Réponse 200 => pas de découpage possible : une seule connexion, pas de reprise.

2. DÉCOUPER le fichier en N segments et ouvrir N connexions en parallèle,
   chacune demandant sa plage (« Range: bytes=debut-fin »). Beaucoup de serveurs
   limitent le débit PAR connexion : N connexions => jusqu'à N fois plus vite.
   Chaque connexion écrit directement à la bonne position (seek) dans un fichier
   pré-alloué à la taille finale.

3. SEGMENTATION DYNAMIQUE : quand une connexion a fini son segment, elle ne reste
   pas inactive. Elle « vole » la moitié du plus gros segment restant d'une autre
   connexion. Toutes les connexions restent occupées jusqu'à la fin.

4. REPRISE : la position de chaque segment est sauvegardée régulièrement dans un
   fichier « .part.json ». Après une pause, une coupure ou un plantage, on relit
   ce fichier et on redemande seulement les octets manquants.
"""

import json
import os
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

CHUNK = 64 * 1024            # taille d'une lecture réseau
MIN_SPLIT = 512 * 1024       # on ne découpe pas un segment restant plus petit
MAX_RETRIES = 8              # tentatives par connexion avant abandon
TIMEOUT = 30                 # secondes sans données avant de réessayer
UA = "Mozilla/5.0 (Varika Download)"

# Statuts affichés
QUEUED = "en attente"
PROBING = "analyse"
DOWNLOADING = "téléchargement"
PAUSED = "en pause"
COMPLETED = "terminé"
ERROR = "erreur"


# ----------------------------------------------------------------- utilitaires

def human_size(n):
    if n is None:
        return "?"
    for unit in ("o", "Ko", "Mo", "Go", "To"):
        if abs(n) < 1024 or unit == "To":
            return f"{n:.0f} {unit}" if unit == "o" else f"{n:.1f} {unit}"
        n /= 1024


def human_time(sec):
    if sec is None:
        return "—"
    sec = int(sec)
    h, r = divmod(sec, 3600)
    m, s = divmod(r, 60)
    return f"{h}h{m:02d}m" if h else (f"{m}m{s:02d}s" if m else f"{s}s")


def _filename_from_headers(headers, url):
    """Nom du fichier : Content-Disposition d'abord, sinon fin de l'URL."""
    cd = headers.get("Content-Disposition", "") or ""
    m = re.search(r"filename\*\s*=\s*([^']*)'[^']*'([^;]+)", cd, re.I)
    if m:
        return urllib.parse.unquote(m.group(2).strip('"'), encoding=m.group(1) or "utf-8")
    m = re.search(r'filename\s*=\s*"?([^";]+)"?', cd, re.I)
    if m:
        return m.group(1).strip()
    name = os.path.basename(urllib.parse.urlparse(url).path)
    return urllib.parse.unquote(name) or "telechargement"


def _safe_name(name):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name or "telechargement"


def _unique_path(path):
    """Évite d'écraser un fichier existant : nom (1).ext, nom (2).ext…"""
    if not os.path.exists(path) and not os.path.exists(path + ".part"):
        return path
    base, ext = os.path.splitext(path)
    i = 1
    while True:
        p = f"{base} ({i}){ext}"
        if not os.path.exists(p) and not os.path.exists(p + ".part"):
            return p
        i += 1


# -------------------------------------------------------------------- segment

class Segment:
    """Une plage d'octets [start, end] (incluse) ; pos = prochain octet à écrire."""

    __slots__ = ("start", "end", "pos", "busy")

    def __init__(self, start, end, pos=None):
        self.start = start
        self.end = end
        self.pos = start if pos is None else pos
        self.busy = False          # une connexion travaille dessus

    @property
    def remaining(self):
        return max(0, self.end - self.pos + 1)

    @property
    def done(self):
        return self.pos > self.end

    def to_dict(self):
        return {"start": self.start, "end": self.end, "pos": self.pos}


# ------------------------------------------------------------------- download

class Download:
    def __init__(self, url, dest_dir=".", connections=8, filename=None, headers=None):
        self.url = url
        self.dest_dir = dest_dir
        self.connections = max(1, min(32, int(connections)))
        self.forced_name = filename
        # En-têtes transmis par le navigateur (Referer, Cookie, User-Agent) :
        # indispensables pour les liens protégés par une session.
        self.headers = {k: v for k, v in (headers or {}).items() if v}
        self._probed = False

        self.filename = None
        self.path = None           # chemin final
        self.total = None          # taille en octets (None = inconnue)
        self.resumable = False     # le serveur accepte les Range ?
        self.segments = []
        self.status = QUEUED
        self.error = None
        self.speed = 0.0           # octets/s (moyenne lissée)
        self.active = 0            # connexions ouvertes en ce moment

        self._stream_bytes = 0     # compteur du mode une seule connexion
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._fatal = None
        self._runner = None

    # ------------------------------------------------- sauvegarde de la liste
    def to_record(self):
        """Ce qu'il faut garder pour retrouver le téléchargement au prochain lancement."""
        return {"url": self.url, "dest_dir": self.dest_dir, "connections": self.connections,
                "filename": self.filename, "path": self.path, "total": self.total,
                "resumable": self.resumable, "status": self.status, "error": self.error,
                # Les cookies ne sont pas écrits sur le disque (ce sont des secrets de session).
                "headers": {k: v for k, v in self.headers.items() if k.lower() != "cookie"}}

    @classmethod
    def from_record(cls, r):
        d = cls(r["url"], r.get("dest_dir", "."), r.get("connections", 8), headers=r.get("headers"))
        d.filename, d.path = r.get("filename"), r.get("path")
        d.total, d.resumable = r.get("total"), r.get("resumable", False)
        d.error = r.get("error")
        if r.get("status") == COMPLETED and d.path and os.path.exists(d.path):
            d.status = COMPLETED
            return d
        d.status = ERROR if r.get("status") == ERROR else PAUSED
        if d.path and d.resumable and d._try_load_state(d.path):
            return d                          # reprise possible là où on s'était arrêté
        d.path, d.total, d.resumable, d.segments = None, None, False, []   # on recommencera
        return d

    kind = "file"

    def cleanup_files(self, delete_final=False):
        """Supprime les fichiers sur le disque (fichier final seulement si demandé)."""
        if not self.path:
            return
        for p in ((self.path,) if delete_final else ()) + (self.part, self.state_file):
            try:
                if os.path.isfile(p):
                    os.remove(p)
            except OSError:
                pass

    # ---------------------------------------------------------------- API
    def start(self):
        if self.is_running or self.status == COMPLETED:
            return
        self._stop.clear()
        self._fatal = None
        self.error = None
        self._runner = threading.Thread(target=self._run, daemon=True)
        self._runner.start()

    def pause(self):
        self._stop.set()

    def wait(self):
        if self._runner:
            self._runner.join()

    @property
    def is_running(self):
        return self._runner is not None and self._runner.is_alive()

    @property
    def part(self):
        return self.path + ".part"

    @property
    def state_file(self):
        return self.path + ".part.json"

    @property
    def downloaded(self):
        if not self.resumable:
            return self._stream_bytes
        with self._lock:
            return sum(s.pos - s.start for s in self.segments)

    @property
    def progress(self):
        if self.status == COMPLETED:
            return 1.0
        if not self.total:
            return 0.0
        return min(1.0, self.downloaded / self.total)

    @property
    def eta(self):
        if not self.total or self.speed < 1:
            return None
        return (self.total - self.downloaded) / self.speed

    def segment_map(self):
        """Copie des segments pour l'affichage : [(start, end, pos), …]"""
        with self._lock:
            return [(s.start, s.end, s.pos) for s in self.segments]

    # ------------------------------------------------------ déroulement
    def _run(self):
        try:
            if self.path is None:
                if not self._probed:
                    self.status = PROBING
                    self._probe()
                self._prepare()
            self.status = DOWNLOADING
            if self.resumable:
                self._run_segmented()
                complete = all(s.done for s in self.segments)
            else:
                complete = self._run_single()
            if complete:
                self._finish()
            else:
                self._save_state()
                self.status = PAUSED
        except Exception as e:  # noqa: BLE001 — on affiche l'erreur à l'utilisateur
            self.error = str(e)
            self.status = ERROR
            try:
                self._save_state()
            except Exception:
                pass
        finally:
            self.speed = 0.0
            self.active = 0

    # 1) Sonder le serveur
    def _h(self, extra=None):
        h = {"User-Agent": UA}
        h.update(self.headers)
        h.update(extra or {})
        return h

    def probe(self):
        """Sonde le serveur tout de suite (lève une exception si le lien ne marche pas)."""
        self._probe()

    def _probe(self):
        req = urllib.request.Request(self.url, headers=self._h({"Range": "bytes=0-0"}))
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            final_url = r.geturl()            # après d'éventuelles redirections
            h = r.headers
            if r.status == 206:
                m = re.search(r"/(\d+)\s*$", h.get("Content-Range", ""))
                if m:
                    self.total = int(m.group(1))
                    self.resumable = self.total > 0
            elif r.status == 200:
                cl = h.get("Content-Length")
                self.total = int(cl) if cl and cl.isdigit() else None
            name = self.forced_name or _filename_from_headers(h, final_url)
        self.url = final_url
        self.filename = _safe_name(name)
        self._probed = True

    # 2) Préparer le fichier et les segments (ou reprendre un état sauvegardé)
    def _prepare(self):
        os.makedirs(self.dest_dir, exist_ok=True)
        wanted = os.path.join(self.dest_dir, self.filename)

        if self.resumable and self._try_load_state(wanted):
            return

        self.path = _unique_path(wanted)
        self.filename = os.path.basename(self.path)
        if not self.resumable:
            return

        # Fichier pré-alloué à la taille finale : chaque connexion écrira à sa place.
        with open(self.part, "wb") as f:
            f.truncate(self.total)

        n = max(1, min(self.connections, self.total // MIN_SPLIT))
        size = self.total // n
        self.segments = [
            Segment(i * size, (i + 1) * size - 1 if i < n - 1 else self.total - 1)
            for i in range(n)
        ]
        self._save_state()

    def _try_load_state(self, path):
        sf = path + ".part.json"
        if not (os.path.exists(sf) and os.path.exists(path + ".part")):
            return False
        try:
            with open(sf, encoding="utf-8") as f:
                st = json.load(f)
        except (OSError, ValueError):
            return False
        if st.get("url") != self.url or st.get("total") != self.total:
            return False
        self.path = path
        self.segments = [Segment(d["start"], d["end"], d["pos"]) for d in st["segments"]]
        return True

    def _save_state(self):
        if not (self.resumable and self.path):
            return
        with self._lock:
            data = {"url": self.url, "total": self.total,
                    "segments": [s.to_dict() for s in self.segments]}
        tmp = self.state_file + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(tmp, self.state_file)   # écriture atomique

    # 3) Mode multi-connexions
    def _run_segmented(self):
        workers = [threading.Thread(target=self._worker, daemon=True)
                   for _ in range(self.connections)]
        for w in workers:
            w.start()

        last_t, last_b, last_save = time.time(), self.downloaded, time.time()
        while any(w.is_alive() for w in workers):
            time.sleep(0.5)
            now, b = time.time(), self.downloaded
            inst = (b - last_b) / max(1e-6, now - last_t)
            self.speed = inst if self.speed == 0 else 0.7 * self.speed + 0.3 * inst
            last_t, last_b = now, b
            if now - last_save > 2:
                self._save_state()
                last_save = now
        for w in workers:
            w.join()
        if self._fatal:
            raise RuntimeError(self._fatal)

    def _worker(self):
        """Une connexion : prend un segment, le télécharge, recommence."""
        while not self._stop.is_set():
            seg = self._acquire()
            if seg is None:
                return                       # plus rien à faire pour cette connexion
            try:
                self._fetch(seg)
            finally:
                with self._lock:
                    seg.busy = False

    def _acquire(self):
        with self._lock:
            # a) un segment libre et inachevé (ex. après une reprise)
            for s in self.segments:
                if not s.busy and not s.done:
                    s.busy = True
                    return s
            # b) segmentation dynamique : couper en deux le plus gros segment en cours
            cands = [s for s in self.segments
                     if s.busy and not s.done and s.remaining >= 2 * MIN_SPLIT]
            if not cands:
                return None
            big = max(cands, key=lambda s: s.remaining)
            mid = big.pos + big.remaining // 2
            new = Segment(mid, big.end)
            new.busy = True
            big.end = mid - 1          # l'autre connexion s'arrêtera plus tôt
            self.segments.append(new)
            return new

    def _fetch(self, seg):
        retries = 0
        while not self._stop.is_set() and not seg.done:
            try:
                req = urllib.request.Request(self.url, headers=self._h({"Range": f"bytes={seg.pos}-{seg.end}"}))
                with urllib.request.urlopen(req, timeout=TIMEOUT) as r, open(self.part, "r+b") as f:
                    if r.status != 206:
                        raise RuntimeError("le serveur a ignoré la plage demandée")
                    with self._lock:
                        self.active += 1
                    try:
                        while not self._stop.is_set():
                            with self._lock:
                                want = min(CHUNK, seg.end - seg.pos + 1)
                            if want <= 0:
                                break        # segment fini (ou raccourci par un vol)
                            data = r.read(want)
                            if not data:
                                raise ConnectionError("connexion fermée trop tôt")
                            # On réserve la zone sous verrou (seg.end a pu rétrécir
                            # entre-temps), puis on écrit hors verrou.
                            with self._lock:
                                data = data[:max(0, seg.end - seg.pos + 1)]
                                off = seg.pos
                                seg.pos += len(data)
                            try:
                                f.seek(off)
                                f.write(data)
                            except OSError:
                                with self._lock:
                                    seg.pos = off   # annule la réservation
                                raise
                            retries = 0
                    finally:
                        with self._lock:
                            self.active -= 1
            except urllib.error.HTTPError as e:
                if e.code not in (408, 429, 500, 502, 503, 504):
                    self._fatal = f"HTTP {e.code} {e.reason}"
                    self._stop.set()
                    return
                retries = self._backoff(retries, f"HTTP {e.code}")
            except (OSError, ConnectionError, RuntimeError) as e:
                retries = self._backoff(retries, str(e))
            if retries < 0:
                return

    def _backoff(self, retries, why):
        retries += 1
        if retries > MAX_RETRIES:
            self._fatal = f"abandon après {MAX_RETRIES} essais : {why}"
            self._stop.set()
            return -1
        self._stop.wait(min(30, 2 ** retries))   # 2 s, 4 s, 8 s… (interruptible)
        return retries

    # Mode une seule connexion (serveur sans Range) : pas de reprise possible
    def _run_single(self):
        self._stream_bytes = 0
        req = urllib.request.Request(self.url, headers=self._h())
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r, open(self.part, "wb") as f:
            self.active = 1
            last_t, last_b = time.time(), 0
            while not self._stop.is_set():
                data = r.read(CHUNK)
                if not data:
                    break
                f.write(data)
                self._stream_bytes += len(data)
                now = time.time()
                if now - last_t >= 0.5:
                    inst = (self._stream_bytes - last_b) / (now - last_t)
                    self.speed = inst if self.speed == 0 else 0.7 * self.speed + 0.3 * inst
                    last_t, last_b = now, self._stream_bytes
        if self._stop.is_set():
            return False
        if self.total is None:
            self.total = self._stream_bytes
        return self._stream_bytes == self.total

    # 4) Assemblage final
    def _finish(self):
        os.replace(self.part, self.path)
        if os.path.exists(self.state_file):
            os.remove(self.state_file)
        self.status = COMPLETED
