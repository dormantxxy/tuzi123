package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.localtools.ExtensionInstaller
import me.rerere.rikkahub.data.localtools.ShellExecutor
import me.rerere.rikkahub.data.localtools.ToolchainExecutor
import me.rerere.tts.provider.TTSManager

/** 未绑定工作区时临时工作区的目录名（workspaces/<root>/files）。 */
private const val TEMP_WORKSPACE_ROOT = "default"

/** 提示词里值得告知模型的常用命令（顺序即展示顺序）。 */
private val NOTABLE_COMMANDS = listOf(
    "git", "curl", "wget", "rg", "jq", "less",
    "python", "python3", "pip", "node", "npm", "npx",
    "sqlite3", "ssh", "scp", "sftp", "ssh-keygen", "openssl",
    "gcc", "clang", "make", "cmake", "go", "cargo", "rustc",
    "java", "javac", "php", "zstd", "unzstd", "tar", "xz", "bzip2",
)


class LocalTools(
    private val context: Context,
    private val eventBus: AppEventBus,
    private val ttsManager: TTSManager,
    private val settingsStore: SettingsStore,
    private val shellExecutor: ShellExecutor,
    private val toolchainExecutor: ToolchainExecutor,
    private val extensionInstaller: ExtensionInstaller,
) {
    /**
     * 未绑定工作区时 AI 的默认工作目录：
     * 有 root/Shizuku 且已授予「所有文件访问」→ /storage/emulated/0/RikkaHub/workspaces/<临时工作区>/files
     * （布局与工具链工作区一致，用户可见、可自行清理）；
     * 没有高级权限时用 /storage/emulated/0/RikkaHub/tmp；
     * 都没授权时回退到 App 私有 files/RikkaHub。
     */
    private fun resolveFallbackWorkingDir(): String {
        val canUsePublic = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)
        val publicRoot = java.io.File("/storage/emulated/0/RikkaHub")
        val candidates = buildList {
            if (canUsePublic && toolchainExecutor.hasPrivilege()) {
                // 临时工作区：与工具链工作区同一套目录约定 workspaces/<root>/files
                add(java.io.File(publicRoot, "workspaces/$TEMP_WORKSPACE_ROOT/files"))
            }
            if (canUsePublic) add(java.io.File(publicRoot, "tmp"))
            add(java.io.File(context.filesDir, "RikkaHub"))
        }
        // 授权了但目录不可写时继续往后回退，避免 AI 工具全部失败
        return candidates.firstOrNull { dir ->
            runCatching {
                dir.mkdirs()
                val probe = java.io.File(dir, ".probe_" + System.nanoTime())
                probe.writeText("x")
                probe.delete()
                true
            }.getOrDefault(false)
        }?.absolutePath ?: java.io.File(context.filesDir, "RikkaHub").apply { mkdirs() }.absolutePath
    }

    /** 上一次算出的目录。每次使用前都要复核，用户可能删掉目录或撤销授权。 */
    @Volatile
    private var cachedWorkDir: String? = null

    private val rikkahubWorkDir: String
        get() {
            cachedWorkDir?.let { cached ->
                val stillUsable = runCatching {
                    java.io.File(cached).let { it.isDirectory && it.canWrite() }
                }.getOrDefault(false)
                if (stillUsable) return cached
            }
            return resolveFallbackWorkingDir().also { cachedWorkDir = it }
        }

    val javascriptTool by lazy { buildJavascriptTool() }
    val timeTool by lazy { buildTimeInfoTool() }
    val clipboardTool by lazy { buildClipboardTool(context) }
    val ttsTool by lazy { buildTextToSpeechTool(eventBus, ttsManager, settingsStore) }
    val askUserTool by lazy { buildAskUserTool() }
    val screenTimeTool by lazy { buildScreenTimeTool(context, eventBus) }
    val calendarQueryTool by lazy { buildCalendarQueryTool(context) }
    val calendarCreateTool by lazy { buildCalendarCreateTool(context) }
    val chartDisplayTool by lazy { buildChartDisplayTool() }

    // ===== 增强工具 =====
    val pkgSearchTool by lazy { buildPkgSearchTool(context) }
    val pkgInstallTool by lazy { buildPkgInstallTool(context, extensionInstaller) }
    val tapTool by lazy { buildTapTool(shellExecutor) }
    val swipeTool by lazy { buildSwipeTool(shellExecutor) }
    val inputTextTool by lazy { buildInputTextTool(shellExecutor) }
    val uiDumpTool by lazy { buildUiDumpTool(shellExecutor) }
    val screenshotTool by lazy { buildScreenshotTool(context, shellExecutor) }
    val logcatTool by lazy { buildLogcatTool(shellExecutor) }
    val appManagerTool by lazy { buildAppManagerTool(shellExecutor) }
    val webFetchTool by lazy { buildWebFetchTool() }

    /** 未绑定工作区时设备级工具实际使用的目录（系统提示词需要告知模型）。 */
    fun fallbackWorkingDir(): String = rikkahubWorkDir

    /** 设备级工具是否有 root/Shizuku：提示词要按这个决定说哪些工具可用。 */
    fun hasDevicePrivilege(): Boolean = toolchainExecutor.hasPrivilege()

    /**
     * 当前已安装扩展提供的主要命令名，用于提示词里如实描述可用工具。
     *
     * 直接列出每个扩展 bin/ 下的所有文件会得到上百个名字（`kadmin`、`kpropd`、`krb5kdc`
     * 这类库附带的运维工具混在里面），既挤占 token 又会把 `sqlite3`、`ssh`、`wget`
     * 这些真正常用的命令挤出提示词。这里只保留常见命令。
     */
    fun installedCommandNames(): List<String> = runCatching {
        val available = extensionInstaller.installed.value.values
            .flatMap { rec -> java.io.File(rec.payloadDir, "bin").listFiles()?.map { it.name } ?: emptyList() }
            .toSet()
        NOTABLE_COMMANDS.filter { it in available }
    }.getOrDefault(emptyList())

    fun getTools(
        options: List<LocalToolOption>,
        /** 设备 Shell 是否需要审批（来自工作区工具审批设置，默认需要）。 */
        deviceShellApproval: Boolean = true,
        /**
         * 实际生效的工作目录。
         * 绑定了工具链工作区时是 workspaces/<工作区id>/files（含会话里选的子目录）；
         * 未绑定时由 [rikkahubWorkDir] 决定。
         * 不能写死成构造期常量：同一进程内切换绑定工作区后必须立刻跟随。
         */
        workingDir: String? = null,
    ): List<Tool> {
        val dir = workingDir?.takeIf { it.isNotBlank() } ?: rikkahubWorkDir
        // 目录在首次使用时可能不存在（例如新绑定工作区、临时工作区被用户删除），这里补建
        runCatching { java.io.File(dir).mkdirs() }
        // 权限状态只取一次：isShizukuGranted 走 Binder，重复调用既浪费，也可能让同一次请求里
        // 前几个工具暴露、后几个不暴露（授权状态刚好在此期间变化），工具集自相矛盾。
        val privileged = toolchainExecutor.hasPrivilege()
        val tools = mutableListOf<Tool>()
        if (options.contains(LocalToolOption.JavascriptEngine)) tools.add(javascriptTool)
        if (options.contains(LocalToolOption.TimeInfo)) tools.add(timeTool)
        if (options.contains(LocalToolOption.Clipboard)) tools.add(clipboardTool)
        if (options.contains(LocalToolOption.Tts)) tools.add(ttsTool)
        if (options.contains(LocalToolOption.AskUser)) tools.add(askUserTool)
        if (options.contains(LocalToolOption.ScreenTime)) tools.add(screenTimeTool)
        if (options.contains(LocalToolOption.Calendar)) {
            tools.add(calendarQueryTool)
            tools.add(calendarCreateTool)
        }
        if (options.contains(LocalToolOption.ChartDisplay)) tools.add(chartDisplayTool)
        // run_shell（设备命令）依赖 root/Shizuku：无权限时不暴露
        if (options.contains(LocalToolOption.Shell) && privileged) {
            val shellTool = buildRunShellTool(shellExecutor, dir, toolchainExecutor, extensionInstaller)
            // 设备 Shell 审批开关：关闭时完全不拦截（包括风险命令）；开启时仅风险命令需要审批
            tools.add(
                if (deviceShellApproval) {
                    shellTool
                } else {
                    shellTool.copy(needsApproval = { false })
                }
            )
        }
        if (options.contains(LocalToolOption.Files)) tools.add(buildManageFilesTool(shellExecutor, dir))
        if (options.contains(LocalToolOption.SearchCode)) tools.add(buildSearchCodeTool(shellExecutor, dir))
        if (options.contains(LocalToolOption.FileTree)) tools.add(buildFileTreeTool(shellExecutor, dir))
        if (options.contains(LocalToolOption.WebFetch)) tools.add(webFetchTool)
        // run_cli 依赖 root/Shizuku：读 App 启动时缓存的权限状态（轻量，不启动 su）
        if (options.contains(LocalToolOption.Cli) && privileged) {
            tools.add(buildRunCliTool(toolchainExecutor, dir))
        }
        // 工具链增强（env_info/pkg_search/pkg_install）依赖 root/Shizuku：无权限时不暴露
        if (options.contains(LocalToolOption.Toolchain) && privileged) {
            tools.add(buildEnvInfoTool(context, toolchainExecutor, extensionInstaller, dir))
            tools.add(pkgSearchTool)
            tools.add(pkgInstallTool)
        }
        // UI 自动化 / 截屏 / logcat 依赖 root 或 Shizuku：无权限时不暴露给 AI
        if (options.contains(LocalToolOption.UiAutomation) && privileged) {
            tools.add(tapTool)
            tools.add(swipeTool)
            tools.add(inputTextTool)
            tools.add(uiDumpTool)
        }
        if (options.contains(LocalToolOption.Screenshot) && privileged) tools.add(screenshotTool)
        if (options.contains(LocalToolOption.Logcat) && privileged) tools.add(logcatTool)
        if (options.contains(LocalToolOption.AppManager) && privileged) tools.add(appManagerTool)
        return tools
    }
}
