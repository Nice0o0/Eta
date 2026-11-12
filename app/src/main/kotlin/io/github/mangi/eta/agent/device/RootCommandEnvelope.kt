package io.github.mangi.eta.agent.device

import java.util.UUID

/**
 * 在固定命令执行前确认特权身份，避免把命令自身的非零退出误判为授权撤销。
 *
 * [requireRootUid] 为 true 时脚本强制要求 uid 0（Root）；为 false 时适用于
 * Shizuku 提供的 shell（uid 2000）通道，不再做 uid 校验，只依赖正向标记确认命令确实由特权端执行。
 */
internal class RootCommandEnvelope(
    command: String,
    token: String = UUID.randomUUID().toString(),
    requireRootUid: Boolean = true,
) {
    private val marker = if (requireRootUid) {
        "ETA_ROOT_GRANTED_$token\n"
    } else {
        "ETA_SHELL_GRANTED_$token\n"
    }
    val markerBytes: Int get() = marker.length
    val script: String = buildString {
        if (requireRootUid) append("[ \"\$(id -u)\" = 0 ] || exit 77; ")
        append("printf '%s\\n' ")
        append(quote(marker.trimEnd()))
        append(" >&2; exec /system/bin/sh -c ")
        append(quote(command))
    }

    fun inspect(stderr: String): Output {
        val index = stderr.indexOf(marker)
        return if (index < 0) Output(granted = false, stderr = stderr) else {
            Output(granted = true, stderr = stderr.removeRange(index, index + marker.length))
        }
    }

    data class Output(val granted: Boolean, val stderr: String) {
        fun denied(completed: Boolean): Boolean = completed && !granted
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
