# 在 Termux / Android 上构建 APK —— 可行性实测报告

环境：Termux（Google Play 版），Android 16 / SDK 36，arm64-v8a，**无 root**（uid=10328 `untrusted_app`），
5 核 / 11 GB RAM / 35 GB 可用空间。所有结论均为本机实测，非推测。

---

## 一、结论

**可以完整构建 APK。** 无需 root、无需 Android SDK Manager；不带 Gradle 可以，带 Gradle 也可以。
实测产物 `build/apk-lab.apk`（12 765 B，v2+v3 已签名）已安装到本机并正常运行，界面显示资源与 Java 代码均正确。

可用的三条路线，**全部已实测跑通**：

| 路线 | 用途 | 状态 |
|---|---|---|
| **A. 无 Gradle 直构**（aapt2 + javac + d8 + zipalign + apksigner） | 从源码构建 | ✅ 实测通过，全流程约 5 秒 |
| **B. apktool 二次打包** | 改已有的 APK（无源码） | ✅ 实测通过，含真机验证 |
| **C. Gradle + AGP** | 真实项目 / AndroidX / Compose | ✅ 实测通过（AGP 9.4.0 + Gradle 9.6.0 + AndroidX appcompat），需 5 项手工配置 |

**唯一硬限制：资源编译的 `compileSdk` 上限是 34。**（原因见第三节）

---

## 二、工具链（全部是 Termux 原生 aarch64 包）

```bash
apt-get install -y openjdk-21 aapt2 d8 apksigner zip aidl
```

| 组件 | 来源 | 版本 |
|---|---|---|
| javac / java | `openjdk-21` | 21.0.11 |
| aapt2 | `aapt2` | 2.19（AOSP 13） |
| aapt (v1) | `aapt` | 13.0.0.6-23 |
| **zipalign** | `aapt` 包附带 | — |
| d8 / **r8** | `d8` | 3.3.20-dev |
| apksigner | `apksigner` | 0.9 |
| aidl | `aidl` | 13.0.0.6-23（**确实是 aarch64 原生，不是 x86_64**） |
| keytool | `openjdk-21` | 21.0.11 |
| zip / unzip | `zip` | 3.0 |

`android.jar` 不在 Termux 源里，需从 Google 手工取（约 62 MB）：

```
https://dl.google.com/android/repository/platform-34-ext7_r03.zip   # -> android-34/android.jar
https://dl.google.com/android/repository/platform-36_r02.zip        # -> android-36/android.jar
```

已有：`clang` / `gcc` / `cmake` / `make`（原生 aarch64）、`python3`、`node`、`adb`。`/tmp` 不可写，临时文件须用 `$TMPDIR` 或工作区。

---

## 三、关键限制：compileSdk 只能到 34

Termux 的 `aapt2` 是 AOSP 13（2.19）编译的，**读不了 API 35/36 的新版资源表**，会直接原生 abort：

```
error: failed to load include path .../android.jar
```

实测边界：

| platform jar | aapt2 2.19 | aapt v1 |
|---|---|---|
| android-33 | ✅ 可读 | — |
| android-34 | ✅ 可读 | — |
| android-35 | ❌ | — |
| android-36 | ❌（且进程 abort） | ❌ 属性全部解析不到 |

**无法通过换二进制解决**：Google 只在 Maven 发布 `linux`(x86_64) / `osx` / `windows` 三种 classifier 的 aapt2，
已实测确认**不存在 `linux-aarch64`**。SDK build-tools 里的 aapt2 同样是 x86_64 ELF，在 aarch64 上跑不起来。

**规避办法（本报告采用）**：资源链接用 API 34，Java 编译用 API 36 —— 两件事本来就是分开的。

```bash
LINK_JAR=.../android-34/android.jar   # aapt2 -I
CODE_JAR=.../android-36/android.jar   # javac / d8
```

代价：XML 里不能用 API ≥35 新增的平台属性；Java 侧可用全部 API 36 符号。

---

## 四、路线 A：无 Gradle 直构（已验证）

流水线（`build.sh`，7 步）：

```
aapt2 compile  res/ -> resources.zip
aapt2 link     resources.zip + AndroidManifest.xml -I LINK_JAR -> base.apk + 生成 R.java
javac          src/**.java + R.java -bootclasspath CODE_JAR -> .class
d8             .class -lib CODE_JAR --min-api 24 -> classes.dex
zip            把 classes.dex 塞进 base.apk
zipalign -f -p 4
apksigner sign --v1 --v2 --v3
```

**实测结果**

- 构建耗时 **约 5 秒**（冷启动，含 JDK 启动开销）
- 产物 12 765 B；`resources.arsc` 数据偏移 1228（**4 字节对齐，合规**，Android 11+ 对 targetSdk≥30 有此硬性校验）
- `apksigner verify`：v2 ✅ v3 ✅
- `aapt2 dump badging`：`package=com.example.apklab` `minSdk=24` `targetSdk=36` `launchable-activity` 正常
- **真机安装 + 启动成功**，界面正确渲染 `strings.xml` / `colors.xml` / `layout` 三处资源，并读出 `ABI: arm64-v8a / Device: 2211133C / API: 36`

