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
 *   pmc install <pkg>      仓库检索优先，未命中自动降级 apk add
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
        "pmc: 资源仓库地址未配置\n" +
            "提示: 用 pmc repo set <url> 指定下载源\n"

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
                "pmc: 索引拉取成功（${index.length} 字符）\n预览: $preview…\n"
            }

            "install" -> {
                val pkg = args.getOrNull(2)
                if (pkg.isNullOrBlank()) {
                    sink("pmc: 缺少包名\n用法: pmc install <pkg>\n")
                } else {
                    runAsync(sink) { installPackage(pkg) }
                }
            }

            "uninstall", "remove" -> {
                val pkg = args.getOrNull(2)
                if (pkg.isNullOrBlank()) {
                    sink("pmc: 缺少包名\n用法: pmc uninstall <pkg>\n")
                } else {
                    sink("pmc: 卸载 '${pkg}' —— 本地已安装清单尚未接入（待完善）\n")
                }
            }

            "pull" -> sink("pmc: 更新已安装资源 —— 待完善\n")

            "local-load" -> {
                val path = args.getOrNull(2)
                if (path.isNullOrBlank()) {
                    sink("pmc: 缺少路径\n用法: pmc local-load <path>\n")
                } else {
                    sink("pmc: 从本地加载: ${path} —— 待完善（风险自负，维护者不审计）\n")
                }
            }

            "repo" -> {
                val url = args.getOrNull(3)
                if (args.getOrNull(2) == "set" && !url.isNullOrBlank()) {
                    repoUrl = url
                    appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        ?.edit()?.putString(KEY_REPO_URL, url)?.apply()
                    sink("pmc: 资源仓库地址已设定: $url\n")
                } else {
                    sink("pmc: 用法: pmc repo set <url>\n")
                }
            }

            else -> sink("pmc: 未知子命令 '${args.getOrNull(1)}'\n" + usage())
        }
    }

    // —— 安装实现：仓库检索（index.json）优先，未命中自动降级 apk add ——

    private fun installPackage(pkg: String): String {
        val ctx = appContext ?: return "pmc: 内部错误（未初始化）\n"
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
                        append("pmc: 仓库命中 '$bare'，已下载（${file.length()} 字节）\n")
                        append("pmc: SHA256 = $sha\n")
                        append("pmc: 已解压 $n 个条目 → ${File(cacheDir, bare).absolutePath}\n")
                        append("pmc: [TODO] install.sh 执行 / manifest.json 解析待完善\n")
                    }
                }
            } catch (_: Exception) {
                // 落入第二档
            }
        }

        // 第二档：容器内 apk add
        return fallbackApkAdd(ctx, bare)
    }

    /** index.json 中是否包含该包（兼容 "name" / "name.zip" 写法）。 */
    private fun indexContains(index: String, bare: String): Boolean {
        val zip = "$bare.zip"
        return index.contains("\"$bare\"") || index.contains("\"$zip\"") || index.contains(zip)
    }

    /** 第二档：在 Alpine 容器内 apk add（apk 也找不到才最终报错）。 */
    private fun fallbackApkAdd(ctx: Context, pkg: String): String {
        val r = try {
            AlpineInstaller.execInAlpine(ctx, listOf("/sbin/apk", "add", "--no-cache", pkg), 180)
        } catch (e: Exception) {
            return "pmc: 安装失败 —— 资源仓库未命中 '$pkg'，apk add 也无法执行: ${e.message}\n"
        }
        return if (r.finished && r.exitCode == 0) {
            "pmc: 未在资源仓库命中 '$pkg'，已降级 apk add 安装 ✓\n" + r.output.takeLast(2000)
        } else {
            val why = if (!r.finished) "超时" else "exit=${r.exitCode}"
            "pmc: 安装失败 —— 资源仓库未命中 '$pkg'，apk add 也失败（$why）\n" + r.output.takeLast(1500)
        }
    }

    private fun runAsync(sink: (String) -> Unit, block: () -> String) {
        worker.execute {
            val out = try {
                block()
            } catch (e: Exception) {
                "pmc: 失败 —— ${e.message}\n"
            }
            mainHandler.post { sink(out) }
        }
    }

    private fun usage(): String = """
        pmc — Coding for Android 包管理指令
        用法:
          pmc install <pkg>       仓库检索优先（未命中自动降级 apk add）
          pmc uninstall <pkg>     卸载插件包
          pmc pull                更新已安装资源
          pmc list                查看仓库索引
          pmc local-load <path>   加载本地自定义包
          pmc repo set <url>      配置资源仓库地址
          pmc help                显示本帮助
        """.trimIndent() + "\n"
}