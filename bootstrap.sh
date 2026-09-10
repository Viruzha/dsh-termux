#!/data/data/com.termux/files/usr/bin/bash
# ===========================================================================
#  DSH on Termux —— 一键引导 / 修复 / 迁移
#
#  用法：
#    ./bootstrap.sh              安装或修复本机环境（幂等，可反复跑）
#    ./bootstrap.sh check        只体检，不改动任何东西
#    ./bootstrap.sh bundle       打包成可迁移捆绑包（换手机用）
#        --with-secrets          连 API 凭据一起打包（含密钥，注意传输安全）
#        --with-sessions         连会话记录与附件一起打包
#    ./bootstrap.sh --start      装完顺手启动 Web 服务
#
#  设计原则：每个步骤先检测、缺什么补什么；任何一步失败都只记录，不中断
#  其余步骤，最后统一汇报。Android 专属的原生产物（npm 拿不到）随包携带。
# ===========================================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SELF_DIR"

# ---- 版本锚点（MANIFEST 可覆盖）----
DSH_VERSION=0.1.5-rc.1
SHARP_VERSION=0.35.4
SHARP_WASM_VERSION=0.35.4
PNPM_MAJOR=10
[ -f MANIFEST ] && . ./MANIFEST

# ---- 路径 ----
DSH_ROOT="${DSH_ROOT:-/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh}"
GLOBAL_NM="${GLOBAL_NM:-/data/data/com.termux/files/usr/lib/node_modules}"
DSH_HOME="${DSH_HOME:-$HOME/.dsh}"
NM="$DSH_ROOT/node_modules"
PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"

# ---- 输出 ----
if [ -t 1 ]; then B=$'\033[1m'; G=$'\033[32m'; Y=$'\033[33m'; R=$'\033[31m'; D=$'\033[2m'; N=$'\033[0m'
else B=; G=; Y=; R=; D=; N=; fi
FAILED=0; FIXED=0
step() { printf '\n%s== %s ==%s\n' "$B" "$*" "$N"; }
ok()   { printf '  %s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '  %s!%s %s\n' "$Y" "$N" "$*"; }
bad()  { printf '  %s✗%s %s\n' "$R" "$N" "$*"; FAILED=$((FAILED+1)); }
did()  { printf '  %s→%s %s\n' "$G" "$N" "$*"; FIXED=$((FIXED+1)); }
dim()  { printf '    %s%s%s\n' "$D" "$*" "$N"; }
have() { command -v "$1" >/dev/null 2>&1; }

# ===========================================================================
# 0. 环境前置检查
# ===========================================================================
cmd_install() {
step "0/10 环境前置检查"
case "$PREFIX" in
  *com.termux*) ok "运行在 Termux ($PREFIX)" ;;
  *) bad "看起来不是 Termux 环境（PREFIX=$PREFIX）" ;;
esac
ARCH="$(getprop ro.product.cpu.abi 2>/dev/null || uname -m)"
case "$ARCH" in
  *arm64*|aarch64) ok "架构 $ARCH（本包含 arm64 原生产物）" ;;
  *) warn "架构 $ARCH 与本包携带的 arm64 产物不匹配：pty/koffi 需要另行获取" ;;
esac
TV="${TERMUX_VERSION:-未知}"
case "$TV" in
  googleplay*) warn "Termux 是 Google Play 版（$TV）：addon 受限、Termux:API 仅部分可用"
               dim "建议改用 F-Droid / GitHub 版 Termux 以获得完整能力" ;;
  *) ok "Termux 渠道 $TV" ;;
esac
AVAIL_KB=$(df -Pk "$HOME" 2>/dev/null | awk 'NR==2{print $4}')
if [ -n "${AVAIL_KB:-}" ] && [ "$AVAIL_KB" -lt 512000 ]; then
  warn "可用空间仅 $((AVAIL_KB/1024)) MB，建议留出 500 MB 以上"
else
  ok "可用空间 $(( ${AVAIL_KB:-0} / 1024 )) MB"
fi

