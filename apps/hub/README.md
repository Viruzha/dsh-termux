# Viruzha 工具箱 —— 唤醒 + DSH + 打卡

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
              开机广播（BOOT_COMPLETED）自动拉起
```

五个 Tab 共用同一个界面框架（`Screen` 基类 + Tab 壳），新增功能只需
写一个 `Screen` 子类并在 `MainActivity` 注册区加一行。

## 构建

运行时（node + rg + 11 个 .so）在仓库里**只保留一份**，位于 `../../runtime/`。
`build.sh` 会自动把它暂存到 `assets/runtime/` 再打包，所以仓库里不会存两份 95MB。

```bash
cd apps/hub && ./build.sh      # SDK 可用 ANDROID_SDK 覆盖
```

## 打卡功能的三个设计要点

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

### 3. 自启动只用官方广播，不用设备管理员/无障碍

- **`BOOT_COMPLETED`** ✅ 官方正道，已实现（含小米/OPPO/vivo 的 `QUICKBOOT_POWERON`）
- **设备管理员** ❌ 那是企业 MDM 用的（远程锁定/擦除/密码策略），与自启无关，
  还会让卸载必须先取消激活
- **无障碍服务** ⚠️ 确实极难被杀，但要手动开、弹安全警告，用它保活属于滥用

> **MIUI / EMUI 还需用户手动开「自启动」白名单**，否则系统会直接拦掉开机广播。
> 这一步任何 App 都绕不过去，界面里已写明路径。

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
- `lib/*.so` **10 个**（node 的**递归**依赖闭包，含 31.6MB 的 `libicudata.so.78`）
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

## 构建与部署

```bash
./build.sh        # 需 ~/.wol-token（唤醒 token），产出 build/hub.apk
```

安装与投喂 DSH 本体（**用 adb，别用 base64 通道**）：

```bash
adb install -r build/hub.apk
adb push dsh-bundle.zip /data/local/tmp/
adb shell 'cat /data/local/tmp/dsh-bundle.zip | \
  run-as site.viruzha.hub sh -c "cat > /data/data/site.viruzha.hub/files/dsh-bundle.zip"'
```

## 沿用的两个坑

- **不用匿名内部类**：d8 3.3.20 处理 JDK 21 编译的匿名类会 NPE
- **构建脚本不能吞 javac 错误**：`javac ... | grep ... || true` 会静默产出缺类的
  坏包，现已改为失败即中止

## 待办

- DSH 服务随 Activity 存活，应改为前台服务（切后台/息屏不被杀）
- 首次运行引导与进度
- 体积：DSH 本体改为首次运行下载
- node 里仍编译了 Termux 的 `bin/bash`、`sh`、`/tmp` 路径（child_process 用），未验证影响
