package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.ToolchainExecutor

private val CLI_DANGEROUS_PATTERNS = listOf(
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

private fun cliIsDangerous(text: String): Boolean =
    CLI_DANGEROUS_PATTERNS.any { it.containsMatchIn(text.lowercase()) }

/**
 * 原生工具链 CLI：在所有已装扩展聚合成的 PATH/LD_LIBRARY_PATH 环境里执行用户命令，
 * 经 root/Shizuku 通道直跑 app data 目录下的 arm64 二进制（无 proot，原生速度）。
 * 装好对应扩展后 git/python3/node/gcc/apktool/java/php/sqlite3/rg 等直接可用。
 */
internal fun buildRunCliTool(executor: ToolchainExecutor, defaultDir: String): Tool = Tool(
    name = "run_cli",
    description = "Run a CLI command inside the native MobiCode toolchain environment " +
        "(PATH/LD_LIBRARY_PATH aggregated from all installed extensions, executed via root). " +
        "Installed tools such as git, python3, node/npm, gcc/clang, java, apktool, php, sqlite3, rg are directly usable. " +
        "Params: command(required), cwd(optional, default temporary workspace: $defaultDir), timeout_seconds(optional, default 60).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject { put("type", "string"); put("description", "Shell command to run in the toolchain env") })
                put("cwd", buildJsonObject { put("type", "string"); put("description", "Working directory (optional)") })
                put("timeout_seconds", buildJsonObject { put("type", "number"); put("description", "Timeout seconds (optional, default 60)") })
            },
            required = listOf("command"),
        )
    },
    needsApproval = { cliIsDangerous(it.toString()) },
    execute = {
        val p = it.jsonObject
        val cmd = p["command"]?.jsonPrimitive?.contentOrNull ?: error("command is required")
        // cwd 可能是相对路径，按工作区根目录解析（进程当前目录不可预期）
        val cwd = p["cwd"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?.let { if (it.startsWith("/")) it else java.io.File(defaultDir, it).path }
            ?: defaultDir
        val timeoutMs = (p["timeout_seconds"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.times(1000) ?: 60_000.0)
            .toLong().coerceIn(1_000L, 600_000L)
        if (!executor.hasPrivilege()) {
            listOf(UIMessagePart.Text(buildJsonObject {
                put("error", "需要 root 或 Shizuku 权限才能直跑原生工具链")
            }.toString()))
        } else {
            val r = executor.run(cmd, cwd = cwd, timeoutMillis = timeoutMs)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("exit_code", r.exitCode)
                put("timed_out", r.timedOut)
                // 截断要显式标注，否则模型会把半截输出当成完整结果
                put("stdout", r.stdout.take(60_000))
                if (r.stdout.length > 60_000) put("stdout_truncated", "${r.stdout.length} chars total")
                put("stderr", r.stderr.take(20_000))
                if (r.stderr.length > 20_000) put("stderr_truncated", "${r.stderr.length} chars total")
            }.toString()))
        }
    }
)
