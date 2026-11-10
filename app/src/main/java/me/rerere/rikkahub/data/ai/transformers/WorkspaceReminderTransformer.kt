package me.rerere.rikkahub.data.ai.transformers

import android.util.Log
import kotlinx.coroutines.CancellationException
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceLayout
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceShellStatus
import java.io.ByteArrayOutputStream
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace 系统提示注入转换器
 *
 * 当助手绑定了一个 shell 已就绪的 workspace 时, 在系统提示词中追加一段引导,
 * 让模型了解 workspace 环境与 workspace_* 工具的使用方式。
 * 提示中包含实际使用的 shell 与发行版, 避免模型默认按 bash / apt 来写命令。
 * 本地工作区同时读取 ~/.agents、/workspace 和会话当前目录中的 AGENTS.md 作为工作区指令。
 * 远程工作区不读取 AGENTS.md, shell 与发行版读到一次后缓存 (见 [RemoteEnvironmentCache]),
 * 连不上时只注入一段说明, 不阻塞对话。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
    private val localTools: me.rerere.rikkahub.data.ai.tools.local.LocalTools,
) : InputMessageTransformer {
    private val remoteEnvironments = RemoteEnvironmentCache()

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString()
        // 未绑定工作区：设备级工具仍在工作，但没有 Rootfs。
        // 必须告诉模型实际的默认目录，否则它会以为目录是 App 私有路径或工作区路径。
        if (workspaceId.isNullOrBlank()) {
            return messages.withPrompt(buildStandalonePrompt())
        }
        val workspace = workspaceRepository.getById(workspaceId) ?: return messages
        // 与 ChatToolFactory.createWorkspaceToolsIfReady 保持一致: 仅在 shell 就绪时注入
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) return messages
        val layout = workspaceRepository.layout(workspaceId) ?: return messages

        val prompt = if (workspace.workspaceType() == me.rerere.workspace.WorkspaceType.TOOLCHAIN) {
            buildToolchainWorkspacePrompt(
                workspace = workspace,
                layout = layout,
                cwd = ctx.workspaceCwd,
                commands = localTools.installedCommandNames(),
                hasPrivilege = localTools.hasDevicePrivilege(),
            )
        } else if (workspace.isSsh()) {
            val environment = remoteEnvironments.get(workspaceId, workspace.sshConfig) {
                // shell 探测需要连接, 失败时不再读发行版, 以免再等一次超时
                RemoteEnvironment(shell = workspaceRepository.shell(workspaceId), distro = readDistroName(workspaceId))
            }.getOrElse { e ->
                Log.w("WorkspaceReminder", "Workspace unreachable: $workspaceId", e)
                return messages.withPrompt(buildUnreachablePrompt(workspace, layout, e))
            }
            buildSshWorkspacePrompt(workspace, layout, environment.shell, environment.distro, ctx.workspaceCwd)
        } else {
            buildWorkspacePrompt(
                workspace = workspace,
                shell = workspaceRepository.shell(workspaceId),
                distro = readDistroName(workspaceId),
                cwd = ctx.workspaceCwd,
            ) + buildAgentsPrompt(workspaceId, ctx.workspaceCwd)
        }
        return messages.withPrompt(prompt)
    }

    // 追加到第一条 system 消息; 若不存在则插入一条
    private fun List<UIMessage>.withPrompt(prompt: String): List<UIMessage> {
        val systemIndex = indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + this
        }
    }

    private suspend fun buildAgentsPrompt(workspaceId: String, cwd: String?): String {
        // ProotShellRunner 将 HOME 固定为 /root；相对 PWD 按 /workspace 解析。
        val workingDirectory = Paths.get(WorkspaceManager.ROOTFS_WORKSPACE_DIR)
            .resolve(cwd?.takeIf { it.isNotBlank() } ?: ".")
            .normalize()
        val paths = linkedSetOf(
            "${WorkspaceManager.ROOTFS_HOME_DIR}/.agents/AGENTS.md",
            "${WorkspaceManager.ROOTFS_WORKSPACE_DIR}/AGENTS.md",
            workingDirectory.resolve("AGENTS.md").toString(),
        )
        val instructions = paths.mapNotNull { path ->
            readRootfsText(workspaceId, path, MAX_AGENTS_BYTES)?.let { path to it }
        }
        if (instructions.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine()
            appendLine("<workspace_instructions>")
            appendLine("Follow the AGENTS.md instructions below.")
            instructions.forEach { (path, content) ->
                appendLine()
                appendLine("AGENTS.md source: $path")
                appendLine(content)
            }
            append("</workspace_instructions>")
        }
    }

    private suspend fun readDistroName(workspaceId: String): String? =
        OS_RELEASE_PATHS.firstNotNullOfOrNull { path ->
            readRootfsText(workspaceId, path, MAX_OS_RELEASE_BYTES)?.let(::parseOsReleaseName)
        }

    // 文件不存在、过大或不可读时返回 null, 不影响提示词的其余部分
    private suspend fun readRootfsText(workspaceId: String, path: String, maxBytes: Long): String? =
        try {
            val size = workspaceRepository.absoluteFileSize(workspaceId, path)
            require(size <= maxBytes) { "$path exceeds $maxBytes bytes" }
            val content = ByteArrayOutputStream().use { output ->
                workspaceRepository.exportAbsoluteFile(workspaceId, path, output)
                output.toString(Charsets.UTF_8.name())
            }
            content.takeIf { it.isNotBlank() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("WorkspaceReminder", "Skipping workspace file: $path", e)
            null
        }


    /** 未绑定工作区时的提示：设备级工具在宿主机上直接工作，没有 Rootfs 挂载。 */
    private fun buildStandalonePrompt(): String = buildString {
        val dir = localTools.fallbackWorkingDir()
        val hasPrivilege = localTools.hasDevicePrivilege()
        appendLine("<workspace>")
        appendLine("No workspace is bound. You are working directly on the Android host, not inside a proot rootfs.")
        appendLine("- Default directory is \"" + dir + "\". Use absolute host paths. This is a temporary area; the user can see and clean it.")
        appendLine("- There is no /workspace, /skills, /upload or /builtin_skills mount here.")
        if (hasPrivilege) {
            val commands = localTools.installedCommandNames()
            if (commands.isNotEmpty()) {
                appendLine("- Commands provided by installed extensions (run them with run_cli, which uses the aggregated PATH/LD_LIBRARY_PATH): " + commands.take(60).joinToString(", ") + ".")
            }
            appendLine("- Available tools: run_shell (system commands: pm, am, dumpsys, ls, cat), run_cli (extension binaries), manage_files, search_code, list_file_tree, web_fetch, env_info (环境信息), pkg_search / pkg_install (扩展商店).")
        } else {
            // 无 root/Shizuku 时 run_shell / run_cli / env_info / pkg_* 都不会暴露，必须如实说明
            appendLine("- This device currently has NO root or Shizuku permission, so command execution tools are unavailable: there is no run_shell, run_cli, env_info, pkg_search or pkg_install.")
            appendLine("- Available tools: manage_files, search_code, list_file_tree, web_fetch. Do file operations with manage_files only, and tell the user root or Shizuku is required for commands.")
        }
        append("</workspace>")
    }
    private companion object {
        const val MAX_AGENTS_BYTES = 64L * 1024
        const val MAX_OS_RELEASE_BYTES = 16L * 1024
        val OS_RELEASE_PATHS = listOf("/etc/os-release", "/usr/lib/os-release")
    }
}

