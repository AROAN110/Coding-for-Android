package com.AROAN110.CodingAndroid.resource

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * zip 包安装器：校验 manifest → 按 type 落库。
 * 任一校验不过直接抛 InstallException，不产生半安装状态
 * （先解压到临时目录，全部通过后原子改名）。
 */
class PackageInstaller(private val ctx: Context) {

    class InstallException(message: String) : Exception(message)

    data class Installed(val type: String, val id: String, val dir: File)

    private val resourcesRoot: File get() = File(ctx.filesDir, "home/resources")
    private val pluginsRoot: File get() = File(ctx.filesDir, "home/plugins")

    /** 从 zip 文件安装。失败抛 InstallException。 */
    fun installZip(zip: File): Installed {
        val manifest = readManifest(zip)
            ?: throw InstallException("manifest.json missing or invalid")
        val type = manifest.optString("type")
        val id = manifest.optString("id")
        val entry = manifest.optString("entry")
        val minApp = manifest.optString("minAppVersion")
        val perms = manifest.optJSONArray("permissions")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()

        if (type != "resource" && type != "plugin") {
            throw InstallException("invalid type: $type")
        }
        if (!id.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) {
            throw InstallException("invalid id: $id")
        }
        if (entry.isBlank()) throw InstallException("entry missing")
        if (minApp.isBlank()) throw InstallException("minAppVersion missing")
        val bad = PermissionGuard.invalidDeclared(perms)
        if (bad.isNotEmpty()) throw InstallException("invalid permissions: $bad")

        val destRoot = if (type == "resource") resourcesRoot else pluginsRoot
        val tmp = File(destRoot, ".$id.tmp")
        val dest = File(destRoot, id)
        tmp.deleteRecursively()
        dest.deleteRecursively()
        tmp.mkdirs()
        try {
            ZipFile(zip).use { zf ->
                val base = tmp.canonicalFile
                var entryFound = false
                for (e in zf.entries()) {
                    val out = File(base, e.name)
                    val canon = out.canonicalFile
                    if (canon != base && !canon.path.startsWith(base.path + File.separator)) {
                        throw InstallException("zip path traversal: ${e.name}")
                    }
                    if (canon.path == File(base, entry).canonicalFile.path) entryFound = true
                    if (e.isDirectory) {
                        canon.mkdirs()
                    } else {
                        canon.parentFile?.mkdirs()
                        zf.getInputStream(e).use { input -> canon.outputStream().use { o -> input.copyTo(o) } }
                    }
                }
                if (!entryFound) throw InstallException("entry file missing in zip: $entry")
            }
            // 原子落位：同 id 覆盖升级
            if (!tmp.renameTo(dest)) {
                dest.deleteRecursively()
                if (!tmp.renameTo(dest)) throw InstallException("install failed: rename")
            }
        } catch (e: Exception) {
            tmp.deleteRecursively()
            if (e is InstallException) throw e
            throw InstallException("install failed: ${e.message}")
        }
        return Installed(type, id, dest)
    }

    private fun readManifest(zip: File): JSONObject? = try {
        ZipFile(zip).use { zf ->
            val e = zf.getEntry("manifest.json") ?: return@use null
            JSONObject(zf.getInputStream(e).bufferedReader().readText())
        }
    } catch (_: Exception) {
        null
    }

    /** 卸载：删目录 + 清授权。返回是否删除了东西。 */
    fun uninstall(type: String, id: String): Boolean {
        val root = if (type == "resource") resourcesRoot else pluginsRoot
        val dir = File(root, id)
        val removed = dir.exists() && dir.deleteRecursively()
        PermissionGuard.clearPkg(ctx, id)
        return removed
    }
}