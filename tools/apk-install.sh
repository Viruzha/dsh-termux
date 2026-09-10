#!/data/data/com.termux/files/usr/bin/bash
# apk-install.sh —— 不依赖 adb 安装 / 卸载 APK（走 Shizuku 的 rsh 通道）
#
#   apk-install.sh app.apk                  安装（等价 pm install -r）
#   apk-install.sh --uninstall <包名>        卸载
#   apk-install.sh --dry-run app.apk         只搬运并校验 md5，不安装
#
# 为什么要绕一圈：pm install 由 system_server 执行，SELinux 不允许它读 /sdcard(FUSE)，
# 报错会提示 "Consider using a file under /data/local/tmp/"。而 Termux(uid 10328)
# 写不进 /data/local/tmp —— 于是用 rish 的命令通道把 base64 搬过去。
#
# 大包（几十 MB）此路较慢，那种场景用 adb install 更合适。

set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
RISH="${HOME}/.shizuku/rish"
RSH="$SELF_DIR/rsh"
REMOTE=/data/local/tmp/dsh-install.apk

die()  { printf '%s\n' "$*" >&2; exit 1; }
info() { printf '%s\n' "$*"; }

MODE=install
DRYRUN=0
ARG=""
for a in "$@"; do case "$a" in
  --uninstall|-U) MODE=uninstall ;;
  --dry-run|-n)   DRYRUN=1 ;;
  -h|--help) sed -n '2,13p' "$0"; exit 0 ;;
  *) ARG="$a" ;;
esac; done

[ -x "$RSH" ] || die "缺少 $RSH"
[ -x "$RISH" ] || die "缺少 $RISH —— 先跑 $SELF_DIR/setup-shizuku-rish.sh"

# ---- 卸载 -------------------------------------------------------------------
if [ "$MODE" = uninstall ]; then
  [ -n "$ARG" ] || die "用法： apk-install.sh --uninstall <包名>"
  info "卸载 $ARG ..."
  "$RSH" "pm uninstall $ARG"
  exit $?
fi

# ---- 安装 -------------------------------------------------------------------
[ -n "$ARG" ] || die "用法： apk-install.sh <apk 路径>"
APK="$ARG"
[ -r "$APK" ] || die "APK 不可读：$APK"

LOCAL_MD5="$(md5sum "$APK" | cut -d' ' -f1)"
SIZE="$(stat -c%s "$APK")"
info "APK: $APK（$SIZE 字节，md5 $LOCAL_MD5）"

info "经 rish 命令通道搬运到 $REMOTE ..."
{
  printf 'base64 -d > %s <<"ZZEOF"\n' "$REMOTE"
  base64 -w0 "$APK"
  printf '\nZZEOF\n'
  printf 'md5sum %s\n' "$REMOTE"
} | timeout 600 "$RISH" >/dev/null 2>&1 || true

REMOTE_MD5="$(timeout 60 "$RSH" "md5sum $REMOTE" 2>/dev/null | awk '{print $1}' | tail -1)"
[ "$REMOTE_MD5" = "$LOCAL_MD5" ] || {
  timeout 60 "$RSH" "rm -f $REMOTE" >/dev/null 2>&1
  die "搬运校验失败：本地 $LOCAL_MD5 ≠ 远端 ${REMOTE_MD5:-空}"
}
info "搬运校验通过"

if [ "$DRYRUN" = 1 ]; then
  timeout 60 "$RSH" "rm -f $REMOTE" >/dev/null 2>&1
  info "--dry-run：不安装，已清理"
  exit 0
fi

info "安装中 ..."
OUT="$(timeout 600 "$RSH" "pm install -r $REMOTE 2>&1")"
RC=$?
printf '%s\n' "$OUT"
timeout 60 "$RSH" "rm -f $REMOTE" >/dev/null 2>&1

case "$OUT" in
  *Success*) info "完成"; exit 0 ;;
  *)         die "安装未成功（退出码 $RC）。若提示无法读取文件，确认 APK 已放进 /data/local/tmp。" ;;
esac
