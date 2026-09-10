---
name: android-device
description: Drive this Android phone from DSH through wireless adb — open or switch apps, check which app is in the foreground, capture the screen and actually read it as an image, simulate taps/keys/text, and push or pull files. Use it whenever a task involves launching or operating a phone app, verifying what is on screen, or when the adb helper tools or the Termux environment need to be located or repaired.
---

# 用 DSH 操作这台 Android 手机

所有能力都建立在**无线 adb 自连**之上：借此拿到 `uid=2000(shell)`，绕开应用沙箱。
辅助工具都在 `~/dsh-termux/tools/`。

## 0. 先确认连接（每次动手前都做）

```bash
adb devices | awk 'NR>1 && $2=="device"{print $1; exit}'   # 有输出 = 已连接
~/dsh-termux/tools/adb-connect-self.sh                     # 无输出就重连（自动扫描端口）
```

首次或配对失效时（需人到手机上开「开发者选项 → 无线调试 → 使用配对码配对设备」，屏幕保持亮着）：

```bash
~/dsh-termux/tools/adb-connect-self.sh --pair <配对端口> <6位配对码>
```

**易错点**：配对对话框显示的**配对端口**与无线调试主页显示的**连接端口**是两个不同的端口；配对码有时效；重启手机后无线调试通常关闭，重开后再跑一次 `adb-connect-self.sh`（一般无需重新配对）。

## 1. 打开 App

```bash
~/dsh-termux/tools/open-app.sh 微信            # 中文别名（内置 25 条）
~/dsh-termux/tools/open-app.sh chrome          # 英文别名
~/dsh-termux/tools/open-app.sh com.tencent.mm  # 包名，或唯一匹配的片段
~/dsh-termux/tools/open-app.sh --list          # 列出可启动的第三方应用
~/dsh-termux/tools/open-app.sh --search 腾讯    # 按包名搜索
~/dsh-termux/tools/open-app.sh --current       # 看当前前台是哪个应用
~/dsh-termux/tools/open-app.sh --url <网址>     # 用浏览器打开网址（优先 Chrome）
```

别名表在脚本顶部，可自行增删。不用脚本时：

```bash
adb shell am start -n com.tencent.mm/.ui.LauncherUI
adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <包名>
adb shell monkey -p <包名> -c android.intent.category.LAUNCHER 1   # 不知道该 Activity 时
```

## 2. 看当前前台（最省事的验证，不碰屏幕内容）

```bash
adb shell dumpsys window | grep -m1 mCurrentFocus
```

## 3. 截图并真正「看」它

```bash
cd ~/dsh-workspace && adb exec-out screencap -p > shot.png
```

然后用 `read_image` 工具读 `shot.png`（要求当前模型支持图像输入）。
必须用 `exec-out`，不要用 `adb shell screencap`（会被 CRLF 破坏成坏 PNG）。

⚠️ 截图包含用户屏幕上的**全部**内容（聊天记录、通知、验证码）。除用户明确要求外，先说明意图再截；优先用第 2 节的文字方式验证。

## 4. 模拟输入（都有可见副作用，先确认）

```bash
adb shell wm size                                   # 先拿分辨率换算坐标（本机 1080x2400）
adb shell input tap X Y
adb shell input swipe X1 Y1 X2 Y2 300
adb shell input keyevent KEYCODE_BACK|KEYCODE_HOME|KEYCODE_ENTER|KEYCODE_WAKEUP
adb shell input text 'ascii-only'                    # 只支持 ASCII；中文需 ADBKeyboard 或剪贴板方案
```

## 5. 文件互传

```bash
adb push 本地路径 /sdcard/Download/
adb pull /sdcard/Download/x.png ./
```

注意：Termux 下 **`/tmp` 不可写**，临时文件用 `$TMPDIR` 或工作区。

## 6. 权限模型（解释为什么必须走 adb）

Termux 进程自身是 `untrusted_app`（uid 10328）：`pm`/`settings`/`dumpsys`、其他进程的 `/proc`、其他应用数据全部被 SELinux 拒绝。经 adb 拿到 `shell`(2000) 后这些才可用，包括 `pm`（装卸/清数据）、`dumpsys`、`settings`、`screencap`、`input`、`wm`、全量 `logcat`。
另：本机 Termux 是 Google Play 版，Termux:API 只实现了电量/音频/摄像头信息等少数几项，短信、通讯录、定位、传感器等为 stub —— 这些需求改走 adb。

## 7. 环境坏了先自检

```bash
~/dsh-termux/bootstrap.sh check      # 18 项体检
~/dsh-termux/bootstrap.sh            # 缺什么补什么（幂等，可反复跑）
~/dsh-web.sh status|restart|logs     # 服务管理
```

常见症状与根因：`glob`/`grep` 失效 → ripgrep 垫片；读图报 `EACCES: open '/data/data'` → 附件库补丁；会话写不进 → session 补丁；`node-pty`/`koffi` 加载失败 → 原生资产缺失。以上 `bootstrap.sh` 都能自动修复；**每次 dsh 升级后必须重跑一次**（npm 会重建 `node_modules`）。

## 8. 红线

- **不要自己重启 DSH 服务**——它就托管着当前会话；用 `~/dsh-web.sh restart`，或请用户执行。
- 截图、模拟输入、通知、亮屏都会**动用户的设备**，先说明再做。
- 涉及用户数据（聊天、通讯录、剪贴板、通知）只做必要读取，不要把内容写进日志或回复里。
