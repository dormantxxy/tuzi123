package me.rerere.rikkahub.data.localtools

import android.content.Context

/**
 * 原生工具链执行器：把已装扩展聚合为一体化环境，并经由 root/shell 域直跑。
 * 仅在 root（或 Shizuku）通道下工作；不再使用普通 app 权限直跑（W^X 限制）。
 */
class ToolchainExecutor(
    private val context: Context,
    private val shellExecutor: ShellExecutor,
    private val installer: ExtensionInstaller,
) {
    fun hasPrivilege(): Boolean = shellExecutor.isRootAvailable() || shellExecutor.isShizukuGranted()
    fun hasToolchains(): Boolean = installer.installed.value.isNotEmpty()

    fun currentEnv(projectRoot: String? = null): ToolchainEnv =
        ToolchainEnvironment.build(context, installer, projectRoot)

    suspend fun run(
        command: String,
        cwd: String? = null,
        timeoutMillis: Long = 60_000,
    ): ShellResult {
        val env = currentEnv(projectRoot = cwd)
        return shellExecutor.executeToolchain(command, cwd, timeoutMillis, env)
    }
}
