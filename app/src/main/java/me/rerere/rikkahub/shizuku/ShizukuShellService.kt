package me.rerere.rikkahub.shizuku

import android.content.Context
import android.os.Bundle
import android.os.Process as AndroidProcess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * 运行在 Shizuku（root）进程中的 Shell UserService。
 *
 * 与 MobiCode Studio 的 ShizukuShellService 相同：
 * - 由 [rikka.shizuku.Shizuku.bindUserService] 启动（app_process，继承 Shizuku 的 root uid）
 * - 通过 AIDL [IShizukuShellService] 接收命令并返回执行结果
 * - 该类不能在 manifest 声明，也不允许被 R8 移除/混淆（见 proguard-rules.pro）
 */
class ShizukuShellService(@Suppress("UNUSED_PARAMETER") context: Context) : IShizukuShellService.Stub() {

    companion object {
        const val KEY_EXIT_CODE = "exitCode"
        const val KEY_OUTPUT = "output"
        /** stderr 单独返回：之前和 stdout 共用一个 buffer，结果里 stderr 永远是空的。 */
        const val KEY_ERROR_OUTPUT = "errorOutput"
        const val KEY_STDOUT_TRUNCATED = "stdoutTruncated"
        const val KEY_STDERR_TRUNCATED = "stderrTruncated"
        const val KEY_TIMED_OUT = "timedOut"
        const val EXIT_INVALID = -1
        const val EXIT_TIMEOUT = 124
        /**
         * 单个流最多保留的字符数。AIDL 的 Bundle 走 Binder 事务，整个 Bundle 超过约 1MB
         * 就会 "Transaction failed on small parcel"（实测 2MB 直接把服务打挂）。
         * 两个流分离后各 128KB，合计最多 256KB，留出足够余量。超出部分继续读但不保存，
         * 否则子进程写满管道会阻塞；并在内容里写明已截断。
         */
        private const val MAX_OUTPUT_CHARS = 128 * 1024
        private const val DRAIN_JOIN_MILLIS = 5_000L
    }

    override fun getUid(): Int = AndroidProcess.myUid()

    override fun destroy() {
        System.exit(0)
    }

    override fun exec(
        argv: List<String>,
        environment: List<String>,
        workingDirectory: String?,
        timeoutSeconds: Int,
    ): Bundle {
        val result = Bundle()
        var proc: java.lang.Process? = null
        try {
            // root 进程的挂载命名空间与 init 不同，/data/data 下只能看到自己的目录。
            // 通过 nsenter 进入 init(pid 1) 的挂载命名空间，恢复完整 /data/data 可见性。
            // Magisk root 的 mnt ns 与 init 不同；Shizuku UserService 继承 Shizuku 的 ns（与 init 相同或不同）。
            val effectiveArgv: List<String> = if (AndroidProcess.myUid() == 0) {
                listOf("nsenter", "-t", "1", "-m", "--") + argv
            } else {
                argv
            }
            val builder = ProcessBuilder(effectiveArgv)
            if (!workingDirectory.isNullOrBlank()) {
                // 目录可能已被删除：ProcessBuilder.directory 指向不存在的路径会抛 IOException，
                // 整个 exec 直接返回 exitCode=-1，调用方只能看到「失败」而查不出原因。
                val dir = File(workingDirectory)
                if (dir.isDirectory) builder.directory(dir)
            }
            if (environment.isNotEmpty()) {
                val env = builder.environment()
                for (entry in environment) {
                    val index = entry.indexOf('=')
                    if (index > 0) {
                        env[entry.substring(0, index)] = entry.substring(index + 1)
                    }
                }
            }
            val p = builder.start()
            proc = p
            runCatching { p.outputStream.close() }

            // 两个流各用各的 buffer，避免 stderr 混进 stdout、顺序错乱。
            val outBuf = StringBuilder()
            val errBuf = StringBuilder()
            val stdoutThread = Thread { drain(p.inputStream, outBuf) }
                .apply { isDaemon = true; start() }
            val stderrThread = Thread { drain(p.errorStream, errBuf) }
                .apply { isDaemon = true; start() }

            val finished = if (timeoutSeconds > 0) {
                p.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            } else {
                p.waitFor()
                true
            }
            if (!finished) {
                // destroyForcibly 只杀 sh -c 这个父进程，sleep 之类的子进程会变孤儿继续跑。
                // Shizuku 服务本身就是 root，递归杀整棵进程树最稳。
                killProcessTree(p)
            }
            stdoutThread.join(DRAIN_JOIN_MILLIS)
            stderrThread.join(DRAIN_JOIN_MILLIS)

            result.putBoolean(KEY_TIMED_OUT, !finished)
            result.putInt(KEY_EXIT_CODE, if (finished) runCatching { p.exitValue() }.getOrDefault(EXIT_INVALID) else EXIT_TIMEOUT)
            result.putString(KEY_OUTPUT, synchronized(outBuf) { outBuf.toString() })
            result.putString(KEY_ERROR_OUTPUT, synchronized(errBuf) { errBuf.toString() })
            result.putBoolean(KEY_STDOUT_TRUNCATED, synchronized(outBuf) { outBuf.length >= MAX_OUTPUT_CHARS })
            result.putBoolean(KEY_STDERR_TRUNCATED, synchronized(errBuf) { errBuf.length >= MAX_OUTPUT_CHARS })
        } catch (t: Throwable) {
            result.putBoolean(KEY_TIMED_OUT, false)
            result.putInt(KEY_EXIT_CODE, EXIT_INVALID)
            result.putString(KEY_OUTPUT, t.message ?: t.toString())
        } finally {
            runCatching { proc?.destroyForcibly() }
        }
        return result
    }

    private fun drain(input: java.io.InputStream, output: StringBuilder) {
        try {
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), 8192).use { reader ->
                val buffer = CharArray(4096)
                var truncated = false
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    synchronized(output) {
                        if (output.length < MAX_OUTPUT_CHARS) {
                            val room = MAX_OUTPUT_CHARS - output.length
                            output.append(buffer, 0, minOf(n, room))
                        } else if (!truncated) {
                            truncated = true
                            output.append("\n[output truncated: only first $MAX_OUTPUT_CHARS chars captured]")
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 进程结束时流被关闭，忽略
        }
    }

    /** 超时/取消时递归杀进程树（Android 的 Process 没有 pid()，反射读私有字段）。 */
    private fun killProcessTree(process: java.lang.Process) {
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
            runCatching {
                Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", cmd))
                    .waitFor(2_500, TimeUnit.MILLISECONDS)
            }
        }
        runCatching { process.destroyForcibly() }
    }
}
