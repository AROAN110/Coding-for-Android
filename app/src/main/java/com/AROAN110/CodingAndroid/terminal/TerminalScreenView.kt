package com.AROAN110.CodingAndroid.terminal

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.ScrollView
import android.widget.TextView

/**
 * 终端屏幕：渲染 shell 输出 + 本地行编辑（光标 / 历史 / 扩展键）。
 *
 * 无 PTY 环境的取舍：
 *  - shell 的 PS1（含实时路径）由 shell 自己打印（ash 原生 + PS1 环境变量）；
 *  - 用户输入的行由本机维护（本地回显 + 光标编辑 + 上下历史）；
 *  - 回车整行提交给会话；扩展键（←→↑↓等）在本机处理。
 */
class TerminalScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TextView(context, attrs, defStyleAttr) {

    /** 整行提交回调（回车）。 */
    var onLineSubmit: ((String) -> Unit)? = null

    /** 直接向会话发送字节的通道（ESC / TAB / 控制码）。 */
    var onSendToSession: ((String) -> Unit)? = null

    /** Ctrl 激活时收到的字符（上层转换为控制码发送）。 */
    var onCtrlChar: ((Char) -> Unit)? = null

    /** 查询 Ctrl 粘滞状态（由扩展键栏提供）。 */
    var ctrlActiveProvider: (() -> Boolean)? = null

    /** 查询 Alt 粘滞状态（由扩展键栏提供）。 */
    var altActiveProvider: (() -> Boolean)? = null

    private val history = StringBuilder()
    private val input = StringBuilder()
    private var cursor = 0
    private var composing = ""
    private val historyList = ArrayList<String>()
    private var historyIndex = -1
    private var historyDraft = ""
    private var cursorOn = true
    private var ansiState = 0
    private val ansiBuf = StringBuilder()
    private val cursorColor = Color.parseColor("#00E5A0")