# ===========================================================================
# 1. Termux 软件包
# ===========================================================================
step "1/10 Termux 软件包"
# "pkg名:检测用的命令"
PKGS=(
  "nodejs:node" "python:python3" "ripgrep:rg" "android-tools:adb"
  "termux-api:termux-battery-status" "tar:tar" "zstd:zstd" "curl:curl" "git:git"
)
MISSING_PKGS=()
for entry in "${PKGS[@]}"; do
  pkgname="${entry%%:*}"; binname="${entry##*:}"
  if have "$binname"; then ok "$pkgname ($binname)"; else MISSING_PKGS+=("$pkgname"); warn "$pkgname 缺失"; fi
done
if [ "${#MISSING_PKGS[@]}" -gt 0 ]; then
  did "安装: ${MISSING_PKGS[*]}"
  if DEBIAN_FRONTEND=noninteractive pkg install -y "${MISSING_PKGS[@]}" >/dev/null 2>&1; then
    for entry in "${PKGS[@]}"; do
      binname="${entry##*:}"; have "$binname" || bad "$binname 仍未就绪"
    done
    ok "软件包安装完成"
  else
    bad "pkg install 失败，请检查网络或手动执行: pkg install -y ${MISSING_PKGS[*]}"
  fi
fi

# ===========================================================================
# 2. pnpm（必须是纯 JS 的 10.x；11+ 依赖无 android 构建的原生二进制）
# ===========================================================================
step "2/10 pnpm"
PNPM_OK=0
if have pnpm; then
  PV="$(pnpm --version 2>/dev/null || echo 0)"
  PMAJ="${PV%%.*}"
  if [ "${PMAJ:-0}" -ge 11 ] 2>/dev/null; then
    warn "pnpm $PV 需要原生二进制（android 无预编译），降级到 ${PNPM_MAJOR}.x"
  else
    ok "pnpm $PV"; PNPM_OK=1
  fi
else
  warn "pnpm 缺失"
fi
if [ "$PNPM_OK" -eq 0 ]; then
  did "npm install -g pnpm@${PNPM_MAJOR}"
  if npm install -g "pnpm@${PNPM_MAJOR}" >/dev/null 2>&1 && pnpm --version >/dev/null 2>&1; then
    ok "pnpm $(pnpm --version 2>/dev/null) 就绪"
  else
    bad "pnpm 安装失败（其安装脚本可能被 npm 拦截，属预期；命令本身应可用）"
  fi
fi

# ===========================================================================
# 3. dsh 本体
# ===========================================================================
step "3/10 dsh 本体"
installed_dsh() { [ -f "$DSH_ROOT/package.json" ] && node -e "process.stdout.write(require('$DSH_ROOT/package.json').version)" 2>/dev/null; }
CUR_DSH="$(installed_dsh || true)"
if [ "$CUR_DSH" = "$DSH_VERSION" ]; then
  ok "dsh $CUR_DSH"
else
  [ -n "$CUR_DSH" ] && warn "dsh 当前 $CUR_DSH，期望 $DSH_VERSION" || warn "dsh 未安装"
  did "npm i -g --ignore-scripts @deepseek-ai/dsh@${DSH_VERSION}"
  if npm install -g --ignore-scripts "@deepseek-ai/dsh@${DSH_VERSION}" >/dev/null 2>&1; then
    CUR_DSH="$(installed_dsh || true)"
    [ "$CUR_DSH" = "$DSH_VERSION" ] && ok "dsh $CUR_DSH 安装完成" || bad "dsh 安装后版本仍为 ${CUR_DSH:-未知}"
  else
    bad "dsh 安装失败（需网络）"
  fi
fi

# ===========================================================================
# 4. sharp（图像规范化；Android 上必须走 wasm32 变体）
# ===========================================================================
step "4/10 sharp / sharp-wasm32"
need_sharp=0
[ -f "$GLOBAL_NM/sharp/package.json" ] && ok "sharp $(node -e "process.stdout.write(require('$GLOBAL_NM/sharp/package.json').version)" 2>/dev/null)" || { warn "sharp 缺失"; need_sharp=1; }
[ -f "$GLOBAL_NM/@img/sharp-wasm32/package.json" ] && ok "@img/sharp-wasm32 就位（Android 靠它加载）" || { warn "@img/sharp-wasm32 缺失"; need_sharp=1; }
if [ "$need_sharp" -eq 1 ]; then
  did "npm i -g --ignore-scripts sharp@${SHARP_VERSION} @img/sharp-wasm32@${SHARP_WASM_VERSION}"
  if npm install -g --ignore-scripts "sharp@${SHARP_VERSION}" "@img/sharp-wasm32@${SHARP_WASM_VERSION}" >/dev/null 2>&1; then
    ok "sharp 安装完成"
  else
    bad "sharp 安装失败（需网络）"
  fi
