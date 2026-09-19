# 回滚点

升级前状态（2026-09-10）：

- dsh 版本：`0.1.0-rc.6`
- 安装路径：`/data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/dsh`
- 备份归档：`dsh-0.1.0-rc.6-backup.tar.zst`（升级前打包，含 node_modules 与 ripgrep 垫片）
- profile 备份：`profiles-web-backup.tar.zst`（`~/.dsh/profiles/web` 的配置与依赖锚定）

## 回滚方式

```bash
# 方式一：用备份归档覆盖回去（最稳，保留 profile 原状）
tar --zstd -xf dsh-0.1.0-rc.6-backup.tar.zst -C /data/data/com.termux/files/usr/lib/node_modules/@deepseek-ai/

# 方式二：从仓库重装指定版本
npm install -g @deepseek-ai/dsh@0.1.0-rc.6
```

回滚后必须重启 dsh 进程，并重跑 `fix-dsh-termux-ripgrep.sh`。

## 已知副作用

升级会重建 `dsh/node_modules`，抹掉 ripgrep 垫片 → `glob`/`grep` 失效，需重跑垫片脚本。
