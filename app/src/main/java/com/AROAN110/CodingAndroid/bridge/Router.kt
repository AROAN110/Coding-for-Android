package com.AROAN110.CodingAndroid.bridge

import android.util.Log
import java.io.File

/**
 * HTTP 响应载体（缺口二 · 骨架）。
 */
data class HttpResponse(
    val status: Int,
    val reason: String,
    val contentType: String = "application/json; charset=utf-8",
    val body: String,
)

/**
 * 统一 JSON 路由器（缺口二 · 骨架）。
 *
 * 协议约定（已拍板）：
 *   请求: {"id":"req_123","action":"ping","params":{...}}
 *   回包: {"id":"req_123","status":"ok","data":{...}}  或  {"id":..,"status":"error","message":..}
 *
 * 本阶段只注册 ping。后续缺口三（CWD 沙箱拦截器）、缺口四（降级管道）
 * 都挂在本 Router 的 dispatch 前置/后置链上。
 */
class Router {

    /** action 处理器表：action -> (id, params) -> data JSON 片段。 */
    private val handlers = HashMap<String, (String, String) -> String>()

    companion object {
        /** 缺口三：终端当前工作目录（由终端侧同步）。Web 请求的 path 必须落在此目录内。 */
        @Volatile
        var currentCwd: String = "/sdcard"
    }

    init {
        register("ping") { _, _ ->
            Log.d("CFA_SKELETON", "Router: ping")
            "\"pong\""
        }
    }

    fun register(action: String, handler: (id: String, paramsJson: String) -> String) {
        handlers[action] = handler
    }

    /**
     * 分发一次请求。method/path 目前不参与路由（统一走 body 的 action），
     * 保留参数是为将来 REST 化或健康检查留口。
     */
    fun dispatch(method: String, path: String, body: String): HttpResponse {
        // 缺口三：CWD 沙箱前置拦截 —— 请求携带的 path 必须落在 currentCwd 内，禁止 ../ 穿越
        checkSandbox(body)?.let { return it }

        if (body.isBlank()) {
            return errorResponse("", "empty_body", "请求体为空")
        }

        val id = Json.extractString(body, "id") ?: ""
        val action = Json.extractString(body, "action")
            ?: return errorResponse(id, "bad_request", "缺少 action 字段")

        val handler = handlers[action]
            ?: return errorResponse(id, "unknown_action", "未知 action: $action")

        val params = Json.extractObject(body, "params") ?: "{}"
        return try {
            val data = handler(id, params)
            HttpResponse(200, "OK", body = """{"id":"$id","status":"ok","data":$data}""")
        } catch (e: Exception) {
            errorResponse(id, "handler_error", e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 缺口三：CWD 沙箱拦截器（预埋）。
     * 请求 params.path 非空时校验其 canonical 路径是否落在 currentCwd 内；
     * 越界 / ../ 穿越 → 403，放行返回 null。
     */
    private fun checkSandbox(body: String): HttpResponse? {
        val params = Json.extractObject(body, "params") ?: return null
        val raw = Json.extractString(params, "path") ?: return null
        if (raw.isBlank()) return null
        return if (isInsideCwd(raw)) {
            null
        } else {
            Log.w("CFA_SKELETON", "沙箱拦截越界路径: $raw (cwd=$currentCwd)")
            errorResponse(Json.extractString(body, "id") ?: "", "forbidden", "路径越界：$raw")
        }
    }

    /** canonical 化后判断 target 是否等于 / 位于 currentCwd 之内（杜绝 ../ 穿越与符号链接逃逸）。 */
    private fun isInsideCwd(raw: String): Boolean {
        return try {
            val base = File(currentCwd).canonicalFile
            val target = File(base, raw).canonicalFile
            val b = base.path
            val t = target.path
            t == b || t.startsWith(b + File.separator)
        } catch (_: Exception) {
            false
        }
    }

    private fun errorResponse(id: String, code: String, message: String): HttpResponse {
        val status = if (code == "forbidden") 403 else 200
        val reason = if (status == 403) "Forbidden" else "OK"
        return HttpResponse(
            status, reason,
            body = """{"id":"$id","status":"error","code":"$code","message":"$message"}""",
        )
    }
}