fi

# ===========================================================================
# 5. Android 原生产物（npm 给不了，随包携带）
# ===========================================================================
step "5/10 Android 原生产物"
install_asset() { # $1=源文件 $2=目标 $3=期望sha $4=名字
  local src="$1" dst="$2" sha="$3" name="$4"
  if [ -f "$dst" ]; then
    local cur; cur="$(sha256sum "$dst" | cut -d' ' -f1)"
    if [ -n "$sha" ] && [ "$cur" = "$sha" ]; then ok "$name 校验一致"; return 0; fi
    warn "$name 存在但校验不符，覆盖"
  fi
  mkdir -p "$(dirname "$dst")"
  if cp -f "$src" "$dst"; then chmod 755 "$dst"; did "$name 已部署 → ${dst#$DSH_ROOT/}"; else bad "$name 部署失败"; fi
}
if [ -d "$DSH_ROOT" ]; then
  install_asset "$SELF_DIR/assets/pty.node"   "$NM/node-pty/prebuilds/android-arm64/pty.node" "${ASSET_PTY_SHA256:-}"   "node-pty pty.node"
  install_asset "$SELF_DIR/assets/koffi.node" "$NM/koffi/build/koffi/android_arm64/koffi.node" "${ASSET_KOFFI_SHA256:-}" "koffi.node"
  # 真加载测试，而不只是看文件在不在
  ( cd "$DSH_ROOT" && node --input-type=module -e "import('node-pty')" ) >/dev/null 2>&1 \
    && ok "node-pty 可加载" || bad "node-pty 加载失败"
  ( cd "$DSH_ROOT" && node -e "require('koffi')" ) >/dev/null 2>&1 \
    && ok "koffi 可加载" || bad "koffi 加载失败"
else
  bad "找不到 dsh 安装目录：$DSH_ROOT"
fi

# ===========================================================================
# 6. ripgrep 垫片（让 DSH 的 glob/grep 能找到 Android 原生 rg）
# ===========================================================================
step "6/10 ripgrep 垫片"
if [ -x "$SELF_DIR/patches/ripgrep-android.sh" ]; then
  # 注意：Termux 下 /tmp 不可写，日志必须落在 $TMPDIR（= $PREFIX/tmp）
  RLOG="${TMPDIR:-$PREFIX/tmp}/ripgrep-patch.log"
  if "$SELF_DIR/patches/ripgrep-android.sh" >"$RLOG" 2>&1; then
    ok "垫片就位（$(grep -c . "$RLOG" 2>/dev/null || echo 0) 行输出）"
  else
    bad "垫片脚本失败：$(tail -1 "$RLOG" 2>/dev/null)"
    dim "完整日志：$RLOG"
  fi
else
  bad "缺少 patches/ripgrep-android.sh"
fi

# ===========================================================================
# 7. Android 补丁（硬链接被禁 / 祖先目录不可读）
# ===========================================================================
step "7/10 Android 补丁"
for p in session-eacces attachment-android; do
  script="$SELF_DIR/patches/$p.sh"
  if [ ! -x "$script" ]; then bad "缺少 patches/$p.sh"; continue; fi
  out="$("$script" 2>&1)" && { ok "$p: $(printf '%s' "$out" | tail -1)"; } \
                          || { bad "$p: $(printf '%s' "$out" | tail -1)"; }
done

