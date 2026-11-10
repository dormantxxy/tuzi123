package me.rerere.rikkahub.data.localtools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference

/**
 * 扩展商店下载路线。用户在商店里长按「安装」选择，全局持久生效；
 * 后续所有下载（含 pkg_install 工具、依赖包、索引刷新）都按它走，再次长按可改。
 */
object DownloadRoute {
    const val PREF_KEY = "toolchain_download_route"

    /** id -> 显示名。auto = 按内置顺序逐个尝试；其余 = 只用这一条。 */
    val OPTIONS: List<Pair<String, String>> = listOf(
        "auto" to "自动（镜像优先，逐个重试）",
        "ustc" to "中科大 USTC",
        "nju" to "南京大学",
        "sjtu" to "上海交通大学",
        "tuna" to "清华大学",
        "bfsu" to "北京外国语大学",
        "official" to "Termux 官方",
        "ghproxy" to "gh-proxy.com",
        "ghproxy2" to "ghproxy.net",
        "ghfast" to "ghfast.top",
        "github" to "GitHub 官方",
    )

    private const val TERMUX_PATH_PREFIX = "apt/termux-main/"

    /** Termux 镜像 base（不含 path）。 */
    private val TERMUX_BASE = mapOf(
        "ustc" to "https://mirrors.ustc.edu.cn/termux/apt/termux-main/",
        "nju" to "https://mirror.nju.edu.cn/termux/apt/termux-main/",
        "sjtu" to "https://mirror.sjtu.edu.cn/termux/apt/termux-main/",
        "tuna" to "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/",
        "bfsu" to "https://mirrors.bfsu.edu.cn/termux/apt/termux-main/",
        "official" to "https://packages.termux.dev/apt/termux-main/",
    )

    /** Termux 索引镜像 base（后面接 dists/.../Packages）。 */
    private val INDEX_BASE = mapOf(
        "ustc" to "https://mirrors.ustc.edu.cn/termux/",
        "nju" to "https://mirror.nju.edu.cn/termux/",
        "sjtu" to "https://mirror.sjtu.edu.cn/termux/",
        "tuna" to "https://mirrors.tuna.tsinghua.edu.cn/termux/",
        "bfsu" to "https://mirrors.bfsu.edu.cn/termux/",
        "official" to "https://packages.termux.dev/",
    )

    /** GitHub 代理前缀。 */
    private val GITHUB_PROXY = mapOf(
        "ghproxy" to "https://gh-proxy.com/",
        "ghproxy2" to "https://ghproxy.net/",
        "ghfast" to "https://ghfast.top/",
        "github" to "", // 官方 = 不加前缀
    )

    fun current(context: Context): String =
        context.readStringPreference(PREF_KEY, "auto") ?: "auto"

    fun set(context: Context, id: String) {
        context.writeStringPreference(PREF_KEY, id)
    }

    fun label(id: String): String = OPTIONS.firstOrNull { it.first == id }?.second ?: "自动（镜像优先，逐个重试）"

    /** 测延时用的探测目标：Termux 侧用一个小包，GitHub 侧用一个小文件。 */
    private const val PROBE_TERMUX_PATH = "apt/termux-main/pool/main/x/xz-utils/xz-utils_5.8.4_aarch64.deb"
    private const val PROBE_GITHUB_URL = "https://github.com/git/git/archive/refs/tags/v2.47.0.tar.gz"

    /** id -> 探测 URL；auto 不参与（它是策略）。 */
    private fun probeUrl(id: String): String? = when (id) {
        "official" -> "https://packages.termux.dev/" + PROBE_TERMUX_PATH
        "github" -> PROBE_GITHUB_URL
        in GITHUB_PROXY.keys -> GITHUB_PROXY.getValue(id) + PROBE_GITHUB_URL
        in TERMUX_BASE.keys -> TERMUX_BASE.getValue(id) + PROBE_TERMUX_PATH.substringAfter("apt/termux-main/")
        else -> null
    }

    /**
     * 并发探测各源延时，返回 id -> 毫秒（失败为 null）。
     * 只取首字节（Range: bytes=0-0）就断开，尽量不耗流量；单源 8 秒超时。
     */
    suspend fun measureLatency(): Map<String, Long?> = coroutineScope {
        val ids = OPTIONS.map { it.first }.filter { it != "auto" && probeUrl(it) != null }
        ids.map { id ->
            async(Dispatchers.IO) {
                val url = probeUrl(id)!!
                val ms = withTimeoutOrNull(8_000L) {
                    runCatching {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 8_000
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "RikkaHub-MobiCode-Toolchain/1.0")
                        conn.setRequestProperty("Range", "bytes=0-0")
                        val t0 = System.nanoTime()
                        val code = conn.responseCode
                        // 读到首字节才算通
                        runCatching { conn.inputStream.use { it.read() } }
                        conn.disconnect()
                        if (code in 200..399) (System.nanoTime() - t0) / 1_000_000 else null
                    }.getOrNull()
                }
                id to ms
            }
        }.map { it.await() }.toMap()
    }

    /**
     * 把原始下载 URL 转成候选列表。
     * - auto：保留调用方原来的多候选顺序（由 [fallback] 提供）。
     * - 指定路线：只返回那一条；若该路线不适用于此 URL 类型，则回退到 auto 的完整候选列表。
     */
    fun candidates(
        context: Context,
        urlStr: String,
        fallback: List<String>,
    ): List<String> {
        val id = current(context)
        if (id == "auto") return fallback
        return when {
            urlStr.contains("packages.termux.dev", true) -> {
                val path = urlStr.substringAfter("packages.termux.dev/apt/termux-main/", "")
                val base = TERMUX_BASE[id]
                if (base != null && path.isNotBlank()) listOf(base + path) else fallback
            }
            urlStr.contains("github.com", true) -> {
                val prefix = GITHUB_PROXY[id]
                if (prefix != null) listOf(prefix + urlStr) else fallback
            }
            else -> fallback
        }
    }

    /** 索引 URL 列表：auto 用全部顺序，指定路线只用对应 base。 */
    fun indexSources(context: Context, relativePath: String, allBases: List<String>): List<String> {
        val id = current(context)
        val base = INDEX_BASE[id]
        return if (base != null) listOf(base + relativePath) else allBases.map { it + relativePath }
    }
}
