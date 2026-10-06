package com.AROAN110.CodingAndroid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import android.widget.EditText
import com.AROAN110.CodingAndroid.bridge.LocalHttpServer
import com.AROAN110.CodingAndroid.bridge.Router
import com.AROAN110.CodingAndroid.command.PmcCommandHandler
import com.AROAN110.CodingAndroid.env.AlpineInstaller
import com.AROAN110.CodingAndroid.env.Environment
import com.AROAN110.CodingAndroid.env.InstallationInterruptedException
import com.AROAN110.CodingAndroid.resource.PluginInfo
import com.AROAN110.CodingAndroid.resource.ResourceManager
import com.AROAN110.CodingAndroid.root.RootRequester
import com.AROAN110.CodingAndroid.terminal.ExtraKeysView
import com.AROAN110.CodingAndroid.terminal.TerminalScreenView
import com.AROAN110.CodingAndroid.terminal.TerminalSession
import java.io.File
import java.util.concurrent.CountDownLatch

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
    private lateinit var webView: WebView

    // ---- Web 渲染层 / 通信总线（缺口一、二） ----
    private var httpServer: LocalHttpServer? = null
    private val router = Router()

    /** 当前前台模式：true = Web 层，false = 终端层。切换不销毁任何一方。 */
    private var webMode = false

    // ---- 资源包 / 插件（缺口五） ----
    private lateinit var resourceManager: ResourceManager

    /** 本次加载资源包时选中的插件（onPageFinished 时注入）。 */
    private var selectedPlugins: List<PluginInfo> = emptyList()

    // ---- 会话状态 ----
    private var alpineSession: TerminalSession? = null
    private var hostSession: TerminalSession? = null
    private var suSession: TerminalSession? = null
    private var baseEnv: Environment = Environment.ALPINE_CONTAINER
    private var alpineRestarts = 0

    // ---- Alpine 伪终端（PTY）状态与降级 ----
    /** script/PTY 启动失败后置位：后续会话改用直连 shell。 */
    private var alpineForceNoScript = false

    /** 当前 Alpine 会话是否用 script 包了 PTY。 */
    private var alpineSessionUsedScript = false

    /** 当前 Alpine 会话启动时刻（用于识别「秒退」）。 */
    private var alpineSessionStartedAt = 0L

    private val buffers = HashMap<TerminalSession, StringBuilder>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 安装闭环是否进行中（含等待用户输入源阶段）。 */
    private var installing = false

    /** 安装闭环是否在运行（唯一入口，防重入）。 */
    @Volatile
    private var installFlowRunning = false

    /** boot() 是否已启动过安装引导：第二次起一律静默，杜绝「未安装」刷屏。 */
    private var bootInstallStarted = false

    /** 是否正在等待用户提供自定义镜像源（对话框期间）。 */
    @Volatile
    private var awaitingMirror = false

    /** 「自定义镜像源」对话框引用（供 Ctrl+C 等价取消）。 */
    private var mirrorDialog: AlertDialog? = null

    /** 取源弹窗等待闸：安装后台线程在此挂起，用户提交/取消/Ctrl+C 后释放。 */
    @Volatile
    private var mirrorLatch: CountDownLatch? = null

    /** 用户最近一次提交的临时源（仅本次引导使用，绝不落任何配置）。 */
    @Volatile
    private var pendingMirrors: List<String> = emptyList()

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
        resourceManager = ResourceManager(this)
        wireTerminal()
        wireExtraKeys()
        setupPluginBall()
        setupWebLayer()
        requestStoragePermissions()
        boot()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ★ 强制释放后台安装线程在 promptForUserMirror() 中的等待，避免线程死锁
        mirrorLatch?.countDown()
        mirrorLatch = null
        // ★ 强制打断仍在 AlpineInstaller.install() 阶段的后台线程，避免向已销毁视图投递输出
        AlpineInstaller.abortInstallation()
        httpServer?.stop()
        suSession?.destroy()
        hostSession?.destroy()
        alpineSession?.destroy()
    }

    override fun onPause() {
        super.onPause()
        // WebView 状态保持：后台暂停 JS，但绝不销毁
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
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
            return
        }
        // 容器未安装：仅首次进入安装闭环；其余情况一律静默终止，
        // 错误处理全部由 promptForUserMirror() 接管，绝不打印任何「未安装」提示。
        if (bootInstallStarted) return
        bootInstallStarted = true
        startInstall()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    /**
     * 安装闭环（唯一入口，非阻塞主线程）：
     *  - 专用后台线程内循环「测速安装 → 失败则弹窗取源 → 立刻用该源重试」，
     *    直到安装成功或用户取消；绝不退回 boot()，绝不刷屏；
     *  - temporaryMirrors 非空时作为首轮源；forcePrompt=true 时先取源再开跑；
     *  - 测速/安装失败一律由 promptForUserMirror() 接管，Ctrl+C 可随时打断。
     */
    private fun startInstall(temporaryMirrors: List<String> = emptyList(), forcePrompt: Boolean = false) {
        if (installFlowRunning) return
        installFlowRunning = true
        installing = true
        terminalView.appendOutput("[CFA] 开始准备 Alpine 容器（需要联网，Ctrl+C 可打断）…\n")
        Thread({
            var mirrors = temporaryMirrors
            var ok = false
            try {
                if (forcePrompt && mirrors.isEmpty()) {
                    // 主动入口（pmc install）：先取源；用户取消则直接退出闭环
                    if (!promptForUserMirror("请提供可用的 Alpine 镜像源")) return@Thread
                    mirrors = pendingMirrors
                }
                while (true) {
                    try {
                        AlpineInstaller.install(this, { line ->
                            mainHandler.post { terminalView.appendOutput(line) }
                        }, mirrors)
                        ok = true
                    } catch (e: InstallationInterruptedException) {
                        // 用户打断：清理残留后强制取源再重试，绝不直接退出闭环
                        AlpineInstaller.cleanupAfterAbort(this)
                        if (!promptForUserMirror("用户打断了安装，请提供备用镜像源")) break
                        mirrors = pendingMirrors
                    } catch (e: Exception) {
                        AlpineInstaller.cleanupAfterAbort(this)
                        val reason = e.message ?: e.javaClass.simpleName
                        mainHandler.post { terminalView.appendOutput("[CFA] 安装失败：$reason\n") }
                        // 失败必须重新取源，绝不跳出安装流程回到 boot()
                        if (!promptForUserMirror(reason)) break
                        mirrors = pendingMirrors
                    }
                }
                ok = AlpineInstaller.isInstalled(this)
            } finally {
                installFlowRunning = false
                installing = false
                mainHandler.post {
                    if (ok) {
                        terminalView.appendOutput("[CFA] 容器就绪，正在启动…\n\n")
                        startAlpine()
                    } else {
                        terminalView.appendOutput("[CFA] 已退出安装流程\n")
                    }
                }
            }
        }, "cfa-installer").start()
    }

    /**
     * 需求三：安装阶段 Ctrl+C —— 立即打断。
     * 返回 true 表示已消费该按键；未处于安装流程时返回 false（交回常规 Ctrl+C 处理）。
     */
    private fun tryAbortInstall(): Boolean {
        if (awaitingMirror) {
            // 正在等待输入源：Ctrl+C 等价于「取消」→ 释放等待闸，闭环自行退出
            mirrorDialog?.dismiss()
            mirrorDialog = null
            awaitingMirror = false
            pendingMirrors = emptyList()
            mirrorLatch?.countDown()
            return true
        }
        if (!installFlowRunning && !installing) return false
        AlpineInstaller.abortInstallation()
        terminalView.appendOutput("[CFA] 正在打断安装…\n")
        return true
    }

    /**
     * 需求四：询问自定义镜像源（阻塞调用线程，**仅限安装后台线程调用**）。
     * 用户提交则 pendingMirrors 生效并返回 true；取消/空输入返回 false（退出闭环）。
     * 红线：临时源绝不写入 SharedPreferences、绝不修改 pmc 仓库配置，仅用于本次引导安装。
     */
    private fun promptForUserMirror(reason: String): Boolean {
        if (isFinishing || isDestroyed) return false
        val latch = CountDownLatch(1)
        val submitted = booleanArrayOf(false)
        mirrorLatch = latch
        mainHandler.post {
            if (isFinishing || isDestroyed) {
                latch.countDown()
                return@post
            }
            awaitingMirror = true
            val input = EditText(this).apply {
                hint = "https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24"
                setSingleLine(false)
            }
            mirrorDialog = AlertDialog.Builder(this)
                .setTitle("安装失败：请提供镜像源")
                .setMessage(
                    "$reason\n\n" +
                        "每行一个 Alpine 镜像 base URL（含 /alpine/vX.Y，不含 /main）。\n" +
                        "本次仅用于引导安装，不会写入任何配置。",
                )
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("重试") { _, _ ->
                    awaitingMirror = false
                    mirrorDialog = null
                    pendingMirrors = input.text.toString().lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    submitted[0] = pendingMirrors.isNotEmpty()
                    latch.countDown()
                }
                .setNegativeButton("取消") { _, _ ->
                    awaitingMirror = false
                    mirrorDialog = null
                    pendingMirrors = emptyList()
                    submitted[0] = false
                    latch.countDown()
                }
                .create()
            mirrorDialog?.show()
        }
        try {
            // ★ 强制最多等待 10 分钟，防止 Activity 销毁导致线程永久死锁
            if (!latch.await(10, java.util.concurrent.TimeUnit.MINUTES)) {
                // 超时边缘竞态：用户可能刚好在此刻点了「重试」→ 以已提交的输入为准
                if (!submitted[0]) {
                    mainHandler.post {
                        mirrorDialog?.dismiss()
                        mirrorDialog = null
                        awaitingMirror = false
                    }
                    return false
                }
                // submitted[0] == true：保留 pendingMirrors，按用户提交结果返回
            }
        } catch (_: InterruptedException) {
            return false
        } finally {
            mirrorLatch = null
        }
        return submitted[0]
    }

    // ================= 会话管理 =================

    private fun startAlpine() {
        if (alpineSession?.isRunning == true) {
            switchTo(alpineSession!!)
            return
        }
        if (!AlpineInstaller.isInstalled(this)) {
            // 容器未就绪：交给安装闭环接管（绝不在此阻塞主线程弹窗）
            startInstall()
            return
        }
        val files = filesDir
        val rootfs = File(files, "alpine")
        val prootDir = File(files, "proot")

        // 伪终端：优先用 util-linux script 包一层 PTY（交互式程序需要）；
        // 缺失或曾启动失败则回退为直连 bash。
        val scriptBin = File(rootfs, "usr/bin/script")
        val useScript = scriptBin.isFile && !alpineForceNoScript
        when {
            scriptBin.isFile && alpineForceNoScript ->
                terminalView.appendOutput("[CFA] 已切换为直连 shell 模式\n")
            !scriptBin.isFile ->
                terminalView.appendOutput("[CFA] 未检测到 script（util-linux 未装），使用直连 shell\n")
        }
        val shellCmd = if (useScript) {
            listOf(
                "/usr/bin/script", "-q",
                "-c", "stty -echo 2>/dev/null; exec /bin/bash --noediting -i",
                "/dev/null",
            )
        } else {
            listOf("/bin/bash", "--noediting", "-i")
        }

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
        ) + shellCmd
        val env = mapOf(
            "PROOT_LOADER" to File(prootDir, "loader").absolutePath,
            "PROOT_TMP_DIR" to File(files, "tmp").absolutePath,
            "LD_LIBRARY_PATH" to prootDir.absolutePath,
        )
        val s = TerminalSession(Environment.ALPINE_CONTAINER, command, files, env, sessionListener)
        s.interruptInterceptor = { tryAbortInstall() }
        // PTY 场景：Ctrl+C 以 ^C 字节送入伪终端，而非直接杀 proot 进程树
        s.preferCtrlByte = useScript
        alpineSession = s
        alpineSessionUsedScript = useScript
        alpineSessionStartedAt = System.currentTimeMillis()
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
        s.interruptInterceptor = { tryAbortInstall() }
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
            val usedScript = alpineSessionUsedScript
            val livedMs = System.currentTimeMillis() - alpineSessionStartedAt
            alpineSession = null
            if (suSession == null && baseEnv == Environment.ALPINE_CONTAINER) {
                // PTY 降级：script 包 PTY 若秒退，判定该环境不支持，改直连 shell 重建
                if (usedScript && livedMs in 0 until QUICK_EXIT_MS) {
                    alpineForceNoScript = true
                    alpineRestarts = 0
                    appendNote(session, "[CFA] 伪终端启动异常（${livedMs}ms），已切换为直连 shell 重建\n")
                    startAlpine()
                    return
                }
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

    // ================= Web 渲染层 / 通信总线（缺口一、二） =================

    /**
     * 初始化 Web 层（缺口一）：
     *  - WebView 与终端同处一个 FrameLayout 画布，初始 GONE；
     *  - 加载资源包目录（assets/resources/default/index.html），非绝对路径；
     *  - onPageFinished 预留 injectPlugins()（缺口五）。
     */
    private fun setupWebLayer() {
        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        // 缺口四预埋：注册原生桥（替代 file:// 下的 fetch，绕开 WebView 跨域拦截）
        webView.addJavascriptInterface(NativeBridge(), "__CFA_HOST__")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG_RES, "onPageFinished: $url")
                // 注入通信端口（保留：兼容旧页面 / 调试）
                val p = httpServer?.port ?: -1
                view?.evaluateJavascript("window.__CFA_PORT__ = $p;", null)
                // 注入全局原生桥对象：页面统一走 window.__CFA_NATIVE__.send(json)
                view?.evaluateJavascript(NATIVE_BRIDGE_JS, null)
                // 注入本次选中的插件
                injectPlugins()
            }
        }
        loadResourcePage()
        startHttpServer()
    }

    /** 加载默认资源包首页（缺口五：从资源包目录而非绝对路径）。 */
    private fun loadResourcePage() {
        seedHomeFromAssets()
        val f = File(filesDir, "home/resources/default/index.html")
        if (f.isFile) {
            webView.loadUrl("file://${f.absolutePath}")
        } else {
            Log.w(TAG_RES, "默认资源包缺失: ${f.absolutePath}")
        }
    }

    /** 首启：把 assets/home 整棵树铺到 filesDir/home（不覆盖已存在文件）。 */
    private fun seedHomeFromAssets() {
        try {
            copyAssetTree("home", File(filesDir, "home"))
        } catch (e: Exception) {
            Log.w(TAG_RES, "seedHomeFromAssets 失败: ${e.message}")
        }
    }

    private fun copyAssetTree(assetPath: String, dest: File) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // 叶子 = 文件
            if (dest.exists()) return
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { inp ->
                dest.outputStream().use { os -> inp.copyTo(os) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetTree("$assetPath/$child", File(dest, child))
        }
    }

    /** 启动本地 HTTP 总线（缺口二）：随机端口 + 仅 127.0.0.1。 */
    private fun startHttpServer() {
        val server = LocalHttpServer(router)
        server.start()
        httpServer = server
        Log.d("CFA_SKELETON", "HTTP server port=${server.port}")
    }

    /**
     * 插件注入（onPageFinished 触发）：读取本次选中的插件 JS 并逐个 evaluateJavascript。
     */
    private fun injectPlugins() {
        val plugins = selectedPlugins
        if (plugins.isEmpty()) {
            Log.d(TAG_RES, "无选中插件，跳过注入")
            return
        }
        var injected = 0
        for (plugin in plugins) {
            try {
                val js = resourceManager.readPluginScript(plugin.entryPath)
                if (js.isNullOrBlank()) {
                    Log.w(TAG_RES, "插件脚本为空，跳过: ${plugin.id}")
                    continue
                }
                webView.evaluateJavascript(js, null)
                injected++
            } catch (e: Exception) {
                Log.w(TAG_RES, "插件注入失败: ${plugin.id} (${e.message})")
            }
        }
        Log.d(TAG_RES, "插件注入完成: $injected/${plugins.size}")
        Toast.makeText(this, "资源包与插件加载完毕", Toast.LENGTH_SHORT).show()
    }
