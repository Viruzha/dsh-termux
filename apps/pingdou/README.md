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

## v1.4 放大不再模糊 —— 改用 Canvas 实时绘制

**现象**：转换后的像素图放大后模糊。

**根因有两层**（原来的做法是把位图交给 `Image` 显示、再叠 `graphicsLayer` 缩放）：

1. `Image` 默认**双线性插值**，会把相邻像素**混成渐变** —— 而像素画要的是边界分明
2. 位图里 1 像素宽的**网格线也被一起放大**，16 倍下变成 16 像素的粗块

**改法：不用位图，按当前缩放实时绘制每一格。**

```kotlin
Canvas(modifier) { drawPattern(indices, cols, rows, scale, offset) }
```

像素画的本质就是一堆彩色方块，直接画就没有分辨率上限，放大多少倍都锐利。
而且**只绘制可见范围内的格子**（放大后绝大多数在屏幕外），所以放大反而更快。

顺带的好处：

- 网格线宽度恒为 1 物理像素，不会随缩放变粗
- 透明格的棋盘格也按当前格大小绘制
- 缩放上限提到 40 倍
- 不再需要为预览渲染高分辨率位图（省内存）

> 卡片里的小预览仍用位图（不需要缩放，位图更省事）。
> `Pixelizer.render` 保留，用于**存档**那张「转换后」图片。

## v1.5 选中格就地标记

**问题**：色号信息只写在底部一行，手指在屏幕中间、信息在屏幕底部，**对不上号**。
误触了也看不出来，对着数格子很容易数错。

**改法：在点中的那一格上直接标出来**，三层信息叠加：

1. **十字参考线**（横竖各一条，铺满画布）—— 顺着线找位置，不用一格格数
2. **双层描边**（黑在外 5px、白在内 2.5px）—— 无论底色是白豆还是黑豆都看得见
3. **贴着格子的标签** —— 「N 列 M 行 · 颜色名」，默认在格子上方，
   上方放不下就挪到下方，左右自动夹在画布内

全部用 `DrawScope` 绘制（`drawLine` / `drawRect(style = Stroke)` / `drawRoundRect` /
`drawText`），所以**标记本身也随缩放实时绘制**，不受位图分辨率限制。

底部那行保留 —— 它现在是「确认」，不是唯一的信息来源。

## v1.6 抖动出现规律性孤立色斑 —— 限制候选色

**现象**：开抖动后，平滑区域里每隔十几格蹦出一个与周围完全不同的色块
（例如一片灰色里的棕色点），非常规律，原图并没有那个颜色。

**根因**：Floyd–Steinberg 的误差会累积，把「工作值」推离原色。
当工作值飘到 (117,101,84) 这种地方时，redmean 距离对暗色的蓝色通道
加权高达 2.5 倍，于是 `棕(130,85,50)` 的得分**优于** `深灰(80,80,80)`
（4350 vs 5071）—— 本该是灰色的格子就蹦出了棕色。

而且因为误差是周期性累积的，所以**出现得很有规律**，看起来像噪点而不是渐变。

**改法：抖动时只用「与原色距离不超过 `DITHER_MAX_DIST`（78）」的候选色。**

实测（80×60 平滑渐变，真实 48 色板）：

| 方案 | 孤立色斑 | 最大单格色偏 | 平均色偏 |
|---|---|---|---|
| FS（原状） | **363** | 147 | 59 |
| **FS + 候选限制** | **0** | 103 | **52** |
| Atkinson 抖动 | 179 | 137 | 53 |

**孤立色斑归零，而且平均色偏反而更小** —— 因为误差不再浪费在够不着的颜色上。

> `DITHER_MAX_DIST` 可调：调大＝抖动更"敢"、渐变更强但更容易出突兀色块；
> 调小＝更保守。当前 78 是实测的拐点。

顺带记录一个失败尝试：**误差钳制**（限制工作值偏离原色的幅度）效果有限 ——
实测 LIM=48/32/20 时孤立色斑几乎不降，因为问题不在幅度而在「候选范围」。

## v1.7 删掉抖动开关

**用户实测反馈：关掉抖动反而更自然。** 这不是错觉，量化后完全站得住：

| 方案 | 局部混色误差 | 相邻格相异率 | 用色数 |
|---|---|---|---|
| **关抖动** | 38.7 | **3.1%** | 5 |
| FS + 候选限制 | 28.1 | 28.7% | 10 |
| Bayer 4×4 | **27.5** | **52.7%** | 6 |

（局部混色误差 = 4×4 块平均色与原色的差，越小渐变更准；
　相邻格相异率 = 噪点感，越高越花）

抖动**确实**把渐变还原得更准（38.7 → 27.5），但代价是相邻格相异率从 3% 涨到 29~53%。
对**实物拼豆**这个代价特别贵：

1. **每一次换色都是真实操作** —— Bay4×4 的 52.7% 意味着几乎每两颗豆就要换色
2. **屏幕能糊弄眼睛、实物不行** —— 屏幕像素 <1mm 且发光，容易融合；
   拼豆 2.6mm 且是实体、还有间隙，满屏交替色块看着像做错了
3. **干净的大色块本就是像素画的美感来源**

考虑到收益只有约 10 分的混色改善，而代价是数倍的换色工作量，
**结论是删掉这个选项**，而不是保留一个默认关闭、多数人用不上的开关。

删除内容：`quantizeWithDither`、`addErr`、`nearestConstrained`、
`DITHER_MAX_DIST`、`ConvertUiState.dither`、`setDither()`、界面上的开关。
量化只保留「直接取最近色」这一条路径。

## v1.8 修复：清理已用库存把历史记录一起删了

**现象**：「清理已用库存」把转换历史也清空了 —— 这是两回事。

**根因（从 plan.md 继承的）**：仓库层里

```kotlin
suspend fun resetUsed() {
    dao.resetUsed()
    dao.clearRecords()   // ← 误删
}
```

原方案就有 `resetUsed() → dao.clearLog()`。**那时"日志"是每色一行的用量明细、
和 `used` 计数器绑定**，一起清还说得通。但 v1.1 把日志改成**带图片的转换记录**
之后，语义已经变成独立历史了 —— 我照搬了那行，没有重新审视。

`initAll()`（批量设置库存）有同样的问题。

**修法**：两个方法都只动库存，不碰历史。

**顺带补上独立的清空入口**：`clearRecords()` 原来**没接到任何界面**，
修完就没地方清历史了。现在记录页顶部有「清空全部」（带确认，明确写"库存不受影响"）。

> 教训：**重构数据结构时要回头审一遍所有操作它的旧代码**。
> 类型换了、语义变了，但那一行 `clearLog()` 看起来还是"合理的"。
