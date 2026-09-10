#!/data/data/com.termux/files/usr/bin/bash
# ===========================================================================
# setup-github.sh —— 配置 git 与 GitHub SSH（幂等，可反复跑）
#
#   ./setup-github.sh                      沿用/补全现有配置
#   ./setup-github.sh Viruzha me@mail.com  指定提交身份
#   ./setup-github.sh --open               顺手打开 GitHub 添加密钥页面
#   ./setup-github.sh --verify             做一次 ssh -T 认证测试
#
# 做什么：
#   1. 确认 git 可用
#   2. ~/.ssh/id_ed25519 不存在则生成（ed25519、无口令，私钥不离开本机）
#   3. 写 ~/.ssh/config 的 github.com 段（IdentitiesOnly，避免用错 key）
#   4. 补全 git 基础配置（只写未设置的项，不覆盖你的选择）
#   5. 打印公钥与指纹，并给出 GitHub 添加地址
#
# 换手机后：私钥不会随迁移包走（属于密钥，默认不打包），在新手机上
# 跑一次本脚本 + 把公钥加到 GitHub 即可。
# ===========================================================================
set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KEY="$HOME/.ssh/id_ed25519"
OPEN_URL=0; VERIFY=0; NAME=""; EMAIL=""
for a in "$@"; do
  case "$a" in
    --open)   OPEN_URL=1 ;;
    --verify) VERIFY=1 ;;
    *) if [ -z "$NAME" ]; then NAME="$a"; elif [ -z "$EMAIL" ]; then EMAIL="$a"; fi ;;
  esac
done

ok()   { printf '  \033[32m✓\033[0m %s\n' "$*"; }
did()  { printf '  \033[32m→\033[0m %s\n' "$*"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$*"; }

command -v git >/dev/null 2>&1 || { echo "✗ 没装 git，先执行: pkg install -y git" >&2; exit 1; }
ok "git $(git --version | awk '{print $3}')"

# --- 1) 密钥 ---
mkdir -p "$HOME/.ssh"; chmod 700 "$HOME/.ssh"
if [ -f "$KEY" ]; then
  ok "SSH 私钥已存在（$KEY）"
else
  ssh-keygen -t ed25519 -N "" -C "$(whoami)@$(getprop ro.product.model 2>/dev/null || echo android)" -f "$KEY" >/dev/null 2>&1
  chmod 600 "$KEY"; chmod 644 "$KEY.pub"
  did "已生成 SSH 密钥 $KEY"
fi

# --- 2) ssh config ---
touch "$HOME/.ssh/config"; chmod 600 "$HOME/.ssh/config"
if grep -q "^Host github.com" "$HOME/.ssh/config"; then
  ok "~/.ssh/config 已有 github.com 段"
else
  cat >> "$HOME/.ssh/config" <<'CFG'

Host github.com
  HostName github.com
  User git
  IdentityFile ~/.ssh/id_ed25519
  IdentitiesOnly yes
CFG
  did "已写入 ~/.ssh/config 的 github.com 段"
fi

# --- 3) git 基础配置（只补未设置的）---
set_if_absent() { # $1=key $2=value
  if [ -z "$(git config --global "$1" || true)" ]; then
    git config --global "$1" "$2"; did "git config $1 = $2"
  else
    ok "git config $1 已设置（$(git config --global "$1")）"
  fi
}
set_if_absent init.defaultBranch main
set_if_absent core.editor nano
set_if_absent color.ui auto
set_if_absent pull.rebase false
set_if_absent credential.helper store
set_if_absent core.autocrlf input

# --- 4) 提交身份 ---
if [ -n "$NAME" ]; then git config --global user.name "$NAME"; did "user.name = $NAME"
else NAME="$(git config --global user.name || true)"; [ -n "$NAME" ] && ok "user.name = $NAME" || warn "user.name 未设置（可执行: $0 <名字> <邮箱>）"; fi
if [ -n "$EMAIL" ]; then git config --global user.email "$EMAIL"; did "user.email = $EMAIL"
else EMAIL="$(git config --global user.email || true)"; [ -n "$EMAIL" ] && ok "user.email = $EMAIL" || warn "user.email 未设置"; fi

# --- 5) 公钥与指纹 ---
echo
echo "  公钥指纹: $(ssh-keygen -lf "$KEY.pub" | awk '{print $2}')"
echo "  公钥全文（整行复制到 GitHub → Settings → SSH and GPG keys → New SSH key）:"
echo
cat "$KEY.pub" | sed 's/^/    /'
echo
echo "  添加地址: https://github.com/settings/ssh/new"

# --- 6) 可选：打开页面 / 认证测试 ---
if [ "$OPEN_URL" = "1" ] && [ -x "$SELF_DIR/open-app.sh" ]; then
  echo
  "$SELF_DIR/open-app.sh" --url "https://github.com/settings/ssh/new"
fi
if [ "$VERIFY" = "1" ]; then
  echo
  echo "  SSH 认证测试："
  # 注意：GitHub 认证成功时 ssh -T 也返回 1（不提供 shell），所以看提示语而不是退出码
  vout="$(timeout 40 ssh -T -o BatchMode=yes -o StrictHostKeyChecking=accept-new git@github.com 2>&1 || true)"
  printf '%s\n' "$vout" | sed 's/^/    /'
  if printf '%s' "$vout" | grep -q "successfully authenticated"; then
    ok "认证通过"
  else
    warn "认证未通过：确认公钥已加到 GitHub，或检查网络"
  fi
fi
exit 0
