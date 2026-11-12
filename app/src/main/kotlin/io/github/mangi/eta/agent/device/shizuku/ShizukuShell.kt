package io.github.mangi.eta.agent.device.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.mangi.eta.BuildConfig
import io.github.mangi.eta.agent.device.ShizukuAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/**
 * 与 Shizuku 的 shell 级 UserService 建立并复用连接。
 *
 * 只有在 Shizuku 已运行且本应用已获得授权时才会真正绑定；绑定与解绑都切回主线程，
 * 具体命令仍由调用方在各自的 IO 线程上发起。
 */
internal object ShizukuShell {
    private const val BIND_TIMEOUT_MS = 4_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bindLock = Any()

    @Volatile private var service: IEtaShellService? = null
    @Volatile private var binding = false
    @Volatile private var appContext: Context? = null

    val isReady: Boolean get() = service != null

    /**
     * 只记录上下文，不触碰 binder；是否可用由 ShizukuAccess 负责判定。
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun onBinderDead() {
        service = null
        binding = false
    }

    /**
     * 已授权时等待绑定完成；未授权、Shizuku 未运行或超时都返回 false。
     */
    fun awaitReady(timeoutMillis: Long = BIND_TIMEOUT_MS): Boolean {
        if (service != null) return true
        if (!ShizukuAccess.isPermissionGranted) return false
        bind()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (service == null) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(10)
        }
        return true
    }

    /**
     * 以 shell 身份启动 `/system/bin/sh -c command`；返回的 Process 与本地进程接口一致。
     */
    fun startProcess(
        command: String,
        directory: String? = null,
        environment: Map<String, String> = emptyMap(),
        ownershipToken: String? = null,
    ): Process? {
        if (!awaitReady()) return null
        val binder = service ?: return null
        // AIDL 的 in String[] 在 Java 侧是数组，必须显式转成 Array 而不是 List。
        val env = environment.entries
            .map { (key, value) -> "$key=$value" }
            .ifEmpty { null }
            ?.toTypedArray()
        return try {
            val handle = binder.spawn(
                arrayOf(SHELL_PATH, "-c", command),
                env,
                directory,
                ownershipToken,
            )
            if (handle <= 0) {
                AndroidAgentLogger.warn("Shizuku shell spawn rejected")
                null
            } else {
                ShizukuShellProcess(binder, handle)
            }
        } catch (failure: Throwable) {
            AndroidAgentLogger.warn("Shizuku shell spawn failed: type=${failure.safeLogType()}")
            null
        }
    }

    private fun bind() {
        val context = appContext ?: return
        synchronized(bindLock) {
            if (service != null || binding) return
            binding = true
        }
        mainHandler.post {
            try {
                if (!Shizuku.pingBinder() || !ShizukuAccess.isPermissionGranted) {
                    binding = false
                    return@post
                }
                // UserService 需要 Shizuku API 10 及以上。
                if (Shizuku.getVersion() < MIN_SERVER_VERSION) {
                    binding = false
                    AndroidAgentLogger.warn("Shizuku version too old for shell service")
                    return@post
                }
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, EtaShellUserService::class.java.name),
                )
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE)
                Shizuku.bindUserService(args, connection)
            } catch (failure: Throwable) {
                binding = false
                AndroidAgentLogger.warn("Shizuku bind rejected: type=${failure.safeLogType()}")
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = binder?.let { IEtaShellService.Stub.asInterface(it) }
            binding = false
            AndroidAgentLogger.info("Shizuku shell service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            binding = false
            AndroidAgentLogger.warn("Shizuku shell service disconnected")
        }
    }

    private const val SHELL_PATH = "/system/bin/sh"
    private const val MIN_SERVER_VERSION = 10
}

/**
 * Shizuku 远程进程的本地包装：三条标准流由 UserService 侧的管道桥接，
 * 判定存活与退出码仍走 binder，避免把管道 EOF 误当作进程结束。
 */
internal class ShizukuShellProcess(
    private val binder: IEtaShellService,
    private val handle: Int,
) : Process() {

    private var input: InputStream? = null
    private var output: InputStream? = null
    private var error: InputStream? = null
    private var feed: OutputStream? = null

    override fun getInputStream(): InputStream = synchronized(this) {
        output ?: ParcelFileDescriptor.AutoCloseInputStream(
            requireNotNull(binder.outputStreamOf(handle)) { "shizuku stdout unavailable" },
        ).also { output = it }
    }

    override fun getErrorStream(): InputStream = synchronized(this) {
        error ?: ParcelFileDescriptor.AutoCloseInputStream(
            requireNotNull(binder.errorStreamOf(handle)) { "shizuku stderr unavailable" },
        ).also { error = it }
    }

    override fun getOutputStream(): OutputStream = synchronized(this) {
        feed ?: ParcelFileDescriptor.AutoCloseOutputStream(
            requireNotNull(binder.inputStreamOf(handle)) { "shizuku stdin unavailable" },
        ).also { feed = it }
    }

    override fun isAlive(): Boolean = runCatching { binder.alive(handle) }.getOrDefault(false)

    override fun waitFor(): Int {
        while (isAlive()) Thread.sleep(POLL_INTERVAL_MS)
        return runCatching { binder.waitFor(handle) }.getOrDefault(-1)
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (isAlive()) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return true
    }

    override fun exitValue(): Int {
        if (isAlive()) throw IllegalThreadStateException("process is still running")
        return runCatching { binder.exitValue(handle) }.getOrDefault(-1)
    }

    override fun destroy() {
        runCatching { binder.kill(handle) }
        closeStreams()
    }

    override fun destroyForcibly(): Process {
        runCatching { binder.kill(handle) }
        closeStreams()
        return this
    }

    /** shell 自己写出的归属标记（"<pid> group|tree|unavailable"）。 */
    fun trackedOwner(): String? = runCatching { binder.trackedOwner(handle) }.getOrNull()

    /** 释放侧句柄；幂等，重复调用不会影响其它会话。 */
    internal fun release() {
        if (!released.compareAndSet(false, true)) return
        closeStreams()
        runCatching { binder.release(handle) }
    }

    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun closeStreams() {
        synchronized(this) {
            runCatching { input?.close() }
            runCatching { output?.close() }
            runCatching { error?.close() }
            runCatching { feed?.close() }
            input = null
            output = null
            error = null
            feed = null
        }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 10L
    }
}
