#!/data/data/com.termux/files/usr/bin/bash
# 在「已 root」的 Android 设备上，把 Termux 自带的 sshd 配置成
# 「仅密钥登录 + 开机自启」的常驻服务。经 adb 操作。
#
#   setup-ssh-server.sh <adb-serial> [选项]
#
#   --port N         监听端口（默认 8022）
#   --listen ADDR    ListenAddress（默认 0.0.0.0；绑具体 IP 会在 WiFi 重启后失效）
#   --pubkey FILE    要授权的公钥（默认 ~/.ssh/id_ed25519.pub）
#   --uid N          指定 Termux 的 uid（默认自动探测）
#   --no-boot        不装开机自启脚本
#   --dry-run        只显示将要做什么
#
# 只做加法：新建 service.d 脚本、往 sshd_config *置前* 加一段（原文完整保留）、
# 追加公钥。改动前都备份，结尾打印回滚命令。
#
# 踩过的坑（详见 README）：
#   1. su 必须带 -G inet(3003) 等附加组，否则 socket 建不出来
#   2. 必须显式 ListenAddress，Android 的 hostname 解析不出可绑定地址
#   3. Termux home 是 777 → StrictModes 默认会拒绝 authorized_keys
#   4. Termux 解压出的主机私钥是 0777 → sshd 拒绝启动
#   5. 不做反向 DNS（UseDNS no），否则卡在 banner 交换
set -uo pipefail

SERIAL=""; PORT=8022; LISTEN=""; PUBKEY="$HOME/.ssh/id_ed25519.pub"; TUID=""; BOOT=1; DRY=0
while [ $# -gt 0 ]; do case "$1" in
  --port)     PORT="$2"; shift 2 ;;
  --listen)   LISTEN="$2"; shift 2 ;;
  --pubkey)   PUBKEY="$2"; shift 2 ;;
  --uid)      TUID="$2"; shift 2 ;;
  --no-boot)  BOOT=0; shift ;;
  --dry-run)  DRY=1; shift ;;
  -h|--help)  sed -n '2,22p' "$0"; exit 0 ;;
  *)          SERIAL="$1"; shift ;;
esac; done

die() { printf '%s\n' "$*" >&2; exit 1; }
[ -n "$SERIAL" ] || die "用法： setup-ssh-server.sh <adb-serial> [--port N] [--listen ADDR]"
[ -r "$PUBKEY" ] || die "公钥不可读：$PUBKEY"
command -v adb >/dev/null || die "缺少 adb"

ADB="adb -s $SERIAL"
$ADB get-state >/dev/null 2>&1 || die "设备未连接：$SERIAL"
$ADB shell 'su -c id' 2>/dev/null | grep -q 'uid=0' || die "该设备没有可用的 root（su -c id 未返回 uid=0）"

[ -n "$TUID" ] || TUID="$($ADB shell 'su -c "stat -c %u /data/data/com.termux"' 2>/dev/null | tr -d '\r\n ')"
[ -n "$TUID" ] || die "无法探测 Termux 的 uid，请用 --uid 指定"
$ADB shell "su -c 'test -x /data/data/com.termux/files/usr/bin/sshd'" 2>/dev/null \
  || die "目标机 Termux 里没有 sshd（先在 Termux 里 pkg install openssh）"

# 默认绑 0.0.0.0：绑具体 IP 的话，WiFi 一重启该 IP 消失，监听套接字即失效且不会自愈
[ -n "$LISTEN" ] || LISTEN="0.0.0.0"

TS="$(date +%Y%m%d-%H%M%S)"
printf '目标设备 : %s\nTermux uid: %s\n监听      : %s:%s\n公钥      : %s\n\n' \
  "$SERIAL" "$TUID" "$LISTEN" "$PORT" "$PUBKEY"
[ "$DRY" = 1 ] && { echo "--dry-run：以上为将要使用的参数，未做任何改动"; exit 0; }

# 远端脚本：推过去执行，避免嵌套引号
REMOTE="$(mktemp "$TMPDIR/setup-sshd.XXXXXX")"
cat > "$REMOTE" <<REMOTE_SCRIPT
#!/system/bin/sh
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home
U=$TUID
TS=$TS

# --- 备份 ---
cp -a \$H/.ssh/authorized_keys \$H/.ssh/authorized_keys.bak-\$TS 2>/dev/null || touch \$H/.ssh/authorized_keys
cp -a \$P/etc/ssh/sshd_config \$P/etc/ssh/sshd_config.bak-\$TS

# --- 公钥 ---
grep -qF "\$(cat /data/local/tmp/dsh-sshd.pub)" \$H/.ssh/authorized_keys 2>/dev/null \\
  || cat /data/local/tmp/dsh-sshd.pub >> \$H/.ssh/authorized_keys

# --- 权限：.ssh 与主机私钥 ---
chown \$U:\$U \$H/.ssh \$H/.ssh/authorized_keys
chmod 700 \$H/.ssh; chmod 600 \$H/.ssh/authorized_keys
chown \$U:\$U \$P/etc/ssh/ssh_host_*_key 2>/dev/null
chmod 600 \$P/etc/ssh/ssh_host_*_key 2>/dev/null
mkdir -p \$P/var/run \$P/tmp; chown -R \$U:\$U \$P/var/run \$P/tmp

# --- sshd_config：置前（sshd 取第一个出现的值），原文保留 ---
if ! grep -q '^# dsh-managed begin' \$P/etc/ssh/sshd_config; then
  {
    echo '# dsh-managed begin'
    echo 'PubkeyAuthentication yes'
    echo 'PasswordAuthentication no'
    echo "Port $PORT"
    echo "ListenAddress $LISTEN"
    echo 'StrictModes no'
    echo 'UseDNS no'
    echo '# dsh-managed end'
    cat \$P/etc/ssh/sshd_config.bak-\$TS
  } > \$P/etc/ssh/sshd_config.new
  cat \$P/etc/ssh/sshd_config.new > \$P/etc/ssh/sshd_config
  rm -f \$P/etc/ssh/sshd_config.new
