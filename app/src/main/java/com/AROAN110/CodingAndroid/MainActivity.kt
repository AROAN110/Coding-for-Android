package com.AROAN110.CodingAndroid

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import android.widget.Toast
import com.AROAN110.CodingAndroid.command.PmcCommandHandler
import com.AROAN110.CodingAndroid.env.AlpineInstaller
import com.AROAN110.CodingAndroid.env.Environment
import com.AROAN110.CodingAndroid.root.RootRequester
import com.AROAN110.CodingAndroid.terminal.ExtraKeysView
import com.AROAN110.CodingAndroid.terminal.TerminalScreenView
import com.AROAN110.CodingAndroid.terminal.TerminalSession
import java.io.File

/**
 * 单终端界面（MVP）。
 *
 * 会话模型：
 *  - 基础会话：Alpine 容器（默认）/ Android 主机 shell（pmc host 切换），挂起式共存；
 *  - su 会话：输入 su 时应用层拦截 → RootRequester 申请真 root → 挂起基础会话进入 su shell；
 *    输入 exit 退出后返回进入前的环境（会话现场保持，不跨环境乱跳）。
 *
 * 输入路由：本地行编辑（TerminalScreenView）→ 回车整行提交；
 *  su / pmc 指令在应用层拦截，其余整行写入当前会话 stdin。
 */
class MainActivity : Activity() {

    private lateinit var envBanner: TextView
    private lateinit var terminalView: TerminalScreenView
    private lateinit var extraKeys: ExtraKeysView

    // ---- 会话状态 ----
    private var alpineSession: TerminalSession? = null
    private var hostSession: TerminalSession? = null
    private var suSession: TerminalSession? = null
    private var baseEnv: Environment = Environment.ALPINE_CONTAINER
    private var alpineRestarts = 0

    private val buffers = HashMap<TerminalSession, StringBuilder>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var installing = false

    private val currentSession: TerminalSession?
        get() = suSession ?: when (baseEnv) {
            Environment.HOST_SHELL -> hostSession
            else -> alpineSession
        }

