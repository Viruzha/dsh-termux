#!/data/data/com.termux/files/usr/bin/bash
# Re-establish the wireless-debugging self-connection: the adb client running in
# Termux connects back to this same device's adbd.
#
# Why a scan is needed: the "connect" port is randomised and changes whenever
# wireless debugging is toggled, and Termux's adb build has no mDNS support
# (`adb mdns services` -> "unknown host service"), so the port cannot be
# discovered automatically. Pairing is a ONE-TIME step and a different port:
# it is shown only inside the "pair with code" dialog, so pass it as PORT/CODE
# to this script only when pairing is needed.
#
# Usage:
#   adb-connect-self.sh                 # reconnect (pairing already done)
#   adb-connect-self.sh --pair PORT CODE
set -uo pipefail

RANGE_START=30000
RANGE_END=49999

if [ "${1:-}" = "--pair" ]; then
  [ $# -eq 3 ] || { echo "usage: $0 --pair <pairing-port> <code>" >&2; exit 2; }
  echo "== 配对 127.0.0.1:$2 =="
  adb pair "127.0.0.1:$2" "$3" || exit 1
fi

echo "== 断开旧连接 =="
adb devices | awk 'NR>1 && $1 ~ /^127\.0\.0\.1:/ {print $1}' | while read -r d; do
  adb disconnect "$d" >/dev/null 2>&1 && echo "  断开 $d"
done

echo "== 扫描 loopback 上的候选端口 =="
mapfile -t ports < <(python3 - "$RANGE_START" "$RANGE_END" <<'PY'
import socket, sys, concurrent.futures as cf
lo, hi = int(sys.argv[1]), int(sys.argv[2])
def probe(p):
    s = socket.socket(); s.settimeout(0.35)
    try:
        return p if s.connect_ex(("127.0.0.1", p)) == 0 else None
    finally:
        s.close()
found = []
with cf.ThreadPoolExecutor(max_workers=400) as ex:
    for r in ex.map(probe, range(lo, hi + 1), chunksize=64):
        if r:
            found.append(r)
print("\n".join(str(p) for p in sorted(found)))
PY
)
echo "  开放端口: ${ports[*]:-（无）}"

if [ "${#ports[@]}" -eq 0 ]; then
  echo "无线调试未开启：请到 设置 -> 更多设置 -> 开发者选项 -> 无线调试 打开它。" >&2
  exit 1
fi

for p in "${ports[@]}"; do
  [ "$p" = "5037" ] && continue   # 本地 adb server，不是设备
  adb connect "127.0.0.1:$p" >/dev/null 2>&1
  if adb devices | grep -qE "^127\.0\.0\.1:$p[[:space:]]+device"; then
    echo "== 已连接 =="
    adb devices -l | grep -E "^127\.0\.0\.1:$p"
    adb -s "127.0.0.1:$p" shell id | head -1
    exit 0
  fi
  adb disconnect "127.0.0.1:$p" >/dev/null 2>&1
done

echo "找到开放端口但都无法作为 adb 连接；可能是配对已失效，需带 --pair 重新配对。" >&2
exit 1
