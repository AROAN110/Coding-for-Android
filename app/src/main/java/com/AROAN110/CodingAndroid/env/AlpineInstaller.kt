package com.AROAN110.CodingAndroid.env

import android.content.Context
import android.os.Process as AndroidProcess
import com.AROAN110.CodingAndroid.bridge.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Alpine 容器安装器：
 *  1. 释放 APK assets 中的 proot 四件套 → filesDir/proot/（并设置执行位）；
 *  2. 下载 Alpine minirootfs（仓库 index.json 驱动）→ 用系统 toybox tar 解包到 filesDir/alpine/；
 *  3. 写入 /etc/resolv.conf 与 /etc/apk/repositories（清华镜像）。
 *
 * 全部步骤可重入：中途失败后重新调用会跳过已完成部分。
 */
object AlpineInstaller {

    /** 基础包安装源（按序强制迭代；每源自动补 /main + /community 两行）。 */
    /**
     * 官方兜底源：Alpine 官方 CDN。
     * 国内镜像对大文件传输不稳定（索引能拉、包本体常被掐），不再作为候选。
     */
    private const val OFFICIAL_CDN = "https://dl-cdn.alpinelinux.org/alpine/v3.24"

    /** 兜底下限：只用于挡住服务端返回的错误页（HTML 通常几百字节），不限制真实 rootfs 大小。 */
    private const val ROOTFS_MIN_BYTES = 100L * 1024L

    /** index.json 提供 size 时的容差比例（服务端 gzip 重压缩可能导致轻微偏差）。 */
    private const val SIZE_TOLERANCE = 0.2

    /**
     * 默认 rootfs 下载源（维护者仓库，内含 index.json 与 rootfs 文件）。
     * 用 raw.githubusercontent.com 而非 github.com：后者只提供网页，拼接文件路径会 404。
     */
    private const val DEFAULT_ROOTFS_REPO =
        "https://raw.githubusercontent.com/AROAN110/CFA-resources/main"

    /** 仓库索引文件名。 */
    private const val INDEX_FILE = "index.json"

    /** 冒烟测试成功输出的标记文本。 */
    private const val SMOKE_OK = "CFA_SMOKE_OK"

    // ---- 镜像测速参数 ----
    private const val PROBE_TIMEOUT_MS = 3_000
    private const val PROBE_TOTAL_WAIT_MS = 6_000L
    private const val MAX_PROBE_THREADS = 6
    private const val USER_AGENT = "Coding-for-Android/1.0"

    /** SIGKILL 信号编号（强杀进程树用）。 */
    private const val SIGNAL_SIGKILL = 9

    /** 下载进度上报步长（字节），用于节流高频回调。 */
    private const val PROGRESS_STEP_BYTES = 256L * 1024L

    /** 进度条宽度（字符数）。 */
    private const val PROGRESS_BAR_WIDTH = 20

    /** 进度行前缀，与 MainActivity 的分流判断保持一致，用于原地刷新而非追加。 */
    private const val PROGRESS_PREFIX = "  进度:"

    // ---- 打断控制 ----

    /** 当前正在执行的子进程（安装阶段可被强制打断）。 */
    @Volatile
    private var currentProcess: Process? = null

    /** 被打断标记：置位后各阶段检查点立即抛出 InstallationInterruptedException。 */
    @Volatile
    private var aborted = false

