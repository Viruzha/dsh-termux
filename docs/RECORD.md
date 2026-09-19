# DSH on Termux 工作记录

> 记录时间：2026-09-19 ｜ 环境：Termux（Google Play 版）/ Android 16 / arm64-v8a / 无 root
> 目标设备：viruzha（家里 Proxmox）、vangogh（M2002J9E，已 root，Android 12）

一共推进了**六条线**，全部有实测结论。下面按线记录，最后汇总通用规律与文件索引。

---

## ① 在手机上构建 APK

**目标**：能不能在 Termux 里造出能装的 APK。

**结论**：三条路线**全部跑通**，都不需要 Gradle、不需要 Android SDK Manager。

| 路线 | 用途 | 结果 |
|---|---|---|
| 无 Gradle 直构 | `aapt2 → javac → d8 → zipalign → apksigner` | ✅ 约 5 秒出包 |
| apktool 二次打包 | 改已有 APK（无源码） | ✅ 真机验证 |
| Gradle + AGP | 真实项目 / AndroidX | ✅ AGP 9.4.0 + AndroidX 也能构建 |

**关键坑**

1. **`compileSdk` 上限是 34**（硬限制）。Termux 的 aapt2 是 AOSP 13（2.19），读不了 API 35/36
   的新资源表，会直接原生 abort：`failed to load include path .../android.jar`。
   而 Google **只发布 linux-x86_64 的 aapt2**（Maven 上无 aarch64），换二进制这条路堵死。
   *规避*：资源链接用 API 34，Java 编译用 API 36 —— 本来就是两件独立的事。
2. **d8 3.3.20 处理不了匿名内部类**。JVM 规范里匿名类的 `InnerClasses` 表项名字是 **null**，
   旧版 d8 对它调 `String.length()` → NPE。**改用具名嵌套类**即可（lambda 也编不过）。
3. **`aapt2 link` 的 `-R` 是 overlay 语义**，主资源要作为**位置参数**传入。
4. **顺序**：`zip 注入 dex → zipalign → apksigner`（签名必须最后）。
5. Android 11+ 要求 `resources.arsc` 以 STORED 存放且 **4 字节对齐**，否则装不上。

**产物**：`apps/apk-lab/`（构建脚本 ×3、`REPORT.md` 实测报告、4 个 APK）

---

## ② rish（Shizuku）通道 —— 不依赖 WiFi 的 shell

**目标**：adb 无线调试太脆弱（端口随机、WiFi 一断就废），有没有别的路。

**结论**：**可行**。`shizuku_server` 已被 init 收养（PPID=1），
只持有 UNIX 域套接字，`/proc/net/tcp`、`tcp6`、`udp` 里套接字数**实测为 0**。
实测关掉 WiFi、adb 完全断开时，`rsh` 仍返回 `uid=2000(shell)`。

**关键坑**

1. **rish 的输出会随机整批落到 stdout 或 stderr**（Shizuku 侧竞态）。
   直接用 `rish ... | grep` 会**随机拿到空结果**。必须 `2>&1` 合并 —— `tools/rsh` 已内置。
2. **Shizuku 应用进程被回收后报 `Request timeout`**（`shizuku_server` 还活着也没用）。
   需要**打开一次 Shizuku 应用**；根治是给 Shizuku 和 Termux 都关掉电池优化。
3. **Android 14+ 的 `app_process` 拒绝加载「可写」的 dex**，`rish_shizuku.dex` 必须 `chmod 400`。

**产物**：`tools/setup-shizuku-rish.sh`、`tools/rsh`、`tools/apk-install.sh`、
重写的 `skills/android-device/SKILL.md`

---

## ③ 一键唤醒（远程开机）

**目标**：远程开机不想每次都"开 Tailscale VPN → SSH → 敲 wakeonlan"。

**架构**

```
[App] --HTTPS--> [Tailscale Funnel 公网入口] --> [wol-api @viruzha] --> wakeonlan
```

**结论**：**手机端完全不需要 VPN**。Funnel 的公网入口由 Tailscale 服务器承担，
家里不需要公网 IP、不需要端口转发、手机也不需要加入 tailnet。

**关键坑**

