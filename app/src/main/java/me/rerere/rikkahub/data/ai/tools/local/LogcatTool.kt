package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.shellQuote

/** 读取 Android 日志（logcat，需要 root/Shizuku）。 */
internal fun buildLogcatTool(executor: ShellExecutor): Tool = Tool(
    name = "logcat",
    description = "读取 Android 系统/应用日志（需 root/Shizuku）。Params: lines(默认 200，最大 2000), filter(可选关键字/tag)。",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("lines", buildJsonObject { put("type", "number"); put("description", "行数（默认 200）") })
                put("filter", buildJsonObject { put("type", "string"); put("description", "过滤关键字/tag") })
            }
        )
    },
    execute = {
        val p = it.jsonObject
        val lines = (p["lines"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 200).coerceIn(10, 2000)
        val filter = p["filter"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val cmd = buildString {
            append("logcat -d -t ").append(lines)
            if (filter != null) append(" | grep -i ").append(filter.shellQuote())
        }
        val r = executor.execute(cmd, timeoutMillis = 30_000, mode = executor.preferredMode())
        listOf(UIMessagePart.Text(buildJsonObject {
            put("exit_code", r.exitCode)
            put("output", r.output.take(120_000))
        }.toString()))
    }
)
