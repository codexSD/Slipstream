#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MERIDIAN="$ROOT/src/Slipstream.Meridian"
APP="$ROOT/src/Slipstream.App"
status=0

echo "==> Colour literals outside the token dictionaries"
offenders=$(grep -rn --include="*.xaml" --include="*.cs" -E '#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?' "$MERIDIAN" "$APP" \
  | grep -v "Themes/Tokens.Light.xaml" | grep -v "Themes/Tokens.Dark.xaml" || true)
[ -n "$offenders" ] && { echo "FAIL: colours belong in Tokens.*.xaml:"; echo "$offenders"; status=1; }

echo "==> Every brush defined in both Light and Dark"
for key in $(grep -oE 'x:Key="Meridian[A-Za-z]+Brush"' "$MERIDIAN/Themes/Tokens.Light.xaml" | sort -u); do
  grep -q "$key" "$MERIDIAN/Themes/Tokens.Dark.xaml" || { echo "FAIL: $key missing from Dark."; status=1; }
done
for key in $(grep -oE 'x:Key="Meridian[A-Za-z]+Brush"' "$MERIDIAN/Themes/Tokens.Dark.xaml" | sort -u); do
  grep -q "$key" "$MERIDIAN/Themes/Tokens.Light.xaml" || { echo "FAIL: $key missing from Light."; status=1; }
done

echo "==> ALL CAPS"
caps=$(grep -rn --include="*.xaml" --include="*.cs" -E 'ToUpper\(\)|CharacterCasing="Upper"' "$APP" "$MERIDIAN" || true)
[ -n "$caps" ] && { echo "FAIL: sentence case only:"; echo "$caps"; status=1; }

echo "==> Elevation / drop shadows"
shadow=$(grep -rn --include="*.xaml" -E 'ThemeShadow|DropShadow|Translation="0,0,[1-9]' "$APP" "$MERIDIAN" || true)
[ -n "$shadow" ] && { echo "FAIL: Meridian uses strokes, not shadows:"; echo "$shadow"; status=1; }

[ "$status" -eq 0 ] && echo "All Meridian WinUI checks passed."
exit "$status"