/** 远程工作区提示词里描述的执行环境 */
internal data class RemoteEnvironment(val shell: String, val distro: String?)

/**
 * 按工作区缓存远程执行环境。
 *
 * 读一次要好几次网络往返, 而每一步请求都要构建提示词; 远程环境很少变化, 读到后就一直沿用, 直到连接配置改变。
 * 连不上的结果只保留 [failureTtlMillis]: 期间的后续步骤不必每步都等连接超时, 过期后再重新尝试。
 */
internal class RemoteEnvironmentCache(
    private val failureTtlMillis: Long = DEFAULT_FAILURE_TTL_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(val config: String?, val result: Result<RemoteEnvironment>, val loadedAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun get(
        workspaceId: String,
        config: String?,
        load: suspend () -> RemoteEnvironment,
    ): Result<RemoteEnvironment> {
        entries[workspaceId]
            ?.takeIf { it.config == config && (it.result.isSuccess || now() - it.loadedAt < failureTtlMillis) }
            ?.let { return it.result }
        val result = try {
            Result.success(load())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        entries[workspaceId] = Entry(config, result, now())
        return result
    }

    private companion object {
        const val DEFAULT_FAILURE_TTL_MS = 60_000L
    }
}

private const val MAX_DISTRO_NAME_LENGTH = 80

/** 从 os-release 内容中取发行版名称, 优先 PRETTY_NAME, 其次 NAME + VERSION_ID */
internal fun parseOsReleaseName(content: String): String? {
    val values = content.lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("#") && it.indexOf('=') > 0 }
        .associate { line ->
            line.substringBefore('=') to line.substringAfter('=').trim().removeSurrounding("\"").removeSurrounding("'")
        }
    val name = values["PRETTY_NAME"]?.takeIf { it.isNotBlank() }
        ?: listOfNotNull(values["NAME"], values["VERSION_ID"]).joinToString(" ")
    // 内容来自 Rootfs 内的文件, 拼进系统提示前去掉控制字符并限制长度
    return name.filterNot { it.isISOControl() }.trim().take(MAX_DISTRO_NAME_LENGTH).ifBlank { null }
}


/** 工具链工作区提示词：命令在宿主机（Android）的聚合扩展环境中执行，没有 PRoot 与 /workspace 挂载。 */
private fun buildToolchainWorkspacePrompt(
    workspace: WorkspaceEntity,
    layout: WorkspaceLayout,
    cwd: String? = null,
    commands: List<String> = emptyList(),
    hasPrivilege: Boolean = true,
): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a workspace named \"" + workspace.name + "\" on the Android host itself, not inside a proot rootfs.")
    appendLine("- The workspace files directory on the host is \"" + layout.workspaceDir + "\". Use absolute host paths for all workspace tools.")
    if (hasPrivilege) {
        val commandHint = if (commands.isEmpty()) "no extensions installed yet" else commands.take(60).joinToString(", ")
        appendLine("- run_shell / run_cli runs commands through the app aggregated toolchain environment (extension commands: " + commandHint + ") with root or Shizuku privileges.")
        appendLine("- Available tools: manage_files (host file operations), search_code, list_file_tree, web_fetch, run_shell / run_cli (aggregated extension PATH), env_info (环境信息), pkg_search / pkg_install (扩展商店).")
    } else {
        // 无 root/Shizuku 时 run_shell / run_cli / env_info 不会暴露；
        // 必须如实说明，否则模型会反复调用不存在的工具。
        appendLine("- This device currently has NO root or Shizuku permission, so command execution tools are unavailable: there is no run_shell, run_cli or env_info.")
        appendLine("- Available tools: manage_files (host file operations), search_code, list_file_tree, web_fetch.")
        appendLine("- You cannot run commands or execute extension binaries here. Tell the user that root or Shizuku is required for commands.")
    }
    appendLine("- There is no /workspace, /skills, /upload or /builtin_skills mount in this workspace type.")
    appendLine("- Do NOT use workspace_* tools here: there is no Rootfs, so workspace_read_file / workspace_write_file / workspace_edit_file / workspace_shell are unavailable.")
    appendLine("- Read and write files with manage_files using absolute host paths, e.g. \"" + layout.workspaceDir + "/notes.md\".")
    appendLine("- Prefer targeted edits over rewriting whole files.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: " + cwd + ".")
    }
    append("</workspace>")
}
private fun buildWorkspacePrompt(
    workspace: WorkspaceEntity,
    shell: String,
    distro: String?,
    cwd: String? = null,
): String = buildString {
    appendLine("<workspace>")
    appendLine(
        "You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment."
    )
    appendLine(
        "- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation."
    )
    appendLine(
        "- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`)."
    )
    if (distro != null) {
        appendLine(
            "- The Rootfs distribution is $distro. Use its native package manager when you need to install missing tools."
        )
    }
    appendShellLine(shell)
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine(
        "  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files."
    )
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine(
        "- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files."
    )
    appendLine(
        "- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions."
    )
    appendLine(
        "- Built-in skills shipped with the app are mounted at `/builtin_skills/<skill-name>/` with the same layout. Treat `/builtin_skills` as READ-ONLY: you may read files and run scripts there, but never modify, overwrite, or delete anything. A skill in `/skills` with the same name overrides the built-in one."
    )
    appendLine(
        "- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy."
    )
    if (!cwd.isNullOrBlank()) {
        appendLine(
            "- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands."
        )
    }
    append("</workspace>")
}

