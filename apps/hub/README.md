# Viruzha 工具箱 —— 唤醒 + 打卡 + DSH 一体化 App

一个 App 里装了三件事，**都不依赖 Termux、也不需要用户额外安装任何东西**：

| Tab | 功能 |
|---|---|
| **唤醒** | 通过 Tailscale Funnel 公网入口远程唤醒家里电脑（手机端不需要开 VPN） |
| **设备** | 设备列表与在线检测 |
| **打卡** | **悬浮按钮常驻最上层**，点一下记录时间；每天凌晨 5 点刷新；可拖动 |
| **DSH** | **App 内嵌完整 node 运行时**，本地起 DSH web，前端直接显示在本页 WebView |
| **设置** | 唤醒服务地址 / token / 设备信息 / 关于 |

## 架构

```
唤醒：App --HTTPS--> Tailscale Funnel --> wol-api(viruzha) --> wakeonlan
DSH ：App --> 私有目录里的 node --> dsh web --port 13080 --> 本页 WebView
                    ↑
              assets/runtime（95MB，构建时从仓库 runtime/ 暂存）
打卡：前台服务 --> TYPE_APPLICATION_OVERLAY 悬浮窗 --> SharedPreferences
                    ↑
              亮屏/解锁 + 凌晨 5 点闹钟（重算状态）
```

五个 Tab 共用同一个界面框架（`Screen` 基类 + Tab 壳），新增功能只需
写一个 `Screen` 子类并在 `MainActivity` 注册区加一行。

## 已知问题

### DSH 本体（`dsh-bundle.zip`，54MB）不在 APK 里，App 无法自行恢复

**症状**：DSH 页点「启动」报「未找到 DSH 本体，请把 dsh-bundle.zip 放到 …」。

**起因**：`pm clear`（或系统设置里的「清除数据」）会清空整个应用数据目录，
连同之前投喂进去的 `dsh-bundle.zip` 一起删掉。

**暴露的三个设计缺口**：

1. **App 没有任何办法自己把包找回来** —— 它依赖一个外部放置的 54MB 文件，
   而 APK 里故意没打包（为了控制 35MB 体积）。
   这直接违背了「发给另一个人，他装上就能用」的原始目标。
2. **报错信息是给开发者看的** —— 提示里给的 `/data/data/<pkg>/files` 路径
   普通用户根本进不去。
3. **检查顺序浪费** —— `DshScreen.BootTask` 是「先解压 95MB 运行时 → 验证 node
   → 才检查压缩包」，包不在时已经白解压了 95MB（又慢又费电）。

**⚠️ 另外**：`pm clear` 会**一起清掉打卡记录**，而且没有导出功能。

**待定的修法**（尚未实施）：

| 方案 | 说明 |
|---|---|
| 让 App 自己下载 | 把包放到 viruzha，首次启动自动下载（带进度）。真正实现「装了就能用」 |
| 加「导入压缩包」按钮 | 从手机存储选 zip 导入。不需服务器，但对方仍得先拿到文件 |
| 两者都做 | 优先下载，失败则手动导入 |

上面第 3 条（调整检查顺序）是零风险的顺手修，也**尚未实施**。

## 关键约束（踩出来的，别改）

### `targetSdkVersion` 必须保持 28

Android 禁止 targetSdk ≥ 29 的 App 执行自身数据目录中的文件
（`error=13, Permission denied`）。降到 28 后进程进入 SELinux 的
`untrusted_app_27` 兼容域，即可正常 exec 内置的 node。

**改高就会导致 DSH 页无法启动。**

### DSH 必须用固定端口 + 显式 trusted-host

DSH 有 `/api` 浏览器信任栅栏。用 `--port 0` 随机端口会导致**前端白屏**：

```
web --no-open --port 13080 --trusted-host 127.0.0.1:13080
```

### 运行时清单（`assets/runtime`，95MB）

