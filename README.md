# DSH on Termux —— 可迁移的一键环境

把「DSH 在 Android/Termux 上跑起来」所需的一切收进一个目录：脚本、版本锚点、
以及 **npm 永远给不了的 Android 原生二进制**。换手机时携带本目录即可一键恢复。

## 目录结构

```
dsh-termux/
├── bootstrap.sh            一键引导/修复/迁移（幂等，可反复跑）
├── manage.sh               进程管理（安装为 ~/dsh-web.sh）
├── MANIFEST                版本锚点 + 资产 sha256
├── assets/
│   ├── pty.node            node-pty 的 android-arm64 预编译（101 KB）
│   ├── koffi.node          koffi 的 android-arm64 预编译（1.1 MB）
│   └── ripgrep-shim/       @vscode/ripgrep 平台解析垫片模板
├── patches/
│   ├── session-eacces.sh         会话落盘 link()→rename() 回退
│   ├── attachment-android.sh     附件库 fsync 走查 + 硬链接回退
│   └── ripgrep-android.sh        glob/grep 的 ripgrep 垫片
├── runtime/                自包含 App 的 Android 运行时（node / ripgrep / 共享库）
├── apps/                   配套 App 工程（源码 + 脚本 + 产物，不含可重下的大件）
│   ├── hub/                Viruzha 工具箱：唤醒 + 打卡 + DSH 五 Tab 一体化（推荐）
│   ├── wake-app/           一键唤醒：Tailscale Funnel + 三 Tab 深色 UI（hub 的前身）
│   └── apk-lab/            无 Gradle 构建 APK 的工具链、三条路线与实测报告
├── tools/
│   ├── setup-ssh-server.sh       在已 root 设备上配置 Termux sshd（密钥+开机自启）
│   ├── setup-shizuku-rish.sh     安装/修复 rish（Shizuku 通道，免 adb/WiFi）
│   ├── rsh                       以 shell(uid 2000) 执行命令（自动重试 + 流合并）
│   ├── apk-install.sh            免 adb 安装/卸载 APK
│   ├── adb-connect-self.sh       无线调试自连（兜底通道；端口随机，自动扫描）
│   ├── open-app.sh               打开 App / 打开网址（中文别名 + --url）
│   ├── setup-github.sh           配置 git + GitHub SSH（幂等）
│   └── probe-termux-api.sh       Termux:API 能力探针
└── config/                 配置快照（不含密钥，除非 bundle --with-secrets）
```

## 获取

```bash
# 从仓库（推荐）
git clone git@github.com:Viruzha/dsh-termux.git && cd dsh-termux && ./bootstrap.sh

# 或离线包
tar --zstd -xf dsh-termux-bundle.tar.zst && cd dsh-termux && ./bootstrap.sh
```

仓库：https://github.com/Viruzha/dsh-termux（私有）

## 日常用法

```bash
./bootstrap.sh check      # 只体检，不改动任何东西
./bootstrap.sh            # 安装 / 修复（缺什么补什么）
./bootstrap.sh --start    # 装完顺手启动
~/dsh-web.sh status|restart|logs
```

## 打开 App 等常用操作

```bash
tools/open-app.sh 微信            # 中文别名（内置别名表）
tools/open-app.sh chrome          # 英文别名
tools/open-app.sh com.tencent.mm  # 包名，或唯一匹配的包名片段
tools/open-app.sh --list          # 列出可启动的第三方应用
tools/open-app.sh --search 腾讯    # 按包名搜索
tools/open-app.sh --current       # 看当前前台是哪个应用
```

打开 App 需要 shell(uid 2000) 身份：优先走 **rish 通道**（不需要 adb / 无线调试 / WiFi），
不可用时自动回退无线 adb。别名表在脚本顶部，可自行增删。

## 已 root 设备的 SSH 常驻（docs/SSH-SERVER.md）

免 root 设备只能靠 adb / Shizuku 拿 shell，且容易被系统回收。**已 root 的设备可以直接跑 SSH 服务**：

```bash
tools/setup-ssh-server.sh <adb-serial>          # 配置好即 ssh -p 8022 <uid>@<ip>
ssh <uid>@<ip> -p 8022 'su -c id'               # 提权到 root
```

要点（都是踩出来的，详见 `docs/SSH-SERVER.md`）：

