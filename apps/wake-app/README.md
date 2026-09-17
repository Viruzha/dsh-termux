# 一键唤醒（Tailscale Funnel + Android App）

远程唤醒家里电脑，**手机端不需要开任何 VPN**，也不依赖 Tailscale 应用。

## 架构

```
[Android App]  --HTTPS-->  [Tailscale Funnel 公网入口]  -->  [viruzha (家里 Proxmox)]
  一键唤醒        token 鉴权     https://viruzha.tail428778.ts.net/      wol-api (127.0.0.1:8787)
                                                                              |
                                                                              v
                                                                     wakeonlan 2c:f0:5d:0e:61:c8
```

关键点：**Funnel 的公网入口由 Tailscale 的服务器承担**，所以家里的机器不需要公网 IP、
不需要路由器端口转发、手机也不需要加入 tailnet。手机侧只发一个普通 HTTPS 请求。

## 为什么不用「App 内连 tailnet」

| 方案 | 手机要不要开 VPN | 代价 |
|---|---|---|
| **本方案（Funnel）** | **不要** | 需要在 Tailscale 后台开通 Funnel |
| Tailscale 应用 + split tunneling | 要 | Android 同时只能有一个 VPN，会和 Clash 冲突 |
| tsnet 内嵌进 App | 不要 | 需 gomobile/NDK 构建，且 App 内要内置 auth key |

## 服务端（viruzha）

- `server/wol-api.py` → `/usr/local/bin/wol-api.py`
- `server/wol-api.service` → `/etc/systemd/system/wol-api.service`（已 enable，开机自启）
- 凭据在 `/etc/wol-api.env`（`chmod 600`），含 `WOL_TOKEN`
- 暴露：`tailscale funnel --bg 8787`

接口：

| 路径 | 说明 |
|---|---|
| `GET /health` | 免鉴权，返回 `{"ok":true,"version":"1.1"}` |
| `GET /wake?token=<TOKEN>` | 发一次 WoL 魔术包；token 错返回 401 |
| `GET /status?token=<TOKEN>` | 目标机在线状态，返回 `{"online":false,"method":"none","target_ip":"192.168.1.5"}` |

改配置：编辑 `/etc/wol-api.env` 后 `systemctl restart wol-api`。

| 环境变量 | 默认 | 说明 |
|---|---|---|
| `WOL_MAC` | `2c:f0:5d:0e:61:c8` | 唤醒目标 |
| `WOL_TARGET_IP` | `192.168.1.5` | 目标机地址（邻居表查不到时的兜底） |
| `WOL_PROBE_PORTS` | `3389,445,22,80` | 判活用的 TCP 端口 |
| `WOL_IFACES` | `vmbr0` | arping 使用的网卡 |

### 判活为什么用「主动 ARP → TCP → ICMP」

这里连踩了两个坑，最终形态是 **v1.2 的分层探测**：

**坑 1：只看 ping 会把开机误判成关机。**
目标机是 Windows，防火墙默认挡 ICMP：`ping 192.168.1.5` 100% 丢包，但机器其实好好的。

**坑 2：读被动邻居表会把关机误判成在线。**
改成加一层「邻居表状态」后，反而出现更糟的误报 —— 电脑刚关机时它的 ARP 条目
**仍然是 `REACHABLE`**，要等缓存过期才变 `FAILED`，于是"已关机却显示在线"。

最终做法：**主动发包，不读缓存**。

| 顺序 | 手段 | 说明 |
|---|---|---|
| 1 | `arping` | 二层探测。既不受 ICMP 被拦影响，**还能校验回包 MAC** —— 若回包来自别的 MAC，说明该 IP 被其他设备占了，判为离线 |
| 2 | TCP 端口 | `WOL_PROBE_PORTS`，默认 3389/445/22/80 |
| 3 | ICMP ping | 兜底 |

