package com.AROAN110.CodingAndroid.command

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.AROAN110.CodingAndroid.env.AlpineInstaller
import com.AROAN110.CodingAndroid.repo.RepositoryConfig
import com.AROAN110.CodingAndroid.repo.ResourceRepository
import com.AROAN110.CodingAndroid.resource.PackageInstaller
import com.AROAN110.CodingAndroid.resource.PermissionGuard
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
                    runAsync(sink) { uninstallPackage(pkg) }
                }
            }

            "pull" -> sink("pmc: update installed resources — not implemented yet\n")

            "local-load" -> {
                val path = args.getOrNull(2)
                if (path.isNullOrBlank()) {
                    sink("pmc: missing path\nUsage: pmc local-load <path>\n")
                } else {
                    runAsync(sink) { installLocalZip(path) }
                }
            }

            "perm" -> handlePerm(args, sink)

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

    // —— zip 包安装 / 卸载 / 授权 ——

    /** 本地 zip 安装：校验 manifest → 按 type 落库。 */
    private fun installLocalZip(path: String): String {
        val f = File(path.trim())
        if (!f.isFile) return "pmc: file not found: $path\n"
        return try {
            val r = PackageInstaller(appContext!!).installZip(f)
            "pmc: installed ${r.type} '${r.id}' → ${r.dir.absolutePath}\n"
        } catch (e: PackageInstaller.InstallException) {
            "pmc: install failed — ${e.message}\n"
        }
    }

    /** 卸载：按 id 删目录 + 清授权。 */
    private fun uninstallPackage(pkg: String): String {
        val installer = PackageInstaller(appContext ?: return "pmc: internal error (not initialized)\n")
        val resRemoved = installer.uninstall("resource", pkg)
        val plugRemoved = installer.uninstall("plugin", pkg)
        return if (resRemoved || plugRemoved) {
            "pmc: uninstalled '$pkg'\n"
        } else {
            "pmc: package not found: $pkg\n"
        }
    }

    /** pmc perm 子指令：grant / revoke / list。 */
    private fun handlePerm(args: List<String>, sink: (String) -> Unit) {
        val ctx = appContext ?: run {
            sink("pmc: internal error (not initialized)\n")
            return
        }
        when (args.getOrNull(2)) {
            "grant", "revoke" -> {
                val pkg = args.getOrNull(3)
                val permsArg = args.getOrNull(4)
                if (pkg.isNullOrBlank() || permsArg.isNullOrBlank()) {
                    sink("pmc: usage: pmc perm grant <pkg> <perm[,perm2...]|all>\n")
                    return
                }
                runAsync(sink) {
                    val declared = findDeclared(ctx, pkg)
                    if (declared == null) return@runAsync "pmc: package not found: $pkg\n"
                    val perms = if (permsArg == "all") {
                        declared.filter { PermissionGuard.isDangerous(it) }
                    } else {
                        permsArg.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                    }
                    if (perms.isEmpty()) return@runAsync "pmc: no grantable permissions to $permsArg\n"
                    val err = if (args[2] == "grant") {
                        PermissionGuard.grant(ctx, pkg, perms, declared)
                    } else {
                        PermissionGuard.revoke(ctx, pkg, perms, declared)
                    }
                    if (err != null) "pmc: $err\n"
                    else "pmc: ${args[2]}ed ${perms.joinToString(",")} for $pkg\n"
                }
            }

            "list" -> {
                val pkg = args.getOrNull(3)
                runAsync(sink) {
                    if (pkg.isNullOrBlank()) {
                        // 列出所有已装包及权限概况
                        val lines = mutableListOf("pmc: packages:")
                        val resRoot = File(ctx.filesDir, "home/resources")
                        val plugRoot = File(ctx.filesDir, "home/plugins")
                        for (root in listOf(resRoot to "resource", plugRoot to "plugin")) {
                            val dirs = root.first.listFiles()?.filter { it.isDirectory } ?: emptyList()
                            for (d in dirs) {
                                val declared = PermissionGuard.declaredOf(d)
                                val grants = PermissionGuard.grantedSet(ctx, d.name)
                                lines.add("  [${root.second}] ${d.name}: declared=${declared.joinToString(",")} granted=${grants.joinToString(",")}")
                            }
                        }
                        if (lines.size == 1) lines.add("  (none installed)")
                        lines.joinToString("\n") + "\n"
                    } else {
                        val declared = findDeclared(ctx, pkg)
                        if (declared == null) return@runAsync "pmc: package not found: $pkg\n"
                        val grants = PermissionGuard.grantedSet(ctx, pkg)
                        val sb = StringBuilder("pmc: $pkg\n")
                        for (p in declared) {
                            val level = if (PermissionGuard.isDangerous(p)) "dangerous" else "normal"
                            val state = if (PermissionGuard.isDangerous(p) && p in grants) "granted" else "declared"
                            sb.append("  $p ($level): $state\n")
                        }
                        sb.toString()
                    }
                }
            }

            else -> sink(
                "pmc: usage:\n" +
                    "  pmc perm grant <pkg> <perm[,perm2...]|all>\n" +
                    "  pmc perm revoke <pkg> <perm[,perm2...]>\n" +
                    "  pmc perm list [<pkg>]\n",
            )
        }
    }

    /** 在 resources / plugins 目录中找包的声明权限；找不到返回 null。 */
    private fun findDeclared(ctx: Context, pkg: String): Set<String>? {
        val res = File(ctx.filesDir, "home/resources/$pkg")
        if (res.isDirectory) return PermissionGuard.declaredOf(res)
        val plug = File(ctx.filesDir, "home/plugins/$pkg")
        if (plug.isDirectory) return PermissionGuard.declaredOf(plug)
        return null
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
          pmc local-load <path>                   install a local zip (resource or plugin)
          pmc uninstall <pkg>                     remove an installed package
          pmc perm grant <pkg> <perm[,perm2...]|all>   grant dangerous permissions
          pmc perm revoke <pkg> <perm[,perm2...]>      revoke permissions
          pmc perm list [<pkg>]                   show permission status
          pmc list                                show repository index
          pmc repo set <url>                      set resource repository URL
          pmc help                                show this help
        Notes:
          --proxy applies to this install only; omit to connect directly
          zip packages need manifest.json (see docs/RESOURCE_AND_PLUGIN_SPEC.md)
        """.trimIndent() + "\n"
}