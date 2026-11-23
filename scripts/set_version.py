"""Met le même numéro de version partout (logiciel Windows, extension, application Android).

    python scripts/set_version.py 1.4.0        (ou v1.4.0 : c'est ce que fait la CI à partir du tag)
    python scripts/set_version.py --check      vérifie que tous les fichiers portent la même version
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# fichier -> liste de (motif, gabarit) ; {v} = 1.4.0, {t} = 1, 4, 0
TARGETS = {
    "app.py": [(r'APP_VERSION = "[^"]+"', 'APP_VERSION = "{v}"')],
    "installer.iss": [(r'#define AppVersion "[^"]+"', '#define AppVersion "{v}"')],
    "version_info.txt": [(r"filevers=\([^)]*\)", "filevers=({t}, 0)"),
                         (r"prodvers=\([^)]*\)", "prodvers=({t}, 0)"),
                         (r"StringStruct\('FileVersion', '[^']+'\)", "StringStruct('FileVersion', '{v}')"),
                         (r"StringStruct\('ProductVersion', '[^']+'\)", "StringStruct('ProductVersion', '{v}')")],
    "extension/manifest.json": [(r'"version": "[^"]+"', '"version": "{v}"')],
    "android/app/build.gradle": [(r"versionName '[^']+'", "versionName '{v}'"),
                                 (r"versionCode \d+", "versionCode {code}")],
    "android/app/src/main/java/com/varikaagency/download/core/Manager.java":
        [(r'VERSION = "[^"]+"', 'VERSION = "{v}"')],
}


def parse(v):
    m = re.fullmatch(r"v?(\d+)\.(\d+)\.(\d+)", v.strip())
    if not m:
        sys.exit(f"Version invalide : {v!r} (attendu : 1.4.0 ou v1.4.0)")
    return tuple(int(x) for x in m.groups())


def current():
    s = open(os.path.join(ROOT, "app.py"), encoding="utf-8").read()
    return re.search(r'APP_VERSION = "([^"]+)"', s).group(1)


def apply(version):
    a, b, c = parse(version)
    v = f"{a}.{b}.{c}"
    # versionCode croît toujours avec la version : Android refuse d'installer un code plus petit
    values = {"v": v, "t": f"{a}, {b}, {c}", "code": str(a * 10000 + b * 100 + c)}
    for rel, rules in TARGETS.items():
        p = os.path.join(ROOT, rel)
        s = open(p, encoding="utf-8").read()
        for pat, tpl in rules:
            s, n = re.subn(pat, tpl.format(**values), s, count=1)
            if n != 1:
                sys.exit(f"{rel} : motif introuvable ({pat})")
        with open(p, "w", encoding="utf-8", newline="") as f:
            f.write(s)
    print(f"Version {v} (versionCode Android {values['code']}) écrite dans {len(TARGETS)} fichiers.")


def check():
    v = current()
    bad = []
    for rel, rules in TARGETS.items():
        s = open(os.path.join(ROOT, rel), encoding="utf-8").read()
        for pat, tpl in rules:
            if "{v}" in tpl and v not in re.search(pat, s).group(0):
                bad.append(rel)
    if bad:
        sys.exit(f"Versions différentes de {v} dans : {', '.join(sorted(set(bad)))}")
    print(f"Toutes les versions sont à {v}.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    check() if sys.argv[1] == "--check" else apply(sys.argv[1])
