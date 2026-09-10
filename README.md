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
│   ├── adb-connect-self.sh       无线调试自连（端口随机，自动扫描）
│   ├── open-app.sh               打开 App / 打开网址（中文别名 + --url）
│   ├── setup-github.sh           配置 git + GitHub SSH（幂等）
│   └── probe-termux-api.sh       Termux:API 能力探针
└── config/                 配置快照（不含密钥，除非 bundle --with-secrets）
```

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

打开 App 走 `adb shell`（shell 身份），所以需要先连着无线调试；没连上会提示
先跑 `tools/adb-connect-self.sh`。别名表在脚本顶部，可自行增删。

## 全局技能（Skills）

DSH 的技能 = 带 YAML frontmatter 的 Markdown 指令集，放进用户级根目录即被自动发现：

```
~/.dsh/skills/<name>/SKILL.md            # 全局：所有会话可见
<工作区>/.dsh/skills/<name>/SKILL.md      # 项目级：只有该项目可见
```

本包自带一个：**`skills/android-device/`**（用 adb 操作手机：开 App、看前台、截图读图、模拟输入、文件互传、环境自检）。`bootstrap.sh` 会把它装到 `~/.dsh/skills/`。

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
