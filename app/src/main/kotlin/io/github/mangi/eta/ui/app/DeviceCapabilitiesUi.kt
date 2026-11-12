package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.libxposed.service.XposedService
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.PrivilegeAccess
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.device.ShizukuAccess
import io.github.mangi.eta.agent.device.ShizukuAccessState
import io.github.mangi.eta.agent.device.ShizukuStatus
import io.github.mangi.eta.agent.tool.AgentToolCapabilities

internal data class DeviceCapabilitiesUi(
    val root: RootAccessState,
    val shizuku: ShizukuAccessState,
    // 服务回调只反映 Binder 连接，不能用于判断管理器中的模块开关或 Hook 生效状态。
    val xposedConnected: Boolean,
    val tools: AgentToolCapabilities,
) {
    val accessibilityAvailable: Boolean get() = tools.accessibilityAvailable

    /** Root 或 Shizuku 任一可用即可视为具备 shell 级特权。 */
    val privilegeAvailable: Boolean get() = PrivilegeAccess.isGranted
}


@Composable
internal fun rememberDeviceCapabilities(): DeviceCapabilitiesUi {
    val context = LocalContext.current.applicationContext
    val root by RootAccess.state.collectAsState()
    val shizuku by ShizukuAccess.state.collectAsState()
    var xposedConnected by remember { mutableStateOf(EtaApp.serviceInstance != null) }
    var tools by remember { mutableStateOf(AgentToolCapabilities.capture(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, context) {
        val listener = object : EtaApp.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                xposedConnected = service != null
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                RootAccess.refresh(context)
                ShizukuAccess.refresh()
                tools = AgentToolCapabilities.capture(context)
            }
        }
        EtaApp.addServiceStateListener(listener, notifyImmediately = true)
        owner.lifecycle.addObserver(observer)
        onDispose {
            EtaApp.removeServiceStateListener(listener)
            owner.lifecycle.removeObserver(observer)
        }
    }
    return DeviceCapabilitiesUi(
        root = root,
        shizuku = shizuku,
        xposedConnected = xposedConnected,
        tools = tools.copy(rootAvailable = PrivilegeAccess.isGranted),
    )
}

internal fun RootAccessState.description(context: Context): String = context.getString(
    when {
        isChecking -> R.string.capability_root_checking
        status == RootAccessStatus.GRANTED -> R.string.capability_root_granted
        status == RootAccessStatus.UNAVAILABLE -> R.string.capability_root_unavailable
        status == RootAccessStatus.TIMED_OUT -> R.string.capability_root_timeout
        else -> R.string.capability_root_not_granted
    },
)

internal fun ShizukuAccessState.description(context: Context): String = context.getString(
    when {
        isRequesting -> R.string.capability_shizuku_requesting
        status == ShizukuStatus.UNAVAILABLE -> R.string.capability_shizuku_unavailable
        status == ShizukuStatus.GRANTED && isGranted -> R.string.capability_shizuku_granted
        status == ShizukuStatus.GRANTED -> R.string.capability_shizuku_connecting
        status == ShizukuStatus.NOT_GRANTED -> R.string.capability_shizuku_not_granted
        else -> R.string.capability_shizuku_not_granted
    },
)
