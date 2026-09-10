#!/data/data/com.termux/files/usr/bin/bash
# 安装 / 修复 rish —— 通过 Shizuku 获得 Android shell(uid 2000)。
#
#   setup-shizuku-rish.sh            自动寻找 Shizuku APK 并安装/修复
#   setup-shizuku-rish.sh <apk>      指定 Shizuku APK 路径
#   setup-shizuku-rish.sh --check    只体检（退出码 0 = rish 可用）
#   setup-shizuku-rish.sh --quiet    安静模式（只输出一行结论）
#
# 为什么需要它：Termux 自身是 untrusted_app(uid 10328)，pm/dumpsys/settings/screencap/input
# 全部被 SELinux 拒绝；Shizuku 以 shell 身份常驻并把能力暴露成 Binder 服务，rish 是官方终端入口。
# 引导只需一次（无 root 时借助 adb），此后 rish 不依赖 adb、无线调试或 WiFi：
# shizuku_server 只持有 UNIX 域套接字，TCP/UDP 套接字数为 0。
#
# 注意：Termux 读不到 /data/app，也跑不了 pm，所以本脚本按下列顺序找 APK：
#   1) 命令行参数  2) adb（若已连）  3) 家目录或 Download 里的 *shizuku*.apk

set -uo pipefail

SHIZUKU_PKG="moe.shizuku.privileged.api"
TERMUX_PKG="com.termux"
DEST="${HOME}/.shizuku"
APK_ARG=""
QUIET=0
CHECK=0

for a in "$@"; do case "$a" in
  --quiet|-q) QUIET=1 ;;
  --check|-c) CHECK=1 ;;
  -h|--help) sed -n '2,17p' "$0"; exit 0 ;;
  *) APK_ARG="$a" ;;
esac; done

log()  { [ "$QUIET" = 1 ] || printf '%s\n' "$*"; }
fail() { printf '%s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

# 判据：真正拿到 uid=2000 才算可用（文件存在不代表能连上 Shizuku）
rish_works() {
  [ -x "$DEST/rish" ] || return 1
  [ -f "$DEST/rish_shizuku.dex" ] || return 1
  # 必须 2>&1：rish 的输出会随机落到 stdout 或 stderr（Shizuku 侧的竞态）
  timeout 20 sh -c "printf 'id\n' | '$DEST/rish'" 2>&1 | grep -q 'uid=2000'
}

if [ "$CHECK" = 1 ]; then
  if rish_works; then echo "rish 可用（uid=2000，免 adb/WiFi）"; exit 0; fi
  echo "rish 不可用：Shizuku 未运行，或 $DEST 未安装" >&2
  exit 1
fi

if rish_works; then log "rish 已就绪（$DEST/rish）"; exit 0; fi

# ---- 找一个可用的 Shizuku APK ------------------------------------------------
TMP="$(mktemp -d "${TMPDIR:-/tmp}/rish.XXXXXX")" || fail "无法创建临时目录"
trap 'rm -rf "$TMP"' EXIT
APK=""
SRC=""

if [ -n "$APK_ARG" ]; then
  [ -r "$APK_ARG" ] || fail "指定的 APK 不可读：$APK_ARG"
  APK="$APK_ARG"; SRC="参数"
fi

if [ -z "$APK" ] && have adb && adb devices 2>/dev/null | awk 'NR>1 && $2=="device"' | grep -q .; then
  p="$(adb shell pm path "$SHIZUKU_PKG" 2>/dev/null | sed 's/^package://' | tr -d '\r' | head -1)"
  if [ -n "$p" ] && adb pull "$p" "$TMP/shizuku.apk" >/dev/null 2>&1; then
    APK="$TMP/shizuku.apk"; SRC="adb"
  fi
fi

if [ -z "$APK" ]; then
  for c in "$HOME"/*hizuku*.apk "$HOME"/storage/downloads/*hizuku*.apk \
           /sdcard/Download/*hizuku*.apk "$HOME"/storage/shared/Download/*hizuku*.apk; do
    [ -f "$c" ] && { APK="$c"; SRC="本地文件 $c"; break; }
  done
fi

[ -n "$APK" ] || fail "找不到 Shizuku APK。
  · 若已装 Shizuku：请开一次无线调试后重跑（脚本会用 adb 提取），
    或把 Shizuku 的 APK 放到 ~/Download 后重跑；
  · 若没装 Shizuku：先安装 https://github.com/RikkaApps/Shizuku/releases 并启动它。"

# ---- 解出 rish + dex ---------------------------------------------------------
unzip -o -q "$APK" 'assets/rish' 'assets/rish_shizuku.dex' -d "$TMP" 2>/dev/null \
  || fail "从 $APK 中取不出 rish 资源（可能不是 Shizuku 的 APK）"

mkdir -p "$DEST" || fail "无法创建 $DEST"
cp -f "$TMP/assets/rish" "$DEST/rish"
cp -f "$TMP/assets/rish_shizuku.dex" "$DEST/rish_shizuku.dex"
sed -i "s/\"PKG\"/\"$TERMUX_PKG\"/" "$DEST/rish"
chmod +x "$DEST/rish"
# Android 14+ 的 app_process 拒绝加载「可写」的 dex —— 这步不能省
chmod 400 "$DEST/rish_shizuku.dex"

# ---- 验证 --------------------------------------------------------------------
if rish_works; then
  log "已从 $SRC 安装 rish；uid=2000 验证通过（免 adb/WiFi）"
  exit 0
fi
fail "已安装 $DEST/rish，但连接 Shizuku 失败。
  常见原因：Shizuku 未启动 / 被系统杀掉（给它和 Termux 关掉电池优化）/ 首次授权未确认。
  启动 Shizuku 后重跑本脚本即可。"
