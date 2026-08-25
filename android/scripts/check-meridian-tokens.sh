#!/usr/bin/env bash
# Design-system gate. Runs first in CI: cheapest check, most likely to catch drift.
set -euo pipefail

MODULE="android/meridian-compose/src"
TOKENS="$MODULE/main/kotlin/com/slipstream/meridian/MeridianTokens.kt"
status=0

echo "==> Colour literals outside MeridianTokens.kt"
offenders=$(grep -rn --include="*.kt" -E "Color\(0x[0-9A-Fa-f]{6,8}\)" "$MODULE" \
  | grep -v "MeridianTokens.kt" | grep -v "/test/" | grep -v "/androidTest/" || true)
if [ -n "$offenders" ]; then
  echo "FAIL: colours must be declared in MeridianTokens.kt, not inline:"
  echo "$offenders"
  status=1
fi

echo "==> isSystemInDarkTheme() call sites"
darkCalls=$(grep -rn --include="*.kt" "isSystemInDarkTheme()" "$MODULE" \
  | grep -v "MeridianTheme.kt" || true)
if [ -n "$darkCalls" ]; then
  echo "FAIL: isSystemInDarkTheme() may only be called in MeridianTheme.kt:"
  echo "$darkCalls"
  status=1
fi

echo "==> Elevation above 1dp"
elevation=$(grep -rn --include="*.kt" -E "((shadow|tonal|default)Elevation\s*=\s*([2-9]|[1-9][0-9])\.dp|[Ee]levation\s*\(\s*[^)]*[2-9][0-9]*\.dp)" "$MODULE" || true)
if [ -n "$elevation" ]; then
  echo "FAIL: Meridian caps elevation at 1dp — structure comes from strokes:"
  echo "$elevation"
  status=1
fi

echo "==> ALL CAPS text transforms"
caps=$(grep -rn --include="*.kt" -E "\.uppercase\(\)|textAllCaps" "$MODULE" || true)
if [ -n "$caps" ]; then
  echo "FAIL: sentence case everywhere — never ALL CAPS:"
  echo "$caps"
  status=1
fi

echo "==> Hardcoded left/right instead of start/end"
sides=$(grep -rn --include="*.kt" -E "padding(Left|Right)\s*=|Alignment\.(CenterLeft|CenterRight)" "$MODULE" || true)
if [ -n "$sides" ]; then
  echo "FAIL: use start/end, never left/right:"
  echo "$sides"
  status=1
fi

echo "==> Every token role present in both modes"
for role in canvas surface stroke tint ink inkMuted brand brandStrong onBrand onBrandMuted strong positive warning critical info; do
  count=$(grep -cE "val $role\b" "$TOKENS" || true)
  if [ "$count" -lt 2 ]; then
    echo "FAIL: role '$role' is not defined in both Light and Dark."
    status=1
  fi
done

[ "$status" -eq 0 ] && echo "All Meridian checks passed."
exit "$status"
