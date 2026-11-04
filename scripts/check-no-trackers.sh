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

# Double vérification sur l'APK final : aucune classe de ces familles ne doit y figurer.
apk="app/build/outputs/apk/release/app-release-unsigned.apk"
if [[ -f "$apk" ]]; then
  if unzip -p "$apk" 'classes*.dex' | grep -a -q -E 'Lcom/google/android/datatransport/runtime/TransportRuntime;|Lcom/google/firebase/analytics/|Lio/sentry/|Lcom/appsflyer/'; then
    echo "::error::Classes de télémétrie trouvées dans $apk"
    exit 1
  fi
fi

echo "OK : aucune dépendance de pistage."