| 坑 | 现象 | 解法 |
|---|---|---|
| `su` 没给附加组 | `socket: Permission denied` | 必须 `-G 3003`(inet) 等完整组集 |
| 绑具体 IP | WiFi 重启后监听失效且不自愈 | 绑 `0.0.0.0` |
| 只启动一次 | 进程被回收后无人拉起 | `service.d` 里放 60 秒看门狗 |
| Android hostname | `bad addr or host: <NULL>` | 显式 `ListenAddress` |
| Termux home 是 777 | StrictModes 拒绝 authorized_keys | `StrictModes no`（或修权限） |
| 主机私钥 0777 | sshd 拒绝启动 | `chmod 600` |

---

## 配套工程（apps/ 与 runtime/）

仓库除"把 DSH 在 Termux 上跑起来"之外，还携带两个 App 工程和一份运行时。

### `runtime/` —— 自包含 App 的运行时

从 Termux 取出的、**npm 与 Gradle 都给不了**的 Android aarch64 原生件：
`node` v26.3.1（46 MB）、`rg` 15.1.0、以及 node 的全部非系统依赖（9 个 `.so`）。

实测证明了它的可重定位性：`node` 与前缀的耦合**只有 `DT_RUNPATH` 一处**，而
`DT_RUNPATH` 的搜索顺序在 `LD_LIBRARY_PATH` **之后**，所以

```bash
LD_LIBRARY_PATH=./runtime/lib ./runtime/bin/node -v   # → v26.3.1
```

详见 `runtime/README.md`。

### `apps/hub/` —— Viruzha 工具箱（一体化，推荐）

把「唤醒」「打卡」「DSH」合成一个五 Tab App，**发给别人装上就能用**：

| Tab | 说明 |
|---|---|
| 唤醒 | Tailscale Funnel 公网入口，手机端无需 VPN |
| 设备 | 设备列表与在线检测 |
| 打卡 | 悬浮按钮常驻最上层，点一下记录时间，每天凌晨 5 点刷新 |
| DSH | **内嵌完整 node 运行时**，本地起 DSH web 并在 WebView 中显示 |
| 设置 | 唤醒地址 / token / 设备信息 |

三个容易踩的点写在 `apps/hub/README.md` 里：`targetSdk` 必须 28、
凌晨 5 点的边界要用日历字段算、「用户想开着」与「服务在跑」要分开记。

```bash
cd apps/hub && ./build.sh       # 产出 build/hub.apk（约 35 MB）
```

### `apps/wake-app/` —— 一键唤醒（hub 的前身）

远程唤醒家里电脑，**手机端不需要开任何 VPN**。链路：

```
App --HTTPS--> Tailscale Funnel --> wol-api(viruzha) --> wakeonlan
```

三 Tab（唤醒 / 设备 / 设置）深色 UI，43 KB，零第三方依赖，用无 Gradle 流水线构建。
服务端 `wol-api.py` 与部署说明在工程内。

### `apps/apk-lab/` —— 在手机上构建 APK

不依赖 Gradle 与 Android SDK Manager 的完整构建链，三条实测跑通的路线：

| 路线 | 说明 |
|---|---|
| 无 Gradle 直构 | `aapt2 + javac + d8 + zipalign + apksigner` |
| apktool 二次打包 | 改已有 APK（需包装脚本过滤旧版 aapt2 不认的参数） |
| Gradle + AGP | AGP 9.4.0 可行，含 AndroidX，需 5 项手工配置 |

`REPORT.md` 是完整实测报告（含 `compileSdk` 只能用 34 的硬限制、d8 不能处理匿名内部类等坑）。
**大件不入库**：`sdk/`、`apktool.jar`、`gradle-test/` 的缓存都可按报告里的 URL 重新获取。

### 构建

两个工程都用同一套无 Gradle 流水线，签名密钥不存在时自动生成，因此克隆后可直接构建：

```bash
cd apps/wake-app   && ./build.sh    # 产出 dist/wake.apk
cd apps/apk-lab    && ./build.sh    # 需要先按 REPORT.md 取 android.jar
```

---

## 两条 shell 通道

非 root 设备上，拿到 `shell`(uid 2000) 只有两条路。本包两条都支持，**默认优先 rish**。

| 通道 | 前提 | 特点 |
|---|---|---|
| **rish（Shizuku）** | Shizuku 已装且运行 | **不需要 adb / 无线调试 / WiFi**；覆盖几乎全部操作 |
| **无线 adb** | 开发者选项开无线调试（需 WiFi） | 兜底；端口随机、WiFi 一断就失效 |

