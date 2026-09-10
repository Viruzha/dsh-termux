#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# session-persistence-jsonl: publish a session file with link(), fall back to
# rename() when the filesystem refuses hard links.
#
# Android/Termux denies link() to an app uid on app-private storage
# (EACCES: permission denied, link ...), so writing a session checkpoint fails
# without this. Same root cause as patches/attachment-android.sh.
#
# Idempotent. Re-run after every dsh upgrade.
# ---------------------------------------------------------------------------
set -euo pipefail

DSH_ROOT="${DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
F=$(find "$DSH_ROOT" -path '*session-persistence-jsonl*' -name 'index.js' 2>/dev/null | head -1)
[ -n "$F" ] || { echo "error: session-persistence-jsonl/index.js not found under $DSH_ROOT" >&2; exit 1; }

if grep -q 'error.code !== "EACCES"' "$F"; then
  echo "session-eacces: already patched"
  exit 0
fi

[ -f "$F.orig" ] || cp "$F" "$F.orig"

# make sure rename() is imported
if ! grep -qE '^import \{[^}]*\brename\b' "$F"; then
  sed -i 's/import { link,/import { rename, link,/' "$F"
fi

PATCH_FILE="$F" python3 <<'PY'
import os, sys
p = os.environ["PATCH_FILE"]
lines = open(p, encoding="utf-8").read().split("\n")
idx = next((i for i, l in enumerate(lines) if "await link(tmp, finalPath)" in l), None)
if idx is None:
    print("error: anchor 'await link(tmp, finalPath)' not found", file=sys.stderr)
    sys.exit(1)
raw = lines[idx]
indent = raw[:len(raw) - len(raw.lstrip())]
lines[idx:idx + 1] = [
    f"{indent}try {{",
    f"{indent}  await link(tmp, finalPath);",
    f"{indent}}} catch (error) {{",
    f'{indent}  /* Android/Termux: hard links are denied (EACCES) for an app uid; rename',
    f'{indent}     publishes the verified temp file atomically instead. */',
    f'{indent}  if (error.code !== "EACCES") throw error;',
    f"{indent}  await rename(tmp, finalPath);",
    f"{indent}}}",
]
open(p, "w", encoding="utf-8").write("\n".join(lines))
print(f"session-eacces: patched line {idx + 1}")
PY

cd "$DSH_ROOT"
node --input-type=module -e '
import("@deepseek-ai/dsh-session-persistence-jsonl")
  .then(() => console.log("session-eacces: ✓ module imports"))
  .catch((e) => { console.log("session-eacces: ✗ import failed:", e.message.split("\n")[0]); process.exit(1); })
'