命中方式会写在 `method` 字段里（`arp` / `tcp:3389` / `icmp` / `none` / `ip-taken-by`），便于排查。
另外会先从邻居表按 MAC 反查 IP，这样目标机重新拿 DHCP 地址也不会失联。

## 手机端结构（可扩展）

经典三 Tab 布局，新增功能只需两步：

```
MainActivity          应用壳：底部导航 + 内容容器 + 选中态
  └─ screens 注册区    screens.add(new XxxScreen(this));  // ← 加一行
Screen                屏幕基类：build() / onShow() / onHide()
 ├─ WakeScreen        首页：服务端状态 + 大按钮 + 最近一次
 ├─ DevicesScreen     设备：设备卡片列表（多设备就从这里长出来）
 └─ SettingsScreen    设置：地址 / token / 设备信息 / 关于
Api                   极简 GET 层（回调在主线程）
Prefs                 配置存储（地址、token、设备、上次唤醒）
Ui                    地址推导与显示工具
```

**新增一个 Tab**：写一个继承 `Screen` 的类 → 在 `MainActivity` 的注册区加一行 →
再往 `TAB_ICON` / `TAB_LABEL` 两个数组各加一项即可，导航栏、选中态、内容切换全部自动生效。

视觉全部用平台原生控件手绘（shape drawable + vector），**没有引入 AndroidX/Material 依赖**，
所以包只有 43 KB。配色见 `res/values/colors.xml`，圆角卡片/按钮/状态胶囊在 `res/drawable/`。

- `build.sh`：复用 apk-lab 的无 Gradle 流水线（aapt2 → javac → d8 → zipalign → apksigner）
- token 不落进源码树：构建时由 `build.sh` 读 `~/.wol-token` 生成 `res/values/config.xml`，
  编译进资源后即删
- App 内**长按标题**可改 token（存 SharedPreferences），换 token 不必重新构建

```bash
# 构建（token 从 ~/.wol-token 读）
WOL_ENDPOINT=https://viruzha.tail428778.ts.net/wake ./build.sh
# 免 adb 安装
~/dsh-termux/tools/apk-install.sh build/wake.apk
```

## 应用图标

`design/icon.svg` 是图标源文件（深色圆角底 + 暗绿细环 + 绿色电源符号）。
`res/mipmap-*/` 下的 PNG 由 `rsvg-convert` 从该 SVG 渲染，`res/mipmap-anydpi-v26/` 是
Android 8+ 的自适应图标（矢量前景 + 渐变背景），因此不需要重新生成位图就能改前景色。

```bash
rsvg-convert -w 192 -h 192 design/icon.svg -o res/mipmap-xxxhdpi/ic_launcher.png
```

## 踩坑记录

### 主题没接上会留一条浅色标题栏

`AndroidManifest.xml` 里必须写 `android:theme="@style/AppTheme"`。用了 `Theme.Material.Light`
之类的浅色主题，系统会画一条 ActionBar，把深色设计毁掉（第一次就踩了）。

### d8 不能处理匿名内部类

Termux 自带 `d8` 是 3.3.20。JDK 21 编译出的**匿名内部类**会让它 NPE：

```
java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null
```

原因是 JVM 规范中匿名类在 `InnerClasses` 表里的 `inner_name` 为 **null**，而这个旧版 d8
对它调用了 `String.length()`。**具名嵌套类是正常的**，所以本 App 的监听器与 Runnable
全部写成具名静态嵌套类，不用任何匿名类。lambda 在 `-bootclasspath android.jar` 下也无法编译。

## 安全说明

Funnel 会把接口暴露到公网。防护：64 位十六进制随机 token + 固定 MAC + 错误 token 时 sleep 1 秒。
接口只能做一件事（发魔术包），因此泄露的后果限于「别人能唤醒你的电脑」。
如需更强防护，可换成 Cloudflare Tunnel + Access，或改用 Tailscale Serve（但那样手机要开 VPN）。
