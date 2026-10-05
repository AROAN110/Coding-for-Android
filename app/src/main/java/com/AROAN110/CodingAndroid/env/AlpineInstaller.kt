package com.AROAN110.CodingAndroid.env

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

    /** 是否已安装完成（可安全启动）。 */
    fun isInstalled(context: Context): Boolean {
        val f = context.filesDir
        return File(f, "alpine/bin/busybox").isFile &&
            File(f, "proot/proot").isFile &&
            File(f, "alpine/etc/apk/repositories").isFile &&
            File(f, "alpine/etc/resolv.conf").isFile &&
            File(f, "proot/.smoketest").isFile
    }

    /** 执行安装（阻塞，需在后台线程调用）。onProgress 用于输出进度文本。 */
    fun install(context: Context, onProgress: (String) -> Unit) {
        val files = context.filesDir

        onProgress("[安装] 释放 proot 运行件…\n")
        extractProot(context, File(files, "proot"))

        val rootfsDir = File(files, "alpine")
        if (!File(rootfsDir, "bin/busybox").isFile) {
            onProgress("[安装] 下载 Alpine minirootfs（约 4MB）…\n")
            val tarGz = downloadRootfs(context, onProgress)
            onProgress("[安装] 解包 Alpine 容器（515 个条目）…\n")
            extractRootfs(tarGz, rootfsDir)
            try {
                tarGz.delete()
            } catch (_: Exception) {
            }
        } else {
            onProgress("[安装] Alpine 容器已存在，跳过下载\n")
        }

        onProgress("[安装] 写入网络配置…\n")
        configureRootfs(rootfsDir)
        File(files, "tmp").mkdirs()
        onProgress("[安装]验证 proot 可执行性（关键检测）…\n")
        smokeTest(context, rootfsDir, File(files, "proot"))
        onProgress("[安装] 安装基础包（bash / coreutils / findutils / grep / sed / gawk，需联网）…\n")
        installBasePackages(context, rootfsDir, File(files, "proot"), onProgress)
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

    private fun downloadRootfs(context: Context, onProgress: (String) -> Unit): File {
        val dest = File(context.filesDir, "alpine-rootfs.tar.gz")
        var lastErr = "未知错误"
        for (url in ROOTFS_URLS) {
            try {
                onProgress("  源: $url\n")
                download(url, dest)
                val len = dest.length()
                if (len < ROOTFS_MIN_BYTES) throw IOException("文件过小（$len 字节）")
                onProgress("  下载完成（${len / 1024 / 1024} MiB）\n")
                return dest
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
            // 连接成功后持续下载直到文件完整；绝不中途掐断
            readTimeout = 0
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "CodingForAndroid/0.1.2")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            conn.inputStream.use { inp ->
                dest.outputStream().use { os -> inp.copyTo(os, bufferSize = 65_536) }
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
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(180, TimeUnit.SECONDS)
        if (!ok) {
            p.destroy()
            throw IOException("解包超时")
        }
        if (p.exitValue() != 0) {
            throw IOException("解包失败（exit=${p.exitValue()}）: ${out.take(200)}")
        }
        if (!File(rootfsDir, "bin/busybox").isFile) {
            throw IOException("解包结果不完整（bin/busybox 缺失）")
        }
    }

    // ---- 网络配置 ----

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
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/community\n",
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
        val finished = p.waitFor(30, TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
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
        val files = context.filesDir
        val rootfsDir = File(files, "alpine")
        val prootDir = File(files, "proot")
        val p = try {
            prootProcess(context, rootfsDir, prootDir, args).start()
        } catch (e: Exception) {
            return ExecResult(false, -1, "启动失败: ${e.message}")
        }
        val finished = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) p.destroyForcibly()
        val out = try {
            p.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        return ExecResult(finished, if (finished) p.exitValue() else -1, out)
    }

    /**
     * 首次安装自动装基础包：按 APK_SOURCES 强制迭代。
     * 每源流程：重写 /etc/apk/repositories → busybox timeout 10 apk update → apk add；
     * 任一环节失败立刻换下一源，全部失败才抛错（以 bin/bash 就位为最终判据）。
     */
    private fun installBasePackages(context: Context, rootfsDir: File, prootDir: File, onProgress: (String) -> Unit) {
        val reposFile = File(rootfsDir, "etc/apk/repositories")
        var lastErr = "未知错误"
        for ((name, base) in APK_SOURCES) {
            onProgress("  源[$name] $base\n")
            try {
                reposFile.writeText("$base/main\n$base/community\n")
            } catch (e: Exception) {
                lastErr = "源配置写入失败（$name）: ${e.message}"
                continue
            }
            val upd = execInAlpine(
                context,
                listOf("/bin/busybox", "timeout", "10", "/sbin/apk", "update"),
                20,
            )
            if (!upd.finished || upd.exitCode != 0) {
                lastErr = if (!upd.finished) {
                    "apk update 超时（$name）"
                } else {
                    "apk update 失败（$name, exit=${upd.exitCode}）"
                }
                onProgress("  [!] $lastErr，换下一源\n")
                continue
            }
            val add = execInAlpine(
                context,
                listOf("/sbin/apk", "add", "--no-cache", "bash", "coreutils", "findutils", "grep", "sed", "gawk"),
                300,
            )
            if (add.finished && add.exitCode == 0 && File(rootfsDir, "bin/bash").isFile) {
                onProgress("  bash / coreutils / findutils / grep / sed / gawk 就绪 ✓\n")
                return
            }
            lastErr = if (!add.finished) "apk add 超时（$name）" else "apk add 失败（$name, exit=${add.exitCode}）"
            onProgress("  [!] $lastErr，换下一源\n")
        }
        throw IOException("所有镜像源均失败：$lastErr")
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