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

---

## v1.1 新增（在实际使用后提出）

### 1. 记录里存原图 + 转换图 + 总豆数

原来只记「颜色 × 数量」，看不出这一批是做哪张图。现在每条记录是：

```
原图缩略图 | 转换后的像素图
共 N 颗豆 · 网格 W × H
（可展开）分色明细：颜色 × 数量 · 替代 XX
```

图片存进应用私有目录 `files/records/rec<id>_{src,out}.png`（PNG，原图另存 720px
缩略图），记录里只留路径 —— **不能把 base64 塞进 JSON**，否则 SharedPreferences 会被撑爆。

> 实际拼豆时是「对着原图看效果、对着像素图数格子」，两张都要。

### 2. 转换图可缩放 + 点按取色

- **双指缩放**（1~12 倍）+ 拖动平移
- **点某一格**显示「第 X 列 Y 行：颜色名」，透明格显示「透明（不放豆）」

坐标换算的关键：图像用 `.size(w, h)` 精确铺满，**不用 `ContentScale.Fit`** ——
Fit 会留黑边，点按坐标就对不上了。

### 3. 支持 PNG 透明区

全透明的地方**不放豆**，用 `Pixelizer.EMPTY = -1` 标记，全程显式跳过：
量化、抖动、库存分配、渲染、统计。

两个实现要点：

- **下采样必须按 alpha 加权**。透明像素的 RGB 是无意义的（常见纯黑或纯白），
  直接平均会把边缘染脏。所以颜色只累加 `alpha > 0` 的部分，并且以 alpha 为权重。
- **抖动不能往透明格里扩散误差**，否则边缘会出现脏点。

渲染时透明格画成**浅色棋盘格**，一眼能看出「这里不放豆」。
结果卡片会显示「需要 N 颗豆 · 跳过 M 个透明格」。

## v1.2 修复（用户实测反馈）

### 点按查色号完全没反应 —— 手势冲突

`detectTransformGestures` 与 `detectTapGestures` 放在**两个独立的 `pointerInput`** 里时，
前者会吃掉指针事件，后者收不到，点按毫无反应。

**改成在同一个 `pointerInput` 里用 `awaitEachGesture` 手动区分**：
按下后循环读取事件，位移超过 touchSlop 就判定为拖拽/缩放，
否则抬手时判定为点按。这是唯一可靠的写法。

### 历史页不能缩放/查色号

原来历史页只放了静态缩略图。现在：

- **记录里增加 `indicesRle`** —— 每格调色板下标的游程编码。
  只存图片是不够的：渲染图上有网格线和透明棋盘格，没法可靠反推色号。
  游程编码对像素画压缩率很高（大片同色）。
- 点「转换后」缩略图 → **全屏对话框**，可缩放、可点格查色号
- 原图缩略图只能看（没有格点信息，查不了色号）

`ZoomablePattern` 重构成接收 `(bmp, indices, cols, rows)`，主界面与历史页共用同一份实现。

## v1.3 全屏查看器

v1.2 的缩放界面被卡片宽度限制住了（最多 460dp），实际对着屏幕数格子时看不清。

现在：**卡片里只放静态预览，点一下开全屏查看器**。

```kotlin
Dialog(
    onDismissRequest = onClose,
    // 少了这个，Dialog 会按平台默认宽度（约 90%）收窄，白费屏幕
    properties = DialogProperties(usePlatformDefaultWidth = false),
) { Surface(Modifier.fillMaxSize()) { ... } }
```

要点：

- **`usePlatformDefaultWidth = false`** —— 否则 Dialog 只占约 90% 宽，做不到真全屏
- 顶栏放标题与关闭；底栏**常驻**显示选中的格子（不再挤在图片下面）
- `statusBarsPadding()` + `navigationBarsPadding()`，内容不压到系统栏底下
- `ZoomablePattern` 重构成在**给定空间内等比适配**（宽高两个方向取小者），
  所以同一份实现既能塞进卡片、也能铺满全屏；缩放上限提到 16 倍

主界面与历史页共用同一个 `FullScreenPattern`。
