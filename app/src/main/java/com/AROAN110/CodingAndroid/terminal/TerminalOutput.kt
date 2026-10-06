package com.AROAN110.CodingAndroid.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 终端输出的 UTF-8 流式解码器。
 *
 * 为什么需要它：终端输出是字节流，一个中文字符（3 字节）可能被 read() 切成两块——
 * 直接 new String(chunk) 会在块边界产生乱码。这里保留未完成的多字节序列，
 * 与下一块拼接后再解码（Termux 中由 TerminalOutput 承担同类职责）。
 *
 * 线程约束：仅在读取线程中调用，无锁设计。
 */
class TerminalOutput {

    private var pending = ByteArray(0)

    /** 传入新块，返回可安全显示文本（可能为空串）。 */
    fun write(chunk: ByteArray): String {
        val input = if (pending.isEmpty()) chunk else pending + chunk
        val inBuf = ByteBuffer.wrap(input)

        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)

        val sb = StringBuilder(input.size)
        while (true) {
            val outBuf = CharBuffer.allocate(8192)
            val result = decoder.decode(inBuf, outBuf, false)
            outBuf.flip()
            sb.append(outBuf)
            if (result.isUnderflow || result.isError) break
        }

        // 尾部不完整序列留给下一次拼接
        pending = if (inBuf.hasRemaining()) {
            ByteArray(inBuf.remaining()).also { inBuf.get(it) }
        } else {
            ByteArray(0)
        }
        return sb.toString()
    }
}