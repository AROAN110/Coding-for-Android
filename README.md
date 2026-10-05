# Coding for Android

![Platform](https://img.shields.io/badge/platform-Android-3DDC84)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)
![Size](https://img.shields.io/badge/size-%3C1MB-brightgreen)

**Ultra-lightweight (&lt;1MB) open-source mobile development workstation for Android.**

一站式开源安卓平板开发工作台 ·「最小底座」

> 把一个完整的 Linux 开发环境装进口袋 —— 安装包不到 1MB。

---

## 这是什么

Coding for Android 是面向安卓平板的超轻量开发工作台。

设计哲学是「最小底座」：APK 只内置最核心的终端与容器管理能力，把 Linux 工具链的选择权完全交还给使用者——想写什么语言，就在容器里装什么工具。

## ✨ 特点

- **极致轻量**：APK 本体约 780KB（&lt;1MB），零第三方依赖、无广告、无跟踪、无付费墙
- **开箱即得的 Linux 环境**：首次启动自动下载并安装 Alpine Linux 容器（约 4MB，多个镜像源自动回退），全程无需 Root
- **双环境体系**：
  - 🐧 **假 Root（Alpine 容器）**：proot 模拟 uid 0，日常开发的主战场
  - 🔓 **真 Root（可选）**：输入 `su` 申请 Android 真 Root，进入真文件系统会话（适合系统级文件迁移），`exit` 返回
- **现代化终端**：本地行编辑（光标 / 历史 / 上下键）、扩展键栏（ESC / TAB / CTRL / ALT / 方向 / HOME / END / `|` `~` `-` `/`）
- **彩色动态提示符**（实时显示当前路径、超长自动截断）：
  - 容器内：`CfAndroid@root:/当前路径#`
  - 真 Root：`:/当前路径#`
- **容器一步到位**：自动安装 bash / coreutils / findutils / grep / sed / gawk，默认 shell 切换为 bash
- **`pmc` 包管理**：插件仓库检索与一键安装，未命中时自动降级 `apk add`
- **插件骨架**：可拖拽悬浮球，规划中的 `.zip` 插件体系（见下文规范）

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
| --- | --- |
| `su` | 申请真 Root，进入真文件系统会话（`exit` 返回） |
| `pmc help` | 查看包管理指令 |
| `pmc install <pkg>` | 安装插件；未命中资源仓库时自动降级 `apk add` |
| `pmc list` | 查看资源仓库索引 |
| `pmc repo set <url>` | 指定你的插件仓库地址（不设默认源） |
| `pmc status` | 查看运行状态（会话 / 容器） |
| 扩展键栏 | ESC / TAB / CTRL / ALT / 方向键 / HOME / END 等 |

## 🔌 插件包规范（预留）

插件以单个 `.zip` 独立分发。解包后**根目录必须直接包含**以下三个文件：

| 文件 | 说明 |
| --- | --- |
| `manifest.json` | 插件声明：名称、版本、作者等元数据 |
| `install.sh` | 安装脚本：安装时在容器内执行 |
| `uninstall.sh` | 卸载脚本：卸载时在容器内执行 |

> ⚠️ 插件脚本将在容器内执行，请仅安装你信任的插件（风险自负，维护者不审计）。

## 🛠 从源码构建

本工程不依赖 Gradle 即可构建（手动工具链：kotlinc + d8 + aapt2 + apksigner）。
详见 [tools/BUILD.md](tools/BUILD.md)。

## ❓ FAQ

- **需要 Root 吗？** —— 不需要。全部核心功能均在免 Root 下工作，`su` 仅为可选的进阶通道。
- **需要联网吗？** —— 仅首次安装容器、以及安装新软件包时需要。
- **支持什么设备？** —— Android 8.0+ 的 ARM64 设备（当前容器资源为 aarch64 构建）。
- **为什么能做到这么小？** —— 编译器、SDK 等一概不打包，按需在容器内自装；APK 本体只保留终端与容器管理。

## 🤝 贡献

欢迎通过 [Issues](https://github.com/AROAN110/Coding-for-Android/issues) 反馈问题，或通过 [Pull Requests](https://github.com/AROAN110/Coding-for-Android/pulls) 贡献代码。

目前项目仍处于起步阶段，存在非常多的未完善功能，欢迎各位开发者集思广益，提一些pr或者issue

我将会在issue里标明待完成的事项

- 保持「最小底座」哲学：不打包臃肿工具链，一切按需。
- 本项目采用 GPL-3.0 许可；提交贡献即表示同意以相同许可证分发。

## 🙏 鸣谢

本项目的诞生离不开这些优秀的开源项目与社区：

- **[Termux](https://github.com/termux/termux-app)** —— 安卓终端模拟与 proot 容器方案的先行者；本项目的终端交互模型与 proot 打包思路均受其启发。
- **[AndroidIDE](https://github.com/itsaky/AndroidIDE)** —— 安卓端 IDE 的探路者，本项目的设计参考之一。
- **[Alpine Linux](https://alpinelinux.org/)** —— 本项目的容器底座：小巧、纯粹、恰到好处。
- **[PRoot](https://proot-me.github.io/)** —— 用户态 chroot 实现，让免 Root 的「假 Root」环境成为可能。
- **[Kotlin](https://kotlinlang.org/)** —— 本项目的开发语言。
- 以及所有为开源生态做出贡献的开发者们。

## 📄 许可证

本项目采用 **GPL-3.0** 许可，详见 [LICENSE](LICENSE)。

---

作者：**AROAN110**