    private val handler = Handler(Looper.getMainLooper())
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorOn = !cursorOn
            render()
            handler.postDelayed(this, BLINK_PERIOD_MS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(blinkRunnable)
        handler.postDelayed(blinkRunnable, BLINK_PERIOD_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(blinkRunnable)
        super.onDetachedFromWindow()
    }

    // ---- 对外 API ----

    /** 追加 shell 输出到屏幕。 */
    fun appendOutput(text: String) {
        if (text.isEmpty()) return
        val follow = isNearBottom()
        history.append(sanitize(text))
        render()
        if (follow) scrollToBottom()
    }

    /** 清屏（本地历史清空）。 */
    fun clearScreen() {
        history.setLength(0)
        ansiState = 0
        ansiBuf.setLength(0)
        render()
    }

    /** 插入文本到编辑行（光标处）。 */
    fun insertText(text: String) {
        if (text.isEmpty()) return
        input.insert(cursor, text)
        cursor += text.length
        render()
    }

    /** 直接发送字节到会话（不经过编辑行）。 */
    fun sendRaw(data: String) {
        onSendToSession?.invoke(data)
    }

    fun moveCursorLeft() {
        if (cursor > 0) {
            cursor--
            render()
        }
    }

    fun moveCursorRight() {
        if (cursor < input.length) {
            cursor++
            render()
        }
    }

    fun moveCursorTo(pos: Int) {
        cursor = pos.coerceIn(0, input.length)
        render()
    }

    fun inputLength(): Int = input.length

    fun historyPrev() {
        if (historyList.isEmpty()) return
        if (historyIndex == -1) {
            historyDraft = input.toString()
            historyIndex = historyList.size
        }
        if (historyIndex > 0) {
            historyIndex--
            setInput(historyList[historyIndex])
        }
    }

    fun historyNext() {
        if (historyIndex == -1) return
        if (historyIndex < historyList.size - 1) {
            historyIndex++
            setInput(historyList[historyIndex])
        } else {
            historyIndex = -1
            setInput(historyDraft)
        }
    }

    /** 回车：回显 + 整行提交。 */
    fun submitLine() {
        composing = ""
        val line = input.toString()
        input.setLength(0)
        cursor = 0
        history.append(line).append('\n')
        if (line.isNotBlank()) {
            if (historyList.lastOrNull() != line) historyList.add(line)
        }
        historyIndex = -1
        historyDraft = ""
        render()
        scrollToBottom()
        onLineSubmit?.invoke(line)
    }

    fun handleBackspace() {
        if (composing.isNotEmpty()) {
            composing = composing.dropLast(1)
            render()
            return
        }
        if (cursor > 0) {
            input.deleteCharAt(cursor - 1)
            cursor--
            render()
        }
    }

    fun handleDeleteForward() {
        if (cursor < input.length) {
            input.deleteCharAt(cursor)
            render()
        }
    }

    /** 清空编辑行（切会话 / Ctrl+U）。 */
    fun discardInput() {
        if (input.isEmpty() && composing.isEmpty() && cursor == 0) return
        input.setLength(0)
        composing = ""
        cursor = 0
        render()
    }

    /** 删除光标到行尾（Ctrl+K）。 */
    fun clearToEnd() {
        if (cursor >= input.length) return
        input.setLength(cursor)
        render()
    }

    /** 删除光标前一个词（Ctrl+W）。 */
    fun deleteWordBeforeCursor() {
        if (cursor <= 0) return
        var start = cursor
        while (start > 0 && input[start - 1] == ' ') start--
        while (start > 0 && input[start - 1] != ' ') start--
        input.delete(start, cursor)
        cursor = start
        render()
    }

    /**
     * TAB 补全：基于历史记录，补全为最近一条以当前输入开头的命令。
     * （无 PTY 环境无法与 shell 交互式补全，MVP 采用本地历史补全方案）
     */
    fun completeFromHistory() {
        val cur = input.toString()
        if (cur.isEmpty()) return
        for (i in historyList.indices.reversed()) {
            val h = historyList[i]
            if (h != cur && h.startsWith(cur)) {
                setInput(h)
                return
            }
        }
    }

    // ---- 渲染 ----

    private fun setInput(s: String) {
        input.setLength(0)
        input.append(s)
        cursor = input.length
        render()
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        appendColoredHistory(sb)
        val c = cursor.coerceIn(0, input.length)
        sb.append(input, 0, c)
        if (composing.isNotEmpty()) {
            val start = sb.length
            sb.append(composing)
            sb.setSpan(UnderlineSpan(), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        sb.append("\u2588")
        val cursorStart = sb.length - 1
        sb.setSpan(
            ForegroundColorSpan(if (cursorOn) cursorColor else Color.TRANSPARENT),
            cursorStart,
            sb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.append(input, c, input.length)
        setText(sb)
    }

    /** 解析 history 中的 SGR 颜色序列，渲染为彩色文本（其余转义已在输入时过滤）。 */
    private fun appendColoredHistory(sb: SpannableStringBuilder) {
        val raw = history
        var i = 0
        var color: Int? = null
        var segStart = sb.length
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\u001B' && i + 1 < raw.length && raw[i + 1] == '[') {
                var j = i + 2
                while (j < raw.length && raw[j] !in '@'..'~') j++
                if (j < raw.length && raw[j] == 'm') {
                    if (color != null && sb.length > segStart) {
                        sb.setSpan(
                            ForegroundColorSpan(color),
                            segStart,
                            sb.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    color = sgrColor(raw.substring(i + 2, j), color)
                    segStart = sb.length
                    i = j + 1
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        if (color != null && sb.length > segStart) {
            sb.setSpan(
                ForegroundColorSpan(color),
                segStart,
                sb.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    /** SGR 参数 → 前景色（null = 默认色）。 */
    private fun sgrColor(params: String, current: Int?): Int? {
        var color = current
        val parts = if (params.isEmpty()) listOf("0") else params.split(';')
        for (p in parts) {
            val v = p.toIntOrNull() ?: continue
            when {
                v == 0 -> color = null
                v in 30..37 -> color = ANSI_COLORS[v - 30]
                v == 39 -> color = null
                v in 90..97 -> color = ANSI_BRIGHT[v - 90]
            }
        }
        return color
    }

    private fun isNearBottom(): Boolean {
        val sv = parent as? ScrollView ?: return true
        val child = sv.getChildAt(0) ?: return true
        return sv.scrollY + sv.height >= child.height - dp(32)
    }

    private fun scrollToBottom() {
        val sv = parent as? ScrollView ?: return
        sv.post { sv.fullScroll(View.FOCUS_DOWN) }
    }

    // ---- 输出净化：静音 tty 提示 + 过滤 ANSI 转义（状态机，跨块安全）----

    private fun sanitize(text: String): String {
        var t = text
        t = t.replace("/system/bin/sh: can't find tty fd: No such device or address\n", "")
        t = t.replace("/system/bin/sh: warning: won't have full job control\n", "")
        t = t.replace("/bin/sh: can't access tty; job control turned off\n", "")
        t = t.replace(Regex("(?m)^bash: cannot set terminal process group.*\n"), "")
        t = t.replace(Regex("(?m)^bash: no job control in this shell.*\n"), "")
        return filterAnsi(t)
    }

    /**
     * 过滤 ANSI 转义（状态机，跨块安全）。
     * SGR 颜色序列（ESC[..m）保留，供渲染阶段着色；其余序列（光标控制 / OSC 等）一律丢弃。
     */
    private fun filterAnsi(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ansiState) {
                0 -> if (ch == '\u001B') {
                    ansiState = 1
                    ansiBuf.setLength(0)
                    ansiBuf.append(ch)
                } else {
                    out.append(ch)
                }
                1 -> {
                    ansiBuf.append(ch)
                    when (ch) {
                        '[' -> ansiState = 2
                        ']' -> ansiState = 3
                        '\u001B' -> {
                            ansiBuf.setLength(0)
                            ansiBuf.append(ch)
                        }
                        else -> ansiState = 0
                    }
                }
                2 -> {
                    ansiBuf.append(ch)
                    if (ch in '@'..'~') {
                        ansiState = 0
                        if (ch == 'm') out.append(ansiBuf)
                    }
                }
                3 -> {
                    ansiBuf.append(ch)
                    when (ch) {
                        '\u0007' -> ansiState = 0
                        '\u001B' -> ansiState = 4
                    }
                }
                4 -> {
                    ansiBuf.append(ch)
                    ansiState = if (ch == '\\') 0 else 3
                }
                else -> ansiState = 0
            }
        }
        return out.toString()
    }

    // ---- 触摸 / 键盘 ----

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            requestFocus()
            post { showIme() }
        }
        return true
    }

    private fun showIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // 物理 Ctrl + 字母 → 控制字符
        if (event.isCtrlPressed && !event.isAltPressed) {
            val ch = physicalChar(keyCode)
            if (ch != null) {
                onCtrlChar?.invoke(ch.lowercaseChar())
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                submitLine()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                handleBackspace()
                return true
            }
            KeyEvent.KEYCODE_FORWARD_DEL -> {
                handleDeleteForward()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveCursorLeft()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveCursorRight()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                historyPrev()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                historyNext()
                return true
            }
            KeyEvent.KEYCODE_TAB -> {
                sendRaw("\t")
                return true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                sendRaw("\u001B")
                return true
            }
            else -> {
                val ch = event.unicodeChar.toChar()
                if (ch != '\u0000' && !ch.isISOControl() && !event.isAltPressed) {
                    if (ctrlActiveProvider?.invoke() == true) {
                        onCtrlChar?.invoke(ch)
                    } else if (altActiveProvider?.invoke() == true) {
                        sendRaw("\u001B$ch")
                    } else {
                        insertText(ch.toString())
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
            -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    private fun physicalChar(keyCode: Int): Char? {
        return when (keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                ('a' + (keyCode - KeyEvent.KEYCODE_A))
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                ('0' + (keyCode - KeyEvent.KEYCODE_0))
            KeyEvent.KEYCODE_SPACE -> ' '
            else -> null
        }
    }

    // ---- 输入法连接 ----

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return TerminalInputConnection(this, false)
    }

    private inner class TerminalInputConnection(target: View, mutable: Boolean) :
        BaseInputConnection(target, mutable) {

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val s = text?.toString() ?: return true
            composing = ""
            if (s == "\n") {
                submitLine()
                return true
            }
            if (s.length == 1 && ctrlActiveProvider?.invoke() == true) {
                onCtrlChar?.invoke(s[0])
                return true
            }
            if (s.length == 1 && altActiveProvider?.invoke() == true) {
                sendRaw("\u001B${s[0]}")
                return true
            }
            if (s.isNotEmpty()) insertText(s)
            return true
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            composing = text?.toString() ?: ""
            render()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) {
                val c = composing
                composing = ""
                insertText(c)
            } else {
                composing = ""
                render()
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtMost(8)) { handleBackspace() }
            return true
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            deleteSurroundingText(beforeLength, afterLength)

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        submitLine()
                        return true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        handleBackspace()
                        return true
                    }
                    KeyEvent.KEYCODE_FORWARD_DEL -> {
                        handleDeleteForward()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        moveCursorLeft()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        moveCursorRight()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        historyPrev()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        historyNext()
                        return true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        sendRaw("\t")
                        return true
                    }
                    KeyEvent.KEYCODE_ESCAPE -> {
                        sendRaw("\u001B")
                        return true
                    }
                    else -> {}
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                    KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_ESCAPE,
                    -> return true
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun performEditorAction(editorAction: Int): Boolean {
            submitLine()
            return true
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence {
            val c = cursor.coerceIn(0, input.length)
            val start = (c - n).coerceAtLeast(0)
            return input.substring(start, c)
        }

        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence {
            val c = cursor.coerceIn(0, input.length)
            val end = (c + n).coerceAtMost(input.length)
            return input.substring(c, end)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val BLINK_PERIOD_MS = 530L

        /** ANSI 基本色 30-37（暗色主题适配）。 */
        val ANSI_COLORS = intArrayOf(
            Color.parseColor("#3F3F3F"),
            Color.parseColor("#FF5252"),
            Color.parseColor("#00E5A0"),
            Color.parseColor("#FFD166"),
            Color.parseColor("#4FC1FF"),
            Color.parseColor("#D670D6"),
            Color.parseColor("#29B8DB"),
            Color.parseColor("#FFFFFF"),
        )

        /** ANSI 亮色 90-97。 */
        val ANSI_BRIGHT = intArrayOf(
            Color.parseColor("#7A7A7A"),
            Color.parseColor("#FF8080"),
            Color.parseColor("#5CFFC4"),
            Color.parseColor("#FFE08A"),
            Color.parseColor("#82D2FF"),
            Color.parseColor("#E8A6E8"),
            Color.parseColor("#6ADFFF"),
            Color.parseColor("#FFFFFF"),
        )
    }
}