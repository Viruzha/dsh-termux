#!/data/data/com.termux/files/usr/bin/bash
# ===========================================================================
# open-app.sh —— 在手机上打开 App
#
#   ./open-app.sh 微信                 中文别名
#   ./open-app.sh wechat               英文别名
#   ./open-app.sh com.tencent.mm       包名（或包名片段，唯一匹配即可）
#   ./open-app.sh --list               列出可启动的第三方应用
#   ./open-app.sh --search 腾讯        按包名模糊搜索
#   ./open-app.sh --current            看当前前台是哪个应用
#   ./open-app.sh --url <网址>         用浏览器打开网址（优先 Chrome）
#
# 需要 shell(uid 2000) 身份：优先走 rish（Shizuku，免 adb/WiFi），失败自动回退无线 adb。
# ===========================================================================
set -uo pipefail

ADB_TARGET=""
USE_RSH=0
SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
RSH_BIN="$SELF_DIR/rsh"

connect_hint() {
  echo "✗ 两条通道都不可用（打开 App 需要 shell 身份）" >&2
  echo "  首选： $SELF_DIR/setup-shizuku-rish.sh      走 Shizuku，不需要 adb/WiFi" >&2
  echo "  兜底： $SELF_DIR/adb-connect-self.sh        无线调试，需要 WiFi" >&2
  echo "  配对失效： $SELF_DIR/adb-connect-self.sh --pair <配对端口> <配对码>" >&2
  exit 1
}
pick_device() {
  # 优先 rish：不依赖 adb / 无线调试 / WiFi
  if [ -x "$HOME/.shizuku/rish" ] && [ -x "$RSH_BIN" ]; then
    if timeout 25 "$RSH_BIN" 'id' 2>/dev/null | grep -q 'uid=2000'; then USE_RSH=1; return 0; fi
    echo "! rish 通道不可用，回退到无线 adb（可跑 setup-shizuku-rish.sh 修复）" >&2
  fi
  ADB_TARGET="$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')"
  [ -n "$ADB_TARGET" ] || connect_hint
}
sh_() {
  if [ "$USE_RSH" = 1 ]; then "$RSH_BIN" "$*" 2>/dev/null
  else adb -s "$ADB_TARGET" shell "$@" 2>/dev/null; fi
}

# --- 中文/英文别名表（按本机已装应用整理，可自行增删）---
ALIASES='
微信|wechat|weixin|com.tencent.mm
QQ|qq|com.tencent.mobileqq
支付宝|alipay|com.eg.android.AlipayGphone
淘宝|taobao|com.taobao.taobao
京东|jd|com.jingdong.app.mall
拼多多|pdd|com.xunmeng.pinduoduo
抖音|douyin|tiktok|com.ss.android.ugc.aweme
B站|bilibili|哔哩哔哩|tv.danmaku.bili
微博|weibo|com.sina.weibo
网易云音乐|netease|com.netease.cloudmusic
高德地图|高德|amap|com.autonavi.minimap
QQ邮箱|邮箱|com.tencent.androidqqmail
Chrome|chrome|浏览器|com.android.chrome
设置|settings|com.android.settings
相机|camera|com.android.camera
相册|gallery|图库|com.miui.gallery
通讯录|联系人|contacts|com.android.contacts
短信|信息|mms|com.android.mms
时钟|闹钟|clock|com.android.deskclock
便签|notes|com.miui.notes
天气|weather|com.miui.weather2
应用商店|商店|market|com.xiaomi.market
安全中心|security|com.miui.securitycenter
桌面|launcher|home|com.miui.home
Termux|termux|com.termux
'

usage() { sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; }

