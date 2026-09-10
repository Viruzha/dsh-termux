#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# Make DSH's attachment store work on Termux / Android.
#
# The store is what the `read_image` tool uses to persist a normalized copy of
# the image. Three Android-specific assumptions break it:
#
#  1. ensureDurableHome() walks every ancestor of DSH_HOME up to the filesystem
#     root and fsync()s each one. /data/data and /data are mode 0711 root:root,
#     so open(path, O_RDONLY) gives EACCES for an app uid
#     ("EACCES: permission denied, open '/data/data'").
#  2. publishStagedObject() publishes with link() -- hard links are denied with
#     EACCES for this app uid on this filesystem.
#  3. publishImmutableAlias() also uses link(); its fallback must copy, not
#     rename, because the source object has to stay in place.
#
# Patches: stop the durability walk at the first ancestor that cannot be opened,
# and fall back to rename()/copyFile() when link() is refused.
#
# Re-run after any dsh upgrade (npm replaces lib/index.js).
# The running dsh process must be restarted afterwards (ESM module cache).
# ---------------------------------------------------------------------------
set -euo pipefail

DSH_ROOT="${DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
TARGET="$DSH_ROOT/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js"

[ -f "$TARGET" ] || { echo "error: not found: $TARGET" >&2; exit 1; }

python3 - "$TARGET" <<'PY'
import os, shutil, sys

path = sys.argv[1]
src = open(path, encoding="utf-8").read()

MARK = "Android/Termux: hard links are denied"
MARK_FSYNC = "Android/Termux: ancestor directory is not openable"

if MARK in src and MARK_FSYNC in src:
    print("already patched; nothing to do")
    sys.exit(0)

if not src.startswith("import"):
    print("error: unexpected file layout (no import block)", file=sys.stderr)
    sys.exit(1)

if not os.path.exists(path + ".orig"):
    shutil.copy2(path, path + ".orig")
changed = []

# --- 1) durability walk: stop at the first ancestor this uid cannot open ----
if MARK_FSYNC not in src:
    old = """\tlet level = target;
\twhile (level !== stop) {
\t\tconst parent = dirname(level);
\t\tawait syncDirectory(parent);
"""
    new = """\tlet level = target;
\twhile (level !== stop) {
\t\tconst parent = dirname(level);
\t\ttry {
\t\t\tawait syncDirectory(parent);
\t\t} catch (error) {
\t\t\t/* Android/Termux: ancestor directory is not openable by this uid
\t\t\t   (e.g. /data/data is mode 0711 root:root, so open() gives EACCES).
\t\t\t   Durability cannot be extended above the first such ancestor; the
\t\t\t   entry itself is still written, fsynced and published. */
\t\t\tif (error?.code === "EACCES" || error?.code === "EPERM") return;
\t\t\tthrow error;
\t\t}
"""
    if src.count(old) != 1:
        print(f"error: durability-walk anchor matched {src.count(old)} times", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old, new)
    changed.append("durability walk")

# --- 2) helpers + call-site swaps for the two hard-link publications --------
if MARK not in src:
    anchor = "async function publishImmutableObject(root, target, data, sha256) {"
    helpers = '''/**
 * Publish one verified file under a new name.
 * Android/Termux: hard links are denied with EACCES for an app uid, so fall back
 * to an atomic same-filesystem rename. The source is this process's own private
 * staging file, which is removed afterwards either way, and the target name is
 * content-addressed, so overwriting an existing name stores identical bytes.
 */
async function linkOrRename(source, target) {
\ttry {
\t\tawait link(source, target);
\t} catch (error) {
\t\tif (!(error instanceof Error && "code" in error && error.code === "EACCES")) throw error;
\t\t/* Android/Termux: hard links are denied; rename publishes atomically. */
\t\tawait rename(source, target);
\t}
}
/**
 * Publish an additional name for an existing object.
 * Android/Termux: hard links are denied with EACCES, so copy instead -- the
 * source object must stay in place. COPYFILE_EXCL preserves link()'s
 * create-if-absent (EEXIST) race semantics for the caller's digest check.
 */
async function linkOrCopy(source, target) {
\ttry {
\t\tawait link(source, target);
\t} catch (error) {
\t\tif (!(error instanceof Error && "code" in error && error.code === "EACCES")) throw error;
\t\t/* Android/Termux: hard links are denied; copy keeps the source. */
\t\tawait copyFile(source, target, constants.COPYFILE_EXCL);
\t}
}
'''
    if src.count(anchor) != 1:
        print(f"error: helper anchor matched {src.count(anchor)} times", file=sys.stderr)
        sys.exit(1)
    src = src.replace(anchor, helpers + anchor, 1)

    staged_call = "\t\t\tawait link(staged.path, target);"
    alias_call = "\t\t\tawait link(source, target);"
    if src.count(staged_call) != 1 or src.count(alias_call) != 1:
        print(f"error: link() call sites: staged={src.count(staged_call)} alias={src.count(alias_call)}", file=sys.stderr)
        sys.exit(1)
    src = src.replace(staged_call, "\t\t\tawait linkOrRename(staged.path, target);")
    src = src.replace(alias_call, "\t\t\tawait linkOrCopy(source, target);")

    # The rename fallback moves the staging file, so the unconditional unlink of
    # that name must tolerate ENOENT (removeTemporary already does).
    if src.count("\t\tawait unlink(staged.path);") != 1:
        print("error: staging unlink anchor not found exactly once", file=sys.stderr)
        sys.exit(1)
    src = src.replace("\t\tawait unlink(staged.path);", "\t\tawait removeTemporary(staged.path);")

    if "copyFile," not in src:
        old_imp = 'import { chmod, link,'
        if src.count(old_imp) != 1:
            print("error: fs/promises import anchor not found", file=sys.stderr)
            sys.exit(1)
        src = src.replace(old_imp, 'import { chmod, copyFile, link,')

    changed.append("hard-link fallbacks")

open(path, "w", encoding="utf-8").write(src)
print("patched:", path)
print("applied:", ", ".join(changed))
print("backup :", path + ".orig")
PY

echo "== 校验：模块能否正常导入 =="
cd "$DSH_ROOT"
node --input-type=module -e '
import("@deepseek-ai/dsh-attachment-local")
  .then(() => console.log("✓ 模块导入成功"))
  .catch((e) => { console.log("✗ 导入失败:", e.message.split("\n")[0]); process.exit(1); })
'
