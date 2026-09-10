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
├── tools/
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
