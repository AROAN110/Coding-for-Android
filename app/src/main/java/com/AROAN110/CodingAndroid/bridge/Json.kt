package com.AROAN110.CodingAndroid.bridge

/**
 * 极简 JSON 取值工具（缺口二 · 骨架）。
 *
 * 为什么手写：MVP 阶段零第三方依赖（org.json 在 Android 上可用，
 * 但为保持与 Web 端协议完全可控、且不引入额外语义，这里只做最小解析）。
 *
 * 局限：仅支持扁平/单层提取，不支持嵌套对象内取值、不支持数组遍历。
 * 若后续协议变复杂，再考虑换 org.json（Android 内置，仍属零依赖）。
 */
object Json {

    /** 提取顶层字符串字段值，例如 "id":"req_1" → req_1。 */
    fun extractString(json: String, key: String): String? {
        val pattern = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        val m = pattern.find(json) ?: return null
        return unescape(m.groupValues[1])
    }

    /** 提取顶层对象字段的原始 JSON 文本，例如 "params":{...} → {...}。 */
    fun extractObject(json: String, key: String): String? {
        val keyIdx = json.indexOf("\"$key\"")
        if (keyIdx < 0) return null
        val colon = json.indexOf(':', keyIdx)
        if (colon < 0) return null
        var i = colon + 1
        while (i < json.length && json[i].isWhitespace()) i++
        if (i >= json.length || json[i] != '{') return null
        var depth = 0
        val start = i
        var inStr = false
        var escaped = false
        while (i < json.length) {
            val c = json[i]
            if (inStr) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return json.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return null
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}