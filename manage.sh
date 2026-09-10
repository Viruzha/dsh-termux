#!/data/data/com.termux/files/usr/bin/bash
# ===========================================================================
#  DSH Web 管理脚本 for Termux
#
#  子命令：
#    start | stop | restart | status | logs     进程管理（原样保留）
#    check | fix                                环境自检 / 自动修复
#    patch | check-patch                        session EACCES 补丁
#    patch-attach | check-attach                附件库 Android 补丁
#    patch-all                                  三个补丁一次打完
#    setup                                      一键引导/修复（委托 bootstrap）
#    bundle [--with-secrets] [--with-sessions]  打包迁移捆绑包
#
#  与旧版的差异：
#    1. 版本兜底不再降级到 0.1.0-rc.6，改用 MANIFEST 里的版本
#    2. 原生依赖优先从 ~/dsh-termux/assets 恢复（2MB），旧备份目录仅作兜底
#    3. 新增 ripgrep 垫片与附件补丁的自检/修复，restart 前自愈更完整
#    4. 补丁逻辑委托给 ~/dsh-termux/patches/，避免两处重复维护
# ===========================================================================
set -uo pipefail

DSH_ROOT="/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh"
DSH_BIN="$DSH_ROOT/lib/bin.js"
NM="$DSH_ROOT/node_modules"
GLOBAL_NM="/data/data/com.termux/files/usr/lib/node_modules"
PID_FILE="$HOME/.dsh-web.pid"
LOG_FILE="$HOME/.dsh-web.log"
PORT=3080

# 迁移包位置（bootstrap / 补丁 / 资产都在这里）
PAYLOAD="${DSH_TERMUX_DIR:-$HOME/dsh-termux}"
# 版本锚点：优先读 MANIFEST
DSH_VERSION="0.1.5-rc.1"
[ -f "$PAYLOAD/MANIFEST" ] && . "$PAYLOAD/MANIFEST"
# 旧版留下的 339MB 备份目录，仅作最后兜底
BACKUP_PKG="$HOME/dsh-install/package/node_modules"

ATTACH_JS="$NM/@deepseek-ai/dsh-attachment-local/lib/index.js"

env_missing() { # 只做检测，输出缺失项名（每行一个）
  [ -f "$DSH_BIN" ] || echo "dsh-bin"
  [ -f "$NM/node-pty/prebuilds/android-arm64/pty.node" ] || echo "pty"
  [ -f "$NM/koffi/build/koffi/android_arm64/koffi.node" ] || echo "koffi"
  [ -f "$GLOBAL_NM/@img/sharp-wasm32/package.json" ] || echo "sharp"
  [ -e "$NM/@vscode/ripgrep-android-arm64/bin/rg" ] || echo "ripgrep-shim"
}

check_env() {
  local m; m="$(env_missing)"
  if [ -z "$m" ]; then echo "✓ 环境自检通过"; return 0; fi
  echo "✗ 缺失项：$(echo "$m" | tr '\n' ' ')"; return 1
}

fix_env() {
  local need; need="$(env_missing)"
  [ -z "$need" ] && { echo "✓ 环境自检通过"; return 0; }

  if echo "$need" | grep -q '^dsh-bin$'; then
    echo "→ 重装 dsh @$DSH_VERSION（不再降级）"
    npm i -g --ignore-scripts "@deepseek-ai/dsh@$DSH_VERSION" || { echo "✗ 重装失败"; return 1; }
    grep -q "alias dsh=" "$HOME/.bashrc" 2>/dev/null || \
      echo "alias dsh='node --expose-internals $DSH_BIN'" >> "$HOME/.bashrc"
  fi

  # 原生资产：payload 优先，旧备份兜底
  local src_pty="$PAYLOAD/assets/pty.node"        dst_pty="$NM/node-pty/prebuilds/android-arm64/pty.node"
  local src_kof="$PAYLOAD/assets/koffi.node"      dst_kof="$NM/koffi/build/koffi/android_arm64/koffi.node"
  if echo "$need" | grep -q '^pty$'; then
    [ -f "$src_pty" ] || src_pty="$BACKUP_PKG/node-pty/prebuilds/android-arm64/pty.node"
    if [ -f "$src_pty" ]; then mkdir -p "$(dirname "$dst_pty")"; cp -f "$src_pty" "$dst_pty"; chmod 755 "$dst_pty"; echo "→ 已恢复 pty.node"
    else echo "✗ 找不到 pty.node 来源（试过 payload 与 $BACKUP_PKG）"; return 1; fi
  fi
  if echo "$need" | grep -q '^koffi$'; then
    [ -f "$src_kof" ] || src_kof="$BACKUP_PKG/koffi/build/koffi/android_arm64/koffi.node"
    if [ -f "$src_kof" ]; then mkdir -p "$(dirname "$dst_kof")"; cp -f "$src_kof" "$dst_kof"; chmod 755 "$dst_kof"; echo "→ 已恢复 koffi.node"
    else echo "✗ 找不到 koffi.node 来源"; return 1; fi
  fi
  if echo "$need" | grep -q '^sharp$'; then
    echo "→ 安装 sharp WASM"
    npm install -g --ignore-scripts "@img/sharp-wasm32@${SHARP_WASM_VERSION:-0.35.4}" --force || { echo "✗ sharp 安装失败"; return 1; }
  fi
  if echo "$need" | grep -q '^ripgrep-shim$'; then
    if [ -x "$PAYLOAD/patches/ripgrep-android.sh" ]; then
      "$PAYLOAD/patches/ripgrep-android.sh" >/dev/null && echo "→ 已重建 ripgrep 垫片"
    else
      echo "✗ 缺少 $PAYLOAD/patches/ripgrep-android.sh"; return 1
    fi
  fi
  echo; check_env
}

