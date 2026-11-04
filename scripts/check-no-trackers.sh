#!/usr/bin/env bash
# Échoue si une bibliothèque de mesure d'audience, de publicité, de rapport de plantage
# ou de télémétrie se trouve dans le classpath d'exécution de la version release.
set -euo pipefail

cd "$(dirname "$0")/.."

BLOCKLIST='com\.google\.firebase:firebase-(analytics|crashlytics|perf|messaging|inappmessaging|config|installations)
com\.google\.firebase:firebase-encoders
com\.google\.android\.datatransport
com\.google\.android\.gms:play-services-(measurement|analytics|ads|ads-identifier|appset)
com\.google\.android\.play:core-common
io\.sentry
com\.bugsnag
com\.instabug
com\.newrelic
com\.microsoft\.appcenter
com\.facebook\.(android|sdk)
com\.appsflyer
com\.adjust\.sdk
io\.branch
com\.mixpanel
com\.amplitude
com\.segment
com\.flurry
com\.onesignal
org\.matomo
org\.piwik
com\.yandex\.android:mobmetricalib
com\.huawei\.hms:hianalytics'

CLASS_BLOCKLIST='com\.google\.android\.datatransport|com\.google\.firebase\.(analytics|crashlytics|perf|messaging|inappmessaging|remoteconfig|installations)|com\.google\.android\.gms\.(measurement|ads|appset)|io\.sentry|com\.bugsnag|com\.instabug|com\.newrelic|com\.microsoft\.appcenter|com\.facebook\.(appevents|ads)|com\.appsflyer|com\.adjust\.sdk|io\.branch|com\.mixpanel|com\.amplitude|com\.segment\.analytics|com\.flurry|com\.onesignal|org\.matomo|org\.piwik|com\.yandex\.metrica|com\.huawei\.hms\.analytics'

deps="$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | tr -d '\r')"
# Garde-fou : une configuration renommée ou vide donnerait une sortie sans dépendance,
# et le contrôle passerait sans rien vérifier.
# (Here-string et non pipe : avec pipefail, `grep -q` ferme le pipe tôt et printf échoue.)
if ! grep -q 'org\.jetbrains\.kotlin:kotlin-stdlib' <<<"$deps"; then
  echo "::error::Arbre releaseRuntimeClasspath vide ou illisible : contrôle impossible"
  exit 1
fi
found="$(printf '%s\n' "$deps" | grep -E -f <(printf '%s\n' "$BLOCKLIST") | sed -E 's/^[ |+\\-]+//' | sort -u || true)"

if [[ -n "$found" ]]; then
  echo "::error::Dépendances de pistage détectées dans releaseRuntimeClasspath :"
  printf '%s\n' "$found"
  exit 1
fi

# Double vérification sur l'APK final : aucune classe de ces familles ne doit y être
# *définie*. (Le SDK Cast garde des références textuelles vers datatransport ; elles sont
# inertes puisque les classes sont absentes, d'où l'option --defined-only.)
# APK debug : non obfusqué (R8 renommerait les classes en release), mêmes dépendances.
apk="app/build/outputs/apk/debug/app-debug.apk"
analyzer=""
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
for candidate in apkanalyzer ${sdk:+"$sdk/cmdline-tools/latest/bin/apkanalyzer" "$sdk/cmdline-tools/latest/bin/apkanalyzer.bat"}; do
  if command -v "$candidate" >/dev/null 2>&1 || [[ -f "$candidate" ]]; then analyzer="$candidate"; break; fi
done

if [[ -f "$apk" ]]; then
  if [[ -z "$analyzer" ]]; then
    echo "::error::apkanalyzer introuvable : impossible de vérifier l'APK"
    exit 1
  fi
  defined="$("$analyzer" dex packages --defined-only "$apk" | tr -d '\r')"
  if [[ -z "$defined" ]]; then
    echo "::error::apkanalyzer n'a rien renvoyé pour $apk"
    exit 1
  fi
  # Paquets des mêmes familles que BLOCKLIST, vus côté classes (une dépendance peut être
  # embarquée sans figurer sous son nom Maven, par exemple dans un AAR « fat »).
  leaks="$(printf '%s\n' "$defined" | awk '$1 == "C"' | grep -E "$CLASS_BLOCKLIST" || true)"
  if [[ -n "$leaks" ]]; then
    echo "::error::Classes de télémétrie présentes dans $apk :"
    printf '%s\n' "$leaks" | head -20
    exit 1
  fi
elif [[ -n "${CI:-}" ]]; then
  # En CI, l'APK doit exister (assembleDebug précède) : son absence masquerait le contrôle.
  echo "::error::$apk introuvable : lancer assembleDebug avant ce script"
  exit 1
else
  echo "Avertissement : $apk absent, vérification des classes de l'APK ignorée (lancer assembleDebug)."
fi

echo "OK : aucune dépendance de pistage."
