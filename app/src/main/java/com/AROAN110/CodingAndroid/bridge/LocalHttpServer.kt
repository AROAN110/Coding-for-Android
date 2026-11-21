package com.AROAN110.CodingAndroid.bridge

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 本地通信总线（缺口二 · 骨架）。
 *
 * 设计约束（已拍板）：
 *  - 纯 ServerSocket 手写 HTTP，零第三方依赖（标准库无 WebSocket 服务端）；
 *  - 端口随机（bind 0 由系统分配），仅监听 127.0.0.1，不对外暴露；
 *  - 统一 JSON Router：请求 {"id":..,"action":..,"params":{..}}，回包固定 JSON。
 *
 * 本阶段只实现 ping 链路，用于验证 WebView ↔ Kotlin 通路。
 * 注意：targetSdk=28 下明文流量默认被禁，WebView 访问 http://127.0.0.1 需
 * manifest 放行（usesCleartextTraffic / networkSecurityConfig）。
 */
class LocalHttpServer(
    private val router: Router,
) {

    /** 当前监听端口（未启动为 -1）。 */
    @Volatile
    var port: Int = -1
        private set

    /** 本次运行的访问令牌：请求头 X-CFA-Token 必须匹配，防同设备其他 App 直连总线。 */
    val token: String = generateToken()

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (running) return
        try {
            // 仅绑回环地址，端口交给系统随机分配
            val ss = ServerSocket(0, 50, InetAddress.getByName(LOOPBACK))
            serverSocket = ss
            port = ss.localPort
            running = true
            Log.d(TAG, "LocalHttpServer started on 127.0.0.1:$port")
            thread(name = "cfa-http-accept") {
                acceptLoop(ss)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start failed: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        port = -1
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "cfa-http-conn") { handleConnection(socket) }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1) ?: "/"

                // 读头部，取 Content-Length 与访问令牌；限制行数与 body 大小，防畸形请求吃内存
                var contentLength = 0
                var headerLines = 0
                var reqToken: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (++headerLines > MAX_HEADER_LINES) return@use
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (line.startsWith("X-CFA-Token:", ignoreCase = true)) {
                        reqToken = line.substringAfter(":").trim()
                    }
                }
                if (contentLength > MAX_BODY_BYTES) return@use
                // 令牌校验：不匹配直接断开（同设备其他 App 无 token 即被拒）
                if (reqToken != token) return@use

                // 读 body（JSON）：按 Content-Length 精确读满，不能只读 header 就返回
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read)
                } else {
                    ""
                }

                Log.d(TAG, "<= $method $path body=${body.take(200)}")

                // 预检请求：直接回 200 空响应，不进 Router
                if (method.equals("OPTIONS", ignoreCase = true)) {
                    writeResponse(
                        s.getOutputStream(),
                        HttpResponse(200, "OK", contentType = "text/plain; charset=utf-8", body = ""),
                    )
                    return@use
                }

                val response = router.dispatch(method, path, body)
                writeResponse(s.getOutputStream(), response)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleConnection error: ${e.message}")
        }
    }

    private fun writeResponse(out: OutputStream, response: HttpResponse) {
        val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            append("Content-Type: ${response.contentType}\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            // 同源策略收紧：令牌校验在请求侧已做，这里不再对任意 Origin 开放
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    companion object {
        private const val TAG = "CFA_HTTP"
        private const val LOOPBACK = "127.0.0.1"
        private const val MAX_HEADER_LINES = 100
        private const val MAX_BODY_BYTES = 1024 * 1024

        private fun generateToken(): String {
            val buf = ByteArray(32)
            java.security.SecureRandom().nextBytes(buf)
            return buf.joinToString("") { "%02x".format(it) }
        }
    }
}