    private val sessionListener = object : TerminalSession.Listener {
        override fun onSessionOutput(session: TerminalSession, text: String) {
            mainHandler.post { handleOutput(session, text) }
        }

        override fun onSessionFinished(session: TerminalSession, exitCode: Int) {
            mainHandler.post { handleFinished(session, exitCode) }
        }

        override fun onSessionError(session: TerminalSession, message: String) {
            mainHandler.post { handleError(session, message) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        envBanner = findViewById(R.id.envBanner)
        terminalView = findViewById(R.id.terminalScreen)
        extraKeys = findViewById(R.id.extraKeys)
        PmcCommandHandler.attach(this)
        wireTerminal()
        wireExtraKeys()
        setupPluginBall()
        requestStoragePermissions()
        boot()
    }

    override fun onDestroy() {
        super.onDestroy()
        suSession?.destroy()
        hostSession?.destroy()
        alpineSession?.destroy()
    }

    // ================= 权限 =================

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            return
        }
        val wanted = listOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )
        val missing = wanted.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQ_STORAGE)
        }
    }

    // ================= 启动 =================

    private fun boot() {
        val ver = appVersion()
        updateBanner(Environment.ALPINE_CONTAINER)
        terminalView.appendOutput(
            "[CFA] Coding for Android v$ver — 最小底座\n" +
                "[CFA] 默认环境: Alpine 容器 | 输入 su 申请 Root | pmc help 查看指令\n\n",
        )
        if (AlpineInstaller.isInstalled(this)) {
            startAlpine()
        } else {
            startInstall()
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private fun startInstall() {
        if (installing) return
        installing = true
        terminalView.appendOutput("[CFA] 首次启动：开始准备 Alpine 容器（需要联网）…\n")
        Thread({
            try {
                AlpineInstaller.install(this) { line ->
                    mainHandler.post { terminalView.appendOutput(line) }
                }
                mainHandler.post {
                    installing = false
                    terminalView.appendOutput("[CFA] 容器就绪，正在启动…\n\n")
                    startAlpine()
                }
            } catch (e: Exception) {
                mainHandler.post {
                    installing = false
                    terminalView.appendOutput(
                        "[CFA] 安装失败：${e.message}\n" +
                            "[CFA] 重启 App 将自动重试（网络问题请检查连接）\n",
                    )
                }
            }
        }, "cfa-installer").start()
    }

    // ================= 会话管理 =================

    private fun startAlpine() {
        if (alpineSession?.isRunning == true) {
            switchTo(alpineSession!!)
            return
        }
        if (!AlpineInstaller.isInstalled(this)) {
            startInstall()
            return
        }
        val files = filesDir
        val rootfs = File(files, "alpine")
        val prootDir = File(files, "proot")
        val command = listOf(
            File(prootDir, "proot").absolutePath,
            "--link2symlink",
            "-0",
            "-r", rootfs.absolutePath,
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/sdcard",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "/bin/bash", "--noediting", "-i",
        )
        val env = mapOf(
            "PROOT_LOADER" to File(prootDir, "loader").absolutePath,
            "PROOT_TMP_DIR" to File(files, "tmp").absolutePath,
            "LD_LIBRARY_PATH" to prootDir.absolutePath,
        )
        val s = TerminalSession(Environment.ALPINE_CONTAINER, command, files, env, sessionListener)
        alpineSession = s
        switchTo(s)
        printWelcome(rootfs)
        s.start()
    }

    /** 输出容器引导文本（/root/welcome.txt，用户可自行修改）。 */
    private fun printWelcome(rootfs: File) {
        val f = File(rootfs, "root/welcome.txt")
        if (!f.isFile) return
        val text = try {
            f.readText().trimEnd()
        } catch (_: Exception) {
            return
        }
        if (text.isNotEmpty()) terminalView.appendOutput(text + "\n\n")
    }

    private fun startHost() {
        if (hostSession?.isRunning == true) {
            switchTo(hostSession!!)
            return
        }
        val wd = File("/sdcard").takeIf { it.isDirectory && it.canRead() }
        val s = TerminalSession(
            environment = Environment.HOST_SHELL,
            command = listOf("/system/bin/sh", "-i"),
            workingDir = wd,
            listener = sessionListener,
        )
        hostSession = s
        switchTo(s)
        s.start()
    }

    private fun ensureBaseVisible() {
        when (baseEnv) {
            Environment.HOST_SHELL -> startHost()
            else -> startAlpine()
        }
    }

    /** 切换前台会话：重建屏幕为对应会话的滚动缓冲。 */
    private fun switchTo(session: TerminalSession) {
        terminalView.discardInput()
        terminalView.clearScreen()
        buffers[session]?.takeIf { it.isNotEmpty() }?.let { terminalView.appendOutput(it.toString()) }
        updateBanner(session.environment)
    }

    private fun updateBanner(env: Environment) {
        envBanner.text = "Coding for Android v${appVersion()} · ${env.label}\n${env.warning}"
    }

    // ================= 输出 / 结束 / 错误 =================

    private fun handleOutput(session: TerminalSession, text: String) {
        val buf = buffers.getOrPut(session) { StringBuilder() }
        buf.append(text)
        if (buf.length > MAX_BUFFER_CHARS) buf.delete(0, buf.length - MAX_BUFFER_CHARS)
        if (session == currentSession) terminalView.appendOutput(text)
    }

    private fun handleFinished(session: TerminalSession, exitCode: Int) {
        appendNote(session, "\n[CFA] ${session.environment.label}会话已结束（exit=$exitCode）\n")

        if (session == suSession) {
            suSession = null
            ensureBaseVisible()
            terminalView.appendOutput("[CFA] 已退出 Root，返回 ${baseEnv.label}\n")
            return
        }

        if (session == alpineSession) {
            alpineSession = null
            if (suSession == null && baseEnv == Environment.ALPINE_CONTAINER) {
                if (exitCode == 0) {
                    alpineRestarts = 0
                    startAlpine()
                    terminalView.appendOutput("[CFA] 容器会话退出，已自动重启\n")
                } else if (alpineRestarts < 3) {
                    alpineRestarts++
                    startAlpine()
                    terminalView.appendOutput("[CFA] 容器异常退出（exit=$exitCode），自动重启（$alpineRestarts/3）\n")
                } else {
                    terminalView.appendOutput("[CFA] 容器连续异常退出，已停止自动重启。请重启 App 排查\n")
                }
            }
            return
        }

        if (session == hostSession) {
            hostSession = null
            if (baseEnv == Environment.HOST_SHELL) {
                baseEnv = Environment.ALPINE_CONTAINER
                if (suSession == null) {
                    ensureBaseVisible()
                    terminalView.appendOutput("[CFA] 主机 shell 已退出，返回 Alpine 容器\n")
                }
            }
        }
    }

    private fun handleError(session: TerminalSession, message: String) {
        appendNote(session, "[CFA] ${session.environment.label}会话启动失败：$message\n")
        if (session == alpineSession) alpineSession = null
        if (session == hostSession) hostSession = null
        if (session == suSession) {
            suSession = null
            ensureBaseVisible()
        }
    }

    private fun appendNote(session: TerminalSession, note: String) {
        buffers.getOrPut(session) { StringBuilder() }.append(note)
        if (session == currentSession) terminalView.appendOutput(note)
    }

    // ================= 输入路由 =================

    private fun wireTerminal() {
        terminalView.onLineSubmit = { line -> onLineSubmit(line) }
        terminalView.onSendToSession = { data -> currentSession?.write(data) }
        terminalView.onCtrlChar = { c -> onCtrlChar(c) }
        terminalView.ctrlActiveProvider = { extraKeys.isCtrlActive }
        terminalView.altActiveProvider = { extraKeys.isAltActive }
    }

    private fun wireExtraKeys() {
        extraKeys.listener = ExtraKeysView.Listener { key -> onExtraKey(key) }
    }

    // ================= 插件系统（骨架） =================

    /** 悬浮球：可拖拽；点击提示"插件系统开发中"。 */
    private fun setupPluginBall() {
        val ball = findViewById<View>(R.id.pluginBall) ?: return
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        ball.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startTx = v.translationX
                    startTy = v.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (dx * dx + dy * dy) > slop * slop) dragging = true
                    if (dragging) {
                        val parent = v.parent as? View ?: return@setOnTouchListener true
                        v.translationX = (startTx + dx).coerceIn((-v.left).toFloat(), (parent.width - v.width - v.left).toFloat())
                        v.translationY = (startTy + dy).coerceIn((-v.top).toFloat(), (parent.height - v.height - v.top).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) Toast.makeText(this, "插件系统开发中", Toast.LENGTH_SHORT).show()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun onLineSubmit(line: String) {
        val trimmed = line.trim()

        // 1) su 拦截：任何非 Root 会话中输入 su → 走应用层授权
        val isSuCmd = trimmed == "su" || trimmed.startsWith("su ")
        if (isSuCmd) {
            if (suSession == null) {
                requestRoot()
            } else {
                terminalView.appendOutput("[CFA] 已在 Root 环境\n")
            }
            return
        }

        // 2) pmc 指令：应用层处理（不进入 shell）
        if (PmcCommandHandler.handles(line)) {
            handlePmcCommand(line)
            return
        }

        // 3) 普通命令 → 写入当前会话
        val session = currentSession
        if (session == null) {
            terminalView.appendOutput("[CFA] 无活动会话，请稍候或输入 pmc status 查看状态\n")
            return
        }
        session.write(line + "\n")
    }

    private fun onCtrlChar(c: Char) {
        val lc = c.lowercaseChar()
        val session = currentSession
        when (lc) {
            'c' -> {
                if (terminalView.inputLength() > 0) {
                    terminalView.discardInput()
                } else {
                    session?.sendInterrupt()
                }
            }
            'a' -> terminalView.moveCursorTo(0)
            'e' -> terminalView.moveCursorTo(terminalView.inputLength())
            'u' -> terminalView.discardInput()
            'k' -> terminalView.clearToEnd()
            'w' -> terminalView.deleteWordBeforeCursor()
            'l' -> terminalView.clearScreen()
            'd' -> session?.write("\u0004")
            else -> {
                val code = lc.code
                if (code in 'a'.code..'z'.code) {
                    session?.write(((code - 'a'.code + 1)).toChar().toString())
                } else {
                    terminalView.insertText(c.toString())
                }
            }
        }
    }

    private fun onExtraKey(key: String) {
        when (key) {
            ExtraKeysView.KEY_ESC -> currentSession?.write("\u001B")
            ExtraKeysView.KEY_TAB -> terminalView.completeFromHistory()
            ExtraKeysView.KEY_LEFT -> terminalView.moveCursorLeft()
            ExtraKeysView.KEY_RIGHT -> terminalView.moveCursorRight()
            ExtraKeysView.KEY_UP -> terminalView.historyPrev()
            ExtraKeysView.KEY_DOWN -> terminalView.historyNext()
            ExtraKeysView.KEY_HOME -> terminalView.moveCursorTo(0)
            ExtraKeysView.KEY_END -> terminalView.moveCursorTo(terminalView.inputLength())
            ExtraKeysView.KEY_SLASH -> terminalView.insertText("/")
            ExtraKeysView.KEY_PIPE -> terminalView.insertText("|")
            ExtraKeysView.KEY_MINUS -> terminalView.insertText("-")
            ExtraKeysView.KEY_TILDE -> terminalView.insertText("~")
        }
    }

    // ================= Root 流程 =================

    private fun requestRoot() {
        terminalView.appendOutput("[CFA] 正在向 Android 申请 Root 权限（请留意 Magisk 弹窗）…\n")
        RootRequester.request { result ->
            when (result) {
                is RootRequester.Result.Success -> enterSuSession()
                is RootRequester.Result.Denied ->
                    terminalView.appendOutput("[CFA] Root 申请被拒绝（exit=${result.exitCode}）\n")
                RootRequester.Result.Timeout ->
                    terminalView.appendOutput("[CFA] Root 申请超时（60 秒无响应）\n")
                is RootRequester.Result.Error ->
                    terminalView.appendOutput("[CFA] Root 申请失败：${result.message}\n")
            }
        }
    }

    private fun enterSuSession() {
        if (suSession != null) return
        val envScript = ensureSuEnvScript()
        val s = TerminalSession(
            environment = Environment.HOST_SU,
            command = listOf("su", "-c", "ENV=${envScript.absolutePath} /system/bin/sh -i"),
            workingDir = File("/").takeIf { it.canExecute() },
            listener = sessionListener,
        )
        suSession = s
        switchTo(s)
        s.start()
        terminalView.appendOutput("[CFA] 已进入 Root 环境（exit 退出，回到 ${baseEnv.label}）\n")
    }

    /** 将 Root 会话提示符脚本写入应用私有目录（mksh 通过 ENV 机制加载）。 */
    private fun ensureSuEnvScript(): File {
        val f = File(filesDir, "suenv.sh")
        try {
            assets.open("suenv.sh").use { inp ->
                f.outputStream().use { os -> inp.copyTo(os) }
            }
            f.setReadable(true, false)
        } catch (_: Exception) {
        }
        return f
    }

    // ================= pmc 指令（会话层） =================

    private fun handlePmcCommand(line: String) {
        val args = line.trim().split(Regex("\\s+"))
        when (val sub = args.getOrNull(1)) {
            "host", "alpine", "status" -> {
                if (suSession != null) {
                    terminalView.appendOutput("[CFA] Root 会话中不可切换环境；请先 exit 退出 Root\n")
                    return
                }
                when (sub) {
                    "host" -> {
                        baseEnv = Environment.HOST_SHELL
                        startHost()
                        terminalView.appendOutput("[CFA] 已切换到 Android 主机 shell（pmc alpine 返回容器）\n")
                    }
                    "alpine" -> {
                        baseEnv = Environment.ALPINE_CONTAINER
                        startAlpine()
                        terminalView.appendOutput("[CFA] 已切换到 Alpine 容器\n")
                    }
                    "status" -> showStatus()
                }
            }
            else -> PmcCommandHandler.handle(line) { out -> terminalView.appendOutput(out) }
        }
    }

    private fun showStatus() {
        val sb = StringBuilder()
        sb.append("[CFA] 运行状态\n")
        sb.append("  当前环境: ${baseEnv.label}").append(if (suSession != null) "（Root 会话中）" else "").append('\n')
        sb.append("  会话: Alpine=").append(aliveText(alpineSession))
        sb.append(" | 主机=").append(aliveText(hostSession))
        sb.append(" | Root=").append(aliveText(suSession)).append('\n')
        sb.append("  容器: ").append(if (AlpineInstaller.isInstalled(this)) "已就绪" else "未安装").append('\n')
        terminalView.appendOutput(sb.toString())
    }

    private fun aliveText(s: TerminalSession?): String = when {
        s == null -> "未启动"
        s.isRunning -> "运行中(pid=${s.pid})"
        else -> "已结束"
    }

    private companion object {
        const val MAX_BUFFER_CHARS = 60_000
        const val REQ_STORAGE = 1001
    }
}