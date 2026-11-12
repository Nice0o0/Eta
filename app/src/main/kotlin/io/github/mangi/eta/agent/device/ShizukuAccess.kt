package io.github.mangi.eta.agent.device

import android.content.Context
import android.content.pm.PackageManager
import io.github.mangi.eta.agent.device.shizuku.ShizukuShell
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

internal enum class ShizukuStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED }

internal data class ShizukuAccessState(
    val status: ShizukuStatus = ShizukuStatus.UNKNOWN,
    val binderAlive: Boolean = false,
    val permissionGranted: Boolean = false,
    val serviceReady: Boolean = false,
    val isRequesting: Boolean = false,
) {
    /** 授权成功即可用于命令执行；shell 服务在首次执行时才建立连接。 */
    val isGranted: Boolean get() = binderAlive && permissionGranted
}

/**
 * Shizuku 授权状态：与 Root 并列的第二条特权通道，提供 shell（uid 2000）能力。
 *
 * 读取状态不会主动弹窗；只有 [request] 才会向 Shizuku 申请权限。
 */
internal object ShizukuAccess {
    private const val REQUEST_CODE = 4071
    private val mutableState = MutableStateFlow(ShizukuAccessState())
    val state: StateFlow<ShizukuAccessState> = mutableState.asStateFlow()

    private var registered = false

    val isPermissionGranted: Boolean get() = mutableState.value.isGranted

    fun initialize(context: Context) {
        ShizukuShell.initialize(context)
        registerListeners()
        refresh()
    }

    fun refresh() {
        publish()
    }

    /** 显式申请；Shizuku 未运行或已授权时不会弹窗。 */
    fun request() {
        if (publish().isGranted) return
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            AndroidAgentLogger.warn("Shizuku request skipped: binder unavailable")
            return
        }
        mutableState.value = mutableState.value.copy(isRequesting = true)
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure { failure ->
            mutableState.value = mutableState.value.copy(isRequesting = false)
            AndroidAgentLogger.warn("Shizuku permission request failed: type=${failure.safeLogType()}")
        }
    }

    private fun registerListeners() {
        if (registered) return
        registered = true
        runCatching {
            // Sticky：Shizuku 在 App 启动前就运行时也能立即收到 binder 状态。
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener {
                ShizukuShell.onBinderDead()
                publish()
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode != REQUEST_CODE) return@addRequestPermissionResultListener
                mutableState.value = mutableState.value.copy(
                    isRequesting = false,
                    permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED,
                )
                publish()
            }
        }.onFailure { failure ->
            AndroidAgentLogger.warn("Shizuku listener registration failed: type=${failure.safeLogType()}")
        }
    }

    private fun publish(): ShizukuAccessState {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val granted = if (!binderAlive) {
            false
        } else {
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)
        }
        val status = when {
            !binderAlive -> ShizukuStatus.UNAVAILABLE
            !granted -> ShizukuStatus.NOT_GRANTED
            else -> ShizukuStatus.GRANTED
        }
        val next = mutableState.value.copy(
            status = status,
            binderAlive = binderAlive,
            permissionGranted = granted,
            serviceReady = granted && ShizukuShell.isReady,
            isRequesting = mutableState.value.isRequesting && !granted,
        )
        mutableState.value = next
        return next
    }
}
