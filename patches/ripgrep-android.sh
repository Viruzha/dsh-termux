#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# Restore DSH's glob/grep tools on Termux / Android.
#
# Why they break:
#   DSH's search tools spawn the ripgrep binary shipped by @vscode/ripgrep.
#   That package picks its platform package as
#   `@vscode/ripgrep-${process.platform}-${process.arch}`. Node on Termux
#   reports process.platform === 'android', so it looks for
#   @vscode/ripgrep-android-arm64 -- which upstream does not publish (its
#   linux-arm64 build is glibc-linked and cannot run on Android/Bionic).
#   Result: `glob`/`grep` fail with
#   "could not start its search command (ripgrep launch failed)".
#
# What this does:
#   Satisfies that resolution with a local shim package whose bin/rg is a
#   symlink to the Termux-native ripgrep (a real Android binary).
#
# Re-run this after any `npm install -g @deepseek-ai/dsh` or dsh upgrade,
# since that replaces dsh/node_modules and removes the shim.
#
# Note: the failure is memoized per process (rgPathPromise ??= ...), so a DSH
# process that already hit the error keeps failing until it is restarted.
# Restart `dsh web` after running this for the first time.
# ---------------------------------------------------------------------------
set -euo pipefail

DSH_ROOT="${DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
SHIM="$DSH_ROOT/node_modules/@vscode/ripgrep-android-arm64"

if [ ! -d "$DSH_ROOT" ]; then
  echo "error: DSH install not found at $DSH_ROOT" >&2
  exit 1
fi

RG="$(command -v rg || true)"
if [ -z "$RG" ]; then
  echo "error: ripgrep not found; run: pkg install -y ripgrep" >&2
  exit 1
fi

mkdir -p "$SHIM/bin"
ln -sfn "$RG" "$SHIM/bin/rg"

# No "exports" field on purpose: require.resolve() must reach the bin/rg
# subpath through legacy package resolution.
cat > "$SHIM/package.json" <<EOF
{
  "name": "@vscode/ripgrep-android-arm64",
  "version": "1.18.0",
  "private": true,
  "description": "Local shim satisfying @vscode/ripgrep platform resolution on Termux/Android. bin/rg is a symlink to the Termux-native ripgrep.",
  "license": "MIT"
}
EOF

echo "shim: $SHIM"
echo "rg  : $RG -> $("$RG" --version | head -1)"

# Verify the exact resolution @vscode/ripgrep performs, then spawn it.
cd "$DSH_ROOT"
node --input-type=module -e '
const { rgPath } = await import("@vscode/ripgrep");
const { spawn } = await import("node:child_process");
process.stdout.write("resolved rgPath: " + rgPath + "\n");
const child = spawn(rgPath, ["--no-config", "--version"]);
child.stdout.pipe(process.stdout);
child.on("error", (e) => { console.error("spawn failed: " + e.message); process.exitCode = 1; });
child.on("close", (code) => { if (code !== 0) process.exitCode = 1; });
'

echo "OK - restart the dsh process (e.g. dsh web) for it to take effect."