- `bin/node` v26.3.1、`bin/rg` 15.1.0
- `lib/*.so` **11 个**（node 的**递归**依赖闭包，含 31.6MB 的 `libicudata.so.78`）
- `etc/openssl.cnf`

必须设置的环境变量：

| 变量 | 原因 |
|---|---|
| `LD_LIBRARY_PATH` | node 的 `DT_RUNPATH` 指向 Termux 前缀，其搜索顺序在 `LD_LIBRARY_PATH` 之后 |
| `OPENSSL_CONF` | node 把 OpenSSL 配置路径**编译**成了 Termux 路径，不覆盖则启动即失败 |
| `TMPDIR` | 必须真实存在，否则 DSH 的 spill 存储 `mkdtemp` 报 ENOENT |
| `HOME` | 指向应用私有目录，DSH 才会把 `.dsh` 写进去 |

### DSH 本体不打包进 APK

APK 已含 95MB 运行时（→ 35MB）。DSH 本体另作 54MB 的 zip，
放在应用私有目录 `files/dsh-bundle.zip`，首次启动时解压（约 4 秒 / 25350 个文件）。

> **必须包含 `@img/sharp-wasm32`** —— 它在 Termux 的**全局** node_modules 里，
> 不在 dsh 自己目录内，漏了会在启动时报 `Could not load the "sharp" module`。

打包命令（在 dsh 目录内执行，让路径形如 `lib/bin.js`）：

```bash
cd $PREFIX/lib/node_modules/@deepseek-ai/dsh && zip -r -q ~/dsh-bundle.zip .
# 另需把 $PREFIX/lib/node_modules/@img/sharp-wasm32 放到 node_modules/@img/ 下
```

## 打卡功能的设计要点

### 1. 「一天」的边界用日历字段算，不能用 epoch 除法

边界是**凌晨 5:00**：把时间回挪 5 小时再取日期，熬夜到凌晨 2 点仍算前一天。

```java
// 对：用 Calendar 定位 5 点
c.set(Calendar.HOUR_OF_DAY, 5); ...
// 错：epoch 毫秒 / 86400000 是 UTC 午夜，东八区相当于本地 08:00，偏 8 小时
```

判定是**纯计算**的，不依赖定时器 —— App 没运行、手机重启过都不影响正确性。

### 2. 「用户想开着」与「服务在跑」必须分开

| 标志 | 含义 | 何时变 |
|---|---|---|
| `enabled` | 用户希望悬浮按钮开着 | 只有点「关闭悬浮按钮」才置 false |
| `running` | 服务此刻在不在跑 | 随进程生死 |

开机广播靠 `enabled` 判断要不要拉起。如果只看「服务在不在跑」，
被系统回收后就永远不会自愈了。

### 3. 配色：未打卡必须最响

最初把「未打卡」做成深灰底、把「已打卡」做成亮绿底，**等于把主次搞反了** ——
这个悬浮框存在的意义就是提醒你打卡，需要你注意的那个状态反而最低调。

现在：**未打卡 = 饱和红底 + 白字**（对比度最高，余光就能扫到）；
**已打卡 = 绿底 + 深色字**（表示完成，不必抢注意力）。

> **语义闭环**：这是**早卡提醒**，不是全天候警报。红色只在你还没打卡时出现，
> 打完卡就消失 —— 所以「红色消失」本身就是正反馈。
> 真要是红了一整天，那说明当天确实忘了打卡。

### 4. 跨天必须有人「主动重算」

**踩过的坑**：`PunchBubble.refresh()` 不是自更新的，只在被调用时才重新读数据。
判定逻辑（`now - 5小时` 取日期）一直是对的，但**过了凌晨 5 点没有任何东西去调它**，
于是悬浮框一直停在旧时间上。这比"按午夜刷新"更糟 —— 它压根没有刷新。

现在有三个触发源：

