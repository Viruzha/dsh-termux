# 不依赖 WiFi / adb，在 Termux 里拿到 Android shell 权限

**结论：可行，已在本机实测验证。** 方案是 **Shizuku + rish**。

---

## 一、为什么必须绕这一圈

Termux 进程是 `untrusted_app`（uid 10328），受 SELinux 约束，`pm` / `dumpsys` / `settings` /
`screencap` / `input` 这些都被拒绝。非 root 设备上，拿到 `shell`(uid 2000) 的通道**只有两类**：

1. **adb**（USB 或无线调试）—— 无线调试必须开 WiFi，且端口每次随机、WiFi 一断就失效。
2. **Shizuku** —— 一个已被提权、以 shell 身份常驻的进程，通过 Binder 对外提供服务。

Shizuku 的引导（bootstrap）本身通常也要靠 adb，但**引导一次之后就不需要了**。
本机恰好已经装好并在运行 Shizuku，所以直接可用。

---

## 二、实测证据

| 验证项 | 结果 |
|---|---|
| `shizuku_server` 身份 | `shell`（uid 2000），`PPID=1`（已被 init 收养，与 adbd 无父子关系） |
| 进程打开的 socket | 2 个，**全部在 `/proc/net/unix`**；`/proc/net/tcp`、`tcp6`、`udp` **命中 0** |
| 关掉 Termux 侧 adb server、设备列表为空后 | `rish` 依然返回 `uid=2000(shell) ... context=u:r:shell:s0` |
| 能力对照 | `pm list packages`（480 个包）、`dumpsys window`、`settings get`、`screencap`、`/data/local/tmp` 全部可用 |
| **真·关闭 WiFi 后**（`wifi_on=0`，`cmd wifi status` 报 `Wifi is disabled`） | `rish` 七项能力**全部照常**：uid=2000、`shizuku_server` 存活、480 个包、`dumpsys`、`settings`、`screencap` 成功写出 282 KB PNG |

即：**Shizuku 完全不使用网络套接字，`rish` 在 adb 断开、WiFi 关闭时都照常工作。**

> 注：断网测试时手机仍开着蜂窝数据，所以 `ping` 外网仍通——这不影响结论，
> 因为验证的关键是 `wifi_on=0` 而 `rish` 依旧可用，且 Shizuku 本身零网络套接字。

---

## 三、安装步骤（已在本机完成）

```bash
# 1. 从 Shizuku APK 里取出 rish 与它依赖的 dex
adb pull "$(adb shell pm path moe.shizuku.privileged.api | sed 's/package://')" shizuku.apk
unzip -o shizuku.apk 'assets/rish' 'assets/rish_shizuku.dex' -d extracted

# 2. 放到 Termux 私有目录（必须在私有目录，否则改不了权限）
mkdir -p ~/.shizuku
cp extracted/assets/rish extracted/assets/rish_shizuku.dex ~/.shizuku/

# 3. 填入终端应用包名
sed -i 's/"PKG"/"com.termux"/' ~/.shizuku/rish
chmod +x ~/.shizuku/rish

# 4. 关键：Android 14+ 的 app_process 拒绝加载「可写」的 dex
chmod 400 ~/.shizuku/rish_shizuku.dex
```

**第 4 步是最容易踩的坑**：如果 dex 保持可写，`rish` 会直接报
`On Android 14+, app_process cannot load writable dex`。

---

## 四、日常使用

```bash
~/dsh-termux/tools/rsh id                      # 单条命令
~/dsh-termux/tools/rsh pm list packages -3     # 任意 shell 命令
~/dsh-termux/tools/rsh                         # 交互式 shell
```

封装脚本的正源是 **`~/dsh-termux/tools/rsh`**（已随仓库版本化），
它做了两件必需的修正：合并被随机分流的 stdout/stderr，以及对 `Request timeout` 退避重试。
**全程不需要 adb、不需要无线调试、不需要 WiFi。**

本方案已固化成 DSH 技能 `android-device` 与仓库工具
（`tools/setup-shizuku-rish.sh`、`tools/rsh`、`tools/apk-install.sh`），
细节以技能内容为准。

---

## 五、局限（务必知道）

1. **重启手机后 Shizuku 不会自动恢复。** 无 root 时，重新引导 Shizuku 仍需一次 adb（无线调试）。
   也就是说：**每次重启需要一次「无线调试引导」，之后又可以不依赖 WiFi。**
   若手机已 root，Shizuku 可开机自启，那就彻底不需要 adb 了。
2. **Shizuku 应用进程或 `shizuku_server` 被系统杀掉后需要重新引导。** MIUI 的后台管控较激进，
   建议给 Shizuku 和 Termux 都关闭电池优化（Shizuku 自己也会提示这一点）。
3. **权限仍受 shell 限制**：读不到其他应用私有数据（`/data/data/<pkg>` 依然 Permission denied），
   这与 adb shell 一致，不是缺陷。

---

## 六、与 adb 的取舍

| | adb 无线调试 | Shizuku + rish |
|---|---|---|
| 需要 WiFi | **是**（且端口随机、易断） | **否** |
| 需要常驻连接 | 是 | 否 |
| 重启后 | 重新开无线调试 | 同样需重新引导一次 |
| 能力 | shell (2000) | 完全相同 |
| 适合场景 | 一次性、复杂操作（push/pull、install） | **长期自动化、不想被 WiFi 绑架** |
