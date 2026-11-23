"""
Teste le moteur de l'application Android (Java) sur ordinateur, avec un JDK :

  python tests/test_android_core.py

Compile android/app/src/main/java/.../core en Java 8 (API compatible Android) puis lance
android/jvm-tests/CoreTest.java contre les mêmes serveurs de test que la version Windows.
"""

import functools
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import test_bugs  # noqa: E402
import test_hls  # noqa: E402
import test_reseau  # noqa: E402


class Control(test_bugs.H):
    """Serveur des tests de bugs + /control pour piloter les scénarios depuis Java."""

    def do_GET(self):
        if self.path.startswith("/control"):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            S = test_bugs.STATE
            if "slow" in q:
                S["slow"] = float(q["slow"][0])
            if "token" in q:
                S["valid_token"] = q["token"][0]
            if "changed" in q:
                S["changed"] = q["changed"][0] == "1"
            if "reset_cookies" in q:
                S["cookies"].clear()
            body = ""
            if "sha" in q:
                body = hashlib.sha256(test_bugs.DATA).hexdigest()
            if "sha2" in q:
                body = hashlib.sha256(test_bugs.DATA2).hexdigest()
            if "cookies" in q:
                body = json.dumps(S["cookies"])
            b = body.encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(b)))
            self.end_headers()
            self.wfile.write(b)
            return
        super().do_GET()


class Quiet(SimpleHTTPRequestHandler):
    def log_message(self, *a):
        pass


def main():
    tmp = tempfile.mkdtemp()
    build = os.path.join(tmp, "classes")
    src = glob.glob(os.path.join(ROOT, "android/app/src/main/java/com/varikaagency/download/core/*.java"))
    subprocess.run(["javac", "--release", "8", "-nowarn", "-d", build, *src,
                    os.path.join(ROOT, "android/jvm-tests/CoreTest.java")], check=True)
    print(f"[ok] moteur Android compilé ({len(src)} fichiers, API Java 8)")

    site = os.path.join(tmp, "site")
    os.makedirs(site)
    test_hls.make_fixtures(site)
    # pistes séparées image / son, en MP4 fragmenté comme Facebook
    os.makedirs(os.path.join(site, "dash"))
    frag = ["-movflags", "frag_keyframe+empty_moov+default_base_moof"]
    test_hls.ff("-f", "lavfi", "-i", "testsrc=size=854x480:rate=25", "-t", "12", "-c:v", "libx264", "-an", *frag,
                os.path.join(site, "dash", "video.mp4"))
    test_hls.ff("-f", "lavfi", "-i", "sine", "-t", "12", "-c:a", "aac", *frag, os.path.join(site, "dash", "audio.mp4"))
    b = ThreadingHTTPServer(("127.0.0.1", test_bugs.PORT), Control)   # les redirections de test visent ce port
    h = ThreadingHTTPServer(("127.0.0.1", 8872), functools.partial(Quiet, directory=site))
    for s in (b, h):
        s.daemon_threads = True
        threading.Thread(target=s.serve_forever, daemon=True).start()
    out = os.path.join(tmp, "sortie")
    gen = test_reseau.Server()                 # serveur que l'on peut « couper » comme Internet
    gen.up()
    env = dict(os.environ, NO_PROXY="127.0.0.1,localhost", no_proxy="127.0.0.1,localhost")
    p = subprocess.Popen(["java", "-Dhttp.nonProxyHosts=127.0.0.1|localhost", "-Djava.net.preferIPv4Stack=true", "-cp", build,
                          "CoreTest", f"http://127.0.0.1:{test_bugs.PORT}", "http://127.0.0.1:8872", out,
                          f"http://127.0.0.1:{test_reseau.PORT}"],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, env=env)
    files = {}
    for line in p.stdout:
        line = line.rstrip("\n")
        if line.startswith("CTRL "):
            cmd = line[5:]
            if cmd == "down":
                gen.down()
            elif cmd == "up":
                gen.up()
                test_reseau.STATE["rate"] = 0
            elif cmd == "slow":
                test_reseau.STATE["rate"] = 8 * 1024 * 1024
            p.stdin.write("OK\n")
            p.stdin.flush()
        elif line.startswith("FILE "):
            _, name, path = line.split(" ", 2)
            files[name] = path
        else:
            print(line, flush=True)
    rc = p.wait(timeout=600)
    if rc != 0:
        print(p.stderr.read()[-2000:])
        sys.exit(1)
    for name, size in (("reseau", 41943040), ("reseau-auto", 41943041)):
        assert test_reseau.file_sha(files.pop(name)) == test_reseau.expected_sha(size), name
        print(f"[ok] {name} : fichier identique à l'original (SHA-256)")
    # Les vidéos produites par le moteur Java sont-elles lisibles ?
    for name, path in files.items():
        kinds, height, dur = test_hls.probe(path)
        want = "audio" if name == "dash-son" else "video"
        assert want in kinds and 11 < dur < 13, (name, kinds, dur)
        print(f"[ok] {name} : {os.path.basename(path)} lisible ({', '.join(kinds)}, {height}p, {dur:.0f} s)")
    b.shutdown()
    h.shutdown()
    shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
