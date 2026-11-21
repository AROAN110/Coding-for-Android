package com.AROAN110.CodingAndroid.bridge

import android.content.Context
import com.AROAN110.CodingAndroid.env.AlpineInstaller
import com.AROAN110.CodingAndroid.resource.PermissionGuard
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * HTTP 响应载体。
 */
data class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String = "application/json; charset=utf-8",
    val body: String,
)

/**
 * 统一 JSON 路由器（接口面写死）。
 *
 * action 白名单 + 权限门禁 + CWD 沙箱，构成资源包/插件唯一可用通道。
 * 协议见 docs/RESOURCE_AND_PLUGIN_SPEC.md。
 * 未注册的 action → unknown_action，终端侧显示为「资源包错误」。
 */
class Router(private val appContext: Context? = null) {

    /** action → (所需权限, 处理器)。perm = null 表示免权限。 */
    private data class Route(val perm: String?, val handler: (String, String) -> String)
    private val handlers = HashMap<String, Route>()

    companion object {
        /** 终端写入回调（MainActivity 启动会话时挂上）。 */
        @Volatile
        var terminalHook: ((String) -> Unit)? = null

        /**
         * 终端当前工作目录（由终端侧同步）。fs 类请求的 path 需落在此目录内。
         * 空串 = 未同步，fs 类请求一律拒绝（默认拒绝，不默认放行 /sdcard）。
         */
        @Volatile
        var currentCwd: String = ""

        const val FS_READ_LIMIT = 256 * 1024
        const val FS_WRITE_LIMIT = 256 * 1024

        /** workspace.import 允许的工程根路径。 */
        val WORKSPACE_ROOTS = listOf(
            "/sdcard/Download/Projects/",
            "/data/media/0/Projects/",
        )
    }

    init {
        register("ping", null) { _, _ -> "\"pong\"" }
        register("fs.list", "fs:read") { _, p -> fsList(p) }
        register("fs.read", "fs:read") { _, p -> fsRead(p) }
        register("fs.write", "fs:write") { _, p -> fsWrite(p) }
        register("fs.mkdir", "fs:struct") { _, p -> fsMkdir(p) }
        register("fs.delete", "fs:struct") { _, p -> fsDelete(p) }
        register("terminal.status", "terminal.read") { _, _ -> terminalStatus() }
        register("terminal.write", "terminal.write") { _, p -> terminalWrite(p) }
        register("workspace.import", "workspace") { _, p -> workspaceImport(p) }
        register("workspace.export", "workspace") { _, _ -> error("not_implemented") }
        register("net.fetch", "network") { _, p -> netFetch(p) }
    }

    fun register(action: String, perm: String?, handler: (id: String, paramsJson: String) -> String) {
        handlers[action] = Route(perm, handler)
    }

