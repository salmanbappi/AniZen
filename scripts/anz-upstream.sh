#!/usr/bin/env bash
#
# AniZen upstream provenance & merge tooling.
#
# AniZen's history is squashed (see .anizen/upstream.json), so git cannot compute
# a merge base with Anikku. This tool reconstructs "ours vs theirs" from the
# recorded content ancestor and performs base-corrected 3-way merges.
#
# Usage:
#   scripts/anz-upstream.sh report [options]     classification + conflict estimate
#   scripts/anz-upstream.sh merge  [options]     base-corrected merge into a sync branch
#
# Options:
#   --ref <tree-ish>    Upstream tree-ish (default: <upstream.remote>/<upstream.branch>)
#   --mirror [<dir>]    Use a blobless bare mirror instead of a local remote-tracking ref
#                       (default dir: .tmp/upstream-mirror). Disk-friendly; no blobs.
#   --fetch             Refresh the upstream ref/mirror first
#   --base <tree-ish>   Override the recorded merge base (default: base.upstreamCommit)
#   --out <file>        Report path (default: .anizen/provenance-report.md)
#   --branch <name>     Sync branch for merge mode (default: upstream-sync)
#   --json              Print a machine-readable summary to stdout
#   -h, --help
#
# Exit codes: 0 ok · 1 usage/setup error · 2 merge conflicts present (merge mode)
#
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

CONFIG=".anizen/upstream.json"
DEFAULT_MIRROR=".tmp/upstream-mirror"

MODE="report"
REF=""
REF_EXPLICIT=0
MIRROR=""
DO_FETCH=0
BASE=""
OUT=".anizen/provenance-report.md"
SYNC_BRANCH="upstream-sync"
AS_JSON=0

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*" >&2; }

usage() { sed -n '3,25p' "$0" | sed 's/^#\{1,2\} \{0,1\}//'; exit 0; }

while [ $# -gt 0 ]; do
  case "$1" in
    report|merge) MODE="$1"; shift ;;
    --ref) [ $# -ge 2 ] || die "--ref needs a value"; REF="$2"; REF_EXPLICIT=1; shift 2 ;;
    --mirror) MIRROR="$DEFAULT_MIRROR"; shift
              if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then MIRROR="$1"; shift; fi ;;
    --fetch) DO_FETCH=1; shift ;;
    --base) [ $# -ge 2 ] || die "--base needs a value"; BASE="$2"; shift 2 ;;
    --out) [ $# -ge 2 ] || die "--out needs a value"; OUT="$2"; shift 2 ;;
    --branch) [ $# -ge 2 ] || die "--branch needs a value"; SYNC_BRANCH="$2"; shift 2 ;;
    --json) AS_JSON=1; shift ;;
    -h|--help) usage ;;
    *) die "unknown argument: $1 (try --help)" ;;
  esac
done

[ -f "$CONFIG" ] || die "missing $CONFIG"

read -r UP_NAME UP_URL UP_REMOTE UP_BRANCH ROOT_COMMIT BASE_DEFAULT < <(
  python3 - "$CONFIG" <<'PY'
import json, sys
c = json.load(open(sys.argv[1]))
print(
    c["upstream"]["name"],
    c["upstream"]["url"],
    c["upstream"]["remote"],
    c["upstream"]["branch"],
    c["anizen"]["rootCommit"],
    c["base"]["upstreamCommit"],
)
PY
)

[ -n "$BASE" ] || BASE="$BASE_DEFAULT"
if [ "$REF_EXPLICIT" = 0 ]; then
  if [ -n "$MIRROR" ]; then REF="refs/heads/$UP_BRANCH"; else REF="refs/remotes/$UP_REMOTE/$UP_BRANCH"; fi
fi

mkdir -p .tmp
WORK="$(mktemp -d .tmp/anz-upstream.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

mirror_fetch() {
  [ -d "$MIRROR" ] || git init --bare -q "$MIRROR"
  info "fetching $UP_URL#$UP_BRANCH (blobless, depth 1) -> $MIRROR"
  git -C "$MIRROR" fetch -q --depth=1 --filter=blob:none --no-tags \
    "$UP_URL" "+refs/heads/$UP_BRANCH:refs/heads/$UP_BRANCH"
}

if [ -n "$MIRROR" ]; then
  [ "$DO_FETCH" = 1 ] && mirror_fetch
  [ -d "$MIRROR" ] || die "mirror '$MIRROR' not found (pass --fetch to create it)"
  git -C "$MIRROR" rev-parse --verify -q "$REF^{commit}" >/dev/null \
    || die "ref '$REF' not found in mirror '$MIRROR'"
  git -C "$MIRROR" ls-tree -r "$REF" > "$WORK/up.txt"
