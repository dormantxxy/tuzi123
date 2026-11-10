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

/**
 * 应用管理工具（需 root 或 Shizuku）。
 * - list_apps: 列出已安装应用（pm list packages）
 * - app_info: 查看应用信息（dumpsys package）
 * - launch_app: 启动应用（monkey / am start）
 * - force_stop: 强制停止（am force-stop）
 * - extract_apk: 提取 APK 路径（pm path）
 * 全部通过 ShellExecutor 执行。
 */
internal fun buildAppManagerTool(executor: ShellExecutor): Tool = Tool(
    name = "manage_apps",
    description = "Manage installed apps on the Android device (requires root or Shizuku). Actions: list_apps, app_info, launch_app, force_stop, extract_apk. Params: action(required), package(optional, required for info/launch/stop/extract).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject { put("type", "string"); put("description", "list_apps|app_info|launch_app|force_stop|extract_apk") })
                put("package", buildJsonObject { put("type", "string"); put("description", "Package name (required for app_info/launch_app/force_stop/extract_apk)") })
            },
            required = listOf("action"),
        )
    },
    execute = {
        val p = it.jsonObject
        val action = p["action"]?.jsonPrimitive?.contentOrNull ?: error("action is required")
        val pkg = p["package"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
        val mode = executor.preferredMode()

        val cmd = when (action) {
            "list_apps" -> "pm list packages 2>/dev/null | sed 's/^package://' | sort | head -n 500"
            "app_info" -> "dumpsys package " + (pkg ?: error("package required for app_info")).shellQuote() + " 2>&1 | head -n 120"
            "launch_app" -> "monkey -p " + (pkg ?: error("package required for launch_app")).shellQuote() + " -c android.intent.category.LAUNCHER 1 2>&1 | tail -n 3"
            "force_stop" -> "am force-stop " + (pkg ?: error("package required for force_stop")).shellQuote() + " 2>&1 && echo STOPPED"
            "extract_apk" -> "pm path " + (pkg ?: error("package required for extract_apk")).shellQuote() + " 2>&1"
            else -> error("unknown action: $action")
        }
        val r = executor.execute(cmd, timeoutMillis = 60_000, mode = mode)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("action", action); put("mode", mode.name.lowercase()); put("exit_code", r.exitCode)
            put("output", r.output.take(60_000))
        }.toString()))
    }
)
