#!/usr/bin/env bash
# Génère un badge SVG de couverture (lignes) à partir du rapport Kover agrégé.
# Usage : scripts/coverage-badge.sh [rapport.xml] [sortie.svg]
# Aucun service externe : le badge est versionné dans le dépôt par la CI.
set -euo pipefail

report="${1:-build/reports/kover/report.xml}"
out="${2:-.github/badges/coverage.svg}"

[[ -f "$report" ]] || { echo "::error::Rapport Kover introuvable : $report" >&2; exit 1; }

# Le dernier compteur LINE du fichier est le total du rapport (après ceux des paquets).
read -r missed covered < <(tr -d '\r' < "$report" \
  | grep -oE '<counter type="LINE" missed="[0-9]+" covered="[0-9]+"' | tail -1 \
  | sed -E 's/.*missed="([0-9]+)" covered="([0-9]+)"/\1 \2/')

total=$((missed + covered))
(( total > 0 )) || { echo "::error::Aucune ligne mesurée dans $report" >&2; exit 1; }

pct=$(awk -v c="$covered" -v t="$total" 'BEGIN { printf "%.1f", 100 * c / t }')
int=${pct%.*}

if   (( int >= 80 )); then color="#4c1"
elif (( int >= 65 )); then color="#97ca00"
elif (( int >= 50 )); then color="#dfb317"
elif (( int >= 35 )); then color="#fe7d37"
else                       color="#e05d44"
fi

label="couverture"
value="${pct} %"
# Largeurs approximatives (Verdana 11 px ≈ 6,5 px par caractère) + marges.
lw=$(( ${#label} * 13 / 2 + 12 ))
vw=$(( ${#value} * 13 / 2 + 12 ))
w=$(( lw + vw ))

mkdir -p "$(dirname "$out")"
cat > "$out" <<SVG
<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="20" role="img" aria-label="$label: $value">
  <title>$label: $value</title>
  <linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/><stop offset="1" stop-opacity=".1"/></linearGradient>
  <clipPath id="r"><rect width="$w" height="20" rx="3" fill="#fff"/></clipPath>
  <g clip-path="url(#r)">
    <rect width="$lw" height="20" fill="#555"/>
    <rect x="$lw" width="$vw" height="20" fill="$color"/>
    <rect width="$w" height="20" fill="url(#s)"/>
  </g>
  <g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">
    <text x="$(( lw / 2 ))" y="15" fill="#010101" fill-opacity=".3">$label</text>
    <text x="$(( lw / 2 ))" y="14">$label</text>
    <text x="$(( lw + vw / 2 ))" y="15" fill="#010101" fill-opacity=".3">$value</text>
    <text x="$(( lw + vw / 2 ))" y="14">$value</text>
  </g>
</svg>
SVG

echo "Couverture des lignes : ${pct} % (${covered}/${total}) → $out"
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && echo "### Couverture des lignes : ${pct} % (${covered}/${total})" >> "$GITHUB_STEP_SUMMARY"
exit 0