1. **`ping` 判活会误报**：目标是 Windows，防火墙默认挡 ICMP，开机被判成关机。
2. **读被动邻居表会误报**：改用邻居表后，电脑刚关机时 ARP 条目**仍是 REACHABLE**，
   于是"已关机却显示在线"。
   *最终方案*：**主动 ARP（arping）→ TCP 端口 → ICMP**，且用回包 MAC 校验。
   **教训：不要用缓存判断状态。**
3. **Funnel 的 `/api` 信任栅栏**：用 `--port 0` 随机端口会导致前端白屏，必须固定端口 + `--trusted-host`。

**产物**：`apps/wake-app/`（源码 + 服务端 `wol-api.py` + 图标源文件 + `dist/wake.apk`）

---

## ④ 自带运行时的 DSH App

**目标**：把整个 DSH 环境嵌进 App，**发给另一个人，他装上就能用**。

**结论**：**跑通了**。App 内嵌的 DSH 实例成功启动，前端在 WebView 中渲染。

**关键坑（这一条线最多）**

1. **`targetSdkVersion` 必须是 28** ⭐ 最要命的一条。
   Android 禁止 targetSdk ≥ 29 的 App 执行自身数据目录中的文件
   （`error=13, Permission denied`）。降到 28 后进程进入 SELinux 的
   `untrusted_app_27` 兼容域，即可正常 exec 内置的 node。
2. **node 是可重定位的**。它与 Termux 前缀的耦合**只有 `DT_RUNPATH` 一处**，
   而 `DT_RUNPATH` 的搜索顺序**在 `LD_LIBRARY_PATH` 之后**，所以可覆盖。
   进一步用 `patchelf --remove-rpath` 删掉后同样能跑 → 可放进任意应用数据目录。
3. **依赖要做递归闭包**。只取直接依赖会漏 `libicudata.so.78`（单个 31.6 MB）；
   `rg` 另需 `libpcre2-8.so`。
4. **三个必须覆盖的环境变量**：
   `LD_LIBRARY_PATH`（覆盖 RUNPATH）、
   `OPENSSL_CONF`（node 把 Termux 路径**编译**进了 OpenSSL 默认配置）、
   `TMPDIR`（必须真实存在，否则 DSH 的 spill 存储 `mkdtemp` 报 ENOENT）。
5. **`sharp` 要 WebAssembly 版**（`@img/sharp-wasm32`），且它装在 Termux 的**全局**
   node_modules，不在 dsh 目录内，打包容易漏。
6. **构建脚本不能吞 javac 错误**。`javac ... | grep ... || true` 会静默产出缺类的坏包。

**产物**：`hub/`（四 Tab 一体化 App，35 MB APK，含 95 MB node 运行时）

---

## ⑤ 已 root 手机的 SSH 常驻

**目标**：不通过 adb，用 SSH 长期管理那台 root 手机。

**结论**：**开机自启已验证**（重启后日志：`11:16:48 看门狗启动` → `11:16:49 拉起 sshd`）。

**关键坑**

1. **`su` 必须带齐附加组**。`su 10297` 只给一个主组，而 Android **需要 `inet`(3003)
   才能创建网络 socket** → sshd 报 `socket: Permission denied / Cannot bind any address`。
   Magisk 的 `su` 支持 `-G`。
2. **必须绑 `0.0.0.0`**。绑具体 IP 的话，**WiFi 一重启该 IP 消失，监听套接字即失效且不自愈**。
3. **Magisk `service.d` 入口必须 `setsid`**。日志能看到 `service.d: exec [...]`，
   但脚本返回时后台子 shell 会被一起回收 —— 连日志文件都不会产生。
4. **看门狗检测必须用 `pidof`**，不能用 `ps | grep "[s]shd"` ——
   后者会匹配到**脚本自己的名字**，于是永远认为服务活着。
5. `StrictModes no`（Termux home 是 777）、主机私钥要 `chmod 600`（Termux 解压出来是 0777）、
   `UseDNS no`（否则卡 banner）、显式 `ListenAddress`（Android hostname 解析不出绑定地址）。
6. **从 SSH/adb shell 会话启动的进程，即使 `setsid` 也活不过会话结束**（仍属同一 cgroup）。
   所以自动靠开机脚本，手动靠装在 Termux 里的 `~/ssh-on`。

