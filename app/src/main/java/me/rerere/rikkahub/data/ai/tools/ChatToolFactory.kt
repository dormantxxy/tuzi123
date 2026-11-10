package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.local.LocalTools
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.files.FileFolders
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.memoryId
import me.rerere.rikkahub.data.skills.SkillManager
import me.rerere.workspace.WorkspaceShellStatus
import me.rerere.workspace.WorkspaceType
import java.io.File
import kotlin.uuid.Uuid

private const val TAG = "ChatToolFactory"

/** 直接操作宿主机的本地工具：绑定普通 Rootfs / SSH 工作区时全部不暴露。 */
private val DEVICE_LEVEL_LOCAL_TOOLS = setOf(
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Shell,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Cli,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Toolchain,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.UiAutomation,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Screenshot,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Logcat,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.AppManager,
    // 这些同样直接操作宿主机文件/网络，Rootfs 与 SSH 工作区里必须隐藏，
    // 否则 AI 会在工作区对话里用 manage_files 写到宿主机（不是 /workspace）。
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.Files,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.SearchCode,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.FileTree,
    me.rerere.rikkahub.data.ai.tools.local.LocalToolOption.WebFetch,
)
private const val MAX_TOOL_OUTPUT_CHARS = 32 * 1024
private const val TOOL_OUTPUT_PREVIEW_CHARS = 4 * 1024

// 搜索结果的体积由用户设置的结果数决定，且结果列表 UI 与引用跳转都依赖完整的 JSON 结构，不参与截断
private val TOOLS_WITHOUT_OUTPUT_TRUNCATION = setOf("search_web")

internal fun shouldUseExternalWebSearch(assistant: Assistant, model: Model): Boolean {
    return assistant.enableWebSearch && BuiltInTools.Search !in model.tools
}

class InvalidMcpServerNamesException(val names: List<String>) :
    IllegalStateException("Invalid MCP server names: ${names.joinToString(", ")}")