/**
     * 缺口四预埋：WebView 原生桥。
     * 页面侧统一调用 window.__CFA_NATIVE__.send(json) 同步取回响应，
     * 与 HTTP 总线共用同一条 Router 路由，彻底避开 file:// 跨域拦截。
     */
    private inner class NativeBridge {
        @JavascriptInterface
        fun send(requestJson: String): String {
            return try {
                router.dispatch("POST", "/rpc", requestJson).body
            } catch (e: Exception) {
                """{"id":"","status":"error","code":"bridge_error","message":"${e.message ?: e.javaClass.simpleName}"}"""
            }
        }
    }

    /** 切换前台层：只改 visibility，不销毁任何一方。 */
    private fun switchLayer(toWeb: Boolean) {
        webMode = toWeb
        if (toWeb) {
            webView.visibility = View.VISIBLE
            terminalView.visibility = View.GONE
            extraKeys.visibility = View.GONE
            envBanner.visibility = View.GONE
            webView.onResume()
        } else {
            webView.visibility = View.GONE
            terminalView.visibility = View.VISIBLE
            extraKeys.visibility = View.VISIBLE
            envBanner.visibility = View.VISIBLE
            webView.onPause()
        }
        Toast.makeText(this, if (toWeb) "已切至 Web 层" else "已切至终端层", Toast.LENGTH_SHORT).show()
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
                    if (!dragging) showLayerMenu()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    /**
     * 悬浮球点击菜单：加载资源包（缺口五）。
     */
    private fun showLayerMenu() {
        val items = arrayOf(
            if (webMode) "→ 切至终端层" else "→ 切至 Web 层",
            "加载资源包",
            "重新加载 Web 页",
            "查看通信端口",
        )
        AlertDialog.Builder(this)
            .setTitle("Coding for Android")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> switchLayer(!webMode)
                    1 -> openResourcePicker()
                    2 -> {
                        loadResourcePage()
                        Toast.makeText(this, "Web 页已重载", Toast.LENGTH_SHORT).show()
                    }
                    3 -> Toast.makeText(
                        this,
                        "127.0.0.1:${httpServer?.port ?: -1}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            .show()
    }

    /** 第一步：扫描并列出资源包。 */
    private fun openResourcePicker() {
        val resources = try {
            resourceManager.scanResources()
        } catch (e: Exception) {
            Log.w(TAG_RES, "scanResources 失败: ${e.message}")
            emptyList()
        }
        if (resources.isEmpty()) {
            Toast.makeText(this, "未找到任何已安装资源包", Toast.LENGTH_SHORT).show()
            return
        }
        val names = resources.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择资源包")
            .setItems(names) { _, index ->
                val picked = resources.getOrNull(index) ?: return@setItems
                onResourcePicked(picked.id, picked.entryPath)
            }
            .show()
    }

    /** 第二步：加载资源包入口并选择插件。 */
    private fun onResourcePicked(resourceId: String, entryPath: String) {
        if (!File(entryPath).isFile) {
            Toast.makeText(this, "资源包入口文件丢失", Toast.LENGTH_SHORT).show()
            return
        }
        val plugins = try {
            resourceManager.scanPlugins()
        } catch (e: Exception) {
            Log.w(TAG_RES, "scanPlugins 失败: ${e.message}")
            emptyList()
        }
        if (plugins.isEmpty()) {
            selectedPlugins = emptyList()
            loadResource(entryPath)
            return
        }
        val names = plugins.map { it.name }.toTypedArray()
        val checked = BooleanArray(plugins.size) { false }
        AlertDialog.Builder(this)
            .setTitle("选择插件（可多选）")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("确定") { _, _ ->
                selectedPlugins = plugins.filterIndexed { i, _ -> checked[i] }
                loadResource(entryPath)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 第四步：加载资源包入口。 */
    private fun loadResource(entryPath: String) {
        try {
            webView.loadUrl("file://$entryPath")
            if (!webMode) switchLayer(true)
        } catch (e: Exception) {
            Log.w(TAG_RES, "loadResource 失败: ${e.message}")
            Toast.makeText(this, "资源包加载失败", Toast.LENGTH_SHORT).show()
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
            when {
                installing -> terminalView.appendOutput("[CFA] 安装流程进行中，请稍候（Ctrl+C 可打断）\n")
                !AlpineInstaller.isInstalled(this) -> startInstall()
                else -> terminalView.appendOutput("[CFA] 无活动会话，请稍候或输入 pmc status 查看状态\n")
            }
            return
        }
        session.write(line + "\n")
    }

    private fun onCtrlChar(c: Char) {
        val lc = c.lowercaseChar()
        val session = currentSession
        when (lc) {
            'c' -> {
                if (tryAbortInstall()) return
                if (terminalView.inputLength() > 0) {
                    terminalView.discardInput()
                } else if (session != null) {
                    session.sendInterrupt()
                    terminalView.appendOutput("^C\n")
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
        s.interruptInterceptor = { tryAbortInstall() }
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
            "install" -> {
                if (args.size == 2) {
                    // 裸 `pmc install` = 容器安装 / 重装入口（安装中断后的闭环恢复）
                    when {
                        AlpineInstaller.isInstalled(this) ->
                            terminalView.appendOutput("[CFA] 容器已就绪，无需重装\n")
                        installing ->
                            terminalView.appendOutput("[CFA] 安装流程已在进行中（Ctrl+C 可打断）\n")
                        else -> startInstall(forcePrompt = true)
                    }
                } else {
                    PmcCommandHandler.handle(line) { out -> terminalView.appendOutput(out) }
                }
            }
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
        sb.append("  安装流程: ").append(if (installing) "进行中" else "空闲").append('\n')
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
        const val TAG_RES = "CFA_RES"

        /** Alpine 会话存活时长低于此值即判定为「秒退」（用于 PTY 降级）。 */
        const val QUICK_EXIT_MS = 3_000L

        /** onPageFinished 注入的桥接适配层：把 window.__CFA_NATIVE__ 落到原生 @JavascriptInterface 上。 */
        const val NATIVE_BRIDGE_JS = """
(function () {
  window.__CFA_NATIVE__ = {
    send: function (json) {
      if (!window.__CFA_HOST__ || typeof window.__CFA_HOST__.send !== 'function') {
        return '{"id":"","status":"error","code":"bridge_unavailable","message":"native bridge not ready"}';
      }
      try {
        return window.__CFA_HOST__.send(String(json));
      } catch (e) {
        return '{"id":"","status":"error","code":"bridge_error","message":"' + e + '"}';
      }
    }
  };
})();
"""
    }
}