fi

# --- 开机自启 ---
if [ "$BOOT" = 1 ]; then
  mkdir -p /data/adb/service.d
  cat > /data/adb/sshd-watchdog.sh <<'WDOGEOF'
#!/system/bin/sh
# Termux sshd 看门狗：每 60 秒检查，不在就拉起。
# 注意：必须用 pidof 精确匹配进程名。用 ps|grep "[s]shd" 会匹配到本脚本
# 自己的名字（sshd-watchdog.sh），导致永远认为 sshd 还活着 —— 实测踩过。
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home
U=__UID__
LOG=$H/.sshd-boot.log
export HOME=$H PREFIX=$P LD_LIBRARY_PATH=$P/lib PATH=$P/bin:/system/bin TMPDIR=$P/tmp
echo "$(date) 看门狗启动 (pid $$)" >> $LOG
while :; do
  if ! pidof sshd >/dev/null 2>&1; then
    echo "$(date) 拉起 sshd" >> $LOG
    su -g $U -G 1004 -G 1007 -G 1011 -G 1015 -G 1028 -G 1078 -G 1079 \
       -G 3001 -G 3002 -G 3003 -G 3006 -G 3009 -G 3011 $U \
       -c "cd $H && $P/bin/sshd" >> $LOG 2>&1
  fi
  sleep 60
done
WDOGEOF

  cat > /data/adb/service.d/99-sshd.sh <<'BOOTEOF'
#!/system/bin/sh
# Magisk service.d 入口。删除本文件即可完全取消开机自启。
# 必须 setsid 脱离进程组：Magisk 日志能看到 "service.d: exec"，但脚本返回时
# 后台子 shell 会被一起回收，看门狗活不下来（实测日志文件都没写）。
setsid /system/bin/sh /data/adb/sshd-watchdog.sh </dev/null >/dev/null 2>&1 &
BOOTEOF
  sed -i "s/__UID__/$U/g" /data/adb/sshd-watchdog.sh
  chmod 700 /data/adb/sshd-watchdog.sh; chown 0:0 /data/adb/sshd-watchdog.sh
  chmod 755 /data/adb/service.d/99-sshd.sh
  chown 0:0 /data/adb/service.d/99-sshd.sh
fi

# --- 启动 ---
# 用 pidof（toybox 的 ps 不支持 -o PID,NAME）
for pid in \$(pidof sshd 2>/dev/null); do kill \$pid 2>/dev/null; done
sleep 2
export HOME=\$H PREFIX=\$P LD_LIBRARY_PATH=\$P/lib PATH=\$P/bin:/system/bin TMPDIR=\$P/tmp
su -g \$U -G 1004 -G 1007 -G 1011 -G 1015 -G 1028 -G 1078 -G 1079 \\
   -G 3001 -G 3002 -G 3003 -G 3006 -G 3009 -G 3011 \$U -c "cd \$H && \$P/bin/sshd"
sleep 2


# --- 立刻启动看门狗（关键）---
# 必须 setsid 完全脱离会话：否则从 Termux 会话或 adb shell 启动的进程
# 会随会话结束被回收，那样 service.d 只在开机时生效，中途挂掉就没人管。
if [ "$BOOT" = 1 ]; then
  su -c "setsid nohup sh /data/adb/service.d/99-sshd.sh >/dev/null 2>&1 </dev/null &" 2>/dev/null
  sleep 2
fi

echo "--- sshd 进程 ---"
pidof sshd 2>/dev/null | sed "s/^/    pid /"
echo "--- 看门狗 ---"
ps -A 2>/dev/null | grep "[9]9-sshd" | head -2
echo "--- 监听 ---"
ss -tlnp 2>/dev/null | grep ":$PORT"
echo "TS=\$TS"
REMOTE_SCRIPT
chmod 755 "$REMOTE"

$ADB push "$PUBKEY" /data/local/tmp/dsh-sshd.pub >/dev/null 2>&1 || die "公钥推送失败"
$ADB push "$REMOTE" /data/local/tmp/dsh-setup-sshd.sh >/dev/null 2>&1 || die "脚本推送失败"
rm -f "$REMOTE"

$ADB shell 'su -c "sh /data/local/tmp/dsh-setup-sshd.sh"' 2>&1 | tail -12
$ADB shell 'rm -f /data/local/tmp/dsh-setup-sshd.sh /data/local/tmp/dsh-sshd.pub' >/dev/null 2>&1

cat <<ROLLBACK

回滚（把 \$TS 换成上面输出的时间戳）：
  $ADB shell 'su -c "
    rm -f /data/adb/service.d/99-sshd.sh
    P=/data/data/com.termux/files/usr; H=/data/data/com.termux/files/home; T=<TS>
    cp \\\$P/etc/ssh/sshd_config.bak-\\\$T \\\$P/etc/ssh/sshd_config
    cp \\\$H/.ssh/authorized_keys.bak-\\\$T \\\$H/.ssh/authorized_keys
    PID=\\\$(ps -A -o PID,NAME | awk \\\"\\\$2==\\\"sshd\\\"{print \\\$1}\\\" | head -1)
    [ -n \\\"\\\$PID\\\" ] && kill \\\$PID
  "'

连接：
  ssh -p $PORT -i ${PUBKEY%.pub} $TUID@$LISTEN
ROLLBACK
