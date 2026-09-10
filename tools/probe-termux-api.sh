#!/data/data/com.termux/files/usr/bin/bash
# Classify which Termux:API commands actually answer, without printing private
# values: JSON results are reduced to their key names, free text to byte counts.
set -uo pipefail
STUB="not yet available on Google Play"

probe() {
  local name="$1"; shift
  local out rc
  out=$(timeout 25 "$name" "$@" 2>&1); rc=$?
  if printf '%s' "$out" | grep -q "$STUB"; then
    printf '%-30s ✗ STUB 拒绝\n' "$name"
    return
  fi
  if [ "$rc" -ne 0 ]; then
    printf '%-30s ✗ exit=%s %s\n' "$name" "$rc" "$(printf '%s' "$out" | head -1 | cut -c1-60)"
    return
  fi
  local bytes; bytes=$(printf '%s' "$out" | wc -c)
  local keys
  keys=$(printf '%s' "$out" | python3 -c '
import json,sys
try:
    d = json.load(sys.stdin)
    print(",".join(list(d.keys())[:8]) if isinstance(d, dict) else f"list[{len(d)}]")
except Exception:
    print("(非 JSON)")
' 2>/dev/null)
  printf '%-30s ✓ %s 字节  字段: %s\n' "$name" "$bytes" "$keys"
}

probe termux-battery-status
probe termux-audio-info
probe termux-camera-info
probe termux-clipboard-get
probe termux-wifi-connectioninfo
probe termux-telephony-deviceinfo
probe termux-tts-engines
probe termux-infrared-frequencies
probe termux-volume
probe termux-usb -l
probe termux-sensor -l
probe termux-notification-list
