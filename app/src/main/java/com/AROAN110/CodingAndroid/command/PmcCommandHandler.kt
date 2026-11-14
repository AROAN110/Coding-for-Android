package com.AROAN110.CodingAndroid.command

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.AROAN110.CodingAndroid.env.AlpineInstaller
import com.AROAN110.CodingAndroid.repo.RepositoryConfig
import com.AROAN110.CodingAndroid.repo.ResourceRepository
import java.io.File
import java.util.concurrent.Executors

/**
 * pmc —— Coding for Android 的包管理指令（云端资源仓库入口）。
 *
 * 子命令：
 *   pmc install <pkg> [--proxy host:port]   仓库检索优先，未命中自动降级 apk add
 *   pmc uninstall <pkg>    卸载插件包（待完善）
 *   pmc pull               更新已安装资源（待完善）
 *   pmc list               拉取并预览仓库索引（index.json）
 *   pmc local-load <path>  加载本地自定义包（待完善）
 *   pmc repo set <url>     配置资源仓库地址（持久化，不设默认源）
 *   pmc help               帮助
 *
 * 线程模型：解析在主线程；网络 / IO 在单线程 worker；输出统一回主线程回调。
 * 资源仓库地址**不设默认**：完全由用户通过 pmc repo set <url> 指定；
 * 未配置时相关指令给出提示。插件包规范见 README（.zip：manifest.json / install.sh / uninstall.sh）。
 */
object PmcCommandHandler {

    const val COMMAND = "pmc"

    private const val PREFS_NAME = "pmc"
    private const val KEY_REPO_URL = "repo_url"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pmc-worker") }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var repoUrl: String = ""

    private val NOT_CONFIGURED: String =
        "pmc: repository URL not configured\n" +
            "Hint: use 'pmc repo set <url>' to specify a source\n"

    /** 在 Activity 创建时调用一次：加载持久化的仓库地址（无默认源）。 */
    fun attach(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        repoUrl = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_REPO_URL, null)
            ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    /** 判断一行输入是否属于 pmc 指令。 */
    fun handles(line: String): Boolean {
        val t = line.trim()
        return t == COMMAND || t.startsWith("$COMMAND ")
    }

