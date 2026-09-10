# 换手机迁移手册

## 一、老手机：打包

```bash
cd ~/dsh-termux
./bootstrap.sh bundle                    # 脚本 + 资产 + 配置（推荐）
./bootstrap.sh bundle --with-secrets     # 额外带上 API 凭据（含密钥！）
./bootstrap.sh bundle --with-sessions    # 额外带上会话记录与附件
```

产出：`~/dsh-termux-bundle.tar.zst`（约 1–2 MB，不含会话/密钥时）。

## 二、新手机：装 Termux（关键）

**必须从 F-Droid 或 GitHub 安装 Termux，不要用 Google Play 版。**
Play 版是残缺构建：addon（Termux:API 等）只有 stub，很多能力拿不到。

- F-Droid: https://f-droid.org/packages/com.termux/
- GitHub: https://github.com/termux/termux-app/releases

装完后打开 Termux，先给存储权限：

```bash
termux-setup-storage
```

## 三、新手机：一键恢复

### 方式 A（推荐）：从 GitHub 克隆

```bash
pkg install -y git
# 先配好 GitHub SSH（见第六节），然后：
git clone git@github.com:Viruzha/dsh-termux.git
cd dsh-termux && ./bootstrap.sh
```

好处：不用手动传文件，且随时 `git pull` 拿到最新脚本与资产。

### 方式 B：离线 tar 包

把 `dsh-termux-bundle.tar.zst` 传到新手机后：

把 `dsh-termux-bundle.tar.zst` 放到新手机（下载目录即可），然后：

```bash
cd ~
tar --zstd -xf storage/downloads/dsh-termux-bundle.tar.zst
cd dsh-termux
./bootstrap.sh                 # 一键：装包 → pnpm → dsh → 原生资产 → 补丁 → 配置
./bootstrap.sh --start         # 想顺手启动就加这个
```

`bootstrap.sh` 每一步都会先自检：已满足的跳过，缺失的补齐，最后给总检报告。

### 恢复会话（可选，若打过 --with-sessions）

```bash
cp -a ~/dsh-termux/data/sessions    ~/.dsh/ 2>/dev/null
cp -a ~/dsh-termux/data/attachments ~/.dsh/ 2>/dev/null
```

### 恢复凭据（可选，若打过 --with-secrets）

```bash
cp ~/dsh-termux/config/credentials.yaml ~/.dsh/.credentials.yaml
chmod 600 ~/.dsh/.credentials.yaml
rm -rf ~/dsh-termux/config/credentials.yaml     # 用完即删
```

没带凭据的话，启动后在 GUI 里重新填 API Key 即可。

## 四、adb 无线调试（可选，给了就是 shell 权限）

新手机需要重新配对（这是 Android 的安全设计，无法自动跳过）：

1. 设置 → 我的设备 → 全部参数与信息 → 连点「OS 版本」7 次，开启开发者选项
2. 设置 → 更多设置 → 开发者选项 → 打开「**无线调试**」
3. 点「使用配对码配对设备」，屏幕上会显示 **配对端口** 和 **6 位配对码**
   （注意：配对端口 ≠ 主页显示的连接端口，两个是不同的）
4. 在 Termux 里：

```bash
~/dsh-termux/tools/adb-connect-self.sh --pair <配对端口> <配对码>
~/dsh-termux/tools/adb-connect-self.sh          # 之后日常重连
```

配对完成后就能做很多事，例如打开 App：

```bash
~/dsh-termux/tools/open-app.sh 微信
~/dsh-termux/tools/open-app.sh --current
```

配对码有时效，且屏幕要保持亮着。重启手机后无线调试通常关闭，重开后再跑一次
`adb-connect-self.sh` 即可（一般无需重新配对）。

## 五、技能（自动安装）

捆绑包里的 `skills/` 会被 `bootstrap.sh` 装到 `~/.dsh/skills/`，无需手工操作：

```bash
ls ~/.dsh/skills/                     # 应看到 android-device
```

技能是热发现的，装好后新会话立刻可用（当前会话也会收到技能目录）。
自建技能放进 `~/.dsh/skills/<name>/SKILL.md` 即可，记得同步回 `~/dsh-termux/skills/` 以便下次迁移。

## 六、GitHub / git 配置（换手机后需重做一次）

SSH 私钥属于密钥，**默认不随迁移包走**。新手机上一条命令重建：

```bash
~/dsh-termux/tools/setup-github.sh <GitHub用户名> <邮箱> --open
```

脚本会：生成新的 ed25519 密钥 → 写好 `~/.ssh/config` → 配好 git 基础项与提交身份 →
打印公钥并打开 GitHub 添加密钥页面。把公钥贴到 GitHub 后验证：

```bash
~/dsh-termux/tools/setup-github.sh --verify
# 期望： Hi <用户名>! You've successfully authenticated...
```

该脚本幂等，可反复跑；只补未设置的配置，不覆盖你的选择。
想连私钥一起迁移，可在老手机上 `./bootstrap.sh bundle --with-secrets`（会一并打包
`~/.dsh/.credentials.yaml` 与 SSH 私钥，注意妥善传输并事后轮换）。

## 七、核对

```bash
./bootstrap.sh check            # 18 项体检
~/dsh-web.sh status             # 服务状态
```

全绿即为恢复完成。

## 八、故障排除

| 现象 | 处理 |
|---|---|
| `pkg install` 卡住/失败 | 检查网络；`pkg update` 后重试 |
| pnpm 报 “does not provide a pre-built binary for android” | pnpm 装成了 11+；`npm i -g pnpm@10` |
| 读图报 `EACCES: open '/data/data'` | 附件补丁没生效：`~/dsh-web.sh patch-attach` 后 `restart` |
| 会话写不进去 | `~/dsh-web.sh patch` 后 `restart` |
| `glob`/`grep` 失效 | `~/dsh-termux/patches/ripgrep-android.sh` 后 `restart` |
| dsh 升级后一堆功能坏掉 | 重跑 `./bootstrap.sh`（它会补齐资产与补丁），再 `~/dsh-web.sh restart` |

> 每次 `npm i -g @deepseek-ai/dsh` 之后都要重跑一次 `./bootstrap.sh`：
> npm 会重建 `node_modules`，把原生产物、垫片和补丁全部抹掉。
