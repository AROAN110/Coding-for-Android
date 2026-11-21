package com.AROAN110.CodingAndroid.repo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 官方资源包拉取器。
 *
 * 流程：拉 index.json → 取 resources 段中 default=true 的包 → 下载 →
 * sha256 校验 → 解压落位 filesDir/home/resources/default/。
 * 文件名与哈希全部来自 index，本地零硬编码，便于云端动态更新。
 */
object OfficialResourceFetcher {
    private const val TAG = "CFA_FETCH"

    class Result(val installed: Boolean, val message: String)

    /** 后台线程调用。repoUrl 为空（用户未配置仓库）时静默跳过。 */
    fun fetchDefault(context: Context, repoUrl: String): Result {
        if (repoUrl.isBlank()) return Result(false, "仓库未配置")
        return try {
            val indexText = ResourceRepository.fetchIndex(repoUrl)
            val entry = findDefaultEntry(JSONObject(indexText))
                ?: return Result(false, "索引中无默认资源包")
            installFromEntry(context, repoUrl, entry)
        } catch (e: Exception) {
            Log.w(TAG, "官方资源包拉取失败: ${e.message}")
            Result(false, e.message ?: "未知错误")
        }
    }

    private fun findDefaultEntry(index: JSONObject): JSONObject? {
        val arr = index.optJSONArray("resources") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("default", false)) return o
        }
        return null
    }

    private fun installFromEntry(context: Context, repoUrl: String, entry: JSONObject): Result {
        val name = entry.optString("name")
        if (name.isBlank() || name.contains('/') || name.contains("\\\\")) {
            return Result(false, "索引包名非法")
        }
        val expectSha = entry.optString("sha256")
        val cacheDir = File(context.filesDir, RepositoryConfig.CACHE_DIR)
        val zip = ResourceRepository.downloadPackage(repoUrl, cacheDir, name)
        try {
            if (expectSha.length == 64) {
                val actual = ResourceRepository.sha256(zip)
                if (!actual.equals(expectSha, ignoreCase = true)) {
                    zip.delete()
                    return Result(false, "sha256 校验失败")
                }
            }
            val resRoot = File(context.filesDir, "home/resources")
            val dest = File(resRoot, "default")
            val tmp = File(resRoot, ".default.tmp")
            tmp.deleteRecursively()
            ResourceRepository.unzipPackage(zip, tmp)
            // 原子落位：先解到临时目录再替换
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) {
                // rename 跨设备失败兜底：直接复制
                tmp.copyRecursively(dest, overwrite = true)
                tmp.deleteRecursively()
            }
            return Result(true, "官方资源包 ${entry.optString("id", "?")} v${entry.optString("version", "?")} 安装完成")
        } finally {
            zip.delete()
        }
    }
}