launch() { # $1 = 包名
  local pkg="$1" act
  act="$(sh_ cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$pkg" | tail -1)"
  if [ -z "$act" ] || ! printf '%s' "$act" | grep -q '/'; then
    echo "✗ 找不到 $pkg 的启动 Activity（可能没有桌面图标）" >&2; return 1
  fi
  echo "→ 启动 $pkg"
  sh_ am start -n "$act" | grep -E 'Starting|Error|Warning' | head -2
  local focus="" i
  for i in 1 2 3 4 5; do
    sleep 1
    focus="$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed -E 's/.*[[:space:]]u0[[:space:]]+//; s/[}[:space:]].*//')"
    case "$focus" in "$pkg"*) break ;; esac
  done
  echo "  当前前台：$focus"
  case "$focus" in
    "$pkg"*) echo "  ✓ 已在前台" ;;
    *)       echo "  ! 前台是 $focus（App 可能仍在加载、停在权限弹窗，或被系统拦下）" ;;
  esac
}

case "${1:-}" in
  ""|-h|--help|help) usage; exit 0 ;;
  --list)
    pick_device
    echo "== 可启动的第三方应用 =="
    sh_ pm list packages -3 | sed 's/^package://' | sort | while read -r p; do
      sh_ cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$p" >/dev/null 2>&1 && echo "  $p"
    done
    exit 0 ;;
  --search)
    pick_device
    kw="${2:?用法: open-app.sh --search <关键词>}"
    echo "== 含 '$kw' 的包 =="
    sh_ pm list packages | sed 's/^package://' | grep -i -- "$kw" | sed 's/^/  /'
    exit 0 ;;
  --url)
    pick_device
    url="${2:?用法: open-app.sh --url <网址>}"
    if sh_ pm list packages | grep -qx "package:com.android.chrome"; then
      sh_ am start -n com.android.chrome/com.google.android.apps.chrome.Main \
             -a android.intent.action.VIEW -d "$url" | grep -E 'Starting|Error' | head -2
    else
      sh_ am start -a android.intent.action.VIEW -d "$url" | grep -E 'Starting|Error' | head -2
    fi
    sleep 2
    cur="$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed -E 's/.*[[:space:]]u0[[:space:]]+//; s/[}[:space:]].*//')"
    echo "  当前前台: ${cur:-（未识别）}"
    exit 0
    ;;

  --current)
    pick_device
    cur="$(sh_ dumpsys window | grep -m1 mCurrentFocus | sed -E 's/.*[[:space:]]u0[[:space:]]+//; s/[}[:space:]].*//')"
    echo "当前前台: ${cur:-（未识别，可能无焦点窗口）}" 
    exit 0 ;;
esac

pick_device
Q="$1"

# 1) 别名精确匹配（每行形如 别名1|别名2|包名，最后一段是包名）
PKG=""
QL="$(printf '%s' "$Q" | tr 'A-Z' 'a-z')"
while IFS= read -r line; do
  [ -n "${line// }" ] || continue
  IFS='|' read -ra words <<< "$line"
  local_n=${#words[@]}
  [ "$local_n" -ge 2 ] || continue
  cand_pkg="${words[$((local_n-1))]}"
  for ((i=0; i<local_n-1; i++)); do
    wl="$(printf '%s' "${words[$i]}" | tr 'A-Z' 'a-z')"
    if [ "$QL" = "$wl" ]; then PKG="$cand_pkg"; break 2; fi
  done
done <<< "$ALIASES"

# 2) 否则按包名匹配（先精确，再唯一子串）
if [ -z "$PKG" ]; then
  if sh_ pm list packages | grep -qx "package:$Q"; then PKG="$Q"
  else
    CAND="$(sh_ pm list packages | sed 's/^package://' | grep -i -- "$Q" | head -20)"
    N=$(printf '%s\n' "$CAND" | grep -c . || true)
    if [ "$N" = "1" ]; then PKG="$CAND"
    elif [ "$N" = "0" ]; then echo "✗ 找不到匹配 '$Q' 的应用。用 --search <关键词> 或 --list 查看" >&2; exit 1
    else echo "✗ '$Q' 匹配到多个，请指定其中之一：" >&2; printf '  %s\n' $CAND >&2; exit 1
    fi
  fi
fi

launch "$PKG"