**产物**：`tools/setup-ssh-server.sh`、`docs/SSH-SERVER.md`（含一键回滚）

---

## ⑥ 网络诊断：mesh 节点粘滞

**症状**：`fuxi(192.168.1.22) → vangogh(192.168.1.10)` 的 SSH 频繁
`Connection timed out during banner exchange`；TCP 握手有时要 32s / 62s
（= SYN 重传 1+2+4+8+16 / +32）。

**排除**（都有数据）：sshd 本身（loopback 10/10、38ms）、CPU/温度（负载 0.49、34°C）、
AP 漫游（60 次采样 BSSID 不变）、后台扫描（关掉无改善）、省电（关掉无改善）、
服务端连接数限制（拉长间隔无改善）。

**根因**：**vangogh 被"粘"在较远的 mesh 节点上**。同名 SSID 下至少有 2 个节点：

| BSSID | 信号 |
|---|---|
| `28:68:d2:92:08:f0` | **-30 dBm**（就在旁边） |
| `94:e7:ea:ae:78:60` | -58 dBm（vangogh 连的是这个） |

于是跨节点的流量要绕行回程链路。**而视频流量只走本节点 → 外网，不跨节点，
所以看视频完全流畅** —— 这正是"视频没问题但两机之间丢包"的原因。

**修复**：强制 vangogh 重连 → 挂到 `28:68:d2:92:08:f0`，
信号 **-58 → -33 dBm**，协商速率 **390 → 866 Mbps**，
无线重传率 **23% → 8.5%**。

---

## ⑦ 打卡悬浮窗（hub 的新功能）

**目标**：一个常驻最上层的小按钮，点一下记录打卡时间，每天凌晨 5 点刷新。

**结论**：跑通了（App 内 `TYPE_APPLICATION_OVERLAY` + 前台服务 + 开机广播）。

**三个关键设计**

1. **「一天」的边界要用日历字段算**。边界是凌晨 5:00（把时间回挪 5 小时再取日期）。
   *错法*：`epoch毫秒 / 86400000` 是 **UTC 午夜**，东八区相当于本地 08:00，**偏 8 小时**。
   判定是纯计算的，不依赖定时器 —— App 没运行、手机重启都不影响正确性。
2. **「用户想开着」与「服务在跑」必须分开记**。`enabled` 只在用户主动关闭时置 false；
   开机广播靠它判断是否拉起。若只看「服务在不在跑」，被系统回收后就永不恢复。
3. **自启动只用官方 `BOOT_COMPLETED`**。设备管理员是 MDM 用的、与自启无关且让卸载变麻烦；
   无障碍服务虽难被杀但要手动开、弹警告，属滥用。

**配色修正（实际使用后反馈）**：最初「未打卡」是深灰底、「已打卡」是亮绿底，
**把主次搞反了** —— 需要提醒的状态反而最低调。改为未打卡=饱和红+白字、
已打卡=绿底深字。

用户补的语义让整个设计闭环了：这是**早卡提醒**而非全天候警报，
红色只在没打卡时出现、打完就消失，所以「红色消失」本身就是正反馈；
真红一整天，那就说明当天确实忘了打。

**撤销功能**：今天的打卡可在卡片里一键撤销，历史列表点任意一条也能删，
两者都弹确认框；删除后调 `PunchService.refreshNow()` 让悬浮框立刻改回「未打卡」。

**白盒审查修掉的问题**：静态强引用持有页面造成泄漏（退出 App 不走 onHide）→ WeakReference；
倒计时退到后台仍每秒唤醒 CPU → 给 Screen 加 onResume/onPause；
拖动起点用 `getLeft()/getTop()` 取值错误（对 WindowManager 窗口那不是屏幕位置，
实测存下过 `bubble_y=-371`）→ 改由服务给 `lp.x/lp.y` 并加边界钳制。

**耗电实测**（因为被问到）：

| 项 | 实测 |
|---|---|
| WakeLock | **size=0**（不持任何唤醒锁，CPU 该睡就睡） |
| 60 秒空闲 CPU | **20 毫秒**（约 0.03%） |
| 内存 | 约 58 MB PSS（含 WebView，不开 DSH 会更低） |

---

## ⑧ 密钥泄露事件（重要教训）

