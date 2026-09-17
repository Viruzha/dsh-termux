# runtime —— 自包含 DSH App 所需的 Android 运行时

这里放的是 **npm 与 Gradle 都给不了**的 Android aarch64 原生件。

| 文件 | 版本 | 说明 |
|---|---|---|
| `bin/node` | v26.3.1 | Node.js（aarch64） |
| `bin/rg` | 15.1.0 | ripgrep，DSH 的 glob/grep 依赖 |
| `lib/*.so` | — | node 的全部非系统依赖，共 9 个 |

## 为什么它能脱离 Termux 运行（已实测）

`node` 与 Termux 前缀的耦合**只有一处**：`DT_RUNPATH=/data/data/com.termux/files/usr/lib`。
而 `DT_RUNPATH` 的搜索顺序**在 `LD_LIBRARY_PATH` 之后**，所以把后者指向本目录的 `lib/` 即可：

```bash
LD_LIBRARY_PATH="$PWD/runtime/lib" ./runtime/bin/node -e 'console.log(process.version)'
# → v26.3.1
```

进一步用 `patchelf --remove-rpath` 删掉 RUNPATH 后同样正常运行，证明它**不依赖任何 Termux 路径**，
因此可以放进任意应用的数据目录。

## 用途

给「自包含 DSH App」用：随 App 分发本目录 → 首次启动解压到应用私有目录 →
以 `LD_LIBRARY_PATH=<解压目录>/lib` 启动 `node`，再运行 DSH 本体。

## 注意

- **仅 aarch64（android-arm64）**，其他 ABI 需另行获取。
- `libc.so` / `libm.so` / `libdl.so` 由 Android 系统提供，**不需要**打包。
- DSH 的两个原生模块 `pty.node` / `koffi.node` 在仓库根 `assets/` 下，不在本目录。
- 升级 Termux 的 `nodejs` / `ripgrep` 包后，需同步更新本目录并刷新 `MANIFEST` 里的校验值。