    fun dispatch(method: String, path: String, body: String): HttpResponse {
        if (body.isBlank()) {
            return errorResponse("", "empty_body", "请求体为空")
        }
        val id = Json.extractString(body, "id") ?: ""
        val action = Json.extractString(body, "action")
            ?: return errorResponse(id, "bad_request", "缺少 action 字段")
        val pkg = Json.extractString(body, "pkg")
        val route = handlers[action]
            ?: return errorResponse(id, "unknown_action", "未知 action: $action")

        // 权限门禁：免权限 action 直过；其余需 pkg + 声明 + （高危时）grant
        if (route.perm != null) {
            val ctx = appContext
                ?: return errorResponse(id, "handler_error", "router not initialized with context")
            val declared = pkg?.let { declaredOf(it) } ?: emptySet()
            if (!PermissionGuard.check(ctx, pkg ?: "", route.perm, declared)) {
                val hint = if (PermissionGuard.isDangerous(route.perm)) {
                    " (grant via: pmc perm grant $pkg ${route.perm})"
                } else ""
                return errorResponse(
                    id, "permission_denied",
                    "action '$action' requires permission '${route.perm}'$hint",
                )
            }
        }

        val params = Json.extractObject(body, "params") ?: "{}"
        return try {
            val data = route.handler(id, params)
            HttpResponse(200, "OK", body = """{"id":"$id","status":"ok","data":$data}""")
        } catch (e: DomainError) {
            errorResponse(id, e.code, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            errorResponse(id, "handler_error", e.message ?: e.javaClass.simpleName)
        }
    }

    /** handler 内抛出即产生对应错误码回包。 */
    class DomainError(val code: String, message: String) : Exception(message)

    private fun error(code: String, message: String = code): Nothing = throw DomainError(code, message)

    private fun errorResponse(id: String, code: String, message: String): HttpResponse {
        val status = if (code == "forbidden") 403 else 200
        val reason = if (status == 403) "Forbidden" else "OK"
        return HttpResponse(
            status, reason,
            // JsonEscape.str 自带首尾引号，此处不能重复包裹
            body = """{"id":"$id","status":"error","code":"$code","message":${JsonEscape.str(message)}}""",
        )
    }

    /** 从落库目录读声明权限（resources 优先，再 plugins；都不存在返回空集）。 */
    private fun declaredOf(pkg: String): Set<String> {
        val ctx = appContext ?: return emptySet()
        val res = File(ctx.filesDir, "home/resources/$pkg")
        if (res.isDirectory) return PermissionGuard.declaredOf(res)
        val plug = File(ctx.filesDir, "home/plugins/$pkg")
        if (plug.isDirectory) return PermissionGuard.declaredOf(plug)
        return emptySet()
    }

    // —— CWD 沙箱 ——

    /** canonical 化并校验 target 是否位于 currentCwd 内，越界即 forbidden。cwd 未同步也拒绝。 */
    private fun sandboxed(raw: String): File {
        val cwd = currentCwd
        if (cwd.isBlank()) error("forbidden", "cwd not synchronized")
        val base = File(cwd).canonicalFile
        val target = File(base, raw).canonicalFile
        val b = base.path
        val t = target.path
        if (t != b && !t.startsWith(b + File.separator)) {
            error("forbidden", "路径越界：$raw")
        }
        return target
    }

    // —— fs actions ——

    private fun fsList(params: String): String {
        val p = Json.extractString(params, "path") ?: error("bad_request", "缺少 path")
        val dir = sandboxed(p)
        if (!dir.isDirectory) error("bad_request", "not a directory: $p")
        val entries = dir.listFiles()?.map { f ->
            val type = if (f.isDirectory) "dir" else "file"
            """{"name":${JsonEscape.str(f.name)},"type":"$type","size":${if (f.isFile) f.length() else 0}}"""
        } ?: emptyList()
        return "{\"entries\":[${entries.joinToString(",")}]}"
    }

    private fun fsRead(params: String): String {
        val p = Json.extractString(params, "path") ?: error("bad_request", "缺少 path")
        val f = sandboxed(p)
        if (!f.isFile) error("bad_request", "not a file: $p")
        if (f.length() > FS_READ_LIMIT) error("bad_request", "file too large (limit $FS_READ_LIMIT bytes)")
        val content = f.readText(Charsets.UTF_8)
        return """{"content":${JsonEscape.str(content)}}"""
    }

    private fun fsWrite(params: String): String {
        val p = Json.extractString(params, "path") ?: error("bad_request", "缺少 path")
        val content = Json.extractString(params, "content") ?: error("bad_request", "缺少 content")
        if (content.length > FS_WRITE_LIMIT) error("bad_request", "content too large")
        val f = sandboxed(p)
        f.parentFile?.mkdirs()
        f.writeText(content, Charsets.UTF_8)
        return """{"bytes":${content.toByteArray(Charsets.UTF_8).size}}"""
    }

    private fun fsMkdir(params: String): String {
        val p = Json.extractString(params, "path") ?: error("bad_request", "缺少 path")
        val d = sandboxed(p)
        if (!d.exists() && !d.mkdirs()) error("handler_error", "mkdir failed: $p")
        return "{}"
    }

    private fun fsDelete(params: String): String {
        val p = Json.extractString(params, "path") ?: error("bad_request", "缺少 path")
        val f = sandboxed(p)
        // 只删文件或空目录，不递归
        if (f.isDirectory && f.listFiles()?.isNotEmpty() == true) {
            error("bad_request", "directory not empty: $p")
        }
        if (!f.delete() && f.exists()) error("handler_error", "delete failed: $p")
        return "{}"
    }

    // —— terminal / workspace / net actions ——

    private fun terminalStatus(): String {
        val installed = appContext?.let { AlpineInstaller.isInstalled(it) } ?: false
        return """{"installed":$installed,"cwd":${JsonEscape.str(currentCwd)}}"""
    }

    private fun terminalWrite(params: String): String {
        val line = Json.extractString(params, "line") ?: error("bad_request", "缺少 line")
        val hook = terminalHook ?: error("handler_error", "no terminal session attached")
        hook(line)
        return "{}"
    }

    private fun workspaceImport(params: String): String {
        val p = Json.extractString(params, "path") ?: error("bad_request", "缺少 path")
        val ctx = appContext ?: error("handler_error", "no context")
        val canonical = File(p).canonicalFile
        // 目标限工程目录白名单（roots 同样 canonical 化，兼容 /sdcard → /storage/emulated/0 链）
        val allowed = WORKSPACE_ROOTS.any { root ->
            try {
                canonical.path.startsWith(File(root).canonicalFile.path)
            } catch (_: Exception) {
                false
            }
        }
        if (!canonical.isDirectory || !allowed) {
            error("bad_request", "workspace path not allowed: $p")
        }
        val dest = File(ctx.filesDir, "workspaces/${canonical.name}")
        canonical.copyRecursively(dest, overwrite = true)
        return """{"workspace":${JsonEscape.str(dest.absolutePath)}}"""
    }

    private fun netFetch(params: String): String {
        val urlStr = Json.extractString(params, "url") ?: error("bad_request", "缺少 url")
        val u = URL(urlStr)
        val host = u.host ?: error("bad_request", "invalid url")
        // 禁回环与内网，防插件直怼本地总线或内网服务
        val ip = try {
            InetAddress.getByName(host).address
        } catch (_: Exception) {
            error("bad_request", "cannot resolve host")
        }
        val isPrivate = ip[0] == 127.toByte() || ip[0] == 10.toByte() ||
            (ip[0] == 192.toByte() && ip[1] == 168.toByte()) ||
            (ip[0] == 172.toByte() && ip[1] in 16..31)
        if (isPrivate) error("forbidden", "loopback/private network blocked")
        val conn = u.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", "Coding-for-Android/1.0")
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("bad_request", "HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return """{"status":$code,"body":${JsonEscape.str(body.take(512 * 1024))}}"""
        } finally {
            conn.disconnect()
        }
    }

    /** JSON 字符串转义。 */
    internal object JsonEscape {
        fun str(s: String): String = buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
            append('"')
        }
    }
}