/** Creates the complete tool set for one generation run, including approval resumption. */
class ChatToolFactory(
    private val context: Context,
    private val memoryRepository: MemoryRepository,
    private val conversationRepository: ConversationRepository,
    private val localTools: LocalTools,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val workspaceRepository: WorkspaceRepository,
) {
    suspend fun createTools(
        settings: Settings,
        assistant: Assistant,
        model: Model,
        workspaceCwd: String? = null,
    ): List<Tool> {
        val boundWorkspace = assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }
        // 工具链工作区、以及未绑定工作区的默认环境：设备级工具（run_shell/run_cli/工具链增强等）就是它的工作方式，不过滤
        // Rootfs / SSH 工作区：设备级工具一律不暴露，命令统一走工作区专属 workspace_shell
        val usesDeviceTools = boundWorkspace == null || boundWorkspace.workspaceType() == WorkspaceType.TOOLCHAIN
        val hideDeviceTools = boundWorkspace != null && !usesDeviceTools
        val effectiveLocalTools = if (hideDeviceTools) {
            assistant.localTools.filterNot { it in DEVICE_LEVEL_LOCAL_TOOLS }
        } else {
            assistant.localTools
        }
        // 工具链工作区（含未绑定工作区的默认环境）严禁使用 Rootfs 工作区工具：
        // 它没有 /workspace、/skills、/upload 挂载，文件操作走 manage_files 与 run_shell（宿主机绝对路径）
        val workspaceTools = if (usesDeviceTools) {
            emptyList()
        } else {
            createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), workspaceCwd)
        }
        // 设备级工具的默认工作目录：
        // - 绑定工具链工作区 → 该工作区的 files 目录，并跟随会话里选的子目录（workspaceCwd 存的是绝对路径）；
        // - 未绑定 → 留空，由 LocalTools 回退到临时工作区 default/files。
        // 不能写死在构造期常量里，否则切换绑定工作区后不会跟随。
        val deviceWorkingDir = if (boundWorkspace?.workspaceType() == WorkspaceType.TOOLCHAIN) {
            val dir = workspaceRepository.workspaceFilesDir(boundWorkspace.id, WorkspaceType.TOOLCHAIN)
            // 会话里选过的子目录可能已经不存在（用户删了、工作区被重建），此时回退到工作区根目录，
            // 否则 shell 的 cd 会失败、文件工具会全部报错。
            // 必须按「目录 + /」比较：只 startsWith(dir) 会把 files-other/x 误判成 files 下的子目录。
            val base = dir.trimEnd('/')
            workspaceCwd
                ?.takeIf { it.isNotBlank() && it != base && it.startsWith("$base/") && java.io.File(it).isDirectory }
                ?: base
        } else null
        val tools = buildList {
            if (assistant.enableMemory) {
                addAll(buildMemoryTools(memoryRepository, assistant.memoryId))
            }
            if (shouldUseExternalWebSearch(assistant, model)) {
                addAll(createSearchTools(settings))
            }
            addAll(
                localTools.getTools(
                    options = effectiveLocalTools,
                    deviceShellApproval = assistant.deviceShellApproval,
                    workingDir = deviceWorkingDir,
                )
            )
            if (assistant.enableRecentChatsReference) {
                addAll(createConversationTools(conversationRepository, assistant.id))
            }
            addAll(workspaceTools)
            if (assistant.enabledSkills.isNotEmpty()) {
                addAll(
                    createSkillTools(
                        enabledSkills = assistant.enabledSkills,
                        allSkills = skillManager.listSkills(),
                    )
                )
            }

            val mcpTools = mcpManager.getAllAvailableTools(assistant)
            val invalidNames = mcpTools
                .map { it.second }
                .distinct()
                .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
            if (invalidNames.isNotEmpty()) {
                throw InvalidMcpServerNamesException(invalidNames)
            }
            mcpTools.forEach { (serverId, serverName, tool) ->
                add(
                    Tool(
                        name = "mcp__${serverName}__${tool.name}",
                        description = tool.description ?: "",
                        parameters = { tool.inputSchema },
                        needsApproval = { tool.needsApproval },
                        execute = { mcpManager.callTool(serverId, tool.name, it.jsonObject) },
                    )
                )
            }
        }
        // 过长的输出存成文件让模型按需读取；只有 Rootfs 工作区把 /tool_outputs 挂进了容器，才读得到。
        // 设备级工具环境（未绑定 / 工具链工作区）与远程 SSH 工作区都没有这个挂载，
        // 截断后模型读不到完整内容，所以原样返回（run_shell / run_cli 自身已限制输出长度）。
        val canReadToolOutputs = workspaceTools.isNotEmpty() &&
            assistant.workspaceId?.let { workspaceRepository.getById(it.toString()) }?.capabilities()?.rootfs == true
        return if (canReadToolOutputs) tools.map(::withOutputTruncation) else tools
    }

    private fun withOutputTruncation(tool: Tool): Tool {
        if (tool.name in TOOLS_WITHOUT_OUTPUT_TRUNCATION) return tool
        val execute = tool.execute
        return tool.copy(execute = { truncateOutput(execute(it)) })
    }

    private fun truncateOutput(output: List<UIMessagePart>): List<UIMessagePart> {
        val textParts = output.filterIsInstance<UIMessagePart.Text>()
        val totalChars = textParts.sumOf { it.text.length }
        if (totalChars <= MAX_TOOL_OUTPUT_CHARS) return output

        val fileName = "${Uuid.random()}.txt"
        Log.i(TAG, "truncateOutput: saving $totalChars chars to $fileName")
        val fullText = textParts.joinToString("\n") { it.text }
        val outputDir = File(context.filesDir, FileFolders.TOOL_OUTPUTS).apply { mkdirs() }
        File(outputDir, fileName).writeText(fullText)

        return listOf(
            UIMessagePart.Text(
                buildString {
                    appendLine("[Tool output truncated: $totalChars characters total]")
                    appendLine("Full output saved to: /tool_outputs/$fileName")
                    appendLine("Use shell to read: `cat /tool_outputs/$fileName`")
                    appendLine("Use shell to search: `grep \"pattern\" /tool_outputs/$fileName`")
                    appendLine()
                    append(fullText.take(TOOL_OUTPUT_PREVIEW_CHARS))
                }
            )
        ) + output.filter { it !is UIMessagePart.Text }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String?): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }
}
