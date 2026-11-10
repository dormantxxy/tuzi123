package me.rerere.rikkahub.ui.pages.assistant.detail

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ui.toast.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Calendar03
import me.rerere.hugeicons.stroke.ChartLineData01
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.Package
import me.rerere.hugeicons.stroke.ClipboardPaste
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.JavaScript
import me.rerere.hugeicons.stroke.MessageQuestion
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.data.localtools.ToolchainExecutor
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.components.ui.permission.PermissionInfo
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.hasUsageStatsPermission
import me.rerere.rikkahub.utils.openUsageAccessSettings
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun AssistantLocalToolPage(id: String) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    Text(stringResource(R.string.assistant_page_tab_local_tools))
                },
                navigationIcon = {
                    BackButton()
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        AssistantLocalToolContent(
            innerPadding = innerPadding,
            assistant = assistant,
            onUpdate = { vm.update(it) }
        )
    }
}

@Composable
private fun AssistantLocalToolContent(
    innerPadding: PaddingValues,
    assistant: Assistant,
    onUpdate: (Assistant) -> Unit
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val toolchainExecutor: ToolchainExecutor = koinInject()
    val shizukuGranted by me.rerere.rikkahub.data.localtools.ShizukuHelper.permissionGranted.collectAsStateWithLifecycle()
    // 只在进入本页面时检测一次权限（不是每次 AI 请求都检测）
    val hasPrivilege by produceState(initialValue = true, shizukuGranted) {
        value = withContext(Dispatchers.IO) { toolchainExecutor.hasPrivilege() }
    }
    // 进入页面时根据权限同步需要 root/Shizuku 的工具：有权限自动开启 Cli，无权限全部移除（AI 不会发现）
    val privilegedTools = setOf(
        LocalToolOption.Cli,
        LocalToolOption.UiAutomation,
        LocalToolOption.Screenshot,
        LocalToolOption.Logcat,
        LocalToolOption.AppManager,
    )
    androidx.compose.runtime.LaunchedEffect(hasPrivilege) {
        // 只做「无权限时剔除需要 root/Shizuku 的工具」。
        // 不能在「有权限」时自动补回 Cli：那样用户手动关掉后每次进页面都会被重新打开，
        // 等于关不掉。新助手默认就带 Cli，不需要自动补。
        if (!hasPrivilege) {
            val updated = assistant.localTools.filterNot { it in privilegedTools }
            if (updated != assistant.localTools) {
                onUpdate(assistant.copy(localTools = updated))
            }
        }
    }
    val permissionRequiredText =
        stringResource(R.string.assistant_page_local_tools_screen_time_permission_required)

    val calendarPermissionState = rememberPermissionState(
        permissions = setOf(
            PermissionInfo(
                permission = Manifest.permission.READ_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_read)) },
                usage = { Text(stringResource(R.string.permission_calendar_read_desc)) },
                required = true
            ),
            PermissionInfo(
                permission = Manifest.permission.WRITE_CALENDAR,
                displayName = { Text(stringResource(R.string.permission_calendar_write)) },
                usage = { Text(stringResource(R.string.permission_calendar_write_desc)) },
                required = true
            ),
        )
    )
    PermissionManager(permissionState = calendarPermissionState)

    fun toggleLocalTool(option: LocalToolOption, enabled: Boolean) {
        if (enabled && option == LocalToolOption.ScreenTime && !context.hasUsageStatsPermission()) {
            toaster.show(message = permissionRequiredText, type = ToastType.Warning)
            context.openUsageAccessSettings()
        }
        if (enabled && option == LocalToolOption.Calendar && !calendarPermissionState.allPermissionsGranted) {
            calendarPermissionState.requestPermissions()
            return
        }
        val newLocalTools = if (enabled) {
            assistant.localTools + option
        } else {
            assistant.localTools - option
        }
        onUpdate(assistant.copy(localTools = newLocalTools))
    }

    fun CardGroupScope.toolItem(
        option: LocalToolOption,
        icon: ImageVector,
        @StringRes title: Int,
        @StringRes description: Int,
        enabled: Boolean = true,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            enabled = enabled,
            // 图标底色跟着开关状态变化
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                    contentColor = if (checked) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            supportingContent = { Text(stringResource(description)) },
            headlineContent = { Text(stringResource(title)) },
        )
    }

    fun CardGroupScope.toolItemRaw(
        option: LocalToolOption,
        icon: ImageVector,
        title: String,
        description: String,
        enabled: Boolean = true,
    ) {
        val checked = assistant.localTools.contains(option)
        switchItem(
            checked = checked,
            onCheckedChange = { toggleLocalTool(option, it) },
            enabled = enabled,
            leadingContent = {
                CardGroupIcon(
                    icon = icon,
                    containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            supportingContent = { Text(description) },
            headlineContent = { Text(title) },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(innerPadding)
            .padding(bottom = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CardGroup {
            toolItem(
                option = LocalToolOption.JavascriptEngine,
                icon = HugeIcons.JavaScript,
                title = R.string.assistant_page_local_tools_javascript_engine_title,
                description = R.string.assistant_page_local_tools_javascript_engine_desc,
            )
            toolItem(
                option = LocalToolOption.TimeInfo,
                icon = HugeIcons.Clock01,
                title = R.string.assistant_page_local_tools_time_info_title,
                description = R.string.assistant_page_local_tools_time_info_desc,
            )
            toolItem(
                option = LocalToolOption.Clipboard,
                icon = HugeIcons.ClipboardPaste,
                title = R.string.assistant_page_local_tools_clipboard_title,
                description = R.string.assistant_page_local_tools_clipboard_desc,
            )
            toolItem(
                option = LocalToolOption.Tts,
                icon = HugeIcons.VolumeHigh,
                title = R.string.assistant_page_local_tools_tts_title,
                description = R.string.assistant_page_local_tools_tts_desc,
            )
            toolItem(
                option = LocalToolOption.AskUser,
                icon = HugeIcons.MessageQuestion,
                title = R.string.assistant_page_local_tools_ask_user_title,
                description = R.string.assistant_page_local_tools_ask_user_desc,
            )
            toolItem(
                option = LocalToolOption.ScreenTime,
                icon = HugeIcons.SmartPhone01,
                title = R.string.assistant_page_local_tools_screen_time_title,
                description = R.string.assistant_page_local_tools_screen_time_desc,
            )
            toolItem(
                option = LocalToolOption.Calendar,
                icon = HugeIcons.Calendar03,
                title = R.string.assistant_page_local_tools_calendar_title,
                description = R.string.assistant_page_local_tools_calendar_desc,
            )
            toolItem(
                option = LocalToolOption.ChartDisplay,
                icon = HugeIcons.ChartLineData01,
                title = R.string.assistant_page_local_tools_chart_display_title,
                description = R.string.assistant_page_local_tools_chart_display_desc,
            )
            toolItemRaw(option = LocalToolOption.Shell, icon = HugeIcons.ComputerTerminal01, title = "设备命令", description = "在设备上执行系统命令")
            toolItemRaw(
                option = LocalToolOption.Cli,
                icon = HugeIcons.ComputerTerminal01,
                title = "命令行工具链",
                description = if (hasPrivilege) "在工具链环境中执行命令"
                else "需要高级权限（未授权时对助手不可见）",
                enabled = hasPrivilege,
            )
            toolItemRaw(option = LocalToolOption.Files, icon = HugeIcons.Package, title = "文件管理", description = "浏览/读写/复制/移动/删除/搜索文件")
            toolItemRaw(option = LocalToolOption.SearchCode, icon = HugeIcons.Code, title = "代码检索", description = "按关键字搜索文件内容")
            toolItemRaw(option = LocalToolOption.FileTree, icon = HugeIcons.FolderOpen, title = "文件树", description = "列出目录结构")
            toolItemRaw(option = LocalToolOption.WebFetch, icon = HugeIcons.Earth, title = "网页浏览", description = "抓取网页正文")
            toolItemRaw(option = LocalToolOption.Toolchain, icon = HugeIcons.ComputerTerminal01, title = "工具链增强", description = "查看环境信息 / 搜索并安装扩展包")
            toolItemRaw(option = LocalToolOption.UiAutomation, icon = HugeIcons.SmartPhone01, title = "界面自动化", description = if (hasPrivilege) "点击 / 滑动 / 输入文本 / 查看控件树" else "需要高级权限", enabled = hasPrivilege)
            toolItemRaw(option = LocalToolOption.Screenshot, icon = HugeIcons.SmartPhone01, title = "截屏", description = if (hasPrivilege) "截取屏幕并保存" else "需要高级权限", enabled = hasPrivilege)
            toolItemRaw(option = LocalToolOption.Logcat, icon = HugeIcons.Code, title = "日志读取", description = if (hasPrivilege) "读取系统与应用日志" else "需要高级权限", enabled = hasPrivilege)
            toolItemRaw(option = LocalToolOption.AppManager, icon = HugeIcons.SmartPhone01, title = "应用管理", description = if (hasPrivilege) "列出 / 启动 / 停止应用，提取 APK" else "需要高级权限", enabled = hasPrivilege)
            // 设备命令审批：与工具列表同风格，默认关闭
            switchItem(
                checked = assistant.deviceShellApproval,
                onCheckedChange = { onUpdate(assistant.copy(deviceShellApproval = it)) },
                leadingContent = { CardGroupIcon(HugeIcons.ComputerTerminal01) },
                supportingContent = { Text("执行命令前是否需要确认（默认关闭）") },
                headlineContent = { Text("设备命令审批") },
            )
        }
    }
}
