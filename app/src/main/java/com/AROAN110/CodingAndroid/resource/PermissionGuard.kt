package com.AROAN110.CodingAndroid.resource

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 权限分级与授权存储。
 *
 * 普通：manifest 声明即生效；高危：声明后默认 denied，需 pmc perm grant；
 * su 恒拒（声明即拒装，运行时也不放行）。
 */
object PermissionGuard {

    const val NORMAL = 0
    const val DANGEROUS = 1
    const val NEVER = 2

    /** 权限 → 级别。 su 是预留位，声明即拒装。 */
    private val LEVELS: Map<String, Int> = mapOf(
        "fs:read" to NORMAL,
        "fs:write" to NORMAL,
        "fs:struct" to NORMAL,
        "workspace" to NORMAL,
        "terminal.read" to NORMAL,
        "network" to NORMAL,
        "clipboard:read" to NORMAL,
        "notification" to NORMAL,
        "terminal.write" to DANGEROUS,
        "shell" to DANGEROUS,
        "su" to NEVER,
    )

    private const val PREFS = "pmc_perm"
    private const val KEY = "grants"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun grants(ctx: Context): MutableMap<String, MutableSet<String>> {
        val raw = prefs(ctx).getString(KEY, null) ?: return HashMap()
        val out = HashMap<String, MutableSet<String>>()
        try {
            val obj = JSONObject(raw)
            for (key in obj.keys()) {
                val set = HashSet<String>()
                val arr = obj.getJSONArray(key)
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                out[key] = set
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveGrants(ctx: Context, grants: Map<String, MutableSet<String>>) {
        val obj = JSONObject()
        for ((k, v) in grants) {
            val arr = JSONArray()
            for (p in v) arr.put(p)
            obj.put(k, arr)
        }
        prefs(ctx).edit().putString(KEY, obj.toString()).apply()
    }

    /** 声明的权限中未知/ su 的项；返回非空即 manifest 不合规。 */
    fun invalidDeclared(perms: List<String>): List<String> =
        perms.filter { LEVELS[it] == null || LEVELS[it] == NEVER }

    /** 高危权限 = 声明后仍需 grant 才可用。 */
    fun isDangerous(perm: String): Boolean = LEVELS[perm] == DANGEROUS

    /**
     * 运行时判定：普通权限看声明，高危权限看声明 + grant。
     * pkg 未知或未声明 → denied。
     */
    fun check(ctx: Context, pkg: String, perm: String, declared: Set<String>): Boolean {
        val level = LEVELS[perm] ?: return false
        if (level == NEVER) return false
        if (perm !in declared) return false
        if (level == NORMAL) return true
        return grants(ctx)[pkg]?.contains(perm) == true
    }

    /** grant：只接受声明过的高危权限。返回错误信息， = 成功。 */
    fun grant(ctx: Context, pkg: String, perms: List<String>, declared: Set<String>): String? {
        for (p in perms) {
            if (LEVELS[p] == null) return "unknown permission: $p"
            if (p !in declared) return "permission not declared: $p"
            if (!isDangerous(p)) return "not a grantable (dangerous) permission: $p"
        }
        val all = grants(ctx)
        val set = all.getOrPut(pkg) { HashSet() }
        set.addAll(perms)
        saveGrants(ctx, all)
        return null
    }

    /** revoke：撤销授权（对普通权限撤销无效果，返回错误）。 */
    fun revoke(ctx: Context, pkg: String, perms: List<String>, declared: Set<String>): String? {
        for (p in perms) {
            if (!isDangerous(p)) return "not a grantable (dangerous) permission: $p"
            if (p !in declared) return "permission not declared: $p"
        }
        val all = grants(ctx)
        val set = all[pkg] ?: return "no grants for $pkg"
        set.removeAll(perms.toSet())
        if (set.isEmpty()) all.remove(pkg)
        saveGrants(ctx, all)
        return null
    }

    /** 包的全部授权集合（无则空集）。 */
    fun grantedSet(ctx: Context, pkg: String): Set<String> =
        grants(ctx)[pkg] ?: emptySet()

    /** 卸载包时清除授权。 */
    fun clearPkg(ctx: Context, pkg: String) {
        val all = grants(ctx)
        if (all.remove(pkg) != null) saveGrants(ctx, all)
    }

    /** 从目录 manifest 读声明权限（manifest 缺失返回空集）。 */
    fun declaredOf(dir: File): Set<String> {
        val f = File(dir, "manifest.json")
        if (!f.isFile) return emptySet()
        return try {
            val arr = JSONObject(f.readText()).optJSONArray("permissions")
            (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }
}