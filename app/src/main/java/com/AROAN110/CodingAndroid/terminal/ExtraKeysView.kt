package com.AROAN110.CodingAndroid.terminal

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 终端扩展按键栏（软键盘上方常驻）。
 *
 * 按键：ESC / TAB / CTRL / ALT / ← / ↑ / ↓ / → / HOME / END / / / | / - / ~
 *
 * CTRL / ALT 粘滞键：
 *  - 点击切换：激活时文字变红（锁定），再点一次变白（取消）；
 *  - CTRL 激活期间，字符输入由上层转换为控制码发送（如 Ctrl+C → 中断信号）；
 *  - ALT 激活期间，字符输入由上层转换为 ESC 前缀发送（如 Alt+B → ESC B）。
 */
class ExtraKeysView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    fun interface Listener {
        fun onExtraKey(key: String)
    }

    var listener: Listener? = null

    /** CTRL 粘滞状态：true = 红（激活锁定）。 */
    var isCtrlActive: Boolean = false
        private set

    /** ALT 粘滞状态：true = 红（激活锁定）。 */
    var isAltActive: Boolean = false
        private set

    private var ctrlButton: TextView? = null
    private var altButton: TextView? = null

    private val normalColor = Color.parseColor("#B8C4CE")
    private val activeColor = Color.parseColor("#FF5252")

    private data class KeyDef(val label: String, val id: String, val isCtrl: Boolean = false, val isAlt: Boolean = false)

    private val keys = listOf(
        KeyDef("ESC", KEY_ESC),
        KeyDef("TAB", KEY_TAB),
        KeyDef("CTRL", KEY_CTRL, isCtrl = true),
        KeyDef("ALT", KEY_ALT, isAlt = true),
        KeyDef("←", KEY_LEFT),
        KeyDef("↑", KEY_UP),
        KeyDef("↓", KEY_DOWN),
        KeyDef("→", KEY_RIGHT),
        KeyDef("HOME", KEY_HOME),
        KeyDef("END", KEY_END),
        KeyDef("/", KEY_SLASH),
        KeyDef("|", KEY_PIPE),
        KeyDef("-", KEY_MINUS),
        KeyDef("~", KEY_TILDE),
    )

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(3), dp(4), dp(3))
        keys.forEach { addKey(it) }
    }

    private fun addKey(def: KeyDef) {
        val tv = TextView(context).apply {
            text = def.label
            gravity = Gravity.CENTER
            isSingleLine = true
            isFocusable = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (def.label.length > 2) 10f else 13f)
            setTextColor(normalColor)
            background = keyBackground(false)
        }
        tv.layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).also {
            it.setMargins(dp(2), 0, dp(2), 0)
        }
        tv.setOnClickListener { handleClick(def) }
        tv.setOnLongClickListener {
            handleClick(def)
            true
        }
        addView(tv)
        if (def.isCtrl) ctrlButton = tv
        if (def.isAlt) altButton = tv
    }

    private fun handleClick(def: KeyDef) {
        when {
            def.isCtrl -> {
                isCtrlActive = !isCtrlActive
                updateToggleVisual(ctrlButton, isCtrlActive)
            }
            def.isAlt -> {
                isAltActive = !isAltActive
                updateToggleVisual(altButton, isAltActive)
            }
            else -> listener?.onExtraKey(def.id)
        }
    }

    private fun updateToggleVisual(button: TextView?, active: Boolean) {
        button?.let {
            it.setTextColor(if (active) activeColor else normalColor)
            it.background = keyBackground(active)
        }
    }

    private fun keyBackground(active: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor(if (active) "#4A1F24" else "#2A333C"))
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), Color.parseColor(if (active) "#FF5252" else "#3A444E"))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val KEY_ESC = "esc"
        const val KEY_TAB = "tab"
        const val KEY_CTRL = "ctrl"
        const val KEY_ALT = "alt"
        const val KEY_LEFT = "left"
        const val KEY_UP = "up"
        const val KEY_DOWN = "down"
        const val KEY_RIGHT = "right"
        const val KEY_HOME = "home"
        const val KEY_END = "end"
        const val KEY_SLASH = "/"
        const val KEY_PIPE = "|"
        const val KEY_MINUS = "-"
        const val KEY_TILDE = "~"
    }
}