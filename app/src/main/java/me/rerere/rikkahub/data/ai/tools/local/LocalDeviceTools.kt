package me.rerere.rikkahub.data.ai.tools.local

import android.util.Base64
import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.ExtensionInstaller
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ShellResult
import me.rerere.rikkahub.data.localtools.ToolchainExecutor
import me.rerere.rikkahub.data.localtools.shellQuote


/**
 * 已装扩展提供的全部命令名（含扩展自带的库工具）。
 * 每次执行现算：扩展可能刚装好或刚卸载，缓存会给出过期结果。
 */
private fun rememberExtensionCommands(installer: ExtensionInstaller?): Set<String> {
    if (installer == null) return emptySet()
    return runCatching {
        installer.installed.value.values
            .flatMap { rec -> File(rec.payloadDir, "bin").listFiles()?.map { it.name } ?: emptyList() }
            .toSet()
    }.getOrDefault(emptySet())
}


// 精确的危险命令判断：避免 rm -rf /data/... 这类安全路径被误判
private val DANGEROUS_PATTERNS = listOf(
    Regex("""rm\s+-rf\s+/(\s|$|;|&|\|)"""),
    Regex("""rm\s+-rf\s+/\*"""),
    Regex("""rm\s+-rf\s+/(system|data|vendor|product|sdcard|storage)(\s|$|;|&|\|)"""),
    Regex("""\bmkfs(\s|$)"""),
    Regex("""\bdd\s+if="""),
    Regex(""">\s*/dev/block"""),
    Regex("""\breboot(\s|$)"""),
    Regex("""\bshutdown(\s|$)"""),
    Regex("""\bpm\s+uninstall(\s|$)"""),
    Regex("""\bfastboot(\s|$)"""),
    Regex("""\bmv\s+/\*"""),
)
private fun isDangerous(text: String): Boolean = DANGEROUS_PATTERNS.any { it.containsMatchIn(text.lowercase()) }

/** 设备 Shell：root(su) 优先，否则普通权限。命令首词命中扩展 bin 时自动切工具链环境。 */
internal fun buildRunShellTool(
    executor: ShellExecutor,
    defaultDir: String,
    toolchainExecutor: ToolchainExecutor? = null,
    installer: ExtensionInstaller? = null,
): Tool = Tool(
    name = "run_shell",
    description = "Execute a shell command on the Android device (root via su if available). Default cwd is the temporary workspace: $defaultDir. Params: command, cwd(optional), timeout_seconds(optional).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run") })
                put("cwd", buildJsonObject { put("type", "string"); put("description", "Working directory (optional)") })
                put("timeout_seconds", buildJsonObject { put("type", "number"); put("description", "Timeout seconds (optional, default 60)") })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { isDangerous(it.toString()) },
    execute = {
        val p = it.jsonObject
        val cmd = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        // cwd 可能是相对路径（模型常写 src 或 ./src）。进程当前目录不可预期（root 下是 /），
        // 必须按工作区根目录解析，否则 cd 到错误位置甚至直接失败。
        val cwd = p["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?.let { if (it.startsWith("/")) it else java.io.File(defaultDir, it).path }
            ?: defaultDir
        val timeoutMs = (p["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.times(1000) ?: 60_000.0)
            .toLong().coerceIn(1_000L, 600_000L)
        val mode = executor.preferredMode()
        // 自动分流：命令里出现任一已装扩展的 bin 命令，就用聚合工具链环境执行。
        // 不能只看第一个词：`cd /x && git status` 的首词是 cd，会让 git 落到系统环境，
        // 而扩展二进制缺 LD_LIBRARY_PATH 会直接 CANNOT LINK（实测 libpcre2-8.so not found）。
        // 系统命令（pm/am/dumpsys）不在扩展 bin 里，仍然走系统环境，不受污染。
        val extensionCommands = rememberExtensionCommands(installer)
        // 只看每条子命令的「命令词」，不能看所有 token：`echo git`、`pm list packages | grep git`
        // 只是参数里出现扩展命令名，若整条被判进工具链环境，系统程序会被扩展的 LD_LIBRARY_PATH 污染。
        // 命令词 = 跳过 VAR=value 赋值与 shell 包装词（do/then/xargs/env…）后的第一个词，
        // 这样 `for f in *; do rg $f; done`、`xargs git` 也能正确识别。
        val shellWrappers = setOf(
            "do", "then", "else", "elif", "if", "while", "until",
            "time", "env", "nohup", "xargs", "command", "exec", "nice", "stdbuf", "sudo",
        )
        val useToolchain = toolchainExecutor != null && extensionCommands.isNotEmpty() &&
            cmd.split(Regex("""[;&|]+"""))
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .any { sub ->
                    val commandWord = sub.split(Regex("""\s+"""))
                        .filter { w -> w.isNotBlank() && !w.contains('=') && w !in shellWrappers }
                        .firstOrNull()
                        ?.substringAfterLast('/')
                        ?: ""
                    commandWord in extensionCommands
                }
        val r = if (useToolchain) {
            val tr = toolchainExecutor.run(cmd, cwd = cwd, timeoutMillis = timeoutMs)
            ShellResult(tr.exitCode, tr.stdout, tr.stderr, tr.timedOut)
        } else {
            executor.execute(cmd, cwd = cwd, timeoutMillis = timeoutMs, mode = mode)
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("mode", if (useToolchain) "toolchain" else mode.name.lowercase())
            put("exit_code", r.exitCode); put("timed_out", r.timedOut)
            // 截断要显式说明：否则模型会把半截输出当成完整结果。
            put("stdout", r.stdout.take(60_000))
            if (r.stdout.length > 60_000) put("stdout_truncated", "${r.stdout.length} chars total")
            put("stderr", r.stderr.take(20_000))
            if (r.stderr.length > 20_000) put("stderr_truncated", "${r.stderr.length} chars total")
        }.toString()))
    }
)