    /**
     * 立即强制终止当前安装（Ctrl+C / 用户取消）。
     * proot 的子进程（busybox / apk）不会随父进程死亡而退出，
     * 需整树 SIGKILL，否则残留进程持有 apk 数据库锁、后续安装卡死。
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
     * 清理上一次中断留下的残留（tmp / apk 锁 / 半包 / 半解包容器），
     * 并复位打断标志，防止后续安装（或 pmc 降级安装）被旧状态污染。
     * 只清「未完成」的产物：已装好的容器（含 bin/busybox）不动。
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
        // 半解包容器：目录存在但没有可用 busybox，说明解包没走完，整棵清掉重来
        try {
            val rootfsDir = File(files, "alpine")
            if (rootfsDir.isDirectory && !File(rootfsDir, "bin/busybox").isFile) {
                deleteQuietly(rootfsDir)
            }
        } catch (_: Exception) {
        }
        // 不在此覆盖 /etc/apk/repositories：用户手动配置的源应保留，
        // 否则每次清理/重装都会被强制改回内置源。
        aborted = false
    }

    // ---- 并发测速选源 ----

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
     *  temporaryMirrors 非空时作为 rootfs 下载源（仅本次引导使用，不落任何配置）；
     *  proxy 为 host:port 形式的 HTTP 代理，空则直连。 */
    fun install(
        context: Context,
        onProgress: (String) -> Unit,
        temporaryMirrors: List<String> = emptyList(),
        proxy: String? = null,
    ) {
        val files = context.filesDir
        // 安装启动瞬间用户可能已按 Ctrl+C：此时 aborted 已置位，需立即退出，
        // 不能无条件清零，否则这次打断会被吞掉，用户得按第二次。
        if (aborted) {
            aborted = false
            throw InstallationInterruptedException()
        }

        checkAbort()
        onProgress("[安装] 释放 proot 运行件…\n")
        extractProot(context, File(files, "proot"))

        val rootfsDir = File(files, "alpine")
        if (!File(rootfsDir, "bin/busybox").isFile) {
            checkAbort()
            onProgress("[安装] 下载 Alpine 容器镜像…\n")
            val tarGz = downloadRootfs(context, onProgress, temporaryMirrors, proxy)
            checkAbort()
            onProgress("[安装] 解包 Alpine 容器…\n")
            try {
                extractRootfs(tarGz, rootfsDir)
            } finally {
                // 成功已解出内容，失败留着也无效：一律删掉，下次重试重新下载
                deleteQuietly(tarGz)
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
        // 预装版 rootfs 已带基础工具，解包后直接可用；只有缺失时才联网装包
        if (File(rootfsDir, "bin/bash").isFile) {
            onProgress("[安装] 基础工具已随镜像预装，跳过联网安装 ✓\n")
        } else {
            onProgress("[安装] 安装基础包（bash / coreutils / findutils / grep / sed / gawk / util-linux，需联网）…\n")
            installBasePackages(context, rootfsDir, onProgress)
        }
        // 安装成功：恢复默认主源，清除本次临时源写入的痕迹
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
        proxy: String? = null,
    ): File {
        val dest = File(context.filesDir, "alpine-rootfs.tar.gz")
        // 候选源：用户填写的优先，否则用维护者仓库；统一规整成可直接取文件的地址
        val bases: List<String> = if (temporaryMirrors.isNotEmpty()) {
            temporaryMirrors.map { normalizeRepoBase(it) }
        } else {
            listOf(DEFAULT_ROOTFS_REPO)
        }
        var lastErr = "未知错误"
        for (base in bases) {
            try {
                val indexUrl = joinUrl(base, INDEX_FILE)
                onProgress("  索引: $indexUrl\n")
                val entry = resolveRootfs(base, proxy)
                val fileUrl = joinUrl(base, entry.name)
                onProgress("  文件: $fileUrl\n")
                val dlStart = System.currentTimeMillis()
                download(fileUrl, dest, proxy) { done, total ->
                    onProgress(progressLine(done, total, System.currentTimeMillis() - dlStart))
                }
                val len = dest.length()
                if (!sizeAcceptable(len, entry.size)) {
                    // 大小与索引不符或明显不是 rootfs（如错误页）：删掉，避免下次误用
                    deleteQuietly(dest)
                    throw IOException("文件大小异常（$len 字节，索引声明 ${entry.size ?: "未知"}）")
                }
                onProgress("  下载完成（${len / 1024 / 1024} MiB）\n")
                return dest
            } catch (e: InstallationInterruptedException) {
                // 打断异常需原样向上抛出，不能被通用 catch 吞掉后继续换源
                throw e
            } catch (e: Exception) {
                lastErr = e.message ?: e.javaClass.simpleName
                onProgress("  失败: $lastErr\n")
            }
        }
        throw IOException("所有下载源均失败：$lastErr")
    }

    /** 索引中的 rootfs 条目：文件名 + 声明大小（可能缺失）。 */
    private data class RootfsEntry(val name: String, val size: Long?)

    /**
     * 判断下载结果大小是否可信：
     *  - 索引声明了 size → 按 ±容差比对，允许魔改或重压缩造成的轻微偏差；
     *  - 索引未声明 size → 只要求不小于兜底下限（挡错误页，不限制真实大小）。
     */
    private fun sizeAcceptable(actual: Long, expected: Long?): Boolean {
        if (expected != null && expected > 0) {
            val lo = (expected * (1.0 - SIZE_TOLERANCE)).toLong().coerceAtLeast(0)
            val hi = (expected * (1.0 + SIZE_TOLERANCE)).toLong()
            return actual in lo..hi
        }
        return actual >= ROOTFS_MIN_BYTES
    }

    /** 拼接仓库地址与文件名。 */
    private fun joinUrl(base: String, name: String): String =
        base.trimEnd('/') + "/" + name.trimStart('/')

    /**
     * 把用户填的仓库地址规整成可直接取文件的 base。
     * 用户可能填网页地址（github.com/<user>/<repo>），该域名不提供文件内容，
     * 自动映射到 raw.githubusercontent.com 的 main 分支；其他地址原样返回。
     */
    private fun normalizeRepoBase(raw: String): String {
        val s = raw.trim().trimEnd('/')
        val prefix = "https://github.com/"
        if (!s.startsWith(prefix)) return s
        val path = s.removePrefix(prefix)
        // 只处理 <user>/<repo> 两段式，带 /tree/ 等后缀的交给用户自己填 raw 地址
        val segs = path.split('/').filter { it.isNotEmpty() }
        if (segs.size != 2) return s
        return "https://raw.githubusercontent.com/${segs[0]}/${segs[1]}/main"
    }

    /**
     * 从仓库 index.json 读取 rootfs 条目（文件名 + 声明大小）。
     * 取 rootfs 数组第一项（约定为最新版）；索引不可用时抛出异常，交由上层换源。
     */
    private fun resolveRootfs(base: String, proxy: String?): RootfsEntry {
        val indexUrl = joinUrl(base, INDEX_FILE)
        val text = httpGetText(indexUrl, proxy)
        val name = Json.extractFirstArrayObjectString(text, "rootfs", "name")
            ?: throw IOException("index.json 中缺少 rootfs.name")
        if (name.isBlank()) throw IOException("index.json 中 rootfs.name 为空")
        val size = Json.extractFirstArrayObjectLong(text, "rootfs", "size")
        return RootfsEntry(name, size)
    }

    /** 拉取文本（index.json 等）。 */
    private fun httpGetText(url: String, proxy: String?): String {
        val conn = openConnection(url, proxy, 10_000, 10_000)
        try {
            val code = conn.responseCode
            // 错误信息带上完整 URL，便于用户判断是地址填错还是网络不通
            if (code !in 200..299) throw IOException("HTTP $code（$url）")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    /** 构造带可选代理的 HTTP 连接。 */
    private fun openConnection(
        url: String,
        proxy: String?,
        connectMs: Int,
        readMs: Int,
    ): HttpURLConnection {
        // 代理只能在建立连接时指定（HttpURLConnection 没有可写的 proxy 属性）
        val p = parseProxy(proxy)
        val raw = if (p != null) URL(url).openConnection(p) else URL(url).openConnection()
        val conn = raw as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        return conn
    }

    /** 解析 "host:port" 形式的代理地址；空或非法返回 null（即直连）。 */
    private fun parseProxy(proxy: String?): Proxy? {
        val raw = proxy?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val hostPort = raw.removePrefix("http://").removePrefix("HTTP://").trimEnd('/')
        val idx = hostPort.lastIndexOf(':')
        if (idx <= 0 || idx == hostPort.length - 1) return null
        val host = hostPort.substring(0, idx)
        val port = hostPort.substring(idx + 1).toIntOrNull() ?: return null
        if (port !in 1..65535) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    /** 生成单行进度文本（由 View 层原地覆盖上一行）。 */
    private fun progressLine(done: Long, total: Long, elapsedMs: Long = 0): String {
        val speed = if (elapsedMs > 500) formatBytes(done * 1000 / elapsedMs) + "/s" else "—"
        if (total <= 0) {
            return "$PROGRESS_PREFIX ${formatBytes(done)}  $speed\n"
        }
        val pct = (done * 100 / total).toInt().coerceIn(0, 100)
        val filled = (pct * PROGRESS_BAR_WIDTH / 100).coerceIn(0, PROGRESS_BAR_WIDTH)
        val bar = "#".repeat(filled) + "-".repeat(PROGRESS_BAR_WIDTH - filled)
        return "$PROGRESS_PREFIX [$bar] $pct%  ${formatBytes(done)}/${formatBytes(total)}  $speed\n"
    }

    private fun download(
        urlStr: String,
        dest: File,
        proxy: String? = null,
        onProgress: ((Long, Long) -> Unit)? = null,
    ) {
        var completed = false
        val conn = openConnection(urlStr, proxy, 8_000, 5_000)
        try {
            val code = conn.responseCode
            // 错误信息带上完整 URL，便于用户判断是地址填错还是网络不通
            if (code !in 200..299) throw IOException("HTTP $code（$urlStr）")
            val total = conn.contentLengthLong
            val buf = ByteArray(65_536)
            var done = 0L
            var lastReport = 0L
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
                        done += n
                        // 节流：每 256KB 或最后一块才上报，避免高频重绘拖慢主线程
                        if (done - lastReport >= PROGRESS_STEP_BYTES) {
                            lastReport = done
                            onProgress?.invoke(done, total)
                        }
                    }
                    onProgress?.invoke(done, total)
                }
            }
            completed = true
        } finally {
            conn.disconnect()
            // 未完成（打断 / 网络中断 / 校验失败）时清掉半截文件，避免占空间与误判
            if (!completed) deleteQuietly(dest)
        }
    }

    /** 静默删除文件或目录树，失败不抛异常。 */
    private fun deleteQuietly(target: File) {
        try {
            if (target.isDirectory) {
                target.listFiles()?.forEach { deleteQuietly(it) }
            }
            target.delete()
        } catch (_: Exception) {
        }
    }

    // ---- rootfs 解包（系统 toybox tar，已验证支持 gzip + symlink）----

    private fun extractRootfs(tarGz: File, rootfsDir: File) {
        // 与 install() 用同一判据：目录里没有 bin/busybox 就说明这是未完成的容器，
        // 失败时整棵删掉；已有可用容器的重装场景不动。
        val hadUsableRootfs = File(rootfsDir, "bin/busybox").isFile
        rootfsDir.mkdirs()
        var ok = false
        try {
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
            ok = true
        } finally {
            // 解包失败且本次没有可复用的容器：整棵删掉，避免半成品被 isInstalled() 误判为已安装
            if (!ok && !hadUsableRootfs) deleteQuietly(rootfsDir)
        }
    }

    // ---- 网络配置 ----

    /**
     * 构造 /etc/apk/repositories 内容：同一 base 同时含 main 与 community。
     * 缺 community 会导致 util-linux、bash-completion 等社区包解析不到而安装失败。
     */
    private fun buildRepositoriesContent(base: String): String {
        val b = base.trimEnd('/')
        return "$b/main\n$b/community\n"
    }

    /** 离线版 repositories：源全部注释掉，apk 只读本地数据库，不会联网卡住。 */
    private fun buildOfflineRepositoriesContent(): String {
        val b = OFFICIAL_CDN.trimEnd('/')
        return "#$b/main\n#$b/community\n"
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
        // 默认离线：源写成注释状态，避免 apk list / version 这类查询命令联网卡住。
        // 需要装包时用 apk-online 启用，或用 apk-offline 关回去。
        File(apk, "repositories").writeText(buildOfflineRepositoriesContent())
        writeApkSwitchers(rootfsDir)
    }

    /**
     * 写入 apk-online / apk-offline 两个切换脚本。
     * 默认离线是为了让 apk 查询类命令立即返回；装包前用 apk-online 打开官方源。
     */
    private fun writeApkSwitchers(rootfsDir: File) {
        val binDir = File(rootfsDir, "usr/local/bin")
        binDir.mkdirs()
        val online = File(binDir, "apk-online")
        online.writeText(
            "#!/bin/sh\n" +
                "# 取消注释官方源，允许 apk update / add 联网操作\n" +
                "sed -i 's|^#\\(https\\)|\\1|' /etc/apk/repositories\n" +
                "echo '已启用官方源，可执行 apk update'\n",
        )
        val offline = File(binDir, "apk-offline")
        offline.writeText(
            "#!/bin/sh\n" +
                "# 注释掉所有源，恢复离线模式（查询类命令不再联网）\n" +
                "sed -i 's|^\\(https\\)|#\\1|' /etc/apk/repositories\n" +
                "echo '已切换离线模式'\n",
        )
        for (f in listOf(online, offline)) {
            f.setExecutable(true, false)
            f.setReadable(true, false)
        }
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

    private fun prootProcess(
        context: Context,
        rootfsDir: File,
        prootDir: File,
        args: List<String>,
        proxy: String? = null,
    ): ProcessBuilder {
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
            // 代理只对本次命令生效，不改容器内任何持久化配置
            val p = proxy?.trim().orEmpty()
            if (p.isNotEmpty()) {
                put("http_proxy", "http://$p")
                put("https_proxy", "http://$p")
                put("HTTP_PROXY", "http://$p")
                put("HTTPS_PROXY", "http://$p")
            }
        }
        return pb
    }

    /** 容器命令执行结果（finished=false 表示超时被杀，exitCode=-1）。 */
    data class ExecResult(val finished: Boolean, val exitCode: Int, val output: String)

    /** 在 Alpine 容器内执行一条命令并收集输出（pmc 降级安装等场景复用）。 */
    fun execInAlpine(
        context: Context,
        args: List<String>,
        timeoutSec: Long = 180,
        proxy: String? = null,
    ): ExecResult {
        if (aborted) return ExecResult(false, -1, "已中断")
        val files = context.filesDir
        val rootfsDir = File(files, "alpine")
        val prootDir = File(files, "proot")
        val p = try {
            prootProcess(context, rootfsDir, prootDir, args, proxy).start()
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
     * 带实时输出的容器命令执行：边跑边把输出行交给 onLine，用于装包阶段显示进度。
     * 返回完整输出与退出码；超时或中断时 finished=false。
     */
    private fun execInAlpineStreaming(
        context: Context,
        args: List<String>,
        timeoutSec: Long,
        onLine: (String) -> Unit,
    ): ExecResult {
        if (aborted) return ExecResult(false, -1, "已中断")
        val files = context.filesDir
        val rootfsDir = File(files, "alpine")
        val prootDir = File(files, "proot")
        val p = try {
            prootProcess(context, rootfsDir, prootDir, args, null).start()
        } catch (e: Exception) {
            return ExecResult(false, -1, "启动失败: ${e.message}")
        }
        currentProcess = p
        val sb = StringBuilder()
        val reader = Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    sb.append(line).append('\n')
                    onLine(line)
                }
            } catch (_: Exception) {
                // 进程被杀时流会提前关闭，忽略
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = waitForProcess(p, timeoutSec)
        if (!finished) killProcessTree(p)
        reader.join(2_000)
        if (currentProcess === p) currentProcess = null
        if (aborted) return ExecResult(false, -1, "已中断")
        return ExecResult(finished, if (finished) p.exitValue() else -1, sb.toString())
    }

    /**
     * 首次安装自动装基础包，按「维护者仓库优先、官方 CDN 兜底」两段式执行：
     *  1. 维护者仓库放在 GitHub raw，小文件稳定，作为首选；
     *  2. 失败再试 Alpine 官方 CDN；
     *  3. 每轮开始前检查中断标志。
     */
    private fun installBasePackages(
        context: Context,
        rootfsDir: File,
        onProgress: (String) -> Unit,
    ) {
        val reposFile = File(rootfsDir, "etc/apk/repositories")

        // 维护者仓库的 apk 目录是自建索引，没有官方签名，需要 --allow-untrusted
        val sources = listOf(
            Triple("维护者仓库", joinUrl(DEFAULT_ROOTFS_REPO, "apk"), true),
            Triple("官方 CDN", OFFICIAL_CDN, false),
        )

        var lastErr = "未知错误"
        for ((name, base, untrusted) in sources) {
            checkAbort()
            onProgress("  源[$name] $base\n")
            try {
                reposFile.writeText(buildRepositoriesContent(base))
            } catch (e: Exception) {
                lastErr = "源配置写入失败（$name）: ${e.message}"
                continue
            }
            if (installFromCurrentRepo(context, rootfsDir, onProgress, name, untrusted)) {
                return
            }
            lastErr = "$name 安装失败"
        }
        checkAbort()
        throw IOException("所有下载源均失败：$lastErr")
    }

    /**
     * 在当前 repositories 配置下装包：apk update → 装 bash → 装工具链。
     * 返回 true 表示装成功。untrusted=true 时加 --allow-untrusted（本地仓库无官方签名）。
     */
    private fun installFromCurrentRepo(
        context: Context,
        rootfsDir: File,
        onProgress: (String) -> Unit,
        label: String,
        untrusted: Boolean,
    ): Boolean {
        val trust = if (untrusted) listOf("--allow-untrusted") else emptyList()

        val upd = execInAlpineStreaming(
            context,
            listOf("/bin/busybox", "timeout", "20", "/sbin/apk", "update") + trust,
            30,
        ) { line -> onProgress("    $line\n") }
        checkAbort()
        if (!upd.finished || upd.exitCode != 0) {
            onProgress("  [!] apk update 失败（$label, exit=${upd.exitCode}）\n")
            return false
        }

        // 单独装 bash：体积小，最容易成功，失败能快速换源
        if (!apkAddWithProgress(context, trust + listOf("bash"), onProgress, "$label · bash")) {
            return false
        }

        if (!apkAddWithProgress(
                context,
                trust + listOf("coreutils", "findutils", "grep", "sed", "gawk", "util-linux"),
                onProgress,
                "$label · 工具链",
            )
        ) {
            return false
        }
        if (!File(rootfsDir, "bin/bash").isFile) {
            onProgress("  [!] 装包完成但 bin/bash 缺失（$label）\n")
            return false
        }
        onProgress("  bash / coreutils / findutils / grep / sed / gawk / util-linux 就绪 ✓\n")
        return true
    }

    /**
     * 执行 apk add 并把下载进度转成原地刷新的进度行。
     * apk 的输出形如 `(3/22) Installing bash (5.3.9-r1)`，据此算已完成包数；
     * 同时统计已下载字节数，按秒计算速度。
     */
    private fun apkAddWithProgress(
        context: Context,
        pkgs: List<String>,
        onProgress: (String) -> Unit,
        label: String,
    ): Boolean {
        val total = pkgs.size
        var done = 0
        var bytes = 0L
        val startAt = System.currentTimeMillis()
        var lastEmit = 0L

        val res = execInAlpineStreaming(
            context,
            listOf("/sbin/apk", "add") + pkgs,
            300,
        ) { line ->
            // 解析 `(n/m) Installing xxx` 与 `xxx (12%)` 两类进度信息
            val m = Regex("""^\((\d+)/(\d+)\)""").find(line)
            if (m != null) {
                done = m.groupValues[1].toIntOrNull() ?: done
                bytes += Regex("""\((\d+)\s*(KiB|MiB)\)""").find(line)
                    ?.let { g ->
                        val v = g.groupValues[1].toLongOrNull() ?: 0L
                        if (g.groupValues[2] == "MiB") v * 1024 * 1024 else v * 1024
                    } ?: 0L
            }
            val now = System.currentTimeMillis()
            if (now - lastEmit >= 250) {
                lastEmit = now
                onProgress(apkProgressLine(label, done, total, bytes, now - startAt))
            }
        }
        checkAbort()
        if (!(res.finished && res.exitCode == 0)) {
            onProgress("  [!] apk add 失败（$label, exit=${res.exitCode}）\n")
            return false
        }
        onProgress(apkProgressLine(label, total, total, bytes, System.currentTimeMillis() - startAt) + "\n")
        return true
    }

    /** 装包阶段的进度行：包数进度 + 已下载体积 + 实时速度。 */
    private fun apkProgressLine(
        label: String,
        done: Int,
        total: Int,
        bytes: Long,
        elapsedMs: Long,
    ): String {
        val ratio = if (total > 0) done.toDouble() / total else 0.0
        val filled = (ratio * PROGRESS_BAR_WIDTH).toInt().coerceIn(0, PROGRESS_BAR_WIDTH)
        val bar = "#".repeat(filled) + "-".repeat(PROGRESS_BAR_WIDTH - filled)
        val pct = (ratio * 100).toInt()
        val size = formatBytes(bytes)
        val speed = if (elapsedMs > 500) formatBytes(bytes * 1000 / elapsedMs) + "/s" else "—"
        return "$PROGRESS_PREFIX [$bar] $pct%  $done/$total  $size  $speed  $label"
    }

    /** 字节数转人类可读（B / KiB / MiB）。 */
    private fun formatBytes(v: Long): String = when {
        v >= 1024L * 1024L -> String.format("%.1f MiB", v / 1024.0 / 1024.0)
        v >= 1024L -> String.format("%.0f KiB", v / 1024.0)
        else -> "$v B"
    }
    /**
     * 安装成功后恢复默认主源：把 /etc/apk/repositories 复原为内置默认源，
     * 使临时源不残留（临时源仅用于本次引导，不持久化）。
     */
    private fun restoreDefaultRepositories(rootfsDir: File) {
        try {
            // 恢复成离线状态，与首次安装一致：避免残留的临时源
            File(rootfsDir, "etc/apk/repositories").writeText(buildOfflineRepositoriesContent())
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