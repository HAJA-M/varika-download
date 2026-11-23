"""
Varika Download — point d'entrée.

  python cli.py                          -> ouvre l'interface graphique
  python cli.py URL [-c 8] [-o dossier]  -> télécharge en ligne de commande
                                             (Ctrl+C = pause ; relancer la même
                                             commande = reprise)
"""

import argparse
import os
import sys
import time

import urllib.parse

from engine import COMPLETED, ERROR, Download, human_size, human_time
from hls import HlsDownload


def make_download(url, out, connections):
    """C1 : un lien .m3u8 est une vidéo en streaming, le reste un fichier classique."""
    if urllib.parse.urlparse(url).path.lower().endswith((".m3u8", ".m3u")):
        return HlsDownload(url, out, connections)
    return Download(url, out, connections)


def cli(url, connections, out):
    d = make_download(url, out, connections)
    d.start()
    try:
        while d.is_running:
            time.sleep(0.5)
            bar_len = 30
            filled = int(d.progress * bar_len)
            sys.stdout.write(
                f"\r[{'#' * filled}{'.' * (bar_len - filled)}] {d.progress * 100:5.1f} % "
                f"{human_size(d.downloaded)}/{human_size(d.total)} "
                f"{human_size(d.speed)}/s  {d.active} conn.  reste {human_time(d.eta)}   "
            )
            sys.stdout.flush()
    except KeyboardInterrupt:
        d.pause()
        d.wait()
    print()
    if d.status == COMPLETED:
        print(f"Terminé : {d.path}")
    elif d.status == ERROR:
        print(f"Erreur : {d.error}")
        return 1
    else:
        print("En pause. Relance la même commande pour reprendre.")
    return 0


def main():
    p = argparse.ArgumentParser(description="Varika Download, gestionnaire de téléchargement multi-connexions")
    p.add_argument("url", nargs="?", help="URL à télécharger (sans URL : interface graphique)")
    p.add_argument("-c", "--connections", type=int, default=8, help="connexions simultanées (1-32)")
    p.add_argument("-o", "--output", default=os.getcwd(), help="dossier de destination")
    a = p.parse_args()
    if not a.url:
        from app import main as gui_main
        gui_main()
        return 0
    return cli(a.url, a.connections, a.output)


if __name__ == "__main__":
    sys.exit(main())
