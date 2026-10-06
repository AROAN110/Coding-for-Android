package com.AROAN110.CodingAndroid.resource

import android.content.Context
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** 资源包描述（home/resources/&lt;id&gt;/）。 */
data class ResourceInfo(
    val name: String,
    val id: String,
    val entryPath: String,
)

/** 插件描述（home/plugins/&lt;id&gt;/）。 */
data class PluginInfo(
    val name: String,
    val id: String,
    val entryPath: String,
)

/**
 * 资源包 / 插件 扫描与解析。
 *
 * 目录约定：
 *   filesDir/home/resources/&lt;资源包ID&gt;/manifest.json  (name, id, entry)
 *   filesDir/home/plugins/&lt;插件ID&gt;/manifest.json      (name, id, entry)
 *
 * 解析失败或字段缺失一律跳过并打日志，绝不抛出。
 */
class ResourceManager(context: Context) {

    private val filesDir: File = context.filesDir

    private val resourcesRoot: File get() = File(filesDir, "home/resources")
    private val pluginsRoot: File get() = File(filesDir, "home/plugins")

    /** 扫描资源包目录；目录不存在则创建并返回空列表。 */
    fun scanResources(): List<ResourceInfo> {
        val root = resourcesRoot
        if (!root.exists()) {
            root.mkdirs()
            return emptyList()
        }
        val result = ArrayList<ResourceInfo>()
        val children = root.listFiles() ?: return emptyList()
        for (dir in children) {
            if (!dir.isDirectory) continue
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) {
                Log.w(TAG_RES, "跳过无 manifest 的目录: ${dir.name}")
                continue
            }
            try {
                val json = JSONObject(manifest.readText())
                val id = json.optString("id").ifBlank { dir.name }
                val name = json.optString("name").ifBlank { id }
                val entry = json.optString("entry").ifBlank { "index.html" }
                val entryPath = File(dir, entry).absolutePath
                if (!File(entryPath).isFile) {
                    Log.w(TAG_RES, "资源包入口缺失，跳过: ${dir.name} ($entry)")
                    continue
                }
                result.add(ResourceInfo(name = name, id = id, entryPath = entryPath))
            } catch (e: JSONException) {
                Log.w(TAG_RES, "manifest.json 解析失败，跳过: ${dir.name} (${e.message})")
            } catch (e: IOException) {
                Log.w(TAG_RES, "manifest.json 读取失败，跳过: ${dir.name} (${e.message})")
            } catch (e: Exception) {
                Log.w(TAG_RES, "资源包解析异常，跳过: ${dir.name} (${e.message})")
            }
        }
        return result
    }

    /** 扫描插件目录；目录不存在则创建并返回空列表。 */
    fun scanPlugins(): List<PluginInfo> {
        val root = pluginsRoot
        if (!root.exists()) {
            root.mkdirs()
            return emptyList()
        }
        val result = ArrayList<PluginInfo>()
        val children = root.listFiles() ?: return emptyList()
        for (dir in children) {
            if (!dir.isDirectory) continue
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) {
                Log.w(TAG_RES, "跳过无 manifest 的目录: ${dir.name}")
                continue
            }
            try {
                val json = JSONObject(manifest.readText())
                val id = json.optString("id").ifBlank { dir.name }
                val name = json.optString("name").ifBlank { id }
                val entry = json.optString("entry").ifBlank { "main.js" }
                val entryPath = File(dir, entry).absolutePath
                if (!File(entryPath).isFile) {
                    Log.w(TAG_RES, "插件入口缺失，跳过: ${dir.name} ($entry)")
                    continue
                }
                result.add(PluginInfo(name = name, id = id, entryPath = entryPath))
            } catch (e: JSONException) {
                Log.w(TAG_RES, "插件 manifest 解析失败，跳过: ${dir.name} (${e.message})")
            } catch (e: IOException) {
                Log.w(TAG_RES, "插件 manifest 读取失败，跳过: ${dir.name} (${e.message})")
            } catch (e: Exception) {
                Log.w(TAG_RES, "插件解析异常，跳过: ${dir.name} (${e.message})")
            }
        }
        return result
    }

    /** 读取插件 JS 入口内容；失败返回 null。 */
    fun readPluginScript(entryPath: String): String? {
        return try {
            val f = File(entryPath)
            if (!f.isFile) {
                Log.w(TAG_RES, "插件脚本不存在: $entryPath")
                null
            } else {
                f.readText()
            }
        } catch (e: IOException) {
            Log.w(TAG_RES, "插件脚本读取失败: $entryPath (${e.message})")
            null
        } catch (e: Exception) {
            Log.w(TAG_RES, "插件脚本读取异常: $entryPath (${e.message})")
            null
        }
    }

    companion object {
        private const val TAG_RES = "CFA_RES"
    }
}