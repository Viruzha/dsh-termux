#!/system/bin/sh
# Termux sshd 看门狗：每 60 秒检查一次，不在就拉起。
# 单独成文件，便于 tests 和手动执行。
P=/data/data/com.termux/files/usr
H=/data/data/com.termux/files/home
U=10297
LOG=$H/.sshd-boot.log
export HOME=$H PREFIX=$P LD_LIBRARY_PATH=$P/lib PATH=$P/bin:/system/bin TMPDIR=$P/tmp

echo "$(date) 看门狗启动 (pid $$)" >> $LOG
while :; do
  # 必须用 pidof 精确匹配进程名：ps|grep "[s]shd" 会匹配到
  # 本脚本自己的名字（sshd-watchdog.sh），导致永远认为 sshd 还活着
  if ! pidof sshd >/dev/null 2>&1; then
    echo "$(date) 拉起 sshd" >> $LOG
    su -g $U -G 1004 -G 1007 -G 1011 -G 1015 -G 1028 -G 1078 -G 1079 \
       -G 3001 -G 3002 -G 3003 -G 3006 -G 3009 -G 3011 $U \
       -c "cd $H && $P/bin/sshd" >> $LOG 2>&1
  fi
  sleep 60
done
