---
name: android-device
description: Operate this Android phone from DSH — open or switch apps, read the foreground app, capture the screen and actually view it, simulate taps/keys/text, install or uninstall APKs, move files, and run pm/dumpsys/settings/am. Two channels — rsh via Shizuku (preferred; no adb, no wireless debugging, no WiFi) and wireless adb (fallback). Use it whenever a task involves launching or driving a phone app, verifying what is on screen, installing an APK, or when adb / Shizuku / the Termux environment need to be located or repaired.
---

# 用 DSH 操作这台 Android 手机

一切都为了拿到 `uid=2000(shell)`，从而绕开应用沙箱（`pm` / `dumpsys` / `settings` /
`screencap` / `input` / `am` / `logcat`）。有**两条通道**：

| 通道 | 前提 | 特点 |
|---|---|---|
| **rsh（Shizuku）** ← 默认首选 | Shizuku 已装且运行 | **不需要 adb / 无线调试 / WiFi**；覆盖绝大多数操作 |
| **adb 无线** | 开发者选项里开无线调试（需 WiFi） | 兜底；端口随机、WiFi 一断就失效 |

## 0. 先选通道

```bash
~/dsh-termux/tools/setup-shizuku-rish.sh --check    # 退出码 0 → 用 rsh
adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'   # 有输出 → adb 可用
```

**默认先试 rsh。** 只有 rsh 不可用、或需要 `adb install` / `adb pull` 时才动 adb。

### rsh 不可用时

```bash
~/dsh-termux/tools/setup-shizuku-rish.sh     # 自动安装/修复（会找 Shizuku APK 并验证 uid=2000）
```

**引导只需一次。** 之后 rsh 不依赖 adb、无线调试和 WiFi ——
`shizuku_server` 只持有 UNIX 域套接字，TCP/UDP 套接字数实测为 0。

### adb 兜底

```bash
~/dsh-termux/tools/adb-connect-self.sh                       # 自动扫描无线调试端口
~/dsh-termux/tools/adb-connect-self.sh --pair <配对端口> <6位配对码>   # 配对失效时
```

配对对话框里的**配对端口**与无线调试主页显示的**连接端口**是两个不同的端口；配对码有时效。

## 1. 打开 App

```bash
~/dsh-termux/tools/open-app.sh 微信             # 中文别名（内置 25 条）
~/dsh-termux/tools/open-app.sh chrome           # 英文别名
~/dsh-termux/tools/open-app.sh com.tencent.mm   # 包名或唯一片段
~/dsh-termux/tools/open-app.sh --list           # 列出可启动的第三方应用
~/dsh-termux/tools/open-app.sh --search 腾讯     # 按包名搜索
~/dsh-termux/tools/open-app.sh --current        # 当前前台应用
~/dsh-termux/tools/open-app.sh --url <网址>      # 用浏览器打开网址
```

不用脚本时（`rsh` 与 `adb shell` 二选一，下同）：

```bash
~/dsh-termux/tools/rsh 'am start -n com.tencent.mm/.ui.LauncherUI'
~/dsh-termux/tools/rsh 'cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <包名>'
~/dsh-termux/tools/rsh 'monkey -p <包名> -c android.intent.category.LAUNCHER 1'   # 不知道 Activity 时
```

## 2. 看当前前台（最省事，不碰屏幕内容）

```bash
~/dsh-termux/tools/rsh 'dumpsys window | grep -m1 mCurrentFocus'
```

## 3. 截图并真正「看」它

**走 rish 时不能把 PNG 从管道里捞出来**（见第 8 节的输出分流问题），先写到设备上再读：

```bash
~/dsh-termux/tools/rsh 'screencap -p /sdcard/Download/dsh-shot.png'
# 然后直接读 Termux 可见的路径：
#   ~/storage/shared/Download/dsh-shot.png
```

走 adb 时用 `exec-out`（**不要**用 `adb shell screencap`，会被 CRLF 破坏成坏 PNG）：

```bash
adb exec-out screencap -p > shot.png
```

然后用 `read_image` 工具读该 PNG（需当前模型支持图像输入）。

⚠️ 截图包含屏幕上的**全部**内容（聊天记录、通知、验证码）。除用户明确要求外先说明意图再截；
优先用第 2 节的文字方式验证。用完删掉临时 PNG。

## 4. 模拟输入（都有可见副作用，先确认）

```bash
~/dsh-termux/tools/rsh 'wm size'                       # 先拿分辨率换算坐标（本机 1080x2400）
~/dsh-termux/tools/rsh 'input tap X Y'
~/dsh-termux/tools/rsh 'input swipe X1 Y1 X2 Y2 300'
~/dsh-termux/tools/rsh 'input keyevent KEYCODE_BACK'
~/dsh-termux/tools/rsh 'input text ascii-only'          # 只支持 ASCII
```

