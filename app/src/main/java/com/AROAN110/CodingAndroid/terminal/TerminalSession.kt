package com.AROAN110.CodingAndroid.terminal

import android.os.Build
import android.os.Process as AndroidProcess
import com.AROAN110.CodingAndroid.env.Environment
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter

/**
 * 终端会话：包裹一个子进程（Alpine 容器 / 宿主 shell / su shell），
 * 负责 stdin 写入、stdout+stderr 合并读取（UTF-8 流式解码）、进程树信号。
 *
 * v0.1.2 新增：
 *  - 自定义工作目录 / 额外环境变量（proot 启动需要）；
 *  - PID 暴露 + 进程树 SIGINT（无 PTY 环境下 Ctrl+C 的替代实现）。
 */
class TerminalSession(
    val environment: Environment,
    private val command: List<String>,
    private val workingDir: File? = null,
    private val extraEnv: Map<String, String> = emptyMap(),
    private val listener: Listener,
) {

    interface Listener {
        fun onSessionOutput(session: TerminalSession, text: String)
        fun onSessionFinished(session: TerminalSession, exitCode: Int)
        fun onSessionError(session: TerminalSession, message: String)
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /**
     * Ctrl+C 上层拦截器：返回 true 表示已被上层消费（如打断安装），
     * 不再向子进程发送信号。安装阶段由 MainActivity 挂上（需求三）。
     */
    var interruptInterceptor: (() -> Boolean)? = null

    /**
     * PTY 场景标记：Ctrl+C 应把 0x03 字节送进伪终端，由 tty 派发 SIGINT；
     * 而不是对进程树发信号（后者会误杀 proot 本体、导致整个会话退出）。
     */
    var preferCtrlByte: Boolean = false

    /** 会话进程 PID（取不到返回 -1）。 */
    val pid: Int
        get() {
            val p = process ?: return -1
            return try {
                val m = p.javaClass.getMethod("pid")
                (m.invoke(p) as? Long)?.toInt() ?: reflectPid(p)
            } catch (_: Throwable) {
                reflectPid(p)
            }
        }

    fun start() {
        if (isRunning) return
        try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(true)
            if (workingDir != null) pb.directory(workingDir)
            if (extraEnv.isNotEmpty()) pb.environment().putAll(extraEnv)
            val p = pb.start()
            process = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream, Charsets.UTF_8))
            isRunning = true
            Thread({ readLoop(p) }, "cfa-term-reader").apply { isDaemon = true }.start()
        } catch (e: Exception) {
            listener.onSessionError(this, e.message ?: e.javaClass.simpleName)
        }
    }

    /** 向会话 stdin 写入（命令行 / 控制字符）。 */
    fun write(data: String) {
        val w = writer ?: return
        try {
            w.write(data)
            w.flush()
        } catch (_: Exception) {
            // 会话已退出，忽略
        }
    }

    /**
     * Ctrl+C：对会话进程树发送 SIGINT。
     * 无 PTY 环境下 0x03 字节不会被 shell 解释为中断，改用真实信号；
     * 权限不足（如 uid0 的 su 链）时退化为写入 0x03。
     */
    fun sendInterrupt() {
        // 上层优先消费（安装打断等，需求三）
        if (interruptInterceptor?.invoke() == true) return
        // PTY 场景：^C 送进伪终端（正确的中断路径，会话不退出）
        if (preferCtrlByte) {
            write("\u0003")
            return
        }
        val rootPid = pid
        if (rootPid <= 0) {
            write("\u0003")
            return
        }
        val targets = mutableListOf<Int>()
        collectDescendants(rootPid, targets)
        if (targets.isEmpty()) targets.add(rootPid)
        var sent = false
        for (t in targets) {
            try {
                AndroidProcess.sendSignal(t, SIGNAL_SIGINT)
                sent = true
            } catch (_: Throwable) {
                // EPERM（跨 uid）等，忽略
            }
        }
        if (!sent) write("\u0003")
    }

    fun destroy() {
        isRunning = false
        try {
            writer?.close()
        } catch (_: Exception) {
        }
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
    }

    // ---- internal ----

    private fun readLoop(p: Process) {
        val decoder = TerminalOutput()
        val buf = ByteArray(8192)
        try {
            val input = p.inputStream
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (_: Exception) {
                    break
                }
                if (n < 0) break
                if (n > 0) {
                    val text = decoder.write(buf.copyOf(n))
                    if (text.isNotEmpty()) listener.onSessionOutput(this, text)
                }
            }
        } finally {
            val code = try {
                p.waitFor()
            } catch (_: Exception) {
                -1
            }
            isRunning = false
            listener.onSessionFinished(this, code)
        }
    }

    /** 递归收集 pid 的全部后代（读 /proc/<pid>/task/<tid>/children）。 */
    private fun collectDescendants(pid: Int, out: MutableList<Int>) {
        val taskDir = File("/proc/$pid/task")
        val tids = taskDir.listFiles() ?: return
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

    private companion object {
        const val SIGNAL_SIGINT = 2
    }
}