# ===========================================================================
# 8. 配置恢复（只在目标缺失时写入，除非 --force-config）
# ===========================================================================
step "8/10 配置"
FORCE_CONFIG="${FORCE_CONFIG:-0}"
restore_file() { # $1=payload 内相对路径 $2=目标绝对路径
  local src="$SELF_DIR/$1" dst="$2"
  [ -f "$src" ] || return 0
  if [ -f "$dst" ] && [ "$FORCE_CONFIG" != "1" ]; then ok "$(basename "$dst") 已存在，跳过"; return 0; fi
  mkdir -p "$(dirname "$dst")"
  cp -f "$src" "$dst" && did "恢复 $dst" || bad "恢复 $dst 失败"
}
restore_file config/settings.yaml                    "$DSH_HOME/settings.yaml"
restore_file config/profiles/web/cordis.yml          "$DSH_HOME/profiles/web/cordis.yml"
restore_file config/profiles/web/cordis.patch.yml    "$DSH_HOME/profiles/web/cordis.patch.yml"
restore_file config/profiles/web/package.json        "$DSH_HOME/profiles/web/package.json"
restore_file config/profiles/web/pnpm-workspace.yaml "$DSH_HOME/profiles/web/pnpm-workspace.yaml"
restore_file config/llm-deepseek/files-v3.json       "$DSH_HOME/llm-deepseek/files-v3.json"
restore_file config/credentials.yaml                 "$DSH_HOME/.credentials.yaml"