# ---------- 补丁 ----------
check_patch() {
  if grep -q 'error.code !== "EACCES"' "$(find "$DSH_ROOT" -path '*session-persistence-jsonl*' -name index.js 2>/dev/null | head -1)" 2>/dev/null; then
    echo "✓ session EACCES 补丁已打"; return 0
  fi
  echo "✗ session EACCES 补丁未打"; return 1
}
apply_patch() {
  if [ -x "$PAYLOAD/patches/session-eacces.sh" ]; then "$PAYLOAD/patches/session-eacces.sh"
  else echo "✗ 缺少 $PAYLOAD/patches/session-eacces.sh"; return 1; fi
}

check_attach() {
  if [ -f "$ATTACH_JS" ] && grep -q 'hard links are denied' "$ATTACH_JS" && grep -q 'ancestor directory is not openable' "$ATTACH_JS"; then
    echo "✓ 附件库 Android 补丁已打"; return 0
  fi
  echo "✗ 附件库 Android 补丁未打"; return 1
}
apply_attach() {
  if [ -x "$PAYLOAD/patches/attachment-android.sh" ]; then "$PAYLOAD/patches/attachment-android.sh"
  else echo "✗ 缺少 $PAYLOAD/patches/attachment-android.sh"; return 1; fi
}

# ---------- 进程管理（与原版一致） ----------
is_running() { [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; }

start() {
  if is_running; then
    echo "DSH Web 已在运行 (PID $(cat "$PID_FILE"))"; echo "地址：http://127.0.0.1:$PORT"; return 0
  fi
  check_env || { echo; echo "尝试自动修复..."; fix_env || { echo "✗ 自动修复未成功"; return 1; }; }
  check_patch >/dev/null || { echo; echo "尝试应用 EACCES 补丁..."; apply_patch || echo "✗ 补丁失败，继续尝试启动"; }
  check_attach >/dev/null || { echo; echo "尝试应用附件库补丁..."; apply_attach || echo "✗ 补丁失败，继续尝试启动"; }

  echo; echo "正在启动 DSH Web..."
  nohup node --expose-internals "$DSH_BIN" web --no-open > "$LOG_FILE" 2>&1 &
  echo $! > "$PID_FILE"
  for i in $(seq 1 20); do
    sleep 1
    if grep -q "http://127.0.0.1:$PORT" "$LOG_FILE" 2>/dev/null; then
      echo "✓ 启动成功 (PID $(cat "$PID_FILE"))"; echo "  地址：http://127.0.0.1:$PORT"; echo "  日志：dsh-web logs"; return 0
    fi
    if ! is_running; then echo "✗ 启动失败，日志末尾："; tail -n 30 "$LOG_FILE"; rm -f "$PID_FILE"; return 1; fi
  done
  echo "✗ 启动超时，请查看日志：$LOG_FILE"; return 1
}

stop() {
  if ! is_running; then echo "DSH Web 未在运行"; rm -f "$PID_FILE"; return 0; fi
  PID=$(cat "$PID_FILE"); echo "正在停止 DSH Web (PID $PID)..."
  kill "$PID" 2>/dev/null
  for i in $(seq 1 10); do sleep 1; kill -0 "$PID" 2>/dev/null || { echo "✓ 已停止"; rm -f "$PID_FILE"; return 0; }; done
  echo "进程未响应，强制结束"; kill -9 "$PID" 2>/dev/null; rm -f "$PID_FILE"
}

restart() { stop; sleep 1; start; }

status() {
  if is_running; then
    echo "状态：运行中 (PID $(cat "$PID_FILE"))"; echo "地址：http://127.0.0.1:$PORT"; echo "日志：$LOG_FILE"
  else echo "状态：未运行"; rm -f "$PID_FILE"; fi
}

logs() { [ -f "$LOG_FILE" ] || { echo "暂无日志"; return 1; }; tail -f "$LOG_FILE"; }

# ---------- 迁移与引导 ----------
setup() {
  [ -x "$PAYLOAD/bootstrap.sh" ] || { echo "✗ 找不到 $PAYLOAD/bootstrap.sh"; return 1; }
  "$PAYLOAD/bootstrap.sh" install
}
bundle() {
  [ -x "$PAYLOAD/bootstrap.sh" ] || { echo "✗ 找不到 $PAYLOAD/bootstrap.sh"; return 1; }
  "$PAYLOAD/bootstrap.sh" bundle "$@"
}

case "${1:-}" in
  start)        start ;;
  stop)         stop ;;
  restart)      restart ;;
  status)       status ;;
  logs)         logs ;;
  check)        check_env ;;
  fix)          fix_env ;;
  patch)        apply_patch ;;
  check-patch)  check_patch ;;
  patch-attach) apply_attach ;;
  check-attach) check_attach ;;
  patch-all)    apply_patch; apply_attach; "$PAYLOAD/patches/ripgrep-android.sh" ;;
  setup)        setup ;;
  bundle)       shift; bundle "$@" ;;
  *)
    echo "用法：$0 {start|stop|restart|status|logs|check|fix|patch|check-patch|patch-attach|check-attach|patch-all|setup|bundle}"; exit 1 ;;
esac
