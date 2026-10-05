package com.AROAN110.CodingAndroid.env

/**
 * 双环境标识（Roadmap 阶段1 核心概念）。
 *
 * 两套环境互相隔离：
 *  1. ALPINE_CONTAINER —— 写代码 / 编译 / 包管理（apk 生效）；
 *  2. HOST_SU          —— 安卓 su 主机 shell，仅用于跨存储文件迁移（apk 无效，exit 切回容器）。
 *
 * UI 与终端提示统一从这里取文案，避免散落硬编码。
 */
enum class Environment(
    val label: String,
    val warning: String,
) {
    ALPINE_CONTAINER(
        label = "Alpine容器",
        warning = "apk 包管理在此环境生效；输入 su 申请 Root",
    ),
    HOST_SHELL(
        label = "Android主机",
        warning = "宿主 shell；输入 su 申请 Root，输入 pmc alpine 返回容器",
    ),
    HOST_SU(
        label = "Android su主机",
        warning = "仅用于文件迁移；apk 命令在此无效，输入 exit 切回容器",
    ),
}