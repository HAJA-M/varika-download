# Varika Download 1.3.0 : rapport d'analyse et corrections

## Méthode
1. Relecture complète du code : `engine.py`, `hls.py`, `app.py`, `bridge.py`, `cli.py`, l'extension et l'interface, soit environ 3 200 lignes.
2. Analyse statique (`pyflakes`).
3. Pour chaque bug suspecté, **un test qui le reproduit** avant toute correction (`tests/test_bugs.py`). Les 16 tests échouaient tous avant les corrections.
4. Correction du bug, puis relance de **toute** la suite de tests pour vérifier qu'aucune correction n'en casse une autre.

## Bugs trouvés et corrigés

| # | Gravité | Problème constaté | Cause | Correction |
|---|---|---|---|---|
| B3 | **Critique** | Deux téléchargements du même nom lancés en même temps se marchent dessus : erreur ou fichier mélangé | Le nom libre était choisi d'après le disque seulement, sans tenir compte des téléchargements en cours | Les noms sont réservés entre téléchargements (`claim_path`) |
| B12 | **Critique** | Si le fichier change sur le serveur pendant une pause, le fichier final mélange l'ancienne et la nouvelle version, **sans aucune erreur** | Aucune vérification d'identité du fichier à la reprise | Vérification de la taille et de l'ETag ou de la date de modification (Last-Modified) : si le fichier a changé, on recommence proprement et on le signale. Contrôle aussi de chaque `Content-Range` |
| B31 | **Sécurité** | Les cookies de session d'un site étaient envoyés à **un autre site** après une redirection | Python conserve les en-têtes lors des redirections | Les cookies et l'authentification ne partent que vers le site d'origine |
| X47 | **Sécurité** | Le serveur local acceptait un en-tête Host étranger (attaque par « DNS rebinding ») | Pas de contrôle de l'en-tête Host | Seuls `127.0.0.1:47821` et `localhost:47821` sont acceptés |
| A23 | **Haute** | Une liste de téléchargements abîmée **empêchait le logiciel de démarrer** | `rec["id"]` lu hors du bloc de protection | Les entrées invalides sont ignorées une par une |
| B14 | Haute | Un fichier du même nom créé pendant le téléchargement était **écrasé** | `os.replace` remplace sans vérifier | Si le nom est pris à la fin du téléchargement, on en choisit un autre |
| B13 | Haute | Connexion coupée sur un serveur sans reprise possible : affichait « En pause » au lieu d'une erreur | Taille reçue non comparée à la taille annoncée | Erreur claire avec la quantité reçue |
| B5 | Moyenne | Reprise impossible après redémarrage avec un lien signé (CDN) expiré | On réutilisait le lien final, qui avait expiré | On garde le lien d'origine et on le renouvelle à la reprise |
| B19 | Moyenne | Disque plein : 8 essais et plusieurs minutes d'attente avant l'échec | Erreur traitée comme une coupure réseau | Vérification de l'espace libre au départ, et arrêt immédiat avec un message en cas de disque plein |
| A22 | Moyenne | Fermeture pendant une sauvegarde automatique : liste corrompue ou erreur | Deux écritures simultanées du même fichier temporaire | Verrou d'écriture |
| A44 | Moyenne | Une erreur inattendue arrêtait **définitivement** la file d'attente | Exception non interceptée dans la boucle | La boucle est protégée et le téléchargement fautif passe en erreur |
| H1 | Moyenne | Une diffusion en direct était téléchargée **tronquée**, sans prévenir | Absence de `#EXT-X-ENDLIST` non détectée | Refus avec le message « diffusion en direct » |
| B1 | Basse | Noms accentués illisibles (« ConfÃ©rence ») | En-tête UTF-8 lu comme du latin-1 | Le nom est relu en UTF-8 |
| B2 | Basse | Noms interdits par Windows (CON, NUL…) ou trop longs : échec | Pas de contrôle | Préfixe « _ » et 180 caractères au maximum |
| A39 | Basse | Un dossier de destination invalide était accepté, puis tous les téléchargements échouaient | Aucune validation | Test d'écriture avant d'enregistrer, avec un message d'erreur |
| C1 | Basse | La ligne de commande refusait les liens `.m3u8` | Elle n'utilisait que le moteur de fichiers | Choix automatique du moteur |

**Corrections sans test automatique** (vérifiées par relecture) :
- **Explorateur :** il n'ouvrait pas le bon dossier quand le chemin contenait des espaces.
- **Démarrage :** il était bloqué par un lien `varika://` lent, ou par un autre logiciel qui occupait le port.
- **Fermeture :** la file d'attente démarrait encore de nouveaux téléchargements pendant la fermeture.
- **Barre de progression :** au-delà de 64 morceaux, elle était incomplète.
- **Mémoire :** un « segment » HLS anormalement gros pouvait saturer la mémoire.
- **Extension :** le panneau vidéo restait bloqué ouvert, le badge « OFF » était masqué par le compteur de vidéos, et la mémoire du service grandissait sans limite.
- **Interface :** les erreurs lors de l'ajout d'un lien ou dans les paramètres n'étaient pas affichées.

**Trouvé pendant les tests Android** : le moteur Java avait le même défaut de cookies que B31, après une redirection. Il a été corrigé et ajouté aux tests.

## Ce qui a été ajouté
- **Interface responsive** : ordinateur, tablette et téléphone. Sur petit écran, le menu devient un tiroir, les fenêtres montent du bas et les boutons sont plus grands. Aucun défilement horizontal aux trois tailles testées.
- **Application Android** (`android/`) : même interface, moteur Java complet (multi-connexions, reprise, HLS, AES-128), service en arrière-plan avec notifications, fichiers rangés dans « Téléchargements », partage depuis les autres applications, et **navigateur vidéo intégré** qui détecte les vidéos des pages, comme l'extension sur ordinateur.

## Tests (tous verts)
| Suite | Contenu |
|---|---|
| `tests/test_moteur.py` | Vitesse multi-connexions, pause et reprise, serveur sans Range |
| `tests/test_bugs.py` | Les 16 bugs ci-dessus |
| `tests/test_hls.py` | Qualités, AES-128, fMP4 avec audio séparé, sans ffmpeg, reprise, DRM refusé |
| `tests/test_extension.py` | Extension chargée dans Chromium : sécurité, capture, cookies, repli |
| `tests/test_video.py` | Bouton vidéo sur un MP4 et sur un lecteur HLS (vrai Chromium) |
| `tests/test_android_core.py` | Moteur Android compilé en Java 8 : 15 scénarios, et vidéos produites vérifiées avec ffprobe |

## Limites connues
- La partie purement Android (écrans, service, MediaStore, conversion `.ts` vers `.mp4` avec MediaMuxer) **n'a pas pu être compilée ici** : le SDK Android n'est pas accessible depuis mon environnement. Elle est compilée par GitHub Actions. À tester sur un vrai téléphone avant de la distribuer.
- YouTube et les contenus protégés par DRM ne sont volontairement pas pris en charge.
- DASH (`.mpd`) et Firefox ne sont pas encore pris en charge.
