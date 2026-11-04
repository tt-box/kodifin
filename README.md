# Zapette

[![CI](https://github.com/jmorille/zapette/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/jmorille/zapette/actions/workflows/ci.yml)
[![Fusion auto Dependabot](https://github.com/jmorille/zapette/actions/workflows/dependabot-automerge.yml/badge.svg)](https://github.com/jmorille/zapette/actions/workflows/dependabot-automerge.yml)
[![Dependabot](https://img.shields.io/badge/Dependabot-activé-025E8C?logo=dependabot&logoColor=white)](https://github.com/jmorille/zapette/security/dependabot)
[![Scan CVE](https://img.shields.io/badge/CVE-OSV--Scanner-4285F4?logo=google&logoColor=white)](https://github.com/google/osv-scanner)
[![Traceurs](https://img.shields.io/badge/traceurs-0-brightgreen)](scripts/check-no-trackers.sh)

[![Android](https://img.shields.io/badge/Android-8%2B%20%E2%86%92%2016%20(API%2036)-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](gradle/libs.versions.toml)
[![AGP](https://img.shields.io/badge/AGP-9.5.0-02303A?logo=gradle&logoColor=white)](gradle/libs.versions.toml)

Télécommande Android pour media centers et lecteurs réseau, **sans aucun traceur**.
Cible Android 16 (API 36), compatible Android 8+.

| Appareil | Protocole | Découverte | Fonctions |
|---|---|---|---|
| Kodi | JSON-RPC 2.0 : HTTP `/jsonrpc` (+ transport WebSocket port 9090) | mDNS `_xbmc-jsonrpc-h._tcp` | navigation, lecture, volume, recherche, lecture d'URL, saisie de texte, état de lecture, bibliothèques (84 méthodes cataloguées) |
| Jellyfin / Emby | REST `/Sessions/…` | UDP 7359 (`who is JellyfinServer?`) | contrôle d'une session cliente, état de lecture |
| Plex | Remote Control du lecteur (port 32500) | GDM 239.0.0.250:32412 | navigation, lecture, volume, recherche ; liaison de compte par code plex.tv/link |
| UPnP / DLNA | AVTransport + RenderingControl (SOAP) | SSDP `MediaRenderer:1` | lecture d'URL, pause, recherche, volume, état |
| Chromecast | SDK Google Cast (récepteur par défaut) | MediaRouter | lecture d'URL, pause, recherche, volume |
| AirPlay | AirPlay 1 vidéo (HTTP port 7000) | mDNS `_airplay._tcp` | lecture d'URL, pause, recherche, arrêt |
| Roku | External Control Protocol (port 8060) | SSDP `roku:ecp` | touches, texte, lecture d'URL (Play on Roku) |
| Fire TV / Android TV | ADB réseau (port 5555, bibliothèque dadb) | mDNS `_amzn-wplay._tcp` | touches, texte, lecture d'URL |

Le code a été écrit à partir des spécifications publiques de chaque protocole.

## Vie privée

- Aucun SDK d'analyse, de publicité ni de rapport de plantage.
- Le transport de télémétrie Google (`com.google.android.datatransport`), embarqué
  par le SDK Cast, est **exclu** du build (`app/build.gradle.kts`).
- `scripts/check-no-trackers.sh` fait échouer la CI si une bibliothèque de pistage
  apparaît dans le classpath release ou dans l'APK.
- Seuls échanges hors du réseau local : plex.tv, uniquement si l'utilisateur lie un compte Plex.
- Mots de passe Jellyfin/Emby : échangés contre un jeton puis supprimés. Le mot de passe
  Kodi (authentification HTTP Basic) reste stocké localement, dans le stockage privé de
  l'application, exclu des sauvegardes.

## Limites connues

- **AirPlay 2** (appairage HomeKit) non pris en charge : seuls les récepteurs acceptant AirPlay 1 vidéo.
- **Fire TV** : nécessite d'activer le débogage ADB et d'accepter la clé de Zapette sur le téléviseur.
- **Chromecast** : dépend des Google Play Services (SDK officiel).
- Le trafic HTTP en clair est autorisé sur le réseau local (Kodi, UPnP, Roku, AirPlay ne proposent pas TLS).

## Architecture

```
app/                 UI Jetpack Compose (Material 3), découverte, persistance DataStore
core/model/          contrat RemoteController, Device, utilitaires HTTP (OkHttp)
protocol/kodi        JSON-RPC HTTP + WebSocket
protocol/mediaserver Jellyfin / Emby
protocol/plex        plex.tv (PIN), GDM, contrôle du lecteur
protocol/upnp        SSDP, description UPnP, SOAP AVTransport / RenderingControl
protocol/roku        ECP
protocol/airplay     AirPlay 1
protocol/firetv      ADB (dadb)
feature/cast         Google Cast (MediaRouter + CastContext)
```

Les modules `protocol/*` sont des bibliothèques Kotlin/JVM pures, testées sans émulateur
(MockWebServer, faux transports).

## Build

Prérequis : JDK 17+ et SDK Android (plateforme 37 pour la compilation).

```bash
./gradlew test testDebugUnitTest lint assembleDebug
```

## CI / sécurité

- **CI** (`.github/workflows/ci.yml`) : tests unitaires, lint, build debug et release,
  contrôle anti-pistage, scan CVE (inventaire CycloneDX analysé par OSV-Scanner,
  aussi lancé chaque jour), soumission du graphe de dépendances pour les alertes Dependabot.
- **Dependabot** (`.github/dependabot.yml`) : mises à jour Gradle (quotidiennes) et
  GitHub Actions (hebdomadaires), plus les mises à jour de sécurité. Une version n'est
  proposée que quelques jours après sa publication (`cooldown`), ce qui laisse le temps de
  retirer une version compromise avant la fusion automatique.
- Les actions GitHub sont épinglées par SHA de commit ; le binaire OSV-Scanner est
  vérifié par empreinte SHA-256 avant exécution.
- **Fusion automatique** (`.github/workflows/dependabot-automerge.yml`) : une PR
  Dependabot est fusionnée (squash) dès que tous les jobs de la CI sont verts sur son
  dernier commit, si tous ses commits sont de Dependabot. La fusion utilisant le
  `GITHUB_TOKEN`, elle ne relance pas la CI sur `main` : le graphe de dépendances est
  republié par l'exécution quotidienne.