两个踩过的坑：

1. `aapt2 link` 的 `-R` 是 **overlay 语义**，主资源应作为**位置参数**传入，否则报 `does not override an existing resource`。
2. `zip` 注入 dex 后必须再跑 `zipalign`（顺序：zip → zipalign → apksigner，**签名必须最后**）。

**发布版（R8）同样可用**：`r8 --release --pg-conf keep.pro --lib android-36/android.jar --min-api 24`
在本机 aarch64 上正常产出 dex —— 示例中 dex 由 2 936 B 缩到 1 660 B，`dex\n037` 魔数校验通过。
即 release 构建所需的 shrink / obfuscate 环节无阻碍。

**Kotlin 同样可行（已验证）**：`apt-get install -y kotlin`（2.4.0）后，只需把 `javac` 换成 `kotlinc`，
其余流水线完全不变（脚本见 `build-kotlin.sh`）。实测构建 **17 秒**，产物 774 KB（绝大部分是 kotlin-stdlib，
经 R8 可大幅缩减），真机运行显示 `Written in Kotlin 2.4.0 / ABI: arm64-v8a / API: 36`。

> 注意：d8 3.3.20 在读 Kotlin 2.4 的 `@Metadata` 时会刷出大量
> `Info: Unexpected error while reading ... kotlin.Metadata`。这是**无害警告**——只表示这个较老的 d8
> 无法利用 Kotlin 元数据做额外优化，编译产物与运行结果均正常（已真机验证）。

**原生库（JNI）同样可行（已验证）**：Termux 自带的 `aarch64-linux-android-clang` 能直接产出合法的
Android aarch64 共享库（`ELF64 / DYN / AArch64`，正确导出 `Java_...` 符号，仅 NEEDED `libc.so`、`libdl.so`）。
把 `.so` 以 **STORED（不压缩）** 放入 `lib/arm64-v8a/`，再走 `zipalign -f -p 4`，实测数据偏移对齐到 4096；
真机运行显示 `JNI: native aarch64 lib loaded`（脚本见 `build-native.sh`）。

> 注意：本机 `zipalign` 是随 `aapt` 包附带的旧版，**不支持 `-P 16`**。本机页大小为 4 KB，`-p 4` 足够；
> 但若目标是 16 KB 页的设备（部分 Android 15+），需要更新的 zipalign，届时得自行补工具。

---

## 五、路线 B：apktool 二次打包（已验证）

Termux 源里没有 apktool，但它是纯 Java jar，可直接跑：`apktool_3.0.3.jar`。

- **解码完全正常**（资源表解析 + baksmali 都是 Java 实现，不依赖原生工具）
- **重建需要两个 hack**：
  1. jar 内置的 `prebuilt/linux/aapt2` 是 x86_64，执行报 `unexpected e_type: 2`。
     → 用 `--aapt` 指向 Termux 原生 aapt2；但它会传 `--no-compile-sdk-metadata`（2.19 不认），
     需要一层包装脚本过滤掉该参数（`aapt2-wrapper.sh`）。
  2. apktool 自带的 framework `1.apk` 资源表太新，aapt2 同样读不了。
     → 用 `-p` 指定 framework 目录，把 **android-34 的 android.jar 直接当作 `1.apk`**。

**实测结果**：改 `strings.xml` → 重建 → 签名 → 安装 → 真机界面正确显示补丁后的文案
（`PATCHED by apktool - no Gradle, no SDK.`）。

---

## 六、路线 C：Gradle + AGP（已验证可行）

**结论：可行，而且能产出可安装的 APK**，但需要 5 项手工配置——缺任何一项都会以很难懂的报错失败。

版本组合：**Gradle 1:9.6.0**（Termux 包）+ **AGP 9.4.0** + Java 21.0.11，`compileSdk 34 / minSdk 24 / targetSdk 34`。

产物：`gradle-test/app/build/outputs/apk/debug/app-debug.apk`（872 KB），
`apksigner verify` 通过（v2 scheme, 1 signer），`aapt2 dump badging` 正常。

**带 AndroidX 的真实项目也可以**：另建一份 `androidx-test/` 工程引入 `appcompat 1.7.0`
（`AppCompatActivity` + `Theme.AppCompat.Light.DarkActionBar`），构建成功——3.25 MB、**4 个 dex**，
`androidx/appcompat` 引用确实存在于 dex 中，签名验证通过。
这说明**依赖解析、AAR 元数据校验、multi-dex 全部正常**（首次构建 1 分 21 秒，主要是下载依赖）。

