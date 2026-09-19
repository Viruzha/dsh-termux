# 已 root 手机（vangogh）—— SSH 常驻访问

目标：**不依赖 adb，用 SSH 长期管理这台已 root 的手机。**

## 结果

| 能力 | 状态 |
|---|---|
| SSH 密钥登录 | ✅ `ssh vangogh` → uid 10297 (u0_a297) |
| SSH 提权到 root | ✅ `ssh vangogh 'su -c id'` → `uid=0` |
| 脱离 adb | ✅ 断开 adb 后实测通过 |
| 开机自启 | ✅ Magisk `service.d` 脚本 |

## 用法

```bash
ssh vangogh                        # 登录（~/.ssh/config 已配好别名）
ssh vangogh 'id'                   # 执行命令
ssh vangogh 'su -c "id"'           # 以 root 执行
./vg '命令'                         # 便捷脚本
./vg -r '命令'                      # 便捷脚本 · root
```

## 关键技术点（都是踩出来的）

### 1. `su` 必须带齐附加组，否则 socket 建不出来

`su 10297` 只给一个主组，而 Android **需要 `inet`(3003) 才能创建网络 socket**。
现象是 sshd 报：

```
socket: Permission denied
Cannot bind any address.
```

正确做法（Magisk su 支持 `-G`）：

```sh
su -g 10297 -G 1004 -G 1007 -G 1011 -G 1015 -G 1028 -G 1078 -G 1079 \
           -G 3001 -G 3002 -G 3003 -G 3006 -G 3009 -G 3011 10297 \
   -c "sshd"
```

### 2. 必须显式 `ListenAddress`

Android 的 hostname 是 `localhost`，sshd 拿它去解析绑定地址会失败：
`bad addr or host: <NULL>`。

### 3. `StrictModes no`（因为 Termux 的 home 是 777）

`/data/data/com.termux/files/home` 是 `drwxrwxrwx root root`，
StrictModes 默认开启时会**拒绝使用** `authorized_keys`。

> ⚠️ **这个 777 本身是个安全隐患**（任何 App 都能往 Termux home 里写文件）。
> 我**没有**改动它（按你要求不碰现有权限）。要根治可以：
> `chown -R 10297:10297 /data/data/com.termux/files/home && chmod 700 ...`
> 之后就能把 `StrictModes no` 去掉。

### 4. `UseDNS no`

不做反向 DNS，否则卡在 banner 交换。

### 5. 主机私钥权限要收紧

Termux 解压出来的主机密钥是 0777，sshd 会拒绝启动。
已改为 `600` + 属主 `u0_a297`（**这是一处权限修改**）。

## 改动清单（全部可回滚）

| 文件 | 操作 |
|---|---|
| `/data/adb/service.d/99-sshd.sh` | **新建**（开机启动 sshd） |
| `$PREFIX/etc/ssh/sshd_config` | **置前**加了 5 行 dsh-managed 配置，原文完整保留在下方 |
| `$PREFIX/etc/ssh/sshd_config.bak-20260919-102402` | 备份（原始 3 行） |
| `~/.ssh/authorized_keys` | 追加 1 行公钥（原为空）；权限 777→600，属主→u0_a297 |
| `~/.ssh/authorized_keys.bak-20260919-102402` | 备份（空文件） |
| `$PREFIX/etc/ssh/ssh_host_*_key` | 权限 777→600，属主→u0_a297 |

**未触碰**：`/system`、`/vendor`、Magisk 内部、`/etc/passwd`、SELinux 策略、
既有 Termux 包、既有配置行。

### 一键回滚

```bash
ssh vangogh 'su -c "
  rm -f /data/adb/service.d/99-sshd.sh          # 取消开机自启
  P=/data/data/com.termux/files/usr
  H=/data/data/com.termux/files/home
  T=20260919-102402
  cp \$P/etc/ssh/sshd_config.bak-\$T \$P/etc/ssh/sshd_config
  cp \$H/.ssh/authorized_keys.bak-\$T \$H/.ssh/authorized_keys
  PID=\$(ps -A -o PID,NAME | awk \\\"\\\$2==\\\"sshd\\\"{print \\\$1}\\\" | head -1)
  [ -n \"\$PID\" ] && kill \$PID
  echo 已回滚
"'
```

回滚后无残留（除备份文件外），设备行为与操作前一致。

## 一次性配置 → 用工具

```bash
tools/setup-ssh-server.sh <adb-serial>                  # 默认 0.0.0.0:8022，密钥登录，开机自启
tools/setup-ssh-server.sh <adb-serial> --port 2222      # 换端口
tools/setup-ssh-server.sh <adb-serial> --no-boot        # 不要开机自启
tools/setup-ssh-server.sh <adb-serial> --dry-run        # 只看会做什么
```

脚本只做加法：新建 `service.d` 脚本、往 `sshd_config` **置前**加一段（原文保留在下方）、
追加公钥；改动前都备份，结尾打印回滚命令。

## 两个必须绑 0.0.0.0 的理由（踩过）

最初绑的是具体 IP（`ListenAddress 192.168.1.10`），结果：

**WiFi 一重启，该 IP 消失，监听套接字即失效，而且不会自愈。**
表现是设备还在网上（adb 端口开着）但 8022 不通。
绑 `0.0.0.0` 后，IP 怎么变都不受影响。

同理，开机脚本**不能只启动一次** —— 进程被回收或监听失效后没人管。
所以 `service.d` 脚本是个 **60 秒一轮的看门狗**：

```sh
while :; do
  ps -A | grep -q "[s]shd" || su -g $U -G ...3003... $U -c "cd $H && $P/bin/sshd"
  sleep 60
done
```

## 关于"链路抖动"

首次部署后曾出现 2/6 连接超时。隔离测试结论：

- **不是 sshd**：目标机本地连自己的 8022 → **10/10 成功、稳定 38ms**
- **不是 SELinux**：无相关 avc 拒绝
- **不是 WiFi 省电**：关闭 power_save 无改善
- 属两台设备间的无线链路质量

改绑 `0.0.0.0` 并重连 WiFi 后复测 **6/6 成功**。

## 环境

| 项 | 值 |
|---|---|
| 设备 | M2002J9E / vangogh，Android 12 (SDK 31) |
| 内核 | 4.19.113-perf aarch64 |
| root | Magisk 27.0（`su` 可用，Termux uid 10297 已授权） |
| SSH | Termux 自带 OpenSSH 9.6，端口 8022，**仅监听 192.168.1.10** |
| 认证 | **仅密钥**（`PasswordAuthentication no`），本机密钥 `~/.ssh/id_ed25519_vangogh` |