# 全局技能：装到 $DSH_HOME/skills（DSH 的 skill-filesystem 会自动发现，改完即时生效）
if [ -d "$SELF_DIR/skills" ]; then
  mkdir -p "$DSH_HOME/skills"
  for d in "$SELF_DIR"/skills/*/; do
    [ -d "$d" ] || continue
    sname="$(basename "$d")"
    if [ -f "$DSH_HOME/skills/$sname/SKILL.md" ] && cmp -s "$d/SKILL.md" "$DSH_HOME/skills/$sname/SKILL.md"; then
      ok "技能 $sname 已是最新"
    else
      mkdir -p "$DSH_HOME/skills/$sname"
      cp -f "$d/SKILL.md" "$DSH_HOME/skills/$sname/SKILL.md" && did "安装/更新技能 $sname"
    fi
  done
fi
if [ -d "$SELF_DIR/data/sessions" ] || [ -d "$SELF_DIR/data/attachments" ]; then
  warn "捆绑包含会话/附件数据（不会自动覆盖）"
  dim "需要时手动： cp -a $SELF_DIR/data/sessions $DSH_HOME/ ; cp -a $SELF_DIR/data/attachments $DSH_HOME/"
fi
[ -f "$SELF_DIR/config/credentials.yaml" ] || dim "凭据未随包携带：首次使用需在 GUI 里重新填 API Key"

# ===========================================================================
# 9. 管理脚本
# ===========================================================================
step "9/10 管理脚本"
if [ -f "$SELF_DIR/manage.sh" ]; then
  if [ -f "$HOME/dsh-web.sh" ] && [ "${FORCE_MANAGE:-0}" != "1" ]; then
    if cmp -s "$SELF_DIR/manage.sh" "$HOME/dsh-web.sh"; then ok "~/dsh-web.sh 已是最新"
    else warn "~/dsh-web.sh 已存在且与包内不同（保留现有；加 FORCE_MANAGE=1 覆盖）"; fi
  else
    [ -f "$HOME/dsh-web.sh" ] && cp -f "$HOME/dsh-web.sh" "$HOME/dsh-web.sh.bak-$(date +%s)"
    cp -f "$SELF_DIR/manage.sh" "$HOME/dsh-web.sh" && chmod +x "$HOME/dsh-web.sh" && did "安装 ~/dsh-web.sh"
  fi
else
  warn "包内没有 manage.sh，跳过"
fi

# ===========================================================================
# 10. 总检
# ===========================================================================
step "10/10 总检"
check_only || true   # 直接调用，避免递归

printf '\n%s===== 结果 =====%s\n' "$B" "$N"
if [ "$FAILED" -eq 0 ]; then
  printf '  %s全部通过%s（本次修复 %s 项）\n' "$G" "$N" "$FIXED"
  dim "启动服务： ~/dsh-web.sh start     查看状态： ~/dsh-web.sh status"
else
  printf '  %s%d 项待处理%s（本次修复 %s 项）\n' "$R" "$FAILED" "$N" "$FIXED"
fi
[ "${START_AFTER:-0}" = "1" ] && { step "启动服务"; "$HOME/dsh-web.sh" restart 2>/dev/null || "$HOME/dsh-web.sh" start; }
}   # end cmd_install

# ===========================================================================
# check：只体检
# ===========================================================================
check_only() {
  local f=0
  chk() { # $1=描述 $2=命令
    if eval "$2" >/dev/null 2>&1; then printf '  %s✓%s %-34s\n' "$G" "$N" "$1"
    else printf '  %s✗%s %-34s\n' "$R" "$N" "$1"; f=$((f+1)); fi
  }
  printf '%s--- 运行时 ---%s\n' "$B" "$N"
  chk "node $(node -v 2>/dev/null)" "node -v"
  chk "npm $(npm -v 2>/dev/null)" "npm -v"
  chk "pnpm $(pnpm --version 2>/dev/null)" "pnpm --version"
  local cur_dsh; cur_dsh="$(node -e "process.stdout.write(require('$DSH_ROOT/package.json').version)" 2>/dev/null)"
  chk "dsh $DSH_VERSION（当前 ${cur_dsh:-无}）" "[ '$cur_dsh' = '$DSH_VERSION' ]"
  printf '%s--- 原生依赖 ---%s\n' "$B" "$N"
  chk "sharp / sharp-wasm32" "[ -f '$GLOBAL_NM/sharp/package.json' ] && [ -f '$GLOBAL_NM/@img/sharp-wasm32/package.json' ]"
  chk "node-pty pty.node" "[ -f '$NM/node-pty/prebuilds/android-arm64/pty.node' ]"
  chk "koffi.node" "[ -f '$NM/koffi/build/koffi/android_arm64/koffi.node' ]"
  chk "node-pty 可加载" "cd '$DSH_ROOT' && node --input-type=module -e \"import('node-pty')\""
  chk "koffi 可加载" "cd '$DSH_ROOT' && node -e \"require('koffi')\""
  chk "ripgrep 垫片" "[ -e '$NM/@vscode/ripgrep-android-arm64/bin/rg' ]"
  chk "ripgrep 可执行" "rg --version"
  printf '%s--- 补丁 ---%s\n' "$B" "$N"
  chk "session EACCES 补丁" "grep -q 'error.code !== \"EACCES\"' \$(find '$DSH_ROOT' -path '*session-persistence-jsonl*' -name index.js | head -1)"
  chk "附件硬链接补丁" "grep -q 'hard links are denied' '$NM/@deepseek-ai/dsh-attachment-local/lib/index.js'"
  chk "附件 fsync 补丁" "grep -q 'ancestor directory is not openable' '$NM/@deepseek-ai/dsh-attachment-local/lib/index.js'"
  printf '%s--- 可选能力 ---%s\n' "$B" "$N"
  chk "adb（无线调试自连）" "adb devices | grep -q device"
  chk "termux-api CLI" "command -v termux-battery-status"
  chk "管理脚本 ~/dsh-web.sh" "[ -x '$HOME/dsh-web.sh' ]"
  chk "全局技能 android-device" "[ -f '$DSH_HOME/skills/android-device/SKILL.md' ]"
  return $f
}

# ===========================================================================
# bundle：打包迁移
# ===========================================================================
cmd_bundle() {
  local with_secrets=0 with_sessions=0
  for a in "$@"; do case "$a" in
    --with-secrets)  with_secrets=1 ;;
    --with-sessions) with_sessions=1 ;;
  esac; done

  step "打包迁移捆绑包"
  local stage; stage="$(mktemp -d)"
  cp -a "$SELF_DIR" "$stage/dsh-termux"
  local P="$stage/dsh-termux"

  # 用本机现役产物刷新资产，保证带走的是"正在工作"的那份
  [ -f "$NM/node-pty/prebuilds/android-arm64/pty.node" ] && cp -f "$NM/node-pty/prebuilds/android-arm64/pty.node" "$P/assets/pty.node"
  [ -f "$NM/koffi/build/koffi/android_arm64/koffi.node" ] && cp -f "$NM/koffi/build/koffi/android_arm64/koffi.node" "$P/assets/koffi.node"
  ok "已刷新原生资产"

  # 配置快照
  mkdir -p "$P/config/profiles/web" "$P/config/llm-deepseek"
  for f in settings.yaml; do [ -f "$DSH_HOME/$f" ] && cp -f "$DSH_HOME/$f" "$P/config/$f"; done
  for f in cordis.yml cordis.patch.yml package.json pnpm-workspace.yaml; do
    [ -f "$DSH_HOME/profiles/web/$f" ] && cp -f "$DSH_HOME/profiles/web/$f" "$P/config/profiles/web/$f"
  done
  [ -f "$DSH_HOME/llm-deepseek/files-v3.json" ] && cp -f "$DSH_HOME/llm-deepseek/files-v3.json" "$P/config/llm-deepseek/files-v3.json"
  ok "已快照配置（settings / profile / 模型状态）"

  if [ "$with_secrets" = "1" ] && [ -f "$DSH_HOME/.credentials.yaml" ]; then
    cp -f "$DSH_HOME/.credentials.yaml" "$P/config/credentials.yaml"; chmod 600 "$P/config/credentials.yaml"
    warn "已包含 API 凭据（config/credentials.yaml）——请用安全方式传输，用完及时删除"
  fi
  if [ "$with_sessions" = "1" ]; then
    mkdir -p "$P/data"
    [ -d "$DSH_HOME/sessions" ] && cp -a "$DSH_HOME/sessions" "$P/data/sessions" && ok "已包含会话记录"
    [ -d "$DSH_HOME/attachments" ] && cp -a "$DSH_HOME/attachments" "$P/data/attachments" && ok "已包含附件"
    : > "$P/data/.restore-hint"   # bootstrap 不会自动覆盖，需手动放置
  fi

  # 资产校验和刷新
  local ps ks
  ps=$(sha256sum "$P/assets/pty.node" | cut -d' ' -f1); ks=$(sha256sum "$P/assets/koffi.node" | cut -d' ' -f1)
  sed -i "s|^ASSET_PTY_SHA256=.*|ASSET_PTY_SHA256=$ps|; s|^ASSET_KOFFI_SHA256=.*|ASSET_KOFFI_SHA256=$ks|" "$P/MANIFEST"

  local OUT="$HOME/dsh-termux-bundle.tar.zst"
  rm -f "$OUT"
  tar --zstd --exclude='.git' --exclude='*.tar.zst' -cf "$OUT" -C "$stage" dsh-termux
  rm -rf "$stage"
  ok "已生成 $OUT（$(du -h "$OUT" | cut -f1)）"
  cat <<EOF

${B}新手机上的恢复步骤${N}
  1) 从 F-Droid / GitHub 安装 Termux（${Y}不要用 Google Play 版${N}，addon 受限）
  2) 把 dsh-termux-bundle.tar.zst 传到新手机，例如放到 ~/storage/downloads/
  3) 在 Termux 里执行：
       termux-setup-storage
       cd ~ && tar --zstd -xf storage/downloads/dsh-termux-bundle.tar.zst
       cd dsh-termux && ./bootstrap.sh
  4) 需要 adb 时按提示开启「开发者选项 → 无线调试」并配对：
       cp dsh-termux/tools/adb-connect-self.sh ~
       ~/adb-connect-self.sh --pair <配对端口> <配对码>
       ~/adb-connect-self.sh
EOF
}

# ===========================================================================
# 入口
# ===========================================================================
CMD=""
ARGS=()
for a in "$@"; do
  case "$a" in
    install|check|bundle) CMD="$a" ;;
    --start)        START_AFTER=1 ;;
    --force-config) FORCE_CONFIG=1 ;;
    *) ARGS+=("$a") ;;
  esac
done
: "${START_AFTER:=0}"; : "${FORCE_CONFIG:=0}"

case "${CMD:-install}" in
  check)  printf '%s===== DSH on Termux 体检 =====%s\n' "$B" "$N"; check_only; rc=$?
          [ "$rc" -eq 0 ] && printf '\n%s全部正常%s\n' "$G" "$N" || printf '\n%s%d 项异常%s\n' "$R" "$rc" "$N"
          exit "$rc" ;;
  bundle) cmd_bundle "${ARGS[@]:-}" ;;
  install) cmd_install ;;
esac
