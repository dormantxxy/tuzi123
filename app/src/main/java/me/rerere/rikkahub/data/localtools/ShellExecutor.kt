package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.shizuku.ShizukuShellManager

/** Shell 执行结果 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val isSuccess: Boolean get() = exitCode == 0
    val output: String get() = buildString {
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) { if (isNotEmpty()) append('\n'); append(stderr) }
    }
}

/** 执行权限模式 */
enum class ShellMode { NORMAL, ROOT, SHIZUKU }

/**
 * 统一 Shell 执行器。
 * - NORMAL：普通 app 权限
 * - ROOT：su（root）
 * - SHIZUKU：通过 Shizuku UserService（root 启动的 Shizuku = root 域，可 exec data 目录、seccomp 关）
 *
 * Shizuku 执行方式与 MobiCode Studio 一致：绑定 ShizukuShellService，不再使用 `Shizuku.newProcess` 反射
 * （该 API 在 Shizuku 13.x 中为私有方法，且会被 R8 移除）。
 */
class ShellExecutor(
    private val context: Context,
) {
    companion object {
        /**
         * 单个流最多保留的字符数。命令输出可能极大（cat 大文件、dd、logcat），
         * 不设上限时 StringBuilder 会一直增长直到 OOM。超过部分继续读掉但不保存，
         * 否则子进程写满管道会阻塞、永远退不出。
         */
        private const val MAX_CAPTURE_CHARS = 2 * 1024 * 1024

        const val HOST_PATH =
            "/sbin:/system/bin:/system/xbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"
    }

    /**
     * run_shell 的 HOME / TMPDIR。之前 root 模式下 HOME=/、TMPDIR 为空，
     * 导致 mktemp、$TMPDIR 这类全都失败（mktemp 会去用不存在的 /tmp）。
     * 与 run_cli 保持一致，统一指向 App 私有目录。
     */
    private val homeDir: String by lazy {
        File(context.filesDir, "RikkaHub/.home").apply { mkdirs() }.absolutePath
    }
    private val tmpDir: String by lazy {
        File(context.filesDir, "RikkaHub/.tmp").apply { mkdirs() }.absolutePath
    }

    @Volatile private var rootChecked = false
    @Volatile private var rootAvailable = false

    /**
     * 每次打开 App 调用一次（在后台线程）：轻量检查 root 并缓存。
     * 之后所有工具/页面只读缓存，不再启动 su 进程。
     */
    @Synchronized
    fun refreshPrivilegeCache() {
        rootAvailable = runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", "su -c id 2>/dev/null").redirectErrorStream(true).start()
            p.outputStream.close()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(8, TimeUnit.SECONDS) && out.contains("uid=0")
        }.getOrDefault(false)
        rootChecked = true
    }

    /** 读缓存的 root 状态（未初始化时兜底检查一次）。 */
    fun isRootAvailable(): Boolean {
        if (!rootChecked) refreshPrivilegeCache()
        return rootAvailable
    }

    fun isShizukuAvailable(): Boolean = ShizukuHelper.isBinderAlive()
    fun isShizukuGranted(): Boolean = ShizukuHelper.hasPermission()

    fun hasPrivilege(): Boolean = isRootAvailable() || isShizukuGranted()

    /** root 优先，其次 Shizuku。 */
    fun preferredMode(): ShellMode = when {
        isRootAvailable() -> ShellMode.ROOT
        isShizukuGranted() -> ShellMode.SHIZUKU
        else -> ShellMode.NORMAL
    }

    /** 只能 root / Shizuku（工具链直跑用），都没有返回 null。 */
    fun privilegedMode(): ShellMode? = when {
        isRootAvailable() -> ShellMode.ROOT
        isShizukuGranted() -> ShellMode.SHIZUKU
        else -> null
    }

    suspend fun execute(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = 60_000,
        mode: ShellMode = ShellMode.NORMAL,
        /**
         * 注入到进程标准输入的数据（可选）。
         * 大文件内容必须走这里：su -c / sh -c 的单个参数上限只有 128KB，
         * 把 base64 内容拼进命令行会直接 Argument list too long。
         */
        stdin: ByteArray? = null,
    ): ShellResult = withContext(Dispatchers.IO) {
        if (mode == ShellMode.SHIZUKU) {
            // Shizuku UserService 里 ProcessBuilder.directory() 实测没生效（pwd 仍是 /），
            // 且默认 HOME=/、TMPDIR 为空，mktemp / $TMPDIR 全废。这里在最外层命令文本里
            // 直接 export + cd，和 root 分支一致，不依赖 AIDL 的 workingDirectory 参数。
            val prefix = buildString {
                append("export HOME=").append(homeDir.shellQuote()).append("; ")
                append("export TMPDIR=").append(tmpDir.shellQuote()).append("; ")
                if (!cwd.isNullOrBlank()) {
                    append("cd ").append(cwd.shellQuote()).append(" || cd /; ")
                }
            }
            // Shizuku 通道（AIDL）没有 stdin 通道，大内容只能中转：
            // 先落到 App 私有缓存，再让远端命令从文件读，最后删掉。
            var tmpFile: File? = null
            val effectiveCommand = if (stdin != null) {
                // 中转文件放在 App 自己的 cacheDir：App 有写权限，Shizuku 服务是 root 也能读。
                // 不能放 /data/local/tmp——App 普通权限写那里会 EACCES（实测）。
                val f = File(context.cacheDir, "stdin_${System.nanoTime()}.b64")
                runCatching { f.writeBytes(stdin) }.getOrElse { e ->
                    return@withContext ShellResult(-1, "", "写入临时文件失败：${e.message ?: e.toString()}")
                }
                tmpFile = f
                // 把命令里的 stdin 读取改成从文件读；prefix 放在子 shell 外，确保 cd 生效
                "$prefix cat ${f.absolutePath.shellQuote()} | ( $command )"
            } else "$prefix$command"
            try {
                val r = ShizukuShellManager.exec(
                    command = effectiveCommand,
                    cwd = cwd,
                    timeoutSeconds = (timeoutMillis / 1000).toInt().coerceAtLeast(1),
                )
                return@withContext ShellResult(r.exitCode, r.stdout, r.stderr, r.timedOut)
            } finally {
                tmpFile?.let { runCatching { it.delete() } }
            }
        }
        val process = try {
            when (mode) {
                ShellMode.NORMAL -> startNormal(command, cwd, keepStdinOpen = stdin != null)
                ShellMode.ROOT -> startRoot(command, cwd, keepStdinOpen = stdin != null)
                ShellMode.SHIZUKU -> error("unreachable")
            }
        } catch (e: Throwable) {
            return@withContext ShellResult(-1, "", e.message ?: "执行失败")
        }
        if (stdin != null) {
            // 写入失败（进程提前退出、管道断开）必须让整个调用失败，
            // 否则调用方会拿到「成功」而文件内容其实是残缺的。
            try {
                withContext(Dispatchers.IO) {
                    process.outputStream.write(stdin)
                    process.outputStream.flush()
                    process.outputStream.close()
                }
            } catch (e: CancellationException) {
                runCatching { process.destroyForcibly() }
                throw e
            } catch (e: Throwable) {
                runCatching { process.destroyForcibly() }
                return@withContext ShellResult(-1, "", "写入标准输入失败：${e.message ?: e.toString()}")
            }
        }
        awaitProcess(process, timeoutMillis)
    }

    /** 在聚合后的原生工具链环境执行（root 优先，其次 Shizuku）。 */
    suspend fun executeToolchain(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = 60_000,
        env: ToolchainEnv,
    ): ShellResult {
        val mode = privilegedMode()
            ?: return ShellResult(-1, "", "需要 root 或 Shizuku 权限才能直跑原生工具链（当前都不可用）")
        val script = buildString {
            // 先导出环境变量：后面 cd 失败时要回退到 env.workDir，那时必须已有 HOME/PATH
            if (env.envVars.isNotEmpty()) {
                // 导出完整环境变量：基础 10 个（MobiCode RUNTIME_VARIABLE_ORDER）+ 每个扩展安装时保存的变量
                env.envVars.forEach { (k, v) ->
                    append("export ").append(k).append("=").append(v.shellQuote()).append("; ")
                }
            } else {
                append("export PATH=").append(env.path.shellQuote()).append("; ")
                if (env.ldLibraryPath.isNotBlank()) append("export LD_LIBRARY_PATH=").append(env.ldLibraryPath.shellQuote()).append("; ")
                append("export HOME=").append(env.home.shellQuote()).append("; ")
                append("export TMPDIR=").append(env.tmp.shellQuote()).append("; ")
            }
            append("export LANG=en_US.UTF-8; ")
            if (!cwd.isNullOrBlank()) {
                // cwd 可能已被删除（用户清理工作区、切换绑定后旧的子目录消失）。
                // 用 || 兜底而不是 &&，否则 cd 失败会让后面整条命令静默不执行，
                // 模型只看到一个失败的 exit code，查不出原因。
                append("cd ").append(cwd.shellQuote())
                append(" || cd ").append(env.workDir.shellQuote())
                append(" || cd /; ")
            }
            append(command)
        }
        return withContext(Dispatchers.IO) {
            when (mode) {
                ShellMode.ROOT -> {
                    val process = try {
                        Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", script))
                    } catch (e: Throwable) {
                        return@withContext ShellResult(-1, "", (e.message ?: "执行失败") + "（mode=$mode）")
                    }
                    awaitProcess(process, timeoutMillis)
                }
                ShellMode.SHIZUKU -> {
                    val r = ShizukuShellManager.exec(
                        command = script,
                        cwd = null,
                        timeoutSeconds = (timeoutMillis / 1000).toInt().coerceAtLeast(1),
                    )
                    ShellResult(r.exitCode, r.stdout, r.stderr, r.timedOut)
                }
                else -> ShellResult(-1, "", "不支持的执行模式")
            }
        }
    }

    private fun startNormal(command: String, cwd: String?, keepStdinOpen: Boolean = false): Process {
        val b = ProcessBuilder("/system/bin/sh", "-c", command)
        // 目录可能已被删除：ProcessBuilder 的 directory 指向不存在的路径会直接抛 IOException，
        // 这里先确认存在再设置，否则整条命令连尝试的机会都没有。
        if (!cwd.isNullOrBlank()) {
            val dir = File(cwd)
            if (dir.isDirectory) b.directory(dir)
        }
        b.environment()["PATH"] = HOST_PATH
        b.environment()["HOME"] = homeDir
        b.environment()["TMPDIR"] = tmpDir
        val p = b.start()
        // 需要往 stdin 写数据时必须保持管道打开，否则写入立刻失败
        if (!keepStdinOpen) runCatching { p.outputStream.close() }
        return p
    }

    private fun startRoot(command: String, cwd: String?, keepStdinOpen: Boolean = false): Process {
        // Magisk su 的挂载命名空间与 init 不同（/data/data 只见自己），
        // 用 nsenter 进入 init(pid 1) 的 mnt 命名空间恢复完整可见性（与 ShizukuShellService 一致）。
        // 实测：su -c "nsenter -t 1 -m -- sh -c ..." 能看到全部 329 个 /data/data 目录。
        val full = buildString {
            append("nsenter -t 1 -m -- sh -c ")
            append((buildString {
                // 先清掉继承来的工具链环境变量。App 进程若带有 LD_LIBRARY_PATH（工具链里 export 过），
                // su 会原样继承，导致 screencap / pm / dumpsys 等系统二进制去加载扩展的 .so 而 CANNOT LINK
                // （实测 screencap 报 libunwindstack.so: cannot locate symbol Xzs_Construct）。
                append("unset LD_LIBRARY_PATH LD_PRELOAD; ")
                // root 默认 HOME=/、TMPDIR 为空，mktemp / $TMPDIR 会失败；统一到 App 私有目录
                append("export HOME=").append(homeDir.shellQuote()).append("; ")
                append("export TMPDIR=").append(tmpDir.shellQuote()).append("; ")
                // cwd 可能已被删除：用 || 兜底，避免 cd 失败后整条命令静默不执行
                if (!cwd.isNullOrBlank()) { append("cd "); append(cwd.shellQuote()); append(" || cd /; ") }
                append(command)
            }).shellQuote())
        }
        // 绝对路径：避免 termux.tools 等扩展在 PATH 里放置的假 su 劫持
        val p = Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", full))
        if (!keepStdinOpen) runCatching { p.outputStream.close() }
        return p
    }


    private suspend fun awaitProcess(process: Process, timeoutMillis: Long): ShellResult {
        val outBuf = StringBuilder(); val errBuf = StringBuilder()
        val outT = daemonReader(process.inputStream, outBuf)
        val errT = daemonReader(process.errorStream, errBuf)
        val finished = try {
            // Process.waitFor(timeout) 由内核等待进程退出，不再每 25ms 轮询唤醒 CPU。
            // runInterruptible 保留协程取消能力，取消时下面仍会清理整棵进程树。
            runInterruptible(Dispatchers.IO) {
                process.waitFor(timeoutMillis.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            }
        } catch (e: CancellationException) {
            killTree(process)
            throw e
        }
        if (!finished) { killTree(process); runCatching { process.waitFor(2, TimeUnit.SECONDS) } }
        // 进程结束后管道会关闭，读取线程随即退出；给它足够时间把缓冲读完，
        // 否则大输出（cat 大文件 / logcat）会被截断成残缺内容。
        outT.join(5_000); errT.join(5_000)
        // 仍未结束说明流没有正确关闭，主动关掉底层流让线程退出，避免累积
        if (outT.isAlive) runCatching { process.inputStream.close() }
        if (errT.isAlive) runCatching { process.errorStream.close() }
        val code = if (finished) runCatching { process.exitValue() }.getOrDefault(-1) else -1
        return ShellResult(code, outBuf.toString(), errBuf.toString(), timedOut = !finished)
    }

    /**
     * 取消/超时时杀掉整棵进程树。
     *
     * root 模式下 su → nsenter → sh → 实际命令，这些进程的 uid 都是 root，
     * 而 App 进程（u0_a811）没有 CAP_KILL，直接 kill 一律 Permission denied，
     * 结果命令的子孙进程变成孤儿继续跑（实测 sleep 30 超时后仍残留到自然结束）。
     * 所以必须借 su 以 root 身份递归杀，并用 pgrep 逐层找子进程（pkill -P 只杀一层）。
     */
    private fun killTree(process: Process) {
        // 注意：Android 的 java.lang.Process 没有 Java 9 的 pid()，直接调用会抛
        // NoSuchMethodError（实测导致整个工具调用失败）。这里反射读实现类的 pid 字段，
        // 拿不到就只做 destroyForcibly，绝不能让清理动作把主流程带崩。
        val pid = runCatching {
            var cls: Class<*>? = process.javaClass
            var found = -1L
            while (cls != null && cls != Any::class.java) {
                val f = runCatching { cls!!.getDeclaredField("pid") }.getOrNull()
                if (f != null) {
                    f.isAccessible = true
                    found = (f.get(process) as Number).toLong()
                    break
                }
                cls = cls.superclass
            }
            found
        }.getOrDefault(-1L)
        if (pid > 0) {
            val cmd = "killtree() { for c in \$(/system/bin/pgrep -P \$1 2>/dev/null); do killtree \$c; done; kill -9 \$1 2>/dev/null; }; killtree $pid"
            val killedByRoot = runCatching {
                Runtime.getRuntime().exec(arrayOf("/system/bin/su", "-c", cmd))
                    .waitFor(2_500, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (!killedByRoot) {
                runCatching {
                    Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                        .waitFor(1_500, TimeUnit.MILLISECONDS)
                }
            }
        }
        runCatching { process.destroyForcibly() }
    }

    private fun daemonReader(stream: InputStream, buf: StringBuilder): Thread {
        val t = Thread {
            runCatching {
                stream.bufferedReader().use { r ->
                    val c = CharArray(4096)
                    var truncated = false
                    while (true) {
                        val n = r.read(c); if (n < 0) break
                        synchronized(buf) {
                            if (buf.length < MAX_CAPTURE_CHARS) {
                                val room = MAX_CAPTURE_CHARS - buf.length
                                buf.append(c, 0, minOf(n, room))
                            } else if (!truncated) {
                                truncated = true
                                buf.append("\n[output truncated: only first ")
                                    .append(MAX_CAPTURE_CHARS).append(" chars captured]")
                            }
                        }
                    }
                }
            }
        }
        t.isDaemon = true; t.start(); return t
    }

    suspend fun fileExists(path: String): Boolean =
        execute("test -e ${path.shellQuote()} && echo YES || echo NO", mode = preferredMode()).stdout.trim() == "YES"

    suspend fun listDirectory(path: String): List<FileEntry> {
        val r = execute("ls -la ${path.shellQuote()} 2>/dev/null | tail -n +2", mode = ShellMode.NORMAL)
        if (!r.isSuccess) return emptyList()
        return r.stdout.lineSequence().filter { it.isNotBlank() }.mapNotNull { parseLsLine(it, path) }.toList()
    }
}

data class FileEntry(val name: String, val isDirectory: Boolean, val sizeBytes: Long, val path: String)

private fun parseLsLine(line: String, basePath: String): FileEntry? {
    if (line.length < 40) return null
    val type = line.firstOrNull() ?: return null
    val name = line.substringAfterLast(" ").trim()
    if (name.isEmpty() || name == "." || name == "..") return null
    val size = line.split(" ").filter { it.isNotBlank() }.getOrNull(4)?.toLongOrNull() ?: 0
    return FileEntry(name, type == 'd', size, if (basePath.endsWith("/")) "$basePath$name" else "$basePath/$name")
}

/** POSIX 单引号转义 */
fun String.shellQuote(): String = "'" + replace("'", "'\\''") + "'"
