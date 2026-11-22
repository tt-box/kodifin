# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Users

Utilisateur principal : un auto-hébergeur à l'aise techniquement, qui a plusieurs lecteurs multimédias sur son réseau local (Kodi, Jellyfin/Emby, Plex, renderers UPnP/DLNA, Chromecast, AirPlay, Roku, Fire TV/Android TV). Il accepte de faire une configuration avancée (activer le débogage ADB, saisir une IP, lier un compte Plex, entrer des identifiants) pour piloter tous ces appareils depuis une seule télécommande qui respecte sa vie privée.

Usage typique : depuis le canapé, téléphone à la main, souvent dans une pièce sombre. Il passe d'un appareil à l'autre et transfère la lecture entre eux.

## Product Purpose

Kodifin est une télécommande Android unique pour les media centers et les lecteurs réseau. Elle remplace les applications propres à chaque marque : découverte automatique des appareils sur le réseau local, navigation, lecture, volume, recherche, saisie de texte, envoi d'URL, parcours des bibliothèques et transfert de la lecture d'un appareil à un autre.

Le produit est réussi quand une seule application pilote de façon fiable tout ce qu'il y a à la maison, sans qu'aucune donnée ne quitte le réseau local.

## Positioning

- **Zéro traceur, vérifié par la CI** : aucun SDK d'analyse, de publicité ni de rapport de plantage. `scripts/check-no-trackers.sh` fait échouer le build sinon. Le seul trafic hors réseau local est plex.tv, et seulement quand l'utilisateur lie un compte Plex.
- **Une télécommande, huit protocoles** : Kodi, Jellyfin/Emby, Plex, UPnP/DLNA, Chromecast, AirPlay 1, Roku, Fire TV/Android TV (ADB), avec transfert de lecture entre appareils.
- **Ouvert et vérifiable** : écrit à partir des spécifications publiques de chaque protocole, build transparent, releases signées (empreinte du certificat publiée).
- **Le téléphone comme clavier** : clavier Bluetooth HID, saisie de texte, envoi d'URL depuis le menu de partage Android.

## Operating Context

- Usage principal : télécommande au quotidien, à une main, souvent dans le noir, pendant que le regard est sur la télévision.
- Usage occasionnel : configuration (découverte, ajout manuel, appairage ADB, identifiants, liaison Plex, diagnostic de connexion, Wake-on-LAN).
- Toutes les fonctions ne sont pas disponibles sur tous les appareils : l'interface affiche seulement ce que le contrôleur annonce (modèle de capacités, voir `CLAUDE.md`).

## Capabilities and Constraints

- Application native Jetpack Compose, Material 3. minSdk 26, targetSdk 36.
- Les fonctions dépendent de chaque protocole (voir le tableau du README) : une fonction absente est masquée, pas désactivée par défaut.
- Limites connues : pas d'AirPlay 2 ; Fire TV demande d'activer le débogage ADB ; Chromecast dépend des Google Play Services ; HTTP en clair autorisé sur le réseau local.
- Langue : interface en français pour l'instant. Une version anglaise est prévue, donc les textes doivent rester faciles à traduire (pas de texte dans les images, des mises en page qui tiennent avec des libellés plus longs).
- Distribution : publication prévue sur le Play Store. La politique de confidentialité FR/EN et l'icône 512 px existent déjà.

## Brand Commitments

- Nom : **Kodifin**.
- Promesse « sans aucun traceur » : c'est une exigence du produit, pas un argument marketing.
- Icône existante : `docs/icon/kodifin-icon.svg`, `docs/icon/play-store-icon-512.png`.
- Textes, commentaires et messages de commit en français.

## Evidence on Hand

- README : tableau des protocoles, section vie privée, limites connues.
- Politique de confidentialité : `docs/privacy.html` (FR/EN).
- Badges CI : couverture, 0 traceur, scan CVE.
- Absents (à ne pas inventer) : témoignages, nombre d'utilisateurs, notes du Play Store, presse.

## Product Principles

1. **La vie privée n'est pas négociable** : aucune fonction ne justifie un traceur ou du trafic qui sort du réseau local sans action explicite de l'utilisateur.
2. **Afficher seulement ce que l'appareil sait faire** : l'interface suit les capacités de chaque contrôleur et ne montre pas de boutons qui ne marchent pas.
3. **La télécommande avant la configuration** : le quotidien (lecture, volume, navigation) doit être immédiat. La configuration peut être plus dense, car ses utilisateurs sont techniques.
4. **Honnête sur les limites** : quand un protocole ne permet pas quelque chose, ou qu'une connexion échoue, dire clairement pourquoi et comment corriger.

## Accessibility & Inclusion

Exigence ferme : compatible TalkBack (libellés sur toutes les commandes), tailles de police agrandies, utilisation à une main, lisible dans une pièce sombre.
