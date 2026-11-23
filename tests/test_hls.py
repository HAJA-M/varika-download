"""
Tests du téléchargement HLS (nécessite ffmpeg pour fabriquer les vidéos de test) :

  python tests/test_hls.py

Fabrique de vraies vidéos HLS de 12 s, les sert en local et vérifie :
  1. playlist maître -> meilleure qualité (720p) choisie, mp4 lisible avec audio ;
  2. qualité demandée (360p) ;
  3. segments chiffrés AES-128 -> déchiffrés ;
  4. fMP4 avec piste audio séparée -> fusionnée par ffmpeg ;
  5. sans ffmpeg -> fichier .ts lisible ;
  6. pause / reprise ;
  7. DRM (SAMPLE-AES) refusé.
"""

import functools
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import hls  # noqa: E402
from engine import COMPLETED, ERROR, PAUSED  # noqa: E402

PORT = 8851
FF = shutil.which("ffmpeg")


def ff(*args):
    subprocess.run([FF, "-y", "-loglevel", "error", *args], check=True)


def make_fixtures(root):
    src = ["-f", "lavfi", "-i", "testsrc=size={s}:rate=25", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "12"]
    for name, size in (("v360", "640x360"), ("v720", "1280x720")):
        os.makedirs(f"{root}/{name}")
        ff(*[a.format(s=size) for a in src], "-c:v", "libx264", "-g", "50", "-c:a", "aac", "-f", "hls",
           "-hls_time", "2", "-hls_playlist_type", "vod", f"{root}/{name}/index.m3u8")
    with open(f"{root}/master.m3u8", "w") as f:
        f.write("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nv360/index.m3u8\n"
                "#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720\nv720/index.m3u8\n")
    # chiffré AES-128
    os.makedirs(f"{root}/enc")
    with open(f"{root}/enc/key.bin", "wb") as f:
        f.write(os.urandom(16))
    with open(f"{root}/enc/keyinfo", "w") as f:
        f.write(f"key.bin\n{root}/enc/key.bin\n")
    ff(*[a.format(s="640x360") for a in src], "-c:v", "libx264", "-c:a", "aac", "-f", "hls", "-hls_time", "2",
       "-hls_playlist_type", "vod", "-hls_key_info_file", f"{root}/enc/keyinfo", f"{root}/enc/index.m3u8")
    # fMP4 avec audio séparé
    os.makedirs(f"{root}/fmp4/v")
    os.makedirs(f"{root}/fmp4/a")
    ff("-f", "lavfi", "-i", "testsrc=size=854x480:rate=25", "-t", "12", "-c:v", "libx264", "-g", "50", "-an",
       "-f", "hls", "-hls_time", "2", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4", f"{root}/fmp4/v/index.m3u8")
    ff("-f", "lavfi", "-i", "sine=frequency=660", "-t", "12", "-c:a", "aac", "-f", "hls", "-hls_time", "2",
       "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4", f"{root}/fmp4/a/index.m3u8")
    with open(f"{root}/fmp4/master.m3u8", "w") as f:
        f.write('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="fr",DEFAULT=YES,URI="a/index.m3u8"\n'
                '#EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=854x480,AUDIO="aud"\nv/index.m3u8\n')
    # DRM
    with open(f"{root}/drm.m3u8", "w") as f:
        f.write('#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://x",KEYFORMAT="com.apple.streamingkeydelivery"\n'
                '#EXTINF:2,\nv360/index0.ts\n#EXT-X-ENDLIST\n')


class Slow(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def end_headers(self):
        if self.path.endswith(".ts") or self.path.endswith(".m4s"):
            time.sleep(0.4)           # pour pouvoir tester la pause
        super().end_headers()


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "stream=codec_type,height:format=duration",
                          "-of", "json", path], capture_output=True, text=True, check=True).stdout
    j = json.loads(out)
    kinds = sorted(s["codec_type"] for s in j["streams"])
    height = next((s.get("height") for s in j["streams"] if s["codec_type"] == "video"), None)
    return kinds, height, float(j["format"]["duration"])


def run(url, out, **kw):
    d = hls.HlsDownload(url, out, 6, **kw)
    d.start()
    d.wait()
    return d


def main():
    if not FF:
        print("ffmpeg introuvable : test ignoré")
        return
    root = tempfile.mkdtemp()
    make_fixtures(root)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), functools.partial(Slow, directory=root))
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{PORT}"
    out = os.path.join(root, "sortie")
    try:
        d = run(f"{base}/master.m3u8", out)
        assert d.status == COMPLETED, d.error
        k, h, dur = probe(d.path)
        assert k == ["audio", "video"] and h == 720 and 11 < dur < 13, (k, h, dur)
        print(f"[ok] playlist maître -> {d.variant}, {d.filename}, {dur:.1f} s, audio + vidéo")

        d = run(f"{base}/master.m3u8", out, quality=480)
        assert d.status == COMPLETED and probe(d.path)[1] == 360, d.error
        print("[ok] qualité demandée <= 480p -> 360p choisie")

        d = run(f"{base}/enc/index.m3u8", out, filename="Ma vidéo chiffrée.mp4")
        assert d.status == COMPLETED, d.error
        k, h, dur = probe(d.path)
        assert k == ["audio", "video"] and 11 < dur < 13, (k, dur)
        print(f"[ok] segments AES-128 déchiffrés -> « {d.filename} » lisible")

        d = run(f"{base}/fmp4/master.m3u8", out)
        assert d.status == COMPLETED, d.error
        k, h, dur = probe(d.path)
        assert k == ["audio", "video"] and h == 480, (k, h)
        print("[ok] fMP4 + piste audio séparée -> fusionnées dans un seul mp4")

        real = hls.find_ffmpeg
        hls.find_ffmpeg = lambda: None
        try:
            d = run(f"{base}/v360/index.m3u8", out)
        finally:
            hls.find_ffmpeg = real
        assert d.status == COMPLETED and d.path.endswith(".ts"), (d.status, d.path, d.error)
        k, _, dur = probe(d.path)
        assert k == ["audio", "video"] and 11 < dur < 13
        print(f"[ok] sans ffmpeg -> {d.filename} (flux .ts lisible par VLC)")

        d = hls.HlsDownload(f"{base}/v720/index.m3u8", out, 2)
        d.start()
        while d.progress == 0 and d.status != ERROR:
            time.sleep(0.05)
        d.pause()
        d.wait()
        assert d.status == PAUSED and 0 < d.progress < 1, (d.status, d.progress)
        mid = d.progress
        rec = d.to_record()
        d2 = hls.HlsDownload.from_record(rec)          # comme après un redémarrage
        d2.start()
        d2.wait()
        assert d2.status == COMPLETED and d2.path == d.path, d2.error
        assert probe(d2.path)[0] == ["audio", "video"]
        print(f"[ok] pause à {mid * 100:.0f} % puis reprise après redémarrage -> vidéo complète")

        d = run(f"{base}/drm.m3u8", out)
        assert d.status == ERROR and "DRM" in d.error, (d.status, d.error)
        print(f"[ok] DRM refusé : « {d.error} »")
        print("Tous les tests HLS passent.")
    finally:
        srv.shutdown()
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    main()
