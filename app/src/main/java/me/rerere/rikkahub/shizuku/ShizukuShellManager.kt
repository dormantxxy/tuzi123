package me.rerere.rikkahub.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * Shizuku UserService 管理器（照搬 MobiCode Studio 的 ShizukuShellManager 思路）。
 *
 * 通过 [Shizuku.bindUserService] 绑定 [ShizukuShellService]（运行在 Shizuku 的 root 进程），
 * 再通过 AIDL 执行 shell 命令。完全不依赖 `Shizuku.newProcess`（13.x 中为私有方法且会被 R8 移除）。
 */
object ShizukuShellManager {
    private const val TAG = "ShizukuShellManager"
    private const val BIND_TIMEOUT_MILLIS = 8_000L

    private val lock = java.lang.Object()

    @Volatile
    private var service: IShizukuShellService? = null

    @Volatile
    private var serviceArgs: Shizuku.UserServiceArgs? = null

    @Volatile
    private var installed = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.i(TAG, "Shizuku shell service connected")
            synchronized(lock) {
                service = IShizukuShellService.Stub.asInterface(binder)
                lock.notifyAll()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Shizuku shell service disconnected")
            synchronized(lock) {
                service = null
            }
        }
    }

    /** 在 Application.onCreate 调用一次。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        serviceArgs = runCatching {
            Shizuku.UserServiceArgs(ComponentName(appContext, ShizukuShellService::class.java))
                .daemon(false)
                .processNameSuffix("shizuku_shell")
                .debuggable(false)
                // 服务实现改了（stderr 分离/截断提示/超时杀树），必须升版本，
                // 否则 Shizuku 会复用旧进程，新代码不生效。
                .version(2)
        }.onFailure { Log.e(TAG, "create UserServiceArgs failed", it) }.getOrNull()

        // binder 重启后旧 service 失效，等待下次 bind
        runCatching {
            Shizuku.addBinderReceivedListenerSticky {
                synchronized(lock) {
                    service = null
                }
            }
        }
        runCatching {
            Shizuku.addBinderDeadListener {
                synchronized(lock) {
                    service = null
                }
            }
        }
    }

    /** 绑定并获取服务（最多等 8 秒）。 */
    private fun ensureService(): IShizukuShellService? {
        service?.let { return it }
        val args = serviceArgs ?: return null
        if (!Shizuku.pingBinder()) return null
        synchronized(lock) {
            service?.let { return it }
            runCatching {
                Shizuku.bindUserService(args, connection)
            }.onFailure {
                Log.e(TAG, "bindUserService failed", it)
            }
            val deadline = System.currentTimeMillis() + BIND_TIMEOUT_MILLIS
            while (service == null && System.currentTimeMillis() < deadline) {
                runCatching { lock.wait(200) }
                if (!Shizuku.pingBinder()) return null
            }
            return service
        }
    }

    /**
     * 在 Shizuku（root）进程执行 shell 命令。
     * 使用 suspendCancellableCoroutine：对话停止/协程取消时立即返回（协程取消不再等命令跑完）。
     */
    suspend fun exec(
        command: String,
        cwd: String? = null,
        timeoutSeconds: Int = 60,
        environment: Map<String, String> = emptyMap(),
    ): Result = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { cont ->
            val worker = Thread {
                try {
                    val svc = ensureService()
                    if (svc == null) {
                        if (cont.isActive) cont.resume(
                            Result(-1, "", "Shizuku shell service 不可用（未授权或绑定失败）", timedOut = false)
                        )
                        return@Thread
                    }
                    val bundle = svc.exec(
                        listOf("/system/bin/sh", "-c", command),
                        environment.map { "${it.key}=${it.value}" },
                        cwd,
                        timeoutSeconds,
                    )
                    if (cont.isActive) cont.resume(
                        Result(
                            exitCode = bundle.getInt(ShizukuShellService.KEY_EXIT_CODE, -1),
                            stdout = bundle.getString(ShizukuShellService.KEY_OUTPUT) ?: "",
                            stderr = bundle.getString(ShizukuShellService.KEY_ERROR_OUTPUT) ?: "",
                            timedOut = bundle.getBoolean(ShizukuShellService.KEY_TIMED_OUT, false),
                        )
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "exec failed", t)
                    if (cont.isActive) cont.resume(Result(-1, "", t.message ?: t.toString(), timedOut = false))
                }
            }
            worker.isDaemon = true
            worker.name = "shizuku-shell-exec"
            worker.start()
            cont.invokeOnCancellation { worker.interrupt() }
        }
    }

    fun isAvailable(): Boolean = Shizuku.pingBinder() && serviceArgs != null

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val timedOut: Boolean,
    )
}
