package com.AROAN110.CodingAndroid.repo

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 云端资源仓库客户端（骨架）。
 *
 * 本轮先打通三条链路：
 *  1. 拉取仓库索引（index.json）
 *  2. 下载资源包到本地缓存
 *  3. SHA256 完整性校验（Roadmap 阶段3 要求：防网络劫持）
 *
 * 线程约定：本类全部方法为**同步阻塞**，只能在后台线程调用。
 * （调用方 PmcCommandHandler 已负责切线程与回主线程回调。）
 *
 * 资源仓库地址当前为占位空链接（见 RepositoryConfig），正式定案后替换。
 */
object ResourceRepository {

    class NetworkException(message: String) : IOException(message)

    /** 拉取索引文本；仓库未配置时抛出 NetworkException。 */
    fun fetchIndex(repoUrl: String): String {
        requireConfigured(repoUrl)
        return httpGetText(joinUrl(repoUrl, RepositoryConfig.INDEX_FILE))
    }

    /** 下载资源包到缓存目录，返回落盘文件。 */
    fun downloadPackage(repoUrl: String, cacheDir: File, fileName: String): File {
        requireConfigured(repoUrl)
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) {
            throw IOException("无法创建缓存目录: ${cacheDir.absolutePath}")
        }
        val dest = File(cacheDir, fileName)
        httpDownload(joinUrl(repoUrl, fileName), dest)
        return dest
    }

    /** 计算文件 SHA256（十六进制小写）。 */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** 解压 zip 到目标目录（防路径穿越），返回解压条目数。 */
    fun unzipPackage(zip: File, destDir: File): Int {
        val base = destDir.canonicalFile
        destDir.mkdirs()
        var count = 0
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val out = File(base, e.name)
                val canon = try {
                    out.canonicalFile
                } catch (_: Exception) {
                    null
                }
                if (canon != null && (canon == base || canon.path.startsWith(base.path + File.separator))) {
                    if (e.isDirectory) {
                        canon.mkdirs()
                    } else {
                        canon.parentFile?.mkdirs()
                        canon.outputStream().use { os -> zis.copyTo(os) }
                    }
                    count++
                }
                e = zis.nextEntry
            }
        }
        return count
    }

    // —— 内部 ——

    private fun requireConfigured(repoUrl: String) {
        if (repoUrl.isBlank()) {
            throw NetworkException("资源仓库地址未配置（当前为占位空链接）")
        }
    }

    private fun joinUrl(base: String, path: String): String =
        base.trimEnd('/') + "/" + path.trimStart('/')

    private fun openConnection(url: String, connectMs: Int, readMs: Int): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectMs
        conn.readTimeout = readMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Coding-for-Android/0.1")
        return conn
    }

    /** 候选地址：GitHub 域先走加速前缀（按序），其余 URL 原样。 */
    private fun candidates(url: String): List<String> {
        val isGitHub = url.contains("github.com") || url.contains("githubusercontent.com")
        if (!isGitHub || RepositoryConfig.MIRRORS.isEmpty()) return listOf(url)
        return RepositoryConfig.MIRRORS.map { it + url } + url
    }

    /** 依次尝试候选地址，返回首个成功结果；全部失败抛最后一个异常。 */
    private fun <T> withCandidates(url: String, block: (String) -> T): T {
        var lastErr: Exception? = null
        for (cand in candidates(url)) {
            try {
                return block(cand)
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw (lastErr ?: NetworkException("请求失败: $url"))
    }

    /** 拉取文本（index.json 等）：连接 10s / 响应 10s 为请求超时，无整体下载时限。 */
    private fun httpGetText(url: String): String = withCandidates(url) { cand ->
        val conn = openConnection(cand, 10_000, 10_000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw NetworkException("HTTP $code: $cand")
            conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }

    /** 下载文件：连接 10s；连接成功后持续下载（read 超时仅限制帧间隔，不掐断整体）。 */
    private fun httpDownload(url: String, dest: File) = withCandidates(url) { cand ->
        val conn = openConnection(cand, 10_000, 30_000)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw NetworkException("HTTP $code: $cand")
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } finally {
            conn.disconnect()
        }
    }
}