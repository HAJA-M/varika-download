# Varika Download : gestionnaire de téléchargement multi-connexions

Application de bureau Windows : moteur Python, interface HTML/CSS moderne affichée dans une fenêtre native (pywebview + Edge WebView2, déjà présent sur Windows 10/11).

## Obtenir VarikaDownload.exe

### Option 1 : compiler sur ton PC (2 minutes)
1. Installer Python 3.10 ou plus récent depuis python.org, en cochant **« Add python.exe to PATH »**.
2. Double-cliquer sur **`build_windows.bat`**.
3. L'exécutable est créé dans **`dist\VarikaDownload.exe`** (un seul fichier, environ 15 Mo, l'icône est incluse).

### Option 2 : installateur « Setup-VarikaDownload-1.0.0.exe »
Après l'option 1, installer [Inno Setup 6](https://jrsoftware.org/isdl.php), ouvrir `installer.iss` puis cliquer sur **Compiler**. Le fichier `dist\Setup-VarikaDownload-1.0.0.exe` installe Varika Download avec un raccourci dans le menu Démarrer, une icône sur le bureau (en option) et un désinstalleur.

### Option 3 : compilation automatique par GitHub
Pousse ce dossier sur un dépôt GitHub. Dans l'onglet **Actions**, ouvre le workflow **Build Windows** et clique sur **Run workflow** (il se lance aussi à chaque tag `v*`). Les fichiers `Varika Download.exe` et `Setup-VarikaDownload-1.0.0.exe` se téléchargent ensuite dans **Artifacts**.

> L'exe n'est pas signé numériquement : au premier lancement, Windows SmartScreen peut afficher « Windows a protégé votre ordinateur ». Clique sur **Informations complémentaires → Exécuter quand même**. Pour supprimer cet avertissement, il faut un certificat de signature de code.

## Extension navigateur (Chrome, Edge, Opera, Brave, Vivaldi)
Comme avec IDM, les fichiers cliqués dans le navigateur partent directement dans Varika Download.

**Installation (une fois par navigateur)** : dans le logiciel, clique sur **Extension navigateur** en bas à gauche et suis les 4 étapes :
1. Ouvrir `chrome://extensions` (`edge://extensions`, `opera://extensions`, `brave://extensions`).
2. Activer le **Mode développeur**.
3. Cliquer sur **Charger l'extension non empaquetée** et choisir le dossier `%APPDATA%\Varika Download\extension` (le bouton « Ouvrir le dossier » l'ouvre directement).
4. Épingler l'icône Varika Download.

**Ce qu'elle fait**
- **Capture automatique** : le téléchargement est mis en pause dans le navigateur, envoyé au logiciel, puis retiré du navigateur. Si le logiciel ne peut pas prendre le lien (lien à usage unique, formulaire…), le navigateur reprend où il en était : rien n'est perdu.
- **Clic droit** sur un lien, une image, une vidéo ou un son : « Télécharger avec Varika Download ».
- **Liens protégés** : les cookies du site et la page d'origine sont transmis. Les cookies ne sont jamais écrits sur le disque.
- **Popup** : activer ou désactiver la capture, tous les types de fichiers ou une liste d'extensions, taille minimale.
- **Logiciel fermé** : le navigateur garde le fichier et propose de lancer Varika Download. Avec le clic droit, l'extension lance le logiciel via `varika://`.
- La navigation privée et les liens `blob:` ou `data:` ne sont jamais capturés.

**Fonctionnement technique** (`bridge.py`, `extension/background.js`)
```
Navigateur ── extension ──HTTP──▶ 127.0.0.1:47821 (Varika Download)
                                  GET /ping   POST /add   POST /show
```
- Le serveur n'écoute que sur la machine locale. Il refuse toute requête venant d'une page web (`Origin` différent de `chrome-extension://…`) et exige l'en-tête `X-Varika-Client` ou le jeton local.
- Instance unique : relancer l'exe (ou ouvrir un lien `varika://`) transmet les liens à la fenêtre déjà ouverte.
- Le protocole `varika://` est enregistré pour l'utilisateur courant au lancement de l'exe (sans droits administrateur) et supprimé par le désinstalleur.
- Publier l'extension sur le Chrome Web Store (frais d'inscription unique de 5 $) ou sur Edge Add-ons supprime l'étape « mode développeur ». Il suffit d'envoyer un zip du dossier `extension/`.
- Firefox : pas encore pris en charge (il faudrait adapter le manifeste et `onDeterminingFilename`).

Diagnostic : dans `chrome://extensions`, clique sur « service worker » sous Varika Download, puis tape `chrome.storage.session.get('vdlog')` dans la console.