实测耗时（`--no-build-cache`，多次重复取稳定值）：
热守护进程 clean **3.6 / 4.0 s**，冷构建（守护进程已停）**10.1 s**，无改动重建 **2.5 s**。
（注：若中途出现 ~2 s 的“clean”，那是 Gradle build cache 命中了 21/33 个任务，不算真冷构建。）

### 必须做的 5 件事

1. **绕过 aapt2**：`gradle.properties` 里设
   `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`
   （AGP 会提示 experimental，但可用，且 aapt2 的 daemon 模式也正常）。

2. **build-tools 必须凑齐且元数据自洽**：AGP 9.4.0 要求 build-tools ≥ 36.0.0，否则报
   `Installed Build Tools revision 36.0.0 is corrupted`。
   注意 `BuildToolInfo.isValid()` 会把 `package.xml` 的 `<revision>` 与 `source.properties` 的
   `Pkg.Revision` 做比对——只写 `36` 而另一边是 `36.0.0` 就会失败，必须写成
   `<major>36</major><minor>0</minor><micro>0</micro>`。

3. **`aidl` 有 aarch64 原生包**：`apt-get install -y aidl`（13.0.0.6-23）。
   不需要用占位脚本假装（我最初以为必须假装）。已用 `readelf` 确认它是 AArch64 ELF。

4. **platform 目录不是只放 android.jar 就够**：还需要 `source.properties`、`build.prop`、
   `package.xml`，以及 **`core-for-system-modules.jar`**（缺它 `JdkImageTransform` 会失败）。

5. **注意 Gradle 守护进程的缓存**：改完 SDK 元数据后若仍报
   `Failed to find target with hash string 'android-34'`，是守护进程缓存了旧的
   `AndroidTargetManager` —— 执行 `gradle --stop` 后即恢复。

### 一个危险的坑

配置 build-tools 时若用 `cp -r` 往一个**含符号链接**的目录里拷文件，
`cp` 会**穿透链接**直接覆盖真实文件——本次实验中 `/usr/bin/{aapt2,aapt,aidl,zipalign,apksigner,d8}`
就被 x86_64 二进制覆盖过，事后用 `apt-get install --reinstall` 才恢复。
搭软链目录时务必避免这种写法。

---

## 七、明确走不通 / 不建议的路线

- **Android SDK Manager（cmdline-tools）+ build-tools**：build-tools 里的 `aapt2` / `zipalign` / `aidl` 均为 x86_64 ELF，在 aarch64 上无法执行。平台的 `android.jar` 却可以单独手工下载使用（本报告即如此）。
- **换新版 aapt2**：Google 不发布 linux-aarch64 版本；从 AOSP 源码自编译代价极高。
- **NDK 原生构建**：NDK 的 host 工具（clang 等）同样是 x86_64。不过 Termux 自带 aarch64 clang，纯 native 库可自行交叉编译后用路线 A 打包。
- **`apktool` / `gradle` / `kotlin` 的 apt 包**：`apktool` 在源中无 candidate；`gradle`(1:9.6.0) 与 `kotlin`(2.4.0) 可装（见第四节）。

---

## 八、文件清单

| 路径 | 说明 |
|---|---|
| `build.sh` | 路线 A 的完整构建脚本（7 步，可复用） |
| `AndroidManifest.xml` / `res/` / `src/` | 示例 App 源码 |
| `sdk/platforms/android-34/android.jar` | 资源链接用（aapt2 兼容上限） |
| `sdk/platforms/android-36/android.jar` | 代码编译用 |
| `build/apk-lab.apk` | **路线 A 产物**（Java，已签名，可直接安装） |
| `build-kotlin.sh` + `kotlin-test/` | Kotlin 版构建脚本与源码 |
| `build-kt/apk-lab-kotlin.apk` | **Kotlin 版产物** |
| `build-native.sh` + `native-test/` | 带 JNI 原生库的构建脚本与 C/Java 源码 |
| `build-native/apk-lab-native.apk` | **原生库版产物** |
| `debug.keystore` | 调试签名密钥（storepass/keypass 均为 `android`） |
| `apktool-test/apktool.jar` | apktool 3.0.3 |
| `apktool-test/aapt2-wrapper.sh` | 过滤 2.19 不认参数的包装脚本 |
| `apktool-test/framework/1.apk` | 冒充 framework 的 android-34 jar |
| `apktool-test/rebuilt-signed.apk` | **路线 B 产物**（修改后重新打包并签名） |
| `gradle-test/` | **路线 C** 的 Gradle/AGP 工程（含手工搭建的 sdk/ 布局） |
| `gradle-test/app/build/outputs/apk/debug/app-debug.apk` | Gradle 产物（无第三方依赖） |
| `gradle-test/androidx-test/.../app-debug.apk` | Gradle + AndroidX appcompat 产物（3.25 MB, 4 dex） |
| `gradle-test/README.md` | Gradle/AGP 路线的完整配置说明 |
| `screen-*.png` | 真机运行截图（`apklab3`=Java、`apktool`=改包、`kotlin`=Kotlin、`native`=JNI） |
