# 拼豆工坊 —— 图片转像素画 + 库存联动

把图片转成拼豆像素图，按实际库存分配颜色；某色不够时自动找相近替代色。

**这是一个独立的 App**（`com.example.bead`），不走 hub 那条无 Gradle 流水线 ——
因为它要 Kotlin + Jetpack Compose，而 Compose 编译器与注解处理器都是 Gradle 插件。

## 构建

```bash
cd apps/pingdou
# local.properties 里的 sdk.dir 必须指向**完整**的 SDK 骨架（见坑 5）
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk（17 MB，7 个 dex）
```

## 版本矩阵（已实测跑通）

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | `1:9.6.0` | Termux 包，系统级 |
| AGP | `9.4.0` | 随 Gradle 9.6 可用 |
| Kotlin | `2.2.10` | **由 AGP 内置**，不手动应用插件 |
| Compose 编译器 | `org.jetbrains.kotlin.plugin.compose` 2.2.10 | 与 Kotlin 同版本 |
| Compose BOM | `2024.09.02` | **不能更高**，见坑 3 |
| compileSdk / minSdk / targetSdk | 34 / 24 / 34 | 资源编译上限 34 |
| aapt2 / aidl | Termux aarch64，经 `android.aapt2FromMavenOverride` 指定 | |

## 踩过的五个坑

### 1. AGP 9 内置 Kotlin，不能再手动应用 `org.jetbrains.kotlin.android`

```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
> Cannot add extension with name 'kotlin', as there is an extension already registered with that name.
```

AGP 9 自己注册了 `kotlin` 扩展。**把 `org.jetbrains.kotlin.android` 从两个
build.gradle.kts 里删掉即可**，AGP 会自动编译 `.kt`。

### 2. KSP 与 AGP 内置 Kotlin 冲突 → 干脆去掉 Room

```
Task :app:kspDebugKotlin FAILED
> unexpected jvm signature V
```

KSP 通过 `kotlin.sourceSets` 注入生成源码，而内置 Kotlin 默认禁止
（可用 `android.disallowKotlinSourceSets=false` 绕过，但随后就是上面那个签名错误）。

**解法是换掉 Room**：原方案的 Room 只用在 `BeadRepository` 背后，
存储需求很轻（一份颜色库存 + 一条用量日志）。改成 `BeadStore`
（内存 `StateFlow` + JSON 持久化），接口与原 DAO 等价，
**`BeadRepository` 与所有 UI/ViewModel 一行都没改**，顺带省掉一整个注解处理器。

### 3. Compose 1.9+ 要求 compileSdk 35，而 aapt2 上限是 34

```
Dependency 'androidx.compose.ui:ui-android:1.9.1' requires ...
to compile against version 35 or later
:app is currently compiled against android-34
```

Termux 的 aapt2（2.19 / AOSP 13）**读不了 API 35/36 的资源表** —— 这是硬限制。
所以 Compose BOM **必须用 `2024.09.02`**（Compose 1.7.x）。
原 plan.md 写的正是这个版本，是正确选择。

### 4. plan.md 的代码有两处小问题

| 位置 | 问题 | 修法 |
|---|---|---|
| `Screens.kt:178` | `Image(...)` 未导入 | 补 `import androidx.compose.foundation.Image` |
| `Screens.kt:464` | 全限定 `clickable(...)` 在新签名下解析失败 | 补 import，改用 `Modifier.clickable(onClick = ...)` |
| `Screens.kt:466` | `rememberRipple()` 已废弃且是 error 级 | 同上，现代重载会自动取主题涟漪 |

### 5. SDK 骨架必须用**完整**的那一份

`gradle-test/sdk` 在**工作区**里是完整的（aapt/aapt2/aidl/zipalign/apksigner/d8
都是指向 Termux 原生二进制的符号链接），但**仓库里那份只剩
`package.xml` 和 `source.properties`** —— 符号链接没被提交进去。

指向仓库那份会报：

```
Build-tool 36.0.0 is missing AAPT at .../sdk/build-tools/36.0.0/aapt
Installed Build Tools revision 36.0.0 is corrupted.
```

**`local.properties` 要指到工作区那份。**

## 另外两个环境问题

**Gradle 拉依赖时 TLS 握手会被掐断**（`Remote host terminated the handshake`），
但 curl 和 Java 单独请求都正常（HTTP 200）。疑似本机代理干扰。
**多跑几轮即可** —— 每轮都能补下一部分，通常 5~8 轮后依赖就齐了。

**MIUI 对全新包会弹安装确认**（`AdbInstallActivity`）。
锁屏状态下 12 秒后会自动取消，报 `INSTALL_FAILED_USER_RESTRICTED`。
**解锁后在屏幕上点一下确认**即可。已装过的包（如 hub）更新时不会弹。

## 与原 plan.md 的差异

- **去掉 Room / KSP**（见坑 2）
- **Compose BOM 用 2024.09.02**（见坑 3）
- 补了 `settings.gradle.kts` / `gradle.properties` / `local.properties`
  —— 原 plan.md 缺这三样，缺任何一样都构建不起来
- 修了两处 import 与一处废弃 API（见坑 4）

算法部分（`domain/Pixelizer.kt`）**原样保留**，未做改动：
面积平均下采样、redmean 加权距离、Floyd–Steinberg 抖动、
按用量降序的库存分配与相近色替代。