private fun buildSshWorkspacePrompt(
    workspace: WorkspaceEntity,
    layout: WorkspaceLayout,
    shell: String,
    distro: String?,
    cwd: String?,
): String = buildString {
    val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" } ?: "the remote host"
    appendLine("<workspace>")
    appendLine(
        "You have access to a persistent remote workspace named \"${workspace.name}\" on a real machine, reached over SSH as `$address`."
    )
    appendLine(
        "- The workspace directory is `${layout.workspaceDir}`. Use it as your working directory; files written there persist across turns of this conversation."
    )
    appendLine(
        "- All paths passed to workspace tools must be absolute paths on the remote machine (for example `${layout.workspaceDir}/notes.md`)."
    )
    appendLine(
        "- This is not a sandbox: commands run with the SSH user's real permissions and can affect other data and services on the machine. Avoid destructive or system-wide changes unless the user asks for them."
    )
    if (distro != null) {
        appendLine("- The remote operating system is $distro.")
    }
    appendShellLine(shell)
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_shell`: run shell commands on the remote machine (defaults to the workspace directory).")
    appendLine(
        "- There are no file write or edit tools in this workspace. Create and modify files with `workspace_shell`, for example a quoted heredoc (`cat > file <<'EOF'`) for new content, or `sed`, `patch` or a short script for targeted edits. Prefer targeted edits over rewriting whole files."
    )
    if (!cwd.isNullOrBlank()) {
        appendLine(
            "- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands."
        )
    }
    append("</workspace>")
}

private fun buildUnreachablePrompt(workspace: WorkspaceEntity, layout: WorkspaceLayout, error: Throwable): String =
    buildString {
        val address = workspace.sshSettings()?.let { "${it.username}@${it.host}" }
        appendLine("<workspace>")
        appendLine(
            "You are bound to a workspace named \"${workspace.name}\"" +
                (address?.let { " on `$it`" } ?: "") +
                " with directory `${layout.workspaceDir}`, but it is currently unreachable: ${error.message}"
        )
        appendLine(
            "- Workspace tools will fail until the connection is restored. If the user asks you to work in the workspace, tell them it is unreachable."
        )
        append("</workspace>")
    }

private fun StringBuilder.appendShellLine(shell: String) {
    if (shell.substringAfterLast('/') == "bash") {
        appendLine("- `workspace_shell` runs commands with `$shell`.")
    } else {
        appendLine(
            "- `workspace_shell` runs commands with `$shell`, a POSIX shell. Bash is not installed, so avoid bash-only syntax such as arrays, here-strings (`<<<`) and brace expansion."
        )
    }
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}