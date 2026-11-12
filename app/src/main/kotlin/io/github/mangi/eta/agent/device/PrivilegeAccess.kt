package io.github.mangi.eta.agent.device

import io.github.mangi.eta.agent.device.shizuku.ShizukuShell
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType

/**
 * 特权命令的统一入口：优先 Root，其次 Shizuku（shell / uid 2000）。
 *
 * 所有特权命令都由 Eta 内部构造；这里只决定"由谁执行"，不解释命令输出。
 */
internal object PrivilegeAccess {

    val rootGranted: Boolean get() = RootAccess.isGranted

    val shizukuGranted: Boolean get() = ShizukuAccess.state.value.isGranted

    /** shell 级特权是否可用：Root 或 Shizuku 任一即可。 */
    val isGranted: Boolean get() = rootGranted || shizukuGranted

    /** 仅真实 Root 才具备的能力（chroot、mount、app_process 等）。 */
    val rootOnlyGranted: Boolean get() = rootGranted

    /**
     * 启动特权 shell 进程。Shizuku 分支的进程天然属于 shell 身份，
     * 因此 [environment] 只在 Root 分支写入，避免把 App 私有路径交给 shell 进程。
     */
    fun startProcess(
        command: String,
        mergeStderr: Boolean,
        environment: Map<String, String> = emptyMap(),
        ownershipToken: String? = null,
    ): Process? {
        if (rootGranted) {
            return try {
                val builder = ProcessBuilder("su", "-c", command).redirectErrorStream(mergeStderr)
                environment.forEach { (key, value) -> builder.environment()[key] = value }
                builder.start()
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn("Root process start failed: type=${failure.safeLogType()}")
                null
            }
        }
        return ShizukuShell.startProcess(
            command = command,
            environment = environment,
            ownershipToken = ownershipToken,
        )
    }
}