    /** 异步处理一条 pmc 指令；每一段输出通过 sink 回调（主线程）。 */
    fun handle(line: String, sink: (String) -> Unit) {
        val args = line.trim().split(Regex("\\s+"))
        when (args.getOrNull(1)) {
            null, "help", "-h", "--help" -> sink(usage())

            "list" -> runAsync(sink) {
                if (repoUrl.isBlank()) return@runAsync NOT_CONFIGURED
                val index = ResourceRepository.fetchIndex(repoUrl)
                val preview = index.take(200).replace('\n', ' ')
                "pmc: index fetched (${index.length} chars)\nPreview: $preview…\n"
            }

            "install" -> {
                val pkg = args.getOrNull(2)
                if (pkg.isNullOrBlank() || pkg.startsWith("--")) {
                    sink("pmc: missing package name\nUsage: pmc install <pkg> [--proxy host:port]\n")
                } else {
                    val proxy = parseProxyArg(args)
                    runAsync(sink) { installPackage(pkg, proxy) }
                }
            }

            "uninstall", "remove" -> {
                val pkg = args.getOrNull(2)
                if (pkg.isNullOrBlank()) {
                    sink("pmc: missing package name\nUsage: pmc uninstall <pkg>\n")
                } else {
                    sink("pmc: remove '$pkg' — local package database not implemented yet\n")
                }
            }

            "pull" -> sink("pmc: update installed resources — not implemented yet\n")

            "local-load" -> {
                val path = args.getOrNull(2)
                if (path.isNullOrBlank()) {
                    sink("pmc: missing path\nUsage: pmc local-load <path>\n")
                } else {
                    sink("pmc: load from local: $path — not implemented yet (use at your own risk)\n")
                }
            }

            "repo" -> {
                val url = args.getOrNull(3)
                if (args.getOrNull(2) == "set" && !url.isNullOrBlank()) {
                    repoUrl = url
                    appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_REPO_URL, url)?.apply()
                    sink("pmc: repository URL set: $url\n")
                } else {
                    sink("pmc: usage: pmc repo set <url>\n")
                }
            }

            else -> sink("pmc: unknown subcommand '${args.getOrNull(1)}'\n" + usage())
        }
    }

    // —— 安装实现：仓库检索（index.json）优先，未命中自动降级 apk add ——

    /** 解析 --proxy host:port 参数；未提供或格式不完整返回 null（直连）。 */
    private fun parseProxyArg(args: List<String>): String? {
        val i = args.indexOf("--proxy")
        if (i < 0) return null
        val v = args.getOrNull(i + 1)?.trim().orEmpty()
        return v.takeIf { it.isNotEmpty() && it.contains(':') }
    }

    private fun installPackage(pkg: String, proxy: String? = null): String {
        val ctx = appContext ?: return "pmc: internal error (not initialized)\n"
        val bare = pkg.removeSuffix(".zip")
        val cacheDir = File(ctx.filesDir, RepositoryConfig.CACHE_DIR)

        // 第一档：私有仓库检索（未配置 / 检索失败 / 下载失败 → 均落入第二档，不报错退出）
        if (repoUrl.isNotBlank()) {
            try {
                val index = ResourceRepository.fetchIndex(repoUrl)
                if (indexContains(index, bare)) {
                    val file = ResourceRepository.downloadPackage(repoUrl, cacheDir, "$bare.zip")
                    val sha = ResourceRepository.sha256(file)
                    val n = ResourceRepository.unzipPackage(file, File(cacheDir, bare))
                    return buildString {
                        append("pmc: '$bare' found in repo, downloaded (${file.length()} bytes)\n")
                        append("pmc: SHA256 = $sha\n")
                        append("pmc: extracted $n entries → ${File(cacheDir, bare).absolutePath}\n")
                        append("pmc: [TODO] install.sh execution / manifest.json parsing not implemented\n")
                    }
                }
            } catch (_: Exception) {
                // 落入第二档
            }
        }

        // 第二档：容器内 apk add
        return fallbackApkAdd(ctx, bare, proxy)
    }

    /** index.json 中是否包含该包（兼容 "name" / "name.zip" 写法）。 */
    private fun indexContains(index: String, bare: String): Boolean {
        val zip = "$bare.zip"
        return index.contains("\"$bare\"") || index.contains("\"$zip\"") || index.contains(zip)
    }

    /** 第二档：在 Alpine 容器内 apk add（apk 也找不到才最终报错）。 */
    private fun fallbackApkAdd(ctx: Context, pkg: String, proxy: String? = null): String {
        val r = try {
            AlpineInstaller.execInAlpine(ctx, listOf("/sbin/apk", "add", "--no-cache", pkg), 180, proxy)
        } catch (e: Exception) {
            return "pmc: install failed — '$pkg' not in repo, apk add could not run: ${e.message}\n"
        }
        return if (r.finished && r.exitCode == 0) {
            "pmc: '$pkg' not in repo, installed via apk add ✓\n" + r.output.takeLast(2000)
        } else {
            val why = if (!r.finished) "timeout" else "exit=${r.exitCode}"
            "pmc: install failed — '$pkg' not in repo, apk add failed ($why)\n" + r.output.takeLast(1500)
        }
    }

    private fun runAsync(sink: (String) -> Unit, block: () -> String) {
        worker.execute {
            val out = try {
                block()
            } catch (e: Exception) {
                "pmc: failed — ${e.message}\n"
            }
            mainHandler.post { sink(out) }
        }
    }

    private fun usage(): String = """
        pmc — package manager for Coding for Android
        Usage:
          pmc install <pkg> [--proxy host:port]   search repo first, fall back to apk add
          pmc uninstall <pkg>                     remove an installed package
          pmc pull                                update installed resources
          pmc list                                show repository index
          pmc local-load <path>                   load a local package
          pmc repo set <url>                      set resource repository URL
          pmc help                                show this help
        Notes:
          --proxy applies to this install only; omit to connect directly
        """.trimIndent() + "\n"
}