```bash
tools/setup-shizuku-rish.sh --check                        # 退出码 0 → rish 可用
tools/rsh id                                               # 以 shell 身份执行命令
tools/rsh 'dumpsys window | grep -m1 mCurrentFocus'
tools/rsh 'screencap -p /sdcard/Download/shot.png'
tools/apk-install.sh app.apk                               # 免 adb 安装
```

**为什么 rish 不需要网络**：`shizuku_server` 是已被 init 收养（PPID=1）的常驻进程，
只持有 UNIX 域套接字 —— `/proc/net/tcp`、`tcp6`、`udp` 里它的套接字数实测为 **0**。
实测关掉 WiFi、甚至 adb 完全断开时，`rsh` 依旧返回 `uid=2000(shell)`。

**两个已知坑**（`tools/rsh` 已内建处理）：

- rish 的输出会**随机整批**落到 stdout 或 stderr，必须合并两路，
  否则 `rsh ... | grep x` 会随机拿到空结果；
- Shizuku **应用进程**被系统回收后 rish 报 `Request timeout`，通常几秒自愈，`rsh` 会退避重试。
  若持续超时，**打开一次 Shizuku 应用**，并给 **Shizuku 和 Termux 都关闭电池优化**（硬要求）。

**无 root 时重启手机后** Shizuku 不会自动恢复，需重新引导一次
（`bootstrap.sh` 的 `10/11 shell 通道` 步骤会尝试自动完成）。

**安装 APK** 有个 Android 的硬约束：`pm install` 由 system_server 执行，SELinux 不允许它读
`/sdcard`(FUSE)，APK 必须位于 `/data/local/tmp/`；而 Termux 写不进那里 —— `tools/apk-install.sh`
用 rish 的命令通道把 base64 搬过去（逐字节校验 md5）。大包请改用 `adb install`。

## 全局技能（Skills）

DSH 的技能 = 带 YAML frontmatter 的 Markdown 指令集，放进用户级根目录即被自动发现：

```
~/.dsh/skills/<name>/SKILL.md            # 全局：所有会话可见
<工作区>/.dsh/skills/<name>/SKILL.md      # 项目级：只有该项目可见
```

本包自带一个：**`skills/android-device/`**（操作手机：开 App、看前台、截图读图、模拟输入、
安装/卸载 APK、文件互传，以及 rish 与无线 adb 两条通道的选择与自检）。
`bootstrap.sh` 会把它装到 `~/.dsh/skills/`。

- `skill-filesystem` 带 watcher：**新增或修改技能后即时生效，无需重启 DSH**。
- 想加技能：在 `~/dsh-termux/skills/` 下建目录写 `SKILL.md`，重跑 `./bootstrap.sh`；也可以直接放进 `~/.dsh/skills/`。
- frontmatter 必填 `name`（kebab-case）与 `description`（技能目录里展示的触发说明，写清"什么时候该用"）；可选 `disable-model-invocation: true`（禁止模型自动调用，只允许人手动）、`user-invocable: false`。
- 模型侧通过 `skill` 工具按名字加载；每个会话开始时会注入一份可用技能目录。

## 为什么需要这些「补丁」

Android 不是普通的 Linux，DSH 有几处假设在这里不成立：

| 症状 | 根因 | 处理 |
|---|---|---|
| `glob`/`grep` 报 ripgrep launch failed | Node 在 Termux 报 `process.platform==='android'`，而 vscode-ripgrep 没有 android 包 | 垫片包指向 Termux 原生 `rg` |
| 会话/附件写入 `EACCES: link ...` | app 私有存储**禁止硬链接** | 回退 `rename()`/`copyFile()` |
| 读图报 `EACCES: open '/data/data'` | 附件库把 DSH_HOME 的每级祖先都 `open()` 做 fsync，而 `/data/data` 是 `0711 root:root` | 走到第一个打不开的祖先即停 |
| `node-pty`/`koffi` 加载失败 | npm 安装脚本被拦 + 无 android 预编译 | 随包携带并校验 sha256 |

## 换手机迁移（一句话版）

老手机：`./bootstrap.sh bundle` → 把产出的 `~/dsh-termux-bundle.tar.zst` 传到新手机 → 新手机装 **F-Droid 版** Termux → `tar --zstd -xf` 解开 → `./bootstrap.sh`。

详细步骤见 `docs/MIGRATION.md`。
