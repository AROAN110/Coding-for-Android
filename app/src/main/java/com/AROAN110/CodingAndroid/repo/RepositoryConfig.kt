package com.AROAN110.CodingAndroid.repo

/**
 * 资源仓库配置。
 *
 * 仓库地址**不设默认**：完全由用户通过 `pmc repo set <url>` 自行指定；
 * 未配置时相关指令给出提示（设计要求：国内网络须支持多源回退，
 * GitHub raw 被墙时自动切换镜像，回退逻辑在下载层统一扩容）。
 */
object RepositoryConfig {

    /** 无默认源 —— 资源仓库地址由用户经 `pmc repo set <url>` 指定。 */
    const val DEFAULT_REPO_URL: String = ""

    /** GitHub 加速前缀（仅对 github.com / raw.githubusercontent.com 地址生效，按序尝试）。 */
    val MIRRORS: List<String> = listOf(
        "https://mirror.ghproxy.com/",
        "https://ghproxy.net/",
    )

    /** 仓库索引文件（相对路径）。 */
    const val INDEX_FILE: String = "index.json"

    /** 资源包本地缓存子目录（位于家目录下）。 */
    const val CACHE_DIR: String = "coding-cache"
}