按 `KEYCODE_BACK` 前想清楚：若前台不是目标 App，会把**用户正在用的 App 一起退出**
（曾因此把微信切到前台并误截了一张含聊天内容的图）。

## 5. 安装 / 卸载 APK

**无需 adb**（已验证）。关键：APK 必须放进 `/data/local/tmp/` ——
`pm install` 由 system_server 执行，而 SELinux **不允许它读 `/sdcard`(FUSE)**，
报错会明确提示 `Consider using a file under /data/local/tmp/`。

```bash
~/dsh-termux/tools/apk-install.sh app.apk          # 一步到位（内部就是下面三步）
~/dsh-termux/tools/apk-install.sh --uninstall com.example.app
```

原理（`~/.shizuku/rish` 的 stdin 就是远端 shell 的脚本，所以可以搬数据）：

```bash
# 1) base64 经命令通道搬进 /data/local/tmp
{ printf 'base64 -d > /data/local/tmp/app.apk <<"ZZEOF"\n'; base64 -w0 app.apk; printf '\nZZEOF\n'; } \
  | ~/.shizuku/rish
# 2) 安装
~/dsh-termux/tools/rsh 'pm install -r /data/local/tmp/app.apk'
# 3) 清理
~/dsh-termux/tools/rsh 'rm -f /data/local/tmp/app.apk'
```

> 该通道是文本且逐字节校验（md5 实测一致）。12 KB 的包约 1 秒；**大包（几十 MB）会明显变慢**，
> 这种场景直接用 `adb install` 更合适。

> **装完紧接着调 rish 常会失联**：实测 `pm install` 会让 MIUI 回收 Shizuku 的应用进程，
> 之后 `rsh` 报 `Request timeout`，数十秒内自愈。要接着做连续操作时，中间留点间隔，
> 或先用 `setup-shizuku-rish.sh --check` 探一下再继续。

## 6. 文件互传

- **设备 → Termux**：shell 写 `/sdcard`，Termux 经 `~/storage/shared` 直接读
  （双向可读，实测通过）。
  ```bash
  ~/dsh-termux/tools/rsh 'cp /data/local/tmp/x /sdcard/Download/x'
  cp ~/storage/shared/Download/x ./
  ```
- **Termux → 设备**：小文件用第 5 节的 base64 手法；大文件用 `adb push`。

## 7. 权限模型（为什么必须绕）

Termux 自身是 `untrusted_app`(uid 10328)，SELinux 拒绝 `pm`/`settings`/`dumpsys`、
其他进程的 `/proc`、其他应用数据。拿到 `shell`(2000) 后这些才可用。
即便如此，**shell 仍读不到其他应用的私有数据**（`/data/data/<pkg>` 依旧 Permission denied），
这与 adb shell 一致，不是缺陷。

本机 Termux 是 Google Play 版，Termux:API 只实现了电量/音频/摄像头等少数几项，
短信、通讯录、定位、传感器是 stub —— 这些需求走 shell。

## 8. rish 的两个坑（务必知道）

1. **输出会随机整批落到 stdout 或 stderr。** 同一命令重复跑，时左时右（Shizuku 侧的竞态）。
   所以**永远用 `~/dsh-termux/tools/rsh`**，它在内部 `2>&1` 合并了两路；
   直接用 `~/.shizuku/rish` 配 `| grep` 会随机拿到空结果。

2. **`Request timeout` 是暂时性的，但有前提。** Shizuku **应用进程**被系统回收后，
   权限校验会超时（`shizuku_server` 还活着也没用）。`rsh` 已内置退避重试（默认 6 次，约 20 秒）。
   若持续超时：
   - **打开一次 Shizuku 应用**即可恢复；
   - 根治：给 **Shizuku 和 Termux 都关闭电池优化**（MIUI 后台管控激进，这是硬要求）。

## 9. 自检与修复

```bash
~/dsh-termux/bootstrap.sh check          # 全项体检（含 rish 通道与技能是否就位）
~/dsh-termux/bootstrap.sh                # 缺什么补什么（幂等，可反复跑）
~/dsh-termux/tools/setup-shizuku-rish.sh --check
~/dsh-web.sh status|restart|logs         # 服务管理
```

常见症状与根因：`glob`/`grep` 失效 → ripgrep 垫片；读图报 `EACCES: open '/data/data'` → 附件库补丁；
会话写不进 → session 补丁；`node-pty`/`koffi` 加载失败 → 原生资产缺失。
以上 `bootstrap.sh` 都能自动修复；**每次 dsh 升级后必须重跑一次**（npm 会重建 `node_modules`）。

## 10. 红线

- **不要自己重启 DSH 服务**——它就托管着当前会话；用 `~/dsh-web.sh restart` 或请用户执行。
- 截图、模拟输入、通知、亮屏都会**动用户的设备**，先说明再做。
- 涉及用户数据（聊天、通讯录、剪贴板、通知）只做必要读取，不要把内容写进日志或回复里。
