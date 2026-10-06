# Coding for Android - 手动构建指南

> 本工程通过 Linux 环境中的手动工具链构建 APK（不依赖 Gradle）。
> 一键脚本: `tools/build-apk.sh`（路径可用环境变量覆盖，见脚本头部注释）

## 一键构建

```sh
cd <项目根>
sh tools/build-apk.sh
```

输出: `<项目根>/dist/Coding-for-Android-v1.0-mvp.apk`（可用 `CFA_OUT` 指定其他目录）

## 工具链组成

| 位置 | 内容 |
|------|------|
| /opt/kotlinc | kotlin-compiler-embeddable-1.9.22.jar + kotlin-stdlib-1.9.22.jar + trove4j-1.0.20200330.jar + **annotations-13.0.jar** |
| /opt/cfa-tools | android.jar（android-11 / SDK30）、r8-d8-8.3.37.jar |
| 系统 | aapt2、zipalign、apksigner、keytool、javac（Ubuntu / JDK 自带） |

⚠️ 坑位备忘: embeddable 版编译器必须手动补 `annotations-13.0.jar`，否则编译在 IR lowering 阶段崩溃
（报 `NoClassDefFoundError: org/jetbrains/annotations/NotNull`）。

## 环境重建（若 /opt 丢失）

```sh
mkdir -p /opt/kotlinc && cd /opt/kotlinc
B=https://maven.aliyun.com/repository/public
curl -fSL -o kotlin-compiler-embeddable-1.9.22.jar $B/org/jetbrains/kotlin/kotlin-compiler-embeddable/1.9.22/kotlin-compiler-embeddable-1.9.22.jar
curl -fSL -o kotlin-stdlib-1.9.22.jar $B/org/jetbrains/kotlin/kotlin-stdlib/1.9.22/kotlin-stdlib-1.9.22.jar
curl -fSL -o trove4j-1.0.20200330.jar $B/org/jetbrains/intellij/deps/trove4j/1.0.20200330/trove4j-1.0.20200330.jar
curl -fSL -o annotations-13.0.jar $B/org/jetbrains/annotations/13.0/annotations-13.0.jar
# android.jar 与 r8-d8: 从 /data/local/dev-libs/ 复制
#   android-sdk-platforms/android-11/android.jar  →  /opt/cfa-tools/android.jar
#   r8-d8-8.3.37.jar                              →  /opt/cfa-tools/r8-d8-8.3.37.jar
```

## 签名

- 首次构建会自动生成**测试用** keystore：`$CFA_WORK/cfa-release.keystore`（默认口令 `changeit`，仅用于本地测试）。
- **正式发布请使用你自己的 keystore**（本仓库不包含任何签名密钥），通过环境变量指定：`CFA_KEYSTORE` / `CFA_KS_ALIAS` / `CFA_KS_PASS`。
- 签名方案: v2 + v3（minSdk 26，无需 v1）