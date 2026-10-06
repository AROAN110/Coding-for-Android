package com.AROAN110.CodingAndroid.root

import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Root 请求器 —— 应用层向 Android 申请 root 的统一入口。
 *
 * 流程：
 *  1. 后台线程执行探针 `su -c id`；
 *  2. 首次调用时 Magisk 弹出授权框，用户点「允许」后该 UID 被持久记忆；
 *  3. 授权成功（uid=0）→ Success；被拒 / 超时 / 无 su 分别归为 Denied / Timeout / Error；
 *  4. 结果统一回主线程回调。
 *
 * 约定：任何「自定义 root 指令」需要真 root 时，统一走本入口，
 * 不要在各处散写 su 调用。
 */
object RootRequester {

    /** 授权等待上限（毫秒）：给用户留足点 Magisk 弹窗的时间。 */
    const val DEFAULT_TIMEOUT_MS: Long = 60_000

    sealed class Result {
        /** 授权成功；output 为 `id` 输出（含 uid=0 等）。 */
        data class Success(val output: String) : Result()

        /** 被拒绝（进程退出码非 0 或输出不含 root 标识）。 */
        data class Denied(val exitCode: Int, val output: String) : Result()

        /** 超时未响应（用户未操作弹窗等）。 */
        object Timeout : Result()

        /** 其他错误（如设备无 su 二进制）。 */
        data class Error(val message: String) : Result()
    }

    /** 发起 root 请求。回调在主线程执行。 */
    fun request(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        callback: (Result) -> Unit,
    ) {
        Thread({
            val result = runProbe(timeoutMs)
            Handler(Looper.getMainLooper()).post { callback(result) }
        }, "root-requester").start()
    }

    private fun runProbe(timeoutMs: Long): Result {
        return try {
            val pb = ProcessBuilder("su", "-c", "id")
            pb.redirectErrorStream(true)
            val p = pb.start()

            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroy()
                return Result.Timeout
            }

            val output = try {
                p.inputStream.bufferedReader().readText().trim()
            } catch (_: Exception) {
                ""
            }
            val exitCode = try {
                p.exitValue()
            } catch (_: Exception) {
                -1
            }

            if (exitCode == 0 && output.contains("uid=0")) {
                Result.Success(output)
            } else {
                Result.Denied(exitCode, output)
            }
        } catch (e: IOException) {
            Result.Error("无法启动 su：${e.message}（设备可能未 root）")
        } catch (e: Exception) {
            Result.Error(e.message ?: e.javaClass.simpleName)
        }
    }
}