else
  if [ "$DO_FETCH" = 1 ]; then
    info "fetching $UP_REMOTE/$UP_BRANCH"
    git fetch -q --no-tags "$UP_REMOTE" "+refs/heads/$UP_BRANCH:refs/remotes/$UP_REMOTE/$UP_BRANCH"
  fi
  git rev-parse --verify -q "$REF^{commit}" >/dev/null \
    || die "ref '$REF' not found (try --fetch, or --mirror for a disk-friendly blobless clone)"
  git ls-tree -r "$REF" > "$WORK/up.txt"
fi

git rev-parse --verify -q "$BASE^{commit}" >/dev/null || die "base '$BASE' not found locally"
git rev-parse --verify -q "$ROOT_COMMIT^{commit}" >/dev/null || die "root commit '$ROOT_COMMIT' not found"

git ls-tree -r "$BASE" > "$WORK/base.txt"
git ls-tree -r "HEAD"  > "$WORK/head.txt"

HEAD_SHA="$(git rev-parse HEAD)"
BASE_SHA="$(git rev-parse "$BASE")"
if [ -n "$MIRROR" ]; then UP_SHA="$(git -C "$MIRROR" rev-parse "$REF")"; else UP_SHA="$(git rev-parse "$REF")"; fi
HEAD_BRANCH="$(git rev-parse --abbrev-ref HEAD)"

mkdir -p "$(dirname "$OUT")"

python3 - "$WORK/base.txt" "$WORK/head.txt" "$WORK/up.txt" "$OUT" "$AS_JSON" \
         "$HEAD_SHA" "$HEAD_BRANCH" "$UP_SHA" "$BASE_SHA" "$UP_NAME" <<'PY'
import json, sys, datetime

base_f, head_f, up_f, out_f, as_json, head_sha, head_branch, up_sha, base_sha, up_name = sys.argv[1:11]

def read_tree(path):
    d = {}
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or "\t" not in line:
                continue
            meta, p = line.split("\t", 1)
            parts = meta.split()
            if len(parts) == 3 and parts[1] == "blob":
                d[p] = parts[2]
    return d

base, head, up = read_tree(base_f), read_tree(head_f), read_tree(up_f)

def changed(a, b):
    return {k for k in (set(a) | set(b)) if a.get(k) != b.get(k)}

anizen_changed = changed(base, head)
up_changed = changed(base, up)
overlap = anizen_changed & up_changed

both = set(head) & set(up)
identical = {p for p in both if head[p] == up[p]}
differing = {p for p in both if head[p] != up[p]}
anizen_only = set(head) - set(up)
up_only = set(up) - set(head)

both_modified = sorted(p for p in overlap if p in head and p in up)
modify_delete = sorted(p for p in overlap if (p in head) ^ (p in up))
both_deleted = sorted(p for p in overlap if p not in head and p not in up)
candidates = both_modified + modify_delete

now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")

L = []
w = L.append
w(f"# AniZen \u21c4 {up_name} provenance report")
w("")
w(f"Generated: {now}")
w("")
w("| | |")
w("|---|---|")
w(f"| AniZen HEAD | `{head_sha[:12]}` (`{head_branch}`) |")
w(f"| Upstream ref | `{up_sha[:12]}` |")
w(f"| Merge base | `{base_sha[:12]}` |")
w("")
w("AniZen's history is squashed, so this report derives *ours vs theirs* from the")
w("recorded content ancestor instead of git ancestry.")
w("")
w("## Summary")
w("")
w("| Metric | Count |")
w("|---|---:|")
w(f"| Files in AniZen | {len(head)} |")
w(f"| Files upstream | {len(up)} |")
w(f"| Identical content | {len(identical)} |")
w(f"| Differing content | {len(differing)} |")
w(f"| AniZen-only files | {len(anizen_only)} |")
w(f"| Upstream-only files | {len(up_only)} |")
w(f"| Changed by AniZen since base | {len(anizen_changed)} |")
w(f"| Changed upstream since base | {len(up_changed)} |")
w(f"| **Overlapping paths (conflict surface)** | **{len(candidates)}** |")
w("")
w("## Conflict surface (upper bound)")
w("")
w("| Shape | Count |")
w("|---|---:|")
w(f"| content vs content | {len(both_modified)} |")
w(f"| modify / delete | {len(modify_delete)} |")
w(f"| deleted on both sides (no conflict) | {len(both_deleted)} |")
w("")
if not candidates:
    w("**Verdict: no overlapping paths — a base-corrected merge should apply cleanly.**")
else:
    w(f"**Verdict: {len(candidates)} paths need attention.** This is an upper bound:")
    w("a base-corrected 3-way merge auto-resolves paths whose edits do not overlap.")
w("")

def table(title, paths, shape_map=None, cap=60):
    if not paths:
        return
    w(f"## {title} ({len(paths)})")
    w("")
    w("| Path | Shape |")
    w("|---|---|")
    for p in paths[:cap]:
        w(f"| `{p}` | {shape_map[p] if shape_map else 'changed'} |")
    if len(paths) > cap:
        w(f"| _... {len(paths) - cap} more_ | |")
    w("")

shape = {}
for p in both_modified: shape[p] = "content vs content"
for p in modify_delete: shape[p] = "modify / delete"
table("Paths needing attention", candidates, shape)
table("Upstream-only files", sorted(up_only))
table("AniZen-only files", sorted(anizen_only))

w("## Resolving")
w("")
w("1. Keep every `// ANZ` block; take upstream outside the markers.")
w("2. Preserve inherited blocks (`// ANK`, `// KMK`, `// SY`, `// AY`) unless the")
w("   review says otherwise \u2014 they map to other upstreams.")
w("3. Re-run `scripts/check-provenance.sh` before pushing.")
w("4. Merge on a sync branch, never directly into `preview`.")
w("")

with open(out_f, "w", encoding="utf-8") as f:
    f.write("\n".join(L) + "\n")

summary = {
    "generated": now,
    "anizenHead": head_sha,
    "upstreamRef": up_sha,
    "mergeBase": base_sha,
    "counts": {
        "anizenFiles": len(head),
        "upstreamFiles": len(up),
        "identical": len(identical),
        "differing": len(differing),
        "anizenOnly": len(anizen_only),
        "upstreamOnly": len(up_only),
        "changedByAnizen": len(anizen_changed),
        "changedByUpstream": len(up_changed),
        "conflictSurface": len(candidates),
        "bothModified": len(both_modified),
        "modifyDelete": len(modify_delete),
    },
    "conflictPaths": candidates,
}
if as_json == "1":
    print(json.dumps(summary, indent=2))
PY

info "report written to $OUT"

# ---------------------------------------------------------------- merge mode
if [ "$MODE" = merge ]; then
  [ -z "$MIRROR" ] || die "merge mode needs a blob-complete local ref (drop --mirror)"
  [ "$HEAD_BRANCH" != "$SYNC_BRANCH" ] || die "cannot update '$SYNC_BRANCH' while it is checked out"
  git diff --quiet && git diff --cached --quiet \
    || info "warning: working tree is dirty; merge-tree still operates on commits only"

  if MT_OUT="$(git merge-tree --write-tree --merge-base "$BASE" HEAD "$REF" 2>&1)"; then
    RC=0
  else
    RC=$?
  fi
  TREE="$(printf '%s\n' "$MT_OUT" | head -1)"

  if [ "$RC" = 0 ]; then
    MSG="chore(upstream): base-corrected merge of $UP_NAME/$UP_BRANCH

Merge base: $BASE_SHA
Upstream:   $UP_SHA"
    NEW="$(git commit-tree "$TREE" -p HEAD -p "$REF" -m "$MSG")"
    git branch -f "$SYNC_BRANCH" "$NEW"
    info "clean merge -> branch '$SYNC_BRANCH' at ${NEW:0:12}"
    info "review, then: git checkout $SYNC_BRANCH && git push -u origin $SYNC_BRANCH"
  else
    printf '%s\n' "$MT_OUT" \
      | sed -n 's/^[0-7]\{6\} [0-9a-f]\{40\} [123]\t//p' | sort -u > "$WORK/conflicts.txt"
    printf '%s\n' "$MT_OUT" | grep '^CONFLICT' > "$WORK/conflict-msgs.txt" || true
    info "merge has conflicts in $(wc -l < "$WORK/conflicts.txt" | tr -d ' ') path(s):"
    sed 's/^/    /' "$WORK/conflicts.txt" >&2
    {
      echo ""
      echo "## Merge attempt ($(date -u +%Y-%m-%dT%H:%M:%SZ))"
      echo ""
      echo "Base-corrected merge of \`${REF}\` (base \`${BASE_SHA:0:12}\`) reported conflicts."
      echo ""
      cat "$WORK/conflicts.txt" | sed 's/^/- `/; s/$/`/'
    } >> "$OUT"
    info "conflict list appended to $OUT"
    exit 2
  fi
fi