## Vidéos : détection comme IDM
- Un bouton **« Télécharger cette vidéo »** apparaît au survol de chaque lecteur vidéo, et quand une vidéo démarre.
- L'icône de l'extension affiche le **nombre de vidéos détectées** sur la page, et son popup les liste avec leur qualité.
- La détection fonctionne pour les **fichiers vidéo et audio** (mp4, webm, mp3…) et pour le **streaming HLS** (`.m3u8`), même quand le lecteur utilise un `blob:`. L'extension observe le trafic réseau de l'onglet (`webRequest`).
- **HLS** (`hls.py`) : choix de la qualité (playlist maître), segments téléchargés en parallèle, déchiffrement AES-128, fMP4, piste audio séparée, pause et reprise.
- **ffmpeg (recommandé)** : s'il est trouvé (dans le PATH, ou `ffmpeg.exe` à côté de `VarikaDownload.exe`), le résultat est un **.mp4** et la piste audio séparée est fusionnée. Sans ffmpeg, on obtient un **.ts** lisible par VLC. Pour l'installer : `winget install Gyan.FFmpeg`.
- **Limites volontaires** : aucun contournement de DRM (Netflix, Disney+, Widevine, SAMPLE-AES : refusé avec un message), pas de YouTube (conditions du site et règles du Chrome Web Store), pas de diffusion en direct. Les flux DASH (`.mpd`) ne sont pas encore pris en charge.

## Lancer sans compiler (développement)
```bash
pip install pywebview
python app.py                 # application
python app.py --debug         # avec les outils de développement (F12)
python cli.py URL -c 8       # version ligne de commande
```
Pour avoir un aperçu du design dans un navigateur, ouvre `ui/index.html` : il démarre en mode démo avec des téléchargements simulés.

## Fonctionnalités
- Jusqu'à 32 connexions par fichier, avec **segmentation dynamique** (une connexion qui a fini aide les autres).
- Pause et reprise, y compris **après la fermeture du programme**. La liste est sauvegardée dans `%APPDATA%\Varika Download\downloads.json`.
- File d'attente (nombre de téléchargements simultanés réglable).
- Détection d'un lien copié dans le presse-papiers, glisser-déposer d'un lien dans la fenêtre, raccourci `Ctrl+N`.
- Tri par catégories (vidéos, musique, documents, archives, programmes, images), recherche, filtres par statut.
- Barre de progression qui montre chaque segment en direct, graphique du débit, espace disque libre.
- Notifications de fin ou d'erreur, avec les boutons « Ouvrir » et « Réessayer ».
- `VarikaDownload.exe https://…` ou un lien `varika://add?url=…` ajoute directement un téléchargement.

## Structure
| Fichier | Rôle |
|---|---|
| `engine.py` | Moteur : sondage `Range`, segments, threads, reprise, nouvelles tentatives |
| `app.py` | Application : fenêtre, pont Python vers JavaScript (`Api`), file d'attente, sauvegarde |
| `ui/index.html` | Interface complète (HTML, CSS et JS dans un seul fichier, sans dépendance) |
| `cli.py` | Version ligne de commande |
| `bridge.py` | Pont navigateur : serveur local sécurisé, protocole `varika://`, instance unique |
| `hls.py` | Téléchargement des vidéos en streaming HLS (.m3u8) |
| `extension/` | Extension Chromium (Manifest V3) : capture, clic droit, détection vidéo (`content.js`), popup |
| `tests/` | Serveur local bridé et tests de bout en bout (`test_moteur.py`, `test_hls.py` ; `test_extension.py` et `test_video.py` avec Playwright) |
| `build_windows.bat`, `version_info.txt`, `installer.iss`, `.github/workflows/` | Compilation et installateur |

## Comment fonctionne le moteur
1. **Sonder** : `Range: bytes=0-0`. Une réponse 206 avec `Content-Range: …/TAILLE` veut dire que le fichier est découpable.
2. **Pré-allouer** `fichier.part` à la taille finale.
3. **Découper** en N plages : chaque connexion demande `Range: bytes=debut-fin` et écrit à sa position (`seek`).
4. **Segmentation dynamique** : la connexion qui a fini coupe en deux le plus gros segment restant.
5. **Reprise** : les positions sont sauvegardées dans `fichier.part.json` toutes les 2 s.
6. **Finir** : `.part` est renommé avec le nom final.

Plusieurs connexions accélèrent le téléchargement quand le serveur limite le débit **par connexion**. Si c'est ta ligne qui est saturée, le gain est faible.
