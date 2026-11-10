package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.ExtensionInstaller
import me.rerere.rikkahub.data.localtools.MobiCodeCatalog
import me.rerere.rikkahub.data.localtools.ToolchainExecutor

/** 工具链增强：查看环境信息。 */
internal fun buildEnvInfoTool(
    context: Context,
    executor: ToolchainExecutor,
    installer: ExtensionInstaller,
    defaultDir: String? = null,
): Tool = Tool(
    name = "env_info",
    description = "查看原生工具链环境：工作目录、PATH、LD_LIBRARY_PATH、已安装扩展列表与权限状态。",
    parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
    execute = {
        val env = executor.currentEnv(projectRoot = defaultDir)
        val payload = buildJsonObject {
            put("workDir", defaultDir?.takeIf { it.isNotBlank() } ?: env.workDir)
            put("projectRoot", env.envVars["MOBICODE_PROJECT_ROOT"] ?: env.workDir)
            put("path", env.path)
            put("ldLibraryPath", env.ldLibraryPath)
            put("hasPrivilege", executor.hasPrivilege())
            put("installed", buildJsonArray {
                installer.installed.value.values.sortedBy { it.id }.forEach { rec ->
                    add(buildJsonObject {
                        put("id", rec.id)
                        put("name", rec.name)
                        put("version", rec.version)
                        put("format", rec.format)
                        put("envVars", rec.envVars.size)
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 工具链增强：搜索扩展商店的 Termux 包。 */
internal fun buildPkgSearchTool(context: Context): Tool = Tool(
    name = "pkg_search",
    description = "搜索扩展商店的 Termux 软件包（约 3000+ 个，来自在线索引）。Params: query(required), limit(optional default 20).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject { put("type", "string"); put("description", "搜索关键词（包名/描述）") })
                put("limit", buildJsonObject { put("type", "number"); put("description", "返回数量（默认 20，最大 100）") })
            },
            required = listOf("query"),
        )
    },
    execute = {
        val p = it.jsonObject
        val query = p["query"]?.jsonPrimitive?.contentOrNull ?: error("query is required")
        val limit = (p["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 20).coerceIn(1, 100)
        val all = MobiCodeCatalog.ensureTermuxIndex(context)
        val q = query.lowercase().trim()
        val results = all.filter { e ->
            e.name.contains(q, true) || e.description.contains(q, true) ||
                e.id.contains(q, true) || e.category.contains(q, true)
        }.take(limit)
        val payload = buildJsonObject {
            put("query", query)
            put("total", all.size)
            put("results", buildJsonArray {
                results.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id)
                        put("name", e.name)
                        put("version", e.version)
                        put("size", e.downloadSize)
                        put("category", e.category)
                        put("description", e.description)
                        put("depends", e.depends.size)
                    })
                }
            })
        }
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/** 工具链增强：安装扩展（自动依赖）。 */
internal fun buildPkgInstallTool(context: Context, installer: ExtensionInstaller): Tool = Tool(
    name = "pkg_install",
    description = "安装扩展商店中的 Termux 软件包（自动安装依赖）。Params: id(required, 包 id 或名称).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string"); put("description", "扩展 id（如 termux.python）或名称（如 python）") })
            },
            required = listOf("id"),
        )
    },
    execute = {
        val p = it.jsonObject
        val id = p["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
        val all = MobiCodeCatalog.ensureTermuxIndex(context)
        val entry = all.firstOrNull { it.id == id || it.name.equals(id, ignoreCase = true) || it.id == "termux." + id.lowercase() }
            ?: error("未找到扩展：$id")
        if (installer.isInstalled(entry.id)) {
            listOf(UIMessagePart.Text("已安装：${entry.name} ${entry.version}"))
        } else {
            val result = installer.install(entry)
            result.fold(
                // depends 只是直接依赖，实际会递归安装传递依赖，这里不再报一个偏小的数字
                onSuccess = { rec -> listOf(UIMessagePart.Text("安装成功：${rec.name} ${rec.version}，依赖已一并安装")) },
                onFailure = { e -> listOf(UIMessagePart.Text("安装失败：${e.message ?: "未知错误"}")) },
            )
        }
    }
)
