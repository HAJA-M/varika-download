# Varika Download : gestionnaire de téléchargement multi-connexions

Application de bureau Windows : moteur Python, interface HTML/CSS moderne affichée dans une fenêtre native (pywebview + Edge WebView2, déjà présent sur Windows 10/11).

## Obtenir VarikaDownload.exe

### Option 1 : compiler sur ton PC (2 minutes)
1. Installer Python 3.10 ou plus récent depuis python.org, en cochant **« Add python.exe to PATH »**.
2. Double-cliquer sur **`build_windows.bat`**.
3. L'exécutable est créé dans **`dist\VarikaDownload.exe`** (un seul fichier, environ 15 Mo, l'icône est incluse).

### Option 2 : installateur « Setup-VarikaDownload-1.4.0.exe »
Après l'option 1, installer [Inno Setup 6](https://jrsoftware.org/isdl.php), ouvrir `installer.iss` puis cliquer sur **Compiler**. Le fichier `dist\Setup-VarikaDownload-1.4.0.exe` installe Varika Download avec un raccourci dans le menu Démarrer, une icône sur le bureau (en option) et un désinstalleur.

### Option 3 : compilation automatique par GitHub
Voir « Intégration continue et mises à jour » ci-dessous : un tag `v1.4.0` produit tout (exe, installateur, APK, extension) et le publie.

> L'exe n'est pas signé numériquement : au premier lancement, Windows SmartScreen peut afficher « Windows a protégé votre ordinateur ». Clique sur **Informations complémentaires → Exécuter quand même**. Pour supprimer cet avertissement, il faut un certificat de signature de code.

## Intégration continue et mises à jour automatiques

**Deux workflows GitHub Actions** (`.github/workflows/`) :

| Workflow | Quand | Ce qu'il fait |
|---|---|---|
| **CI** | chaque `push` et pull request | tous les tests : moteur PC, coupures réseau (fichier de 1 Go), HLS, vérification des mises à jour, moteur Android sur la JVM, extension et détection vidéo dans Chromium, cohérence des numéros de version |
| **Release** | un tag `v*` (ou *Run workflow* à la main) | relance les tests, compile `VarikaDownload.exe`, `Setup-VarikaDownload-x.y.z.exe`, `VarikaDownload-x.y.z.apk` et l'extension `.zip`, calcule `SHA256SUMS.txt` puis crée la **Release GitHub** |

**Publier une nouvelle version** (le numéro est pris dans le tag et écrit partout automatiquement) :
```
git tag v1.4.1
git push origin v1.4.1
```
Pour changer la version à la main : `python scripts/set_version.py 1.4.1` (et `--check` pour vérifier).

**Comment les logiciels installés se mettent à jour**
- Windows et Android interrogent `https://api.github.com/repos/HAJA-M/varika-download-releases/releases/latest` à l'ouverture (désactivable dans Paramètres), puis affichent un bandeau « Mise à jour x.y.z ».
- Le fichier est téléchargé puis comparé à `SHA256SUMS.txt` : **un seul octet différent, ou une Release sans empreintes, et la mise à jour est refusée**.
- Windows : l'installateur se lance en mode silencieux, ferme Varika Download et le relance. La version portable se remplace elle-même.
- Android : l'installeur système s'ouvre et l'utilisateur confirme (Android l'exige). La première fois, il faut autoriser « Installer des applications inconnues » pour Varika Download.

**À configurer une fois sur GitHub**
1. Crée un dépôt **public** `HAJA-M/varika-download-releases` (il peut rester vide) : c'est là que les logiciels installés lisent les mises à jour, même si le code source reste privé. Pour utiliser un autre dépôt : variable GitHub `UPDATE_REPO`, et `UPDATE_REPO` / `REPO` dans `updater.py` et `Updater.java` (sous Windows, la variable d'environnement `VARIKA_UPDATE_REPO` suffit).
2. Dans le dépôt du code : *Settings > Secrets and variables > Actions* → secret **`RELEASES_TOKEN`** (jeton personnel *fine-grained* avec « Contents : Read and write » sur le dépôt public). Sans ce secret, la Release est créée seulement dans le dépôt du code.
3. Si ton dépôt de code est lui-même public, tu peux simplement mettre son nom dans `UPDATE_REPO` et ignorer l'étape 2.

