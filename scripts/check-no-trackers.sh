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

deps="$(./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath)"
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
for candidate in apkanalyzer "${ANDROID_HOME:-}/cmdline-tools/latest/bin/apkanalyzer" "${ANDROID_HOME:-}/cmdline-tools/latest/bin/apkanalyzer.bat"; do
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
  leaks="$(printf '%s\n' "$defined" | awk '$1 == "C"' | grep -E 'com\.google\.android\.datatransport|com\.google\.firebase\.(analytics|crashlytics)|io\.sentry|com\.appsflyer|com\.facebook\.appevents|org\.matomo|org\.piwik' || true)"
  if [[ -n "$leaks" ]]; then
    echo "::error::Classes de télémétrie présentes dans $apk :"
    printf '%s\n' "$leaks" | head -20
    exit 1
  fi
fi

echo "OK : aucune dépendance de pistage."
