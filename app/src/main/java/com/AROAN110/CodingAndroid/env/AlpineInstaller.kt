package com.AROAN110.CodingAndroid.env

import android.content.Context
import android.os.Process as AndroidProcess
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Alpine 容器安装器：
 *  1. 释放 APK assets 中的 proot 四件套 → filesDir/proot/（并设置执行位）；
 *  2. 下载 Alpine minirootfs（多源回退）→ 用系统 toybox tar 解包到 filesDir/alpine/；
 *  3. 写入 /etc/resolv.conf 与 /etc/apk/repositories（清华镜像）。
 *
 * 全部步骤可重入：中途失败后重新调用会跳过已完成部分。
 */
object AlpineInstaller {

    /**
     * rootfs 下载源（按序尝试）：仅使用公共镜像（不依赖个人仓库）。
     */
    private val ROOTFS_URLS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
        "https://mirrors.aliyun.com/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz",
    )

    /** 基础包安装源（按序强制迭代；每源自动补 /main + /community 两行）。 */
    private val APK_SOURCES = listOf(
        "官方 CDN" to "https://dl-cdn.alpinelinux.org/alpine/v3.24",
        "清华" to "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24",
        "阿里" to "https://mirrors.aliyun.com/alpine/v3.24",
        "中科大" to "https://mirrors.ustc.edu.cn/alpine/v3.24",
        "LeaseWeb" to "http://mirror.leaseweb.com/alpine/v3.24",
        "Clarkson" to "http://mirror.clarkson.edu/alpine/v3.24",
    )

    private const val ROOTFS_MIN_BYTES = 3_000_000L

    /** 冒烟测试成功输出的标记文本。 */
    private const val SMOKE_OK = "CFA_SMOKE_OK"

    // ---- 测速参数（需求一）----
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val PROBE_TOTAL_WAIT_MS = 6_000L
    private const val MAX_PROBE_THREADS = 6
    private const val USER_AGENT = "Coding-for-Android/1.0"

    /** SIGKILL 信号编号（强杀进程树用）。 */
    private const val SIGNAL_SIGKILL = 9

    // ---- 打断控制（需求三）----

    /** 当前正在执行的子进程（安装阶段可被强制打断）。 */
    @Volatile
    private var currentProcess: Process? = null

    /** 被打断标记：置位后各阶段检查点立即抛出 InstallationInterruptedException。 */
    @Volatile
    private var aborted = false

    /**
     * 立即强制终止当前安装（Ctrl+C / 用户取消）。
     * 关键：proot 的子进程（busybox / apk）不会随父进程死亡而退出，
     * 必须整树 SIGKILL，否则残留进程持有 apk 数据库锁、后续安装卡死。
     */
    fun abortInstallation() {
        aborted = true
        val p = currentProcess
        currentProcess = null
        if (p != null) killProcessTree(p)
    }

    /** 打断检查点：在耗时步骤前调用。 */
    private fun checkAbort() {
        if (aborted) throw InstallationInterruptedException()
    }

    /**
     * 等待进程结束（可打断）：
     *  - 每 500ms 检查一次 abort 标志，中断时立即强杀进程树；
     *  - 强杀后宽限 5 秒仍不退出则放行，避免安装线程被无响应进程拖挂。
     * 返回 true = 进程正常结束。
     */
    private fun waitForProcess(p: Process, timeoutSec: Long): Boolean {
        var remaining = timeoutSec * 1000
        var abortGrace = -1L
        while (remaining > 0) {
            val slice = if (remaining > 500) 500L else remaining
            if (p.waitFor(slice, TimeUnit.MILLISECONDS)) return true
            if (aborted) {
                killProcessTree(p)
                if (abortGrace < 0) abortGrace = 5_000
                abortGrace -= slice
                if (abortGrace <= 0) return false
            }
            remaining -= slice
        }
        return false
    }

    /** 强杀进程树：先全部后代、再本体（proot 后代可能持有 apk 数据库锁）。 */
    private fun killProcessTree(p: Process) {
        val rootPid = pidOf(p)
        if (rootPid > 0) {
            val targets = mutableListOf<Int>()
            collectDescendants(rootPid, targets)
            for (t in targets) {
                try {
                    AndroidProcess.sendSignal(t, SIGNAL_SIGKILL)
                } catch (_: Throwable) {
                }
            }
            try {
                AndroidProcess.sendSignal(rootPid, SIGNAL_SIGKILL)
            } catch (_: Throwable) {
            }
        }
        try {
            p.destroyForcibly()
        } catch (_: Exception) {
        }
    }

    /** 递归收集 pid 的全部后代（读 /proc/<pid>/task/<tid>/children）。 */
    private fun collectDescendants(pid: Int, out: MutableList<Int>) {
        val tids = File("/proc/$pid/task").listFiles() ?: return
        for (tid in tids) {
            val children = try {
                File(tid, "children").readText()
            } catch (_: Exception) {
                continue
            }
            for (tok in children.trim().split(Regex("\\s+"))) {
                if (tok.isEmpty()) continue
                val c = tok.toIntOrNull() ?: continue
                if (out.contains(c)) continue
                out.add(c)
                collectDescendants(c, out)
            }
        }
    }

    /** 取进程 PID（API26+ 反射 Process.pid()，失败回退旧字段）。 */
    private fun pidOf(p: Process): Int {
        return try {
            val m = p.javaClass.getMethod("pid")
            (m.invoke(p) as? Long)?.toInt() ?: reflectPid(p)
        } catch (_: Throwable) {
            reflectPid(p)
        }
    }

    /** 兼容旧系统的 PID 反射（ProcessImpl.pid 字段）。 */
    private fun reflectPid(p: Process): Int {
        return try {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(p)
        } catch (_: Throwable) {
            -1
        }
    }

    /**
     * 清理上一次中断留下的残留（tmp / apk 锁 / 半包），恢复默认源，
     * 并复位打断标志，防止后续安装（或 pmc 降级安装）被旧状态污染。
     */
    fun cleanupAfterAbort(context: Context) {
        val files = context.filesDir
        try {
            File(files, "tmp").listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {
        }
        try {
            File(files, "alpine/lib/apk/db/lock").delete()
            File(files, "alpine/lib/apk/db/lock").parentFile?.mkdirs()
        } catch (_: Exception) {
        }
        try {
            File(files, "alpine-rootfs.tar.gz").delete()
        } catch (_: Exception) {
        }
        // ★ 建议一：不再强制写回官方 CDN。
        //   原逻辑会在每次清理/重装时把用户手动改过的源覆盖成连不上的官方源，已注释移除。
        // try {
        //     val repos = File(files, "alpine/etc/apk/repositories")
        //     if (repos.isFile) {
        //         repos.writeText(buildRepositoriesContent(APK_SOURCES.first().second))
        //     }
        // } catch (_: Exception) {
        // }
        aborted = false
    }

    // ---- 并发测速选源（需求一）----

    /**
     * 并发探测各镜像的 APKINDEX.tar.gz，返回耗时最低的镜像 base URL；全部失败返回 null。
     * 注：本机无 kotlinx-coroutines（零第三方依赖约束），用线程池 + CountDownLatch 实现等价的并发 awaitAll。
     */
    fun selectFastestMirror(mirrors: List<String>): String? {
        if (mirrors.isEmpty()) return null
        val results = HashMap<String, Long>()
        val latch = CountDownLatch(mirrors.size)
        val pool = Executors.newFixedThreadPool(minOf(mirrors.size, MAX_PROBE_THREADS))
        for (mirror in mirrors) {
            pool.execute {
                val cost = probeMirror(mirror)
                synchronized(results) { results[mirror] = cost }
                latch.countDown()
            }
        }
        try {
            latch.await(PROBE_TOTAL_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        pool.shutdownNow()
        return synchronized(results) {
            results.entries
                .filter { it.value != Long.MAX_VALUE }
                .minByOrNull { it.value }
                ?.key
        }
    }

    /** 探测单个镜像；成功返回耗时 ms，失败/超时返回 Long.MAX_VALUE。 */
    private fun probeMirror(base: String): Long {
        val url = "${base.trimEnd('/')}/main/aarch64/APKINDEX.tar.gz"
        val start = System.currentTimeMillis()
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return Long.MAX_VALUE
                // 真读一小段，确认内容可达
                conn.inputStream.use { inp ->
                    val buf = ByteArray(1024)
                    inp.read(buf)
                }
                System.currentTimeMillis() - start
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            Long.MAX_VALUE
        }
    }

    /** 是否已安装完成（可安全启动）。 */
    fun isInstalled(context: Context): Boolean {
        val f = context.filesDir
        return File(f, "alpine/bin/busybox").isFile &&
            File(f, "proot/proot").isFile &&
            File(f, "alpine/etc/apk/repositories").isFile &&
            File(f, "alpine/etc/resolv.conf").isFile &&
            File(f, "proot/.smoketest").isFile
    }

    /** 执行安装（阻塞，需在后台线程调用）。onProgress 用于输出进度文本。
     *  temporaryMirrors 非空时优先用作基础包源（仅本次引导使用，不落任何配置）。 */
    fun install(context: Context, onProgress: (String) -> Unit, temporaryMirrors: List<String> = emptyList()) {
        val files = context.filesDir
        aborted = false

        checkAbort()
        onProgress("[安装] 释放 proot 运行件…\n")
        extractProot(context, File(files, "proot"))

        val rootfsDir = File(files, "alpine")
        if (!File(rootfsDir, "bin/busybox").isFile) {
            checkAbort()
            onProgress("[安装] 下载 Alpine minirootfs（约 4MB）…\n")
            val tarGz = downloadRootfs(context, onProgress, temporaryMirrors)
            checkAbort()
            onProgress("[安装] 解包 Alpine 容器（515 个条目）…\n")
            extractRootfs(tarGz, rootfsDir)
            try {
                tarGz.delete()
            } catch (_: Exception) {
            }
        } else {
            onProgress("[安装] Alpine 容器已存在，跳过下载\n")
        }

        checkAbort()
        onProgress("[安装] 写入网络配置…\n")
        configureRootfs(rootfsDir)
        File(files, "tmp").mkdirs()
        checkAbort()
        onProgress("[安装]验证 proot 可执行性（关键检测）…\n")
        smokeTest(context, rootfsDir, File(files, "proot"))
        checkAbort()
        onProgress("[安装] 安装基础包（bash / coreutils / findutils / grep / sed / gawk / util-linux，需联网）…\n")
        installBasePackages(context, rootfsDir, onProgress, temporaryMirrors)
        // 安装成功：恢复默认主源，清除本次临时源写入的痕迹（需求四红线）
        restoreDefaultRepositories(rootfsDir)
        checkAbort()
        onProgress("[安装] 写入用户环境（.bashrc / welcome.txt / 默认 shell）…\n")
        writeUserEnv(context, rootfsDir)
        File(files, "proot/.smoketest").writeText("ok")
        onProgress("[安装] 容器就绪 ✓\n")
    }

    // ---- proot 四件套 ----

    private fun extractProot(context: Context, destDir: File) {
        val curVer = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
        val marker = File(destDir, ".version")
        val names = context.assets.list("proot") ?: emptyArray()
        if (names.isEmpty()) throw IOException("APK 内缺少 proot 资源")

        val needCopy = !marker.isFile || marker.readText() != curVer ||
            !File(destDir, "proot").isFile
        if (needCopy) {
            destDir.mkdirs()
            destDir.listFiles()?.forEach { it.delete() }
            for (name in names) {
                val out = File(destDir, name)
                context.assets.open("proot/$name").use { inp ->
                    out.outputStream().use { os -> inp.copyTo(os) }
                }
            }
            marker.writeText(curVer)
        }
        for (name in names) ensureExecutable(File(destDir, name))
    }

    private fun ensureExecutable(f: File) {
        if (!f.isFile) return
        f.setReadable(true, true)
        f.setExecutable(true, true)
        if (!f.canExecute()) {
            try {
                ProcessBuilder("/system/bin/chmod", "700", f.absolutePath)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            } catch (_: Exception) {
            }
        }
    }

    // ---- rootfs 下载 ----

    private fun downloadRootfs(
        context: Context,
        onProgress: (String) -> Unit,
        temporaryMirrors: List<String> = emptyList(),
    ): File {
        val dest = File(context.filesDir, "alpine-rootfs.tar.gz")
        // 候选源：临时源（用户输入）优先，按其 base 拼接标准 rootfs 路径；为空才用内置源
        val urls: List<String> = if (temporaryMirrors.isNotEmpty()) {
            temporaryMirrors.map {
                "${it.trimEnd('/')}/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz"
            }
        } else {
            ROOTFS_URLS
        }
        var lastErr = "未知错误"
        for (url in urls) {
            try {
                onProgress("  源: $url\n")
                download(url, dest)
                val len = dest.length()
                if (len < ROOTFS_MIN_BYTES) throw IOException("文件过小（$len 字节）")
                onProgress("  下载完成（${len / 1024 / 1024} MiB）\n")
                return dest
            } catch (e: InstallationInterruptedException) {
                // 打断必须穿透：不能被通用 catch 吞掉后继续换源
                throw e
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                onProgress("  失败: $lastErr\n")
            }
        }
        throw IOException("所有下载源均失败：$lastErr")
    }

    private fun download(urlStr: String, dest: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            // 分片读 + 超时循环：既能读到完整文件，又能在被打断时及时退出
            readTimeout = 5_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val buf = ByteArray(65_536)
            conn.inputStream.use { inp ->
                dest.outputStream().use { os ->
                    while (true) {
                        checkAbort()
                        val n = try {
                            inp.read(buf)
                        } catch (_: java.net.SocketTimeoutException) {
                            continue
                        }
                        if (n < 0) break
                        os.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---- rootfs 解包（系统 toybox tar，已验证支持 gzip + symlink）----

    private fun extractRootfs(tarGz: File, rootfsDir: File) {
        rootfsDir.mkdirs()
        val pb = ProcessBuilder(
            "/system/bin/toybox", "tar", "-x", "-z", "-o",
            "-f", tarGz.absolutePath, "-C", rootfsDir.absolutePath,
        )
        pb.redirectErrorStream(true)
        val p = pb.start()
        currentProcess = p
        val finished = waitForProcess(p, 180)
        if (!finished) killProcessTree(p)
        if (currentProcess === p) currentProcess = null
        // 中断优先：避免把「被打断」误报成解包失败
        if (aborted) throw InstallationInterruptedException()
        if (!finished) throw IOException("解包超时")
        val out = try {
            p.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        if (p.exitValue() != 0) {
            throw IOException("解包失败（exit=${p.exitValue()}）: ${out.take(200)}")
        }
        if (!File(rootfsDir, "bin/busybox").isFile) {
            throw IOException("解包结果不完整（bin/busybox 缺失）")
        }
    }

    // ---- 网络配置 ----

    /**
     * 构造 /etc/apk/repositories 内容：同一 base 必须同时含 main 与 community。
     * 缺 community 会导致 util-linux、bash-completion 等社区包解析不到而安装失败。
     */
    private fun buildRepositoriesContent(base: String): String {
        val b = base.trimEnd('/')
        return "$b/main\n$b/community\n"
    }

    private fun configureRootfs(rootfsDir: File) {
        val etc = File(rootfsDir, "etc")
        etc.mkdirs()
        File(etc, "resolv.conf").writeText(
            "nameserver 223.5.5.5\n" +
                "nameserver 119.29.29.29\n",
        )
        val apk = File(etc, "apk")
        apk.mkdirs()
        File(apk, "repositories").writeText(
            // 与 restoreDefaultRepositories 保持一致：默认主源用中科大
            buildRepositoriesContent("https://mirrors.ustc.edu.cn/alpine/v3.24"),
        )
    }

    // ---- 冒烟测试：验证 proot 能真正执行（Android 10+ W^X 执行限制检测）----

    private fun smokeTest(context: Context, rootfsDir: File, prootDir: File) {
        val pb = ProcessBuilder(
            File(prootDir, "proot").absolutePath,
            "--link2symlink", "-0",
            "-r", rootfsDir.absolutePath,
            "-w", "/",
            "/bin/echo", SMOKE_OK,
        )
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("PROOT_LOADER", File(prootDir, "loader").absolutePath)
            put("PROOT_TMP_DIR", File(context.filesDir, "tmp").absolutePath)
            put("LD_LIBRARY_PATH", prootDir.absolutePath)
        }
        val p = try {
            pb.start()
        } catch (e: Exception) {
            throw IOException("proot 无法执行（系统拒绝 exec）: ${e.message}")
        }
        currentProcess = p
        val finished = waitForProcess(p, 30)
        if (!finished) killProcessTree(p)
        if (currentProcess === p) currentProcess = null
        // 中断优先：避免把「被打断」误报成冒烟测试失败
        if (aborted) throw InstallationInterruptedException()
        if (!finished) {
            throw IOException("proot 冒烟测试超时")
        }
        val out = p.inputStream.bufferedReader().readText()
        if (p.exitValue() != 0 || !out.contains(SMOKE_OK)) {
            throw IOException("proot 冒烟测试失败（exit=${p.exitValue()}）: ${out.trim().take(300)}")
        }
    }

    // ---- 首次安装：基础包与用户环境 ----

    private fun prootProcess(context: Context, rootfsDir: File, prootDir: File, args: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(
            listOf(
                File(prootDir, "proot").absolutePath,
                "--link2symlink", "-0",
                "-r", rootfsDir.absolutePath,
                "-w", "/",
            ) + args,
        )
        pb.redirectErrorStream(true)
        pb.environment().apply {
            put("PROOT_LOADER", File(prootDir, "loader").absolutePath)
            put("PROOT_TMP_DIR", File(context.filesDir, "tmp").absolutePath)
            put("LD_LIBRARY_PATH", prootDir.absolutePath)
            put("HOME", "/root")
            put("PATH", "/usr/sbin:/usr/bin:/sbin:/bin")
            put("LANG", "C")
        }
        return pb
    }

    /** 容器命令执行结果（finished=false 表示超时被杀，exitCode=-1）。 */
    data class ExecResult(val finished: Boolean, val exitCode: Int, val output: String)

    /** 在 Alpine 容器内执行一条命令并收集输出（pmc 降级安装等场景复用）。 */
    fun execInAlpine(context: Context, args: List<String>, timeoutSec: Long = 180): ExecResult {
        if (aborted) return ExecResult(false, -1, "已中断")
        val files = context.filesDir
        val rootfsDir = File(files, "alpine")
        val prootDir = File(files, "proot")
        val p = try {
            prootProcess(context, rootfsDir, prootDir, args).start()
        } catch (e: Exception) {
            return ExecResult(false, -1, "启动失败: ${e.message}")
        }
        // 登记为当前进程，允许 abortInstallation() 立即强杀整树
        currentProcess = p
        val finished = waitForProcess(p, timeoutSec)
        if (!finished) killProcessTree(p)
        if (currentProcess === p) currentProcess = null
        if (aborted) return ExecResult(false, -1, "已中断")
        val out = try {
            p.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        return ExecResult(finished, if (finished) p.exitValue() else -1, out)
    }

    /**
     * 首次安装自动装基础包：
     *  1. 先并发测速挑出最快镜像（需求一），只把主源写入 repositories，避免 apk 多源反复卡死；
     *  2. 主源失败时按测速耗时顺序依次回退；
     *  3. 每轮开始前检查中断标志。
     * temporaryMirrors 非空时以其为候选源（仅本次引导）。
     */
    private fun installBasePackages(
        context: Context,
        rootfsDir: File,
        onProgress: (String) -> Unit,
        temporaryMirrors: List<String> = emptyList(),
    ) {
        val reposFile = File(rootfsDir, "etc/apk/repositories")

        // 候选源：临时源优先，否则用内置源表
        val candidates: List<Pair<String, String>> = if (temporaryMirrors.isNotEmpty()) {
            temporaryMirrors.map { it to it }
        } else {
            APK_SOURCES.map { (name, base) -> name to base }
        }
        val urls = candidates.map { it.second }

        onProgress("  [测速] 并发探测 ${urls.size} 个镜像…\n")
        val fastest = selectFastestMirror(urls)
        val ordered: List<Pair<String, String>> = if (fastest != null) {
            onProgress("  [测速] 最快: $fastest\n")
            candidates.filter { it.second == fastest } +
                candidates.filter { it.second != fastest }
        } else {
            onProgress("  [测速] 无可用镜像，按默认顺序尝试\n")
            candidates
        }

        var lastErr = "未知错误"
        for ((name, base) in ordered) {
            // 生成变体：先试 HTTPS，如果 fail 自动强制回退 HTTP
            val variants = mutableListOf<Pair<String, String>>()
            variants.add(name to base)
            if (base.startsWith("https://")) {
                variants.add("$name[HTTP回退]" to ("http://" + base.removePrefix("https://")))
            }

            for ((vName, vBase) in variants) {
                checkAbort()
                onProgress("  源[$vName] $vBase\n")
                try {
                    reposFile.writeText(buildRepositoriesContent(vBase))
                } catch (e: Exception) {
                    lastErr = "源配置写入失败（$vName）: ${e.message}"
                    continue
                }

                // 1. 先跑 apk update
            val upd = execInAlpine(
                    context,
                    listOf("/bin/busybox", "timeout", "10", "/sbin/apk", "update"),
                    20,
                )
                checkAbort()
                if (!upd.finished || upd.exitCode != 0) {
                    lastErr = if (!upd.finished) "apk update 超时（$vName）"
                              else "apk update 失败（$vName, exit=${upd.exitCode}）"
                    onProgress("  [!] $lastErr\n")
                    continue
                }

                // 2. 单独装 bash（体积小，最容易成功）
                val addBash = execInAlpine(
                    context,
                    listOf("/sbin/apk", "add", "--no-cache", "bash"),
                    300,
                )
                checkAbort()
                if (!(addBash.finished && addBash.exitCode == 0)) {
                    lastErr = if (!addBash.finished) "apk add bash 超时（$vName）"
                              else "apk add bash 失败（$vName, exit=${addBash.exitCode}）"
                    onProgress("  [!] $lastErr\n")
                    continue
                }

                // 3. 再装剩下的工具链
                val addTools = execInAlpine(
                    context,
                    listOf(
                        "/sbin/apk", "add", "--no-cache",
                        "coreutils", "findutils", "grep", "sed", "gawk", "util-linux",
                    ),
                    300,
                )
                checkAbort()
                if (addTools.finished && addTools.exitCode == 0 && File(rootfsDir, "bin/bash").isFile) {
                    onProgress("  bash / coreutils / findutils / grep / sed / gawk / util-linux 就绪 ✓\n")
                    return
                }
                lastErr = if (!addTools.finished) "apk add 工具链 超时（$vName）"
                          else "apk add 工具链 失败（$vName, exit=${addTools.exitCode}）"
                onProgress("  [!] $lastErr\n")
            }
        }
        checkAbort()
        throw IOException("所有镜像源均失败：$lastErr")
    }
    /**
     * 安装成功后恢复默认主源：把 /etc/apk/repositories 复原为内置默认源，
     * 确保临时源不留痕（需求四红线：绝不持久化用户临时输入的源）。
     */
    private fun restoreDefaultRepositories(rootfsDir: File) {
        try {
            // ★ 建议二：写回稳定的国内源（中科大），而非连不上的官方 CDN
            File(rootfsDir, "etc/apk/repositories").writeText(
                buildRepositoriesContent("https://mirrors.ustc.edu.cn/alpine/v3.24"),
            )
        } catch (_: Exception) {
        }
    }

    /** 首次安装写入用户环境：.bashrc（彩色动态提示符）、welcome.txt、默认 shell 换 bash。 */
    private fun writeUserEnv(context: Context, rootfsDir: File) {
        val bashrc = File(rootfsDir, "root/.bashrc")
        bashrc.parentFile?.mkdirs()
        context.assets.open("alpine/bashrc").use { inp ->
            bashrc.outputStream().use { os -> inp.copyTo(os) }
        }
        val welcome = File(rootfsDir, "root/welcome.txt")
        context.assets.open("alpine/welcome.txt").use { inp ->
            welcome.outputStream().use { os -> inp.copyTo(os) }
        }
        val passwd = File(rootfsDir, "etc/passwd")
        if (passwd.isFile) {
            val text = passwd.readText()
            val replaced = text.replace(":/root:/bin/sh", ":/root:/bin/bash")
            if (replaced != text) passwd.writeText(replaced)
        }
    }
}