**经过**：`apps/wake-app/dist/wake.apk` 是**提交进库的成品 APK**，而 `build.sh` 会把
WOL token 编译成 `default_token` 字符串资源 —— 等于把 64 位真实 token 推到了 GitHub，
并进入 git 历史。

**讽刺之处**：`build.sh` 的设计本身是对的（注释明写「token 不落进源码树，只在 build/ 下生成」），
**防线只做了一半** —— 代码没写进去，产物却提交了。
根因是 `.gitignore` 只忽略了 `apps/*/build/`，**漏了 `dist/`**。

**处理**：

1. **轮换 token**（唯一真正的补救，因为已推到公共托管平台）
   —— 旧 token 实测 401，新 token 200，公网入口与本机都已同步
2. 重写 git 历史（`filter-branch` + 删 `refs/original/` + `reflog expire` + `gc`）
   并强制推送
3. `.gitignore` 补上 `dist/` 并**在文件里写明原因**
4. **从 GitHub 全新镜像克隆回来验证** —— 光看本地不算数

**审计结论**：除该 APK 外，私钥、API key 均为 0；
`config/` 6 个文件敏感词命中 0；
`textPassword`/`secret`/`Authorization` 等匹配全是代码、文档或库字符串的误报。

**教训**：凡是「构建产物可能含密钥」的场景，`.gitignore` 必须**连产物目录一起覆盖**。
另外 GitHub 侧的旧对象不会立即回收，所以**推出去的密钥只能当已泄露处理，必须轮换**。

---

## ⑨ 对外接口域名调研 + 安全审查

**起因**：token 泄露事件后，想把对外暴露的接口域名换掉。

### 结论一：Tailscale Funnel **不支持自定义域名**

- 官方功能请求 [tailscale/tailscale#17913](https://github.com/tailscale/tailscale/issues/17913) 仍是 **open**
- 社区讨论帖标题直接就是 ["Mission Impossible?"](https://github.com/NginxProxyManager/nginx-proxy-manager/discussions/4743)
- Funnel 地址恒为 `<机器名>.<tailnet名>.ts.net`

### 结论二：两段名字的可改程度完全不同

按官方文档 [Tailnet names and types](https://tailscale.com/docs/concepts/tailnet-name)：

| 段 | 能自己指定吗 | 说明 |
|---|---|---|
| **机器名**（`viruzha`） | ✅ **完全自由** | 后台 Machines 页或 `tailscale set --hostname=` 随时改 |
| **tailnet 名**（`tail428778`） | ❌ **只能从随机候选里挑** | 后台 DNS 页生成一组随机名（如 `cat-crocodile.ts.net`）供选择 |

⚠️ **且 tailnet 名基本是一次性的**：文档明确写「一旦用随机名签发了 HTTPS 证书，
就**不能再重新生成**，只能在新旧名之间切换」。

**改机器名的隐藏坑**：Serve/Funnel 配置**不会随主机名迁移**
（[issue #6452](https://github.com/tailscale/tailscale/issues/6452)），
改完必须重新 `tailscale funnel --bg --https=443 http://127.0.0.1:8787`。

### 结论三：**域名对安全没有影响**（源码级确认）

逐行审了 `wol-api.py`：

| 安全项 | 实现 |
|---|---|
| 鉴权 | `/wake`、`/status` 均必须带 token |
| 比较方式 | `hmac.compare_digest` —— **常数时间**，防时序侧信道 |
| 失败即拒绝 | `bool(TOKEN) and ...` —— token 未配置时**默认全拒**（fail-closed） |
| 抗爆破 | 401 前 `time.sleep(1)` |
| 日志 | `log_message` 被禁用 → **token 不会落进日志** |

三个小瑕疵（均**不构成实际风险**）：`/health` 免鉴权且泄露版本号；
那 1 秒 sleep 在抗爆破的同时也占住线程（轻度 DoS 面）；
token 走查询串而非请求头。

**关于"会不会被扫到"**：Funnel 证书走 Let's Encrypt，**CT 日志公开可查**，
所以 `*.ts.net` 主机名理论上可枚举。但拿到域名后访问 `/wake` 仍是 **401**，
爆破要面对 **2²⁵⁶** 的空间且每次失败被拖 1 秒。**发现 ≠ 能进去。**

**决定：不改。** 改域名只是隐蔽性游戏，收益极小。

---

## 通用规律（跨条线，值得记）

1. **不要用缓存判断状态**。ARP 表、DHCP 租约、DNS 缓存都会滞后 —— 主动探测慢一点，但不会骗你。
2. **`targetSdkVersion` 不是形式**。它决定 SELinux 域、存储模型、可否 exec 私有目录。
3. **进程生命周期比想象中脆弱**。会话结束、系统回收、cgroup 都会带走子进程；
   `setsid` 只能解决一部分。
4. **构建/脚本不要吞错误**。`| grep ... || true` 这类写法会把失败变成"看起来成功"。
5. **大文件传输别用 base64 管道**。走 `/sdcard` 中转：23 MB 从"几分钟还老超时"变成 6 秒。
   链路差时改用**分片 + 断点续传**。
6. **"能跑"和"可靠"是两件事**。前者证明可行性，后者才决定能不能用。
7. **同名 WiFi ≠ 同一个 AP**。mesh 下客户端可能粘在远处节点，且系统认为当前信号"够用"而不漫游。
8. **"公开可发现"不等于"可被攻破"**。服务暴露在公网会被扫到，但只要鉴权做对
   （强随机 + 常数时间比较 + fail-closed），被扫到本身不是问题。
   判断风险要看**攻破成本**，不是看**暴露面**。

---

## 文件索引

### 仓库 `~/dsh-termux`（已推送 GitHub，私有）

| 路径 | 说明 |
|---|---|
| `runtime/` | 自包含 App 的运行时：node 26.3.1 + rg + 10 个 .so + openssl.cnf（95 MB） |
| `assets/` | pty.node / koffi.node / ripgrep 垫片 |
| `tools/` | rsh、setup-shizuku-rish.sh、apk-install.sh、open-app.sh、setup-ssh-server.sh… |
| `skills/android-device/` | 设备操作技能（rish 为主、adb 为备） |
| `docs/SSH-SERVER.md` | root 设备 SSH 方案与回滚 |
| `apps/apk-lab/` | 构建工具链 + `REPORT.md` |
| `apps/wake-app/` | 一键唤醒 |
| `bootstrap.sh` / `manage.sh` | 一键引导 / 进程管理 |

### 本地工作区 `~/dsh-workspace`

| 路径 | 说明 |
|---|---|
| `apk-lab/` | APK 构建实验（含 SDK、gradle-test、报告） |
| `dsh-app/` | hub 的前身（纯 DSH 版） |
| `wake-app/` | hub 的前身（纯唤醒版），已入库 |
| `rooted-phone/` | SSH 部署脚本与文档 |
| `hub/` | 五 Tab 一体化 App（已入库到 apps/hub） |
| `RECORD.md` | 本文件 |

---

## 待办

- [x] `hub` 已入库到 `apps/hub`（五 Tab：唤醒/设备/打卡/DSH/设置）
- [x] 打卡悬浮窗 + 开机自启
- [x] 密钥泄露：token 已轮换、历史已重写、远端已克隆验证
- [ ] hub 的 DSH 服务应改为**前台服务**（现在随 Activity 存活）
- [ ] DSH 本体（54 MB）建议改为**首次运行下载**，别塞进 APK
- [ ] node 里仍编译了 Termux 的 `bin/bash`、`sh`、`/tmp` 路径（child_process 用），未验证影响
- [ ] `dsh-termux` 里的运行时是**手动同步**的，升级 Termux 包后需重新刷新并更新 MANIFEST
- [ ] SSH 自愈（sshd 挂掉后看门狗拉起）**逻辑已修正但未验证成功**
- [ ] vangogh 的 mesh 节点粘滞会复现，可能需要定期重连或从路由器侧固定
- [ ] WebView 延迟初始化（不开 DSH 可省下约 58MB 里的 WebView 部分）
- [x] 对外接口域名：调研完毕，**决定不改**（Funnel 不支持自定义域名；域名与安全无关）
- [ ] 打卡「自愈」逻辑已修正但未在真实重启后验证
- [ ] dsh-app/（hub 的前身，纯 DSH 版）未入库，已被 hub 取代
- [ ] rooted-phone 里的一次性调试脚本（fix-sshd/killsshd/restart-sshd）未入库，属开发残留