**Signature de l'APK** : Android n'installe une mise à jour que si elle est signée avec la **même clé**. Le dépôt contient une clé de test (`android/keystore/varika-test.jks`) utilisée par défaut, pour que toutes les compilations CI s'installent par-dessus les précédentes. Pour le Play Store ou une diffusion large, crée ta propre clé et ajoute les secrets `VARIKA_KEYSTORE_B64`, `VARIKA_KEYSTORE_PASSWORD`, `VARIKA_KEY_ALIAS` et `VARIKA_KEY_PASSWORD`. Attention : passer de la clé de test à ta clé oblige à désinstaller une fois l'ancienne application.

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

## Application Android (dossier `android/`)
La même interface que sur Windows, avec un moteur réécrit en Java, sans aucune bibliothèque externe.

**Obtenir l'APK**
- **GitHub Actions** : workflow **Release**, bouton **Run workflow** (ou un tag `v*`). L'APK `VarikaDownload-1.4.0.apk` se télécharge dans *Artifacts* ou dans la Release. Pour la signature, voir plus haut.
- **Android Studio** : *Open* sur le dossier `android/`, accepte la création du Gradle wrapper, puis *Build > Build APK(s)*.

**Installation sur le téléphone** : Android 10 ou plus récent. Ouvre l'APK et autorise « Installer des applications inconnues » pour le gestionnaire de fichiers ou le navigateur utilisé.

**Pensé pour les très gros fichiers (5 Go et plus)**
- Écriture directe dans **Téléchargements/Varika Download** : pas de copie finale, donc pas besoin de deux fois l'espace.
- **Coupure d'Internet** : le téléchargement passe en « Attente réseau » au lieu d'échouer, puis **reprend seul** dès que la connexion revient, à l'octet près (vérifié avec un fichier de 5 Go coupé à 52 %).
- **Wi-Fi uniquement** (option) : sur les données mobiles, les gros fichiers attendent le Wi-Fi. Un avertissement s'affiche avant de lancer un gros fichier en données mobiles, avec un choix fichier par fichier.
- Le service garde le processeur et le Wi-Fi éveillés pendant le téléchargement, même écran éteint. Pour les plus gros fichiers, autorise « Batterie : sans restriction » (bouton dans Paramètres).
- Si Android ferme l'application ou si le téléphone redémarre, les téléchargements reprennent à la réouverture.
- L'état est écrit sur le disque toutes les 3 s, après synchronisation du fichier : une coupure brutale fait perdre au plus quelques secondes.

**Fonctions**
- Coller un lien, ou bien **Partager → Varika Download** depuis Chrome, WhatsApp, Telegram… Si le lien mène à une page web plutôt qu'à un fichier, la page s'ouvre dans le navigateur vidéo.
- **Navigateur vidéo** : il observe les requêtes de la page et repère les MP4, WebM et flux HLS. Le bouton « Télécharger la vidéo » envoie la vidéo avec les cookies du site. YouTube est exclu.
- Téléchargements en arrière-plan avec une notification de progression et un bouton « Tout suspendre ».
- Les fichiers terminés vont dans **Téléchargements/Varika Download** et apparaissent dans l'application Fichiers.
- Les vidéos HLS sont converties en `.mp4` avec les outils intégrés d'Android (MediaMuxer), avec repli sur `.ts`.

**Structure** : `core/` contient le moteur (pur Java, testé sur ordinateur par `tests/test_android_core.py`). Le reste est propre à Android : `MainActivity` (interface et pont JavaScript), `BrowserActivity`, `DownloadService`, `Publisher` et `MediaRemuxer`.

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
| `android/` | Application Android (moteur Java dans `core/`, écrans et service Android) |
| `RAPPORT-ANALYSE.md` | Analyse du code : bugs trouvés, corrections, tests |
| `tests/` | Serveur local bridé et tests de bout en bout (`test_moteur.py`, `test_bugs.py`, `test_hls.py`, `test_android_core.py` ; `test_extension.py` et `test_video.py` avec Playwright) |
| `build_windows.bat`, `version_info.txt`, `installer.iss`, `.github/workflows/` | Compilation et installateur |

## Comment fonctionne le moteur
1. **Sonder** : `Range: bytes=0-0`. Une réponse 206 avec `Content-Range: …/TAILLE` veut dire que le fichier est découpable.
2. **Pré-allouer** `fichier.part` à la taille finale.
3. **Découper** en N plages : chaque connexion demande `Range: bytes=debut-fin` et écrit à sa position (`seek`).
4. **Segmentation dynamique** : la connexion qui a fini coupe en deux le plus gros segment restant.
5. **Reprise** : les positions sont sauvegardées dans `fichier.part.json` toutes les 2 s.
6. **Finir** : `.part` est renommé avec le nom final.

Plusieurs connexions accélèrent le téléchargement quand le serveur limite le débit **par connexion**. Si c'est ta ligne qui est saturée, le gain est faible.
