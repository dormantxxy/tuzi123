package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()

    @Serializable
    @SerialName("shell")
    data object Shell : LocalToolOption()

    @Serializable
    @SerialName("files")
    data object Files : LocalToolOption()

    @Serializable
    @SerialName("web_fetch")
    data object WebFetch : LocalToolOption()

    @Serializable
    @SerialName("search_code")
    data object SearchCode : LocalToolOption()

    @Serializable
    @SerialName("file_tree")
    data object FileTree : LocalToolOption()

    /** 原生工具链 CLI（run_cli）：在聚合后的多扩展环境里执行命令。 */
    @Serializable
    @SerialName("cli")
    data object Cli : LocalToolOption()

    /** 工具链增强：env_info / pkg_search / pkg_install。 */
    @Serializable
    @SerialName("toolchain")
    data object Toolchain : LocalToolOption()

    /** UI 自动化：tap / swipe / input_text / ui_dump（需 root 或 Shizuku）。 */
    @Serializable
    @SerialName("ui_automation")
    data object UiAutomation : LocalToolOption()

    /** 截屏（需 root 或 Shizuku）。 */
    @Serializable
    @SerialName("screenshot")
    data object Screenshot : LocalToolOption()

    /** 日志读取（logcat，需 root 或 Shizuku）。 */
    @Serializable
    @SerialName("logcat")
    data object Logcat : LocalToolOption()

    /** 应用管理：列出/启动/停止/提取 APK（需 root 或 Shizuku）。 */
    @Serializable
    @SerialName("app_manager")
    data object AppManager : LocalToolOption()
}
