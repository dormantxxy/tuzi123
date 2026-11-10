package me.rerere.rikkahub.data.localtools

import kotlinx.coroutines.runBlocking
import me.rerere.workspace.WorkspaceCommandResult
import me.rerere.workspace.WorkspaceShellContext
import me.rerere.workspace.WorkspaceShellRunner

/**
 * 工具链工作区的命令执行器：走聚合后的扩展环境（PATH / LD_LIBRARY_PATH / CA 等），
 * 由 root 或 Shizuku 通道执行，不需要安装 Rootfs。
 */
class ToolchainWorkspaceShellRunner(
    private val toolchainExecutor: ToolchainExecutor,
) : WorkspaceShellRunner {

    override fun execute(context: WorkspaceShellContext): WorkspaceCommandResult {
        val result = runBlocking {
            toolchainExecutor.run(
                command = context.command,
                cwd = context.workingDir.absolutePath,
                timeoutMillis = context.timeoutMillis,
            )
        }
        return WorkspaceCommandResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            timedOut = result.timedOut,
        )
    }
}
