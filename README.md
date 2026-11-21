# Coding for Android

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![Size](https://img.shields.io/badge/size-%3C1MB-brightgreen)

**Ultra-lightweight (<1MB) open-source mobile development workstation for Android.**

一站式开源安卓平板开发工作台 ·「最小底座」

> 把一个完整的 Linux 开发环境装进口袋 —— 安装包不到 1MB。

---

## 这是什么

Coding for Android 是面向安卓平板的超轻量开发工作台。

设计哲学是「最小底座」：APK 只内置最核心的终端与容器管理能力，把 Linux 工具链的选择权完全交还给使用者——想写什么语言，就在容器里装什么工具。

## ✨ 特点

- **极致轻量**：APK 本体约 780KB（<1MB），零第三方依赖、无广告、无跟踪、无付费墙
- **开箱即得的 Linux 环境**：首次启动自动下载并安装 Alpine Linux 容器（约 4MB），全程无需 Root；下载失败时会弹出对话框，可手动填写自己的仓库地址（多个用英文逗号分隔，自动并发测速）与代理端口
- **双环境体系**：
  - 🐧 **假 Root（Alpine 容器）**：proot 模拟 uid 0，日常开发的主战场
  - 🔓 **真 Root（可选）**：输入 `su` 申请 Android 真 Root，进入真文件系统会话（适合系统级文件迁移），`exit` 返回
- **现代化终端**：本地行编辑（光标 / 历史 / 上下键）、扩展键栏（ESC / TAB / CTRL / ALT / 方向 / HOME / END / `|` `~` `-` `/`）
- **彩色动态提示符**（实时显示当前路径、超长自动截断）：
  - 容器内：`CfAndroid@root:/当前路径#`
  - 真 Root：`:/当前路径#`
- **硬核容灾安装逻辑**：
  - **仓库索引驱动**：rootfs 下载地址不写死在 APK 里，而是从仓库的 `index.json` 读取，换版本只需改索引、无需重新发版。
  - **单源优先 + 手动兜底**：默认走维护者仓库；失败即弹窗，用户可填入任意自建仓库地址（逗号分隔多个，自动并发测速选最快）。
  - **实时进度**：下载进度以单行原地刷新方式显示在终端，不刷屏；每 256KB 更新一次，避免拖慢界面。
  - **Ctrl+C 强制打断**：安装中途可按 `Ctrl+C` 阻断；被打断时自动清理半截文件与半解包目录，不会留下「假安装」状态。
  - **分步安装**：先装 `bash` 再装工具链，避免一次性下载大文件被运营商 QOS 掐断。
- **Web 渲染层与资源包体系（v1.0.5）**：
  - 内置 WebView 容器，可加载资源包（整站 HTML/CSS/JS）替换 Web 层界面。
  - 资源包/插件模拟 APK 格式：manifest.json 声明 + 权限模型（普通权限声明即得，高危权限默认禁止）。
  - 高危权限经终端 `pmc perm grant` 手动授权，su 恒拒——接口面写死在 App 层，资源包只能调用白名单 action。
  - 详见 [docs/RESOURCE_AND_PLUGIN_SPEC.md](docs/RESOURCE_AND_PLUGIN_SPEC.md)。
- **容器一步到位**：预装 Alpine rootfs（bash / coreutils / findutils / grep / sed / gawk / util-linux），默认离线模式，`apk-online` / `apk-offline` 切换
- **`pmc` 包管理**：资源包/插件 zip 本地与仓库安装、权限管理，容器软件包自动降级 `apk add`

## 📥 安装

从 [Releases](https://github.com/AROAN110/Coding-for-Android/releases) 下载最新 APK 并安装。

- 系统要求：Android 8.0+（API 26）
- 架构：**ARM64（aarch64）**
- 首次启动需要联网（获取 Alpine 容器，约 4MB）

## 🚀 快速上手

首次启动会自动准备容器，完成后即进入 Alpine 终端（「假 Root」环境）。

```sh
apk add python3 nodejs git vim …   # 想装什么就装什么
```

常用操作：

| 操作 | 说明 |
|------|------|
| `su` | 申请真 Root，进入真文件系统会话（`exit` 返回） |
| `pmc help` | 查看包管理指令 |
| `pmc local-load <path>` | 安装本地资源包/插件 zip（按 manifest.json 校验落库） |
| `pmc install <pkg>` | 仓库检索安装；未命中时自动降级 `apk add`（容器软件包） |
| `pmc uninstall <pkg>` | 卸载资源包/插件，同时清除授权 |
| `pmc perm grant <pkg> <perm\|all>` | 授予高危权限（逐个 / 逗号批量 / all） |
| `pmc perm revoke <pkg> <perm>` | 撤销高危权限 |
| `pmc perm list [<pkg>]` | 查看权限声明与授权状态 |
| `pmc list` | 查看资源仓库索引 |
| `pmc repo set <url>` | 指定你的资源仓库地址（不设默认源） |
| `pmc status` | 查看运行状态（环境 / 会话 / 容器） |
| `Ctrl+C` | 打断当前安装，弹出输入框手动提供可用镜像源 |
| 扩展键栏 | ESC / TAB / CTRL / ALT / 方向键 / HOME / END 等 |
### 🔌 资源包与插件
资源包（整站 HTML 界面）与插件（JS 增强）均以 `.zip` 分发，格式规范与权限模型见
[docs/RESOURCE_AND_PLUGIN_SPEC.md](docs/RESOURCE_AND_PLUGIN_SPEC.md)。

要点：
- zip 根目录必须有 `manifest.json`（type / id / entry / minAppVersion / permissions）；
- 权限分级：普通权限声明即得；高危权限（terminal.write / shell）默认禁止，需 `pmc perm grant` 授权；`su` 恒拒；
- 资源包只能调用 App 层写死的 action 白名单（fs / terminal / workspace / network），越界即报「资源包错误」；
- 安装：`pmc local-load /sdcard/Download/xxx.zip`，悬浮球菜单加载资源包并勾选插件。

### 🏗 自建镜像仓库（可选）

容器镜像不写死在 APK 里，而是由静态仓库提供。自建一个目录，放两个东西即可：

**1. `index.json`** —— 仓库索引：

```json
{
  "version": 1,
  "updated": "2026-11-14",
  "rootfs": [
    {
      "name": "alpine-minirootfs-3.24.2-aarch64.tar.gz",
      "version": "3.24.2",
      "arch": "aarch64",
      "size": 4194304
    }
  ]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `name` | ✅ | rootfs 文件名，与 tar.gz 实际文件名一致 |
| `size` | 建议 | 字节数。填写后下载完会校验，偏差超过 ±20% 视为损坏并重试；留空则只做最低限度检查 |
| `version` / `arch` | — | 仅作说明用途，程序不解析 |

**2. `alpine-minirootfs-*.tar.gz`** —— 与 `name` 同名的镜像文件，放在同一目录。

**约定**：`rootfs` 数组的**第一项**视为最新版。追加新版本时在最前面插入一项，旧版本往后排即可，App 永远取第一个。

仓库托管在任意静态空间都行（GitHub 仓库、对象存储、自建 Web 服务器）。安装失败时把仓库根地址填进弹窗即可生效。

### 🛠 从源码构建

本工程不依赖 Gradle 即可构建（手动工具链：kotlinc + d8 + aapt2 + apksigner）。
详见 [tools/BUILD.md](tools/BUILD.md)。

## ❓ FAQ

- **需要 Root 吗？** —— 不需要。全部核心功能均在免 Root 下工作，`su` 仅为可选的进阶通道。
- **需要联网吗？** —— 仅首次安装容器、以及安装新软件包时需要。
- **支持什么设备？** —— Android 8.0+ 的 ARM64 设备（当前容器资源为 aarch64 构建）。
- **为什么能做到这么小？** —— 编译器、SDK 等一概不打包，按需在容器内自装；APK 本体只保留终端与容器管理。
- **安装时报错 `exit=4` 或者卡住怎么办？** —— 这通常是网络到不了默认仓库。按下 `Ctrl+C` 打断，在弹出的对话框里填写一个可达的仓库地址（见上文「自建镜像仓库」）；如果本机有代理，可同时在代理端口一栏填入 `127.0.0.1:7890` 之类。

## 🤝 贡献

欢迎通过 Issues 反馈问题，或通过 Pull Requests 贡献代码。

- 保持「最小底座」哲学：不打包臃肿工具链，一切按需。
- 本项目采用 GPL-3.0 许可；提交贡献即表示同意以相同许可证分发。

## 🙏 鸣谢

本项目的诞生离不开这些优秀的开源项目与社区：

- **Termux** —— 安卓终端模拟与 proot 容器方案的先行者；本项目的终端交互模型与 proot 打包思路均受其启发。
- **AndroidIDE** —— 安卓端 IDE 的探路者，本项目的设计参考之一。
- **Alpine Linux** —— 本项目的容器底座：小巧、纯粹、恰到好处。
- **PRoot** —— 用户态 chroot 实现，让免 Root 的「假 Root」环境成为可能。
- **Kotlin** —— 本项目的开发语言。
- 以及所有为开源生态做出贡献的开发者们。

## 📄 许可证

本项目采用 GPL-3.0 许可，详见 [LICENSE](LICENSE)。

---

作者：AROAN110