/** 文件管理（list/read/write/delete/mkdir/copy/move/exists/search/info）。 */
internal fun buildManageFilesTool(executor: ShellExecutor, defaultDir: String): Tool = Tool(
    name = "manage_files",
    description = "Manage files on the Android device. Default working area (temporary workspace): $defaultDir. Actions: list, read, write, delete, mkdir, copy, move, exists, search, info. Params: action(required), path, dst, content.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("description", "list|read|write|delete|mkdir|copy|move|exists|search|info") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Target path") })
                put("dst", buildJsonObject { put("type", "string"); put("description", "Destination (copy/move)") })
                put("content", buildJsonObject { put("type", "string"); put("description", "Text content (write) / search pattern") })
            },
            required = listOf("action"),
        )
    },
    needsApproval = { isDangerous(it.toString()) },
    execute = {
        val p = it.jsonObject
        val action = p["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        // ShellExecutor 的 root 进程初始目录是 /。文件工具的相对路径必须从默认工作区解析，
        // 否则像 write { path: "a.txt" } 会错误写到 /a.txt 并因只读而失败。
        fun resolveWorkspacePath(raw: String): String =
            if (raw.startsWith("/")) raw else java.io.File(defaultDir, raw).path
        // cp/mv（toybox）不拒绝「目标在源目录内部」：cp -rf a a/b/inner 会无限递归
        // （实测产生 1009 层嵌套目录；目录里若有大文件会成倍复制）。这里先挡住。
        fun ensureNotNested(srcPath: String, dstPath: String, verb: String) {
            val srcCanon = runCatching { java.io.File(srcPath).canonicalPath }.getOrNull() ?: return
            val dstCanon = runCatching { java.io.File(dstPath).canonicalPath }.getOrNull() ?: return
            if (dstCanon == srcCanon || dstCanon.startsWith(srcCanon + java.io.File.separator)) {
                error("不能把路径${verb}到它自己的子目录里（会无限递归）")
            }
        }
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?.let(::resolveWorkspacePath)
        val dst = p["dst"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?.let(::resolveWorkspacePath)
        val content = p["content"]?.jsonPrimitive?.contentOrNull
        val mode = executor.preferredMode()

        if (action == "search") {
            // search 用纯 Kotlin 实现（不依赖系统 grep）
            val pattern = content ?: error("content/pattern required for search")
            val searchPath = path ?: defaultDir
            val output = searchFiles(searchPath, pattern, include = null, maxResults = 200, caseSensitive = false)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("action", action); put("mode", "kotlin"); put("exit_code", 0); put("output", output.take(80_000))
            }.toString()))
        } else if (action == "write") {
            // 内容一律走 stdin：su -c / sh -c 的单个参数上限只有 128KB，
            // 把 base64 拼进命令行写大文件必然 Argument list too long。
            // 命令本身保持常量长度，与文件大小无关。
            val pth = path ?: error("path is required for write")
            val bytes = (content ?: "").toByteArray(Charsets.UTF_8)
            val parent = pth.substringBeforeLast('/', "")
            val mkdir = if (parent.isNotBlank()) "mkdir -p ${parent.shellQuote()}; " else ""
            val cmd = "${mkdir}base64 -d > ${pth.shellQuote()} 2>/dev/null && echo WROTE_OK || { echo WRITE_FAILED; false; }"
            val r = executor.execute(
                cmd,
                timeoutMillis = 120_000,
                mode = mode,
                stdin = Base64.encodeToString(bytes, Base64.NO_WRAP).toByteArray(Charsets.UTF_8),
            )
            listOf(UIMessagePart.Text(buildJsonObject {
                put("action", action); put("mode", mode.name.lowercase()); put("exit_code", r.exitCode)
                put("output", r.output.take(80_000))
            }.toString()))
        } else {
        val cmd: String = when (action) {
            "list" -> "ls -la ${(path ?: error("path is required for list")).shellQuote()} 2>&1"
            "read" -> {
                val pth = path ?: error("path is required for read")
                val q = pth.shellQuote()
                // 目录/不存在必须以非零退出：否则 exit_code=0，模型会把「Is a directory」
                // 这类错误输出当成文件正文。超过 60KB 只取前 60KB 并在末尾标注真实大小。
                "if [ -d $q ]; then echo '[error] path is a directory'; exit 1; fi; " +
                    "if [ ! -e $q ]; then echo '[error] file not found'; exit 1; fi; " +
                    "sz=\$(wc -c < $q 2>/dev/null || echo 0); " +
                    "cat $q 2>&1 | head -c 60000; " +
                    "if [ \"\$sz\" -gt 60000 ] 2>/dev/null; then echo; echo \"[truncated: \$sz bytes total, first 60000 shown]\"; fi"
            }
            "delete" -> "rm -rf ${(path ?: error("path is required for delete")).shellQuote()} 2>&1 && echo DELETED || { echo DELETE_FAILED; false; }"
            "copy" -> {
                val src = path ?: error("path required")
                val d = dst ?: error("dst required")
                ensureNotNested(src, d, "复制")
                "cp -rf ${src.shellQuote()} ${d.shellQuote()} 2>&1 && echo COPIED || { echo COPY_FAILED; false; }"
            }
            "mkdir" -> "mkdir -p ${(path ?: error("path is required for mkdir")).shellQuote()} 2>&1 && echo MKDIR_OK || { echo MKDIR_FAILED; false; }"
            "move" -> {
                val src = path ?: error("path required")
                val d = dst ?: error("dst required")
                ensureNotNested(src, d, "移动")
                "mv -f ${src.shellQuote()} ${d.shellQuote()} 2>&1 && echo MOVED || { echo MOVE_FAILED; false; }"
            }
            "exists" -> "test -e ${(path ?: error("path required")).shellQuote()} && echo YES || echo NO"
            "info" -> "ls -ld ${(path ?: error("path required for info")).shellQuote()} 2>&1 && true || { echo INFO_FAILED; false; }"
            else -> error("unknown action: $action")
        }
        val r = executor.execute(cmd, timeoutMillis = 120_000, mode = mode)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("action", action); put("mode", mode.name.lowercase()); put("exit_code", r.exitCode); put("output", r.output.take(80_000))
        }.toString()))
        }
    }
)
