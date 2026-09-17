#!/data/data/com.termux/files/usr/bin/bash
# Termux's aapt2 is 2.19 (AOSP 13) and rejects options that newer apktool passes.
# Strip the unsupported ones and forward everything else to the real binary.
args=()
for a in "$@"; do
  case "$a" in
    --no-compile-sdk-metadata) continue ;;
  esac
  args+=("$a")
done
exec /data/data/com.termux/files/usr/bin/aapt2 "${args[@]}"
