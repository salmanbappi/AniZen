#!/usr/bin/env bash
#
# AniZen provenance guard.
#
# Enforces the fork-hygiene rules that keep upstream (Anikku) merges tractable.
# See AGENTS.md for the convention this protects.
#
# Checks (diff-scoped, so existing code is never blocked):
#   FAIL  R1  edits to non-base locales (Weblate owns translations)
#   FAIL  R2  new <string>/<plurals> added to a frozen upstream i18n module
#   WARN  R3  changed Kotlin files whose added lines carry no provenance marker
#   WARN  R4  settings.gradle.kts project includes with no matching directory
#
# R1/R2 are skipped for upstream-sync (`chore(upstream):`) and Weblate commits,
# which legitimately touch frozen modules and non-base locales.
#
# Environment:
#   BASE_REF   ref to diff against (default: origin/preview)
#   STRICT=1   let warnings fail the run too
#
# Exit: 0 clean (or warnings when not strict) · 1 violations · 0 if base ref missing
#
set -euo pipefail

BASE_REF="${BASE_REF:-origin/preview}"
STRICT="${STRICT:-0}"

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  sed -n '3,22p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'
  exit 0
fi

cd "$(git rev-parse --show-toplevel)"

if ! git rev-parse --verify -q "$BASE_REF^{commit}" >/dev/null; then
  echo "provenance: base ref '$BASE_REF' not found — skipping checks"
  echo "provenance: set BASE_REF to the branch you are merging into (e.g. origin/preview)"
  exit 0
fi

MB="$(git merge-base "$BASE_REF" HEAD)"
CHANGED="$(git diff --name-only "$MB" HEAD || true)"

if [ -z "$CHANGED" ]; then
  echo "provenance: no changes against $BASE_REF — nothing to check"
  exit 0
fi

FAILURES=()
WARNINGS=()

# Upstream syncs and Weblate imports legitimately modify frozen modules and
# non-base locales, so they are exempt from R1/R2.
SYNC_RANGE=0
if git log --format='%s' "$MB..HEAD" 2>/dev/null | grep -qiE '^chore\(upstream\):|weblate'; then
  SYNC_RANGE=1
fi

# ── R1: non-base locale edits ────────────────────────────────────────────────
if [ "$SYNC_RANGE" = 0 ]; then
  while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
      i18n*/src/*/moko-resources/*) ;;
      *) continue ;;
    esac
    case "$f" in
      */moko-resources/base/*) continue ;;
    esac
    FAILURES+=("R1 non-base locale edited (Weblate owns these): $f")
  done <<< "$CHANGED"

  # ── R2: new strings in a frozen upstream module ────────────────────────────
  FROZEN_RE='^(i18n|i18n-aniyomi|i18n-kmk|i18n-sy)/'
  FROZEN_FILES="$(printf '%s\n' "$CHANGED" | grep -E "$FROZEN_RE" || true)"
  if [ -n "$FROZEN_FILES" ]; then
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      hits="$(git diff -U0 "$MB" HEAD -- "$f" \
        | grep -E '^\+[^+]' \
        | grep -E '<(string|plurals)[ >]' || true)"
      if [ -n "$hits" ]; then
        FAILURES+=("R2 new string in frozen upstream module '$f' — add it to i18n-ank (AMR) instead")
      fi
    done <<< "$FROZEN_FILES"
  fi
fi

# ── R3: provenance markers on changed Kotlin ─────────────────────────────────
UNMARKED=()
while IFS= read -r f; do
  [ -n "$f" ] || continue
  [ -f "$f" ] || continue
  added="$(git diff -U0 "$MB" HEAD -- "$f" \
    | grep -E '^\+[^+]' \
    | grep -vE '^\+\s*(//|/\*|\*)' \
    | grep -vE '^\+import ' || true)"
  count="$(printf '%s\n' "$added" | grep -c . || true)"
  [ "${count:-0}" -ge 3 ] || continue
  printf '%s\n' "$added" | grep -qE '//\s*(ANZ|ANK|KMK|SY|AY|EXH)' && continue
  UNMARKED+=("$f")
done <<< "$(printf '%s\n' "$CHANGED" | grep -E '\.(kt|kts)$' || true)"

if [ "${#UNMARKED[@]}" -gt 0 ]; then
  WARNINGS+=("R3 ${#UNMARKED[@]} changed Kotlin file(s) add code without a // ANZ marker:")
  for f in "${UNMARKED[@]}"; do WARNINGS+=("       $f"); done
fi

# ── R4: settings includes must resolve to a directory ────────────────────────
if [ -f settings.gradle.kts ]; then
  while IFS= read -r proj; do
    [ -n "$proj" ] || continue
    rel="${proj#:}"
    dir="${rel//:/\/}"
    [ -d "$dir" ] || WARNINGS+=("R4 settings.gradle.kts includes '$proj' but '$dir/' does not exist")
  done <<< "$(grep -oE 'include\(":[^"]+"\)' settings.gradle.kts | sed -E 's/include\("(:[^"]+)"\)/\1/')"
fi

# ── report ───────────────────────────────────────────────────────────────────
echo "provenance guard: ${#FAILURES[@]} violation(s), ${#WARNINGS[@]} warning(s) vs $BASE_REF"
[ "$SYNC_RANGE" = 1 ] && echo "note: upstream-sync/Weblate range detected — R1/R2 skipped"

if [ "${#FAILURES[@]}" -gt 0 ]; then
  echo
  echo "✗ violations"
  for m in "${FAILURES[@]}"; do echo "  $m"; done
fi
if [ "${#WARNINGS[@]}" -gt 0 ]; then
  echo
  echo "! warnings"
  for m in "${WARNINGS[@]}"; do echo "  $m"; done
fi

if [ "${#FAILURES[@]}" -gt 0 ]; then
  echo
  echo "See AGENTS.md → 'Provenance markers' and 'Upstream merging'."
  exit 1
fi

if [ "$STRICT" = "1" ] && [ "${#WARNINGS[@]}" -gt 0 ]; then
  echo
  echo "STRICT=1 set — failing on warnings."
  exit 1
fi

echo "✓ ok"