| 触发源 | 作用 | 说明 |
|---|---|---|
| **亮屏 / 解锁** | 主要保障 | 动态注册 `SCREEN_ON`+`USER_PRESENT`。设备醒来正好是用户看手机的时机 |
| **5:00 的闹钟** | 兜底 | `AlarmManager` + `RTC`（**不是** `RTC_WAKEUP`，不为改颜色唤醒设备）；用 `set()` 不需要特殊权限，代价是投递窗口 ±1h |
| **应用更新** | 相关缺陷 | 加 `MY_PACKAGE_REPLACED`。原来每次装新版进程被杀、悬浮窗消失，得手动再开 |

> 打卡页的倒计时**只**在 App 前台停在那一页时运行，退到后台就停。

**开销实测**：10 分钟内 6 次刷新（含解锁时 `SCREEN_ON`+`USER_PRESENT` 各一次）。
每次约 1~3ms，一天按 400 次算不到 1 秒 CPU。无轮询、无唤醒锁（`Wake Locks: size=0`）。

### 5. 打卡可以撤销（点错了要能改）

- **今天打错了**：今天卡片里直接有「撤销今天打卡」按钮（已打卡时才出现）
- **往日记错了**：历史列表**点任意一条**即可删除
- **所有删除都弹确认框**（`AlertDialog`），避免误删

删除后调 `PunchService.refreshNow()` 让悬浮框**立刻**改回「未打卡」——
否则界面改了、悬浮框还显示旧时间，看起来像没删掉。

> 实现注意：`AlertDialog` 的按钮回调是 `DialogInterface.OnClickListener`，
> **不能写匿名内部类**（d8 3.3.20 处理匿名类会 NPE），所以用了具名的
> `DeleteConfirm` / `RowClick` 两个类。

### 6. 自启动只用官方广播，不用设备管理员/无障碍

- **`BOOT_COMPLETED`** ✅ 官方正道，已实现（含小米/OPPO/vivo 的 `QUICKBOOT_POWERON`）
- **设备管理员** ❌ 那是企业 MDM 用的（远程锁定/擦除/密码策略），与自启无关，
  还会让卸载必须先取消激活
- **无障碍服务** ⚠️ 确实极难被杀，但要手动开、弹安全警告，用它保活属于滥用

> **MIUI / EMUI 还需用户手动开「自启动」白名单**，否则系统会直接拦掉开机广播。
> 这一步任何 App 都绕不过去，界面里已写明路径。

## 构建与部署

运行时（node + rg + 11 个 .so）在仓库里**只保留一份**，位于 `../../runtime/`。
`build.sh` 会自动把它暂存到 `assets/runtime/` 再打包，所以仓库里不会存两份 95MB。

```bash
cd apps/hub && ./build.sh      # SDK 可用 ANDROID_SDK 覆盖
```

安装与投喂 DSH 本体（把 zip 放进应用私有目录）：

```bash
adb install -r build/hub.apk
adb push dsh-bundle.zip /data/local/tmp/
adb shell 'cat /data/local/tmp/dsh-bundle.zip | \
  run-as site.viruzha.hub sh -c "cat > /data/data/site.viruzha.hub/files/dsh-bundle.zip"'
```

> 没有 adb 时，`/sdcard` 中转为：shell（能读 `/sdcard`）→ `run-as`（能写私有目录）。
> **不要用 base64 通道传几十 MB 的文件**，实测慢到不可用。

## 沿用的两个坑

- **不用匿名内部类**：d8 3.3.20 处理 JDK 21 编译的匿名类会 NPE
- **构建脚本不能吞 javac 错误**：`javac ... | grep ... || true` 会静默产出缺类的
  坏包，现已改为失败即中止

## 待办

- DSH 服务随 Activity 存活，应改为前台服务（切后台/息屏不被杀）
- 首次运行引导与进度
- **DSH 本体拿不到的问题**（见上方「已知问题」）
- 打卡记录没有导出功能，`pm clear` 会全丢
- WebView 延迟初始化（不开 DSH 可省下约 58MB 里的 WebView 部分）
- node 里仍编译了 Termux 的 `bin/bash`、`sh`、`/tmp` 路径（child_process 用），未验证影响
