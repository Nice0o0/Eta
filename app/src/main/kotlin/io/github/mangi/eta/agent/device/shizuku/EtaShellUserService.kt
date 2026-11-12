package io.github.mangi.eta.agent.device.shizuku

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * 运行在 Shizuku 侧 shell 进程中的命令执行服务。
 *
 * 这里只做三件事：用 shell 身份启动进程、把三条标准流桥接成 [ParcelFileDescriptor]、
 * 维护句柄到进程的映射。命令构造仍由 App 侧负责，模型参数不会直接进入这里。
 */
@Keep
class EtaShellUserService : IEtaShellService.Stub {

    /**
     * Shizuku 反射实例化时使用；无参与 Context 两个构造都要保留，
     * Context 构造覆盖 API 13 起的实例化路径。
     * 这里显式声明辅助构造函数并调用 super()，不引入主构造，避免反射找不到预期签名。
     */
    @Keep
    constructor() : super()

    @Keep
    constructor(context: Context) : super()

    private class Session(
        val process: Process,
        val output: ParcelFileDescriptor?,
        val error: ParcelFileDescriptor?,
        val input: ParcelFileDescriptor?,
        val ownershipPath: String?,
    ) {
        /** 归属标记由 shell 写入，只有本进程（同为 shell）能读取。 */
        @Volatile var ownerContent: String? = null
    }

    private val handles = AtomicInteger()
    private val sessions = ConcurrentHashMap<Int, Session>()

    override fun spawn(command: Array<String>?, env: Array<String>?, dir: String?, token: String?): Int {
        if (command == null || command.isEmpty()) return 0
        val ownershipPath = token?.takeIf { it.isNotBlank() }?.let { OWNERSHIP_DIRECTORY + "/" + it.ifBlank { "unknown" } }
        val process = try {
            if (ownershipPath != null) runCatching { File(OWNERSHIP_DIRECTORY).mkdirs() }
            val builder = ProcessBuilder(command.toList())
            if (env != null && env.isNotEmpty()) {
                val environment = builder.environment()
                env.forEach { entry ->
                    val index = entry.indexOf('=')
                    if (index > 0) environment[entry.substring(0, index)] = entry.substring(index + 1)
                }
            }
            if (!dir.isNullOrBlank()) builder.directory(File(dir))
            builder.start()
        } catch (_: Throwable) {
            return 0
        }

        val outputStream = runCatching { process.inputStream }.getOrNull()
        val errorStream = runCatching { process.errorStream }.getOrNull()
        val inputStream = runCatching { process.outputStream }.getOrNull()

        val output = outputStream?.let { pumpToPipe(it) }
        val error = errorStream?.let { pumpToPipe(it) }
        val input = inputStream?.let { pumpFromPipe(it) }

        val handle = handles.incrementAndGet()
        sessions[handle] = Session(process, output, error, input, ownershipPath)
        return handle
    }

    override fun trackedOwner(handle: Int): String? {
        val session = sessions[handle] ?: return null
        session.ownerContent?.let { return it }
        val path = session.ownershipPath ?: return null
        val deadline = System.nanoTime() + OWNER_WAIT_NANOS
        while (System.nanoTime() < deadline) {
            val content = runCatching { File(path).readText().trim() }.getOrNull()
            if (!content.isNullOrBlank()) {
                session.ownerContent = content
                return content
            }
            if (!session.process.isAlive) break
            Thread.sleep(10)
        }
        return null
    }

    /**
     * 子进程输出 → 管道写端；读端交给 App，App 关闭时一并结束复制线程。
     */
    private fun pumpToPipe(source: InputStream): ParcelFileDescriptor? {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val read = pipe[0]
        val write = pipe[1]
        thread(name = "eta-shizuku-copy", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { target -> source.use { it.copyTo(target) } }
            } catch (_: Throwable) {
                runCatching { write.close() }
                runCatching { source.close() }
            }
        }
        return read
    }

    /**
     * App 写入端 → 子进程 stdin；App 关闭写端后复制线程结束并关闭 stdin。
     */
    private fun pumpFromPipe(target: OutputStream): ParcelFileDescriptor? {
        val pipe = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        val read = pipe[0]
        val write = pipe[1]
        thread(name = "eta-shizuku-feed", isDaemon = true) {
            try {
                ParcelFileDescriptor.AutoCloseInputStream(read).use { source -> target.use { source.copyTo(it) } }
            } catch (_: Throwable) {
                runCatching { read.close() }
                runCatching { target.close() }
            }
        }
        return write
    }

    override fun outputStreamOf(handle: Int): ParcelFileDescriptor? = sessions[handle]?.output

    override fun errorStreamOf(handle: Int): ParcelFileDescriptor? = sessions[handle]?.error

    override fun inputStreamOf(handle: Int): ParcelFileDescriptor? = sessions[handle]?.input

    override fun waitFor(handle: Int): Int {
        val session = sessions[handle] ?: return -1
        return try {
            session.process.waitFor()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        }
    }

    override fun exitValue(handle: Int): Int {
        val session = sessions[handle] ?: return -1
        if (session.process.isAlive) throw IllegalThreadStateException("process still running")
        return try {
            session.process.exitValue()
        } catch (_: Throwable) {
            -1
        }
    }

    override fun alive(handle: Int): Boolean = sessions[handle]?.process?.isAlive == true

    override fun kill(handle: Int) {
        val session = sessions[handle] ?: return
        runCatching { session.process.destroy() }
        runCatching { session.process.destroyForcibly() }
    }

    override fun release(handle: Int) {
        val session = sessions.remove(handle) ?: return
        runCatching { session.process.destroy() }
        runCatching { session.output?.close() }
        runCatching { session.error?.close() }
        runCatching { session.input?.close() }
        session.ownershipPath?.let { path -> runCatching { File(path).delete() } }
    }

    /** Shizuku 服务端的固定销毁入口。 */
    override fun destroy() {
        sessions.keys.toList().forEach { release(it) }
        System.exit(0)
    }

    private companion object {
        /** 与 App 侧约定一致的 shell 可写目录；App 侧不直接读写，只通过 binder 取结果。 */
        const val OWNERSHIP_DIRECTORY = "/data/local/tmp/eta/owners"
        const val OWNER_WAIT_NANOS = 1_200_000_000L
    }
}
