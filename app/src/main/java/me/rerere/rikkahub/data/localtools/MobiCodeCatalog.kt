package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 扩展目录（在线商店模式）。
 *
 * - 默认列表：APK 内置的精选 MobiCode 扩展（assets/mobicode_extensions.json，16 个，带中文说明）
 * - 搜索：内置扩展 + Termux 官方仓库全部软件包（约 3000+，运行时从镜像站拉取索引并缓存，不打包进 APK）
 * - 下载：全部走国内镜像站（GitHub 镜像 / 清华、中科大、北外 Termux 镜像）
 */

@Serializable
data class CatalogFile(
    val ok: Boolean = true,
    val code: String = "",
    val data: CatalogData = CatalogData(),
)

@Serializable
data class CatalogData(
    val extensions: List<RawExtension> = emptyList(),
)

@Serializable
data class RawVersion(
    val version: String = "",
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("archive_sha256") val sha256: String = "",
    @SerialName("download_size") val downloadSize: Long = 0,
    /** mcext 或 deb */
    val format: String = "mcext",
)

@Serializable
data class RawExtension(
    @SerialName("extension_id") val extensionId: String,
    val name: String = "",
    val version: String = "",
    val publisher: String = "",
    val description: String = "",
    val category: String = "",
    @SerialName("download_size") val downloadSize: Long = 0,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("archive_sha256") val sha256: String = "",
    @SerialName("repository_url") val repositoryUrl: String = "",
    @SerialName("license_name") val licenseName: String = "",
    @SerialName("minimum_app_version") val minimumAppVersion: Int = 0,
    val languages: List<String> = emptyList(),
    @SerialName("project_templates") val projectTemplates: List<String> = emptyList(),
    /** mcext 或 deb */
    val format: String = "mcext",
    /** Termux deb 依赖的包名列表（已去掉版本约束）。 */
    val depends: List<String> = emptyList(),
    /** 可选版本列表（MobiCode 扩展有多个历史版本）。 */
    val versions: List<RawVersion> = emptyList(),
)

@Serializable
data class ExtensionVersion(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
    val downloadSize: Long,
    val format: String = "mcext",
)

/** Termux 索引缓存项（运行时缓存到 filesDir，不打包进 APK）。 */
@Serializable
data class CachedTermuxPackage(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val category: String,
    val downloadSize: Long,
    val downloadUrl: String,
    val sha256: String,
    val depends: List<String> = emptyList(),
)

@Serializable
data class TermuxIndexCache(
    val updatedAt: Long = 0L,
    val packages: List<CachedTermuxPackage> = emptyList(),
)

data class ToolchainExtension(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val category: String,
    val downloadSize: Long,
    val downloadUrl: String,
    val sha256: String,
    val repositoryUrl: String,
    val licenseName: String,
    val minimumAppVersion: Int,
    /** mcext 或 deb */
    val format: String = "mcext",
    /** Termux deb 依赖包名列表。 */
    val depends: List<String> = emptyList(),
    /** 可选版本（从新到旧）。 */
    val versions: List<ExtensionVersion> = emptyList(),
) {
    /** 该扩展所有可选版本（至少包含自身当前版本）。 */
    val availableVersions: List<ExtensionVersion> by lazy {
        if (versions.isNotEmpty()) versions else listOf(
            ExtensionVersion(version, downloadUrl, sha256, downloadSize, format)
        )
    }
}

object MobiCodeCatalog {
    const val EXCLUDED_ID = "__include_all__"

    /** 商店分类（顺序即 UI 显示顺序）。 */
    val CATEGORIES = listOf(
        "全部", "精选", "已安装", "语言环境", "工具链", "编辑器", "网络", "数据库", "媒体", "实用工具", "游戏", "库", "其他"
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cached: List<ToolchainExtension>? = null

    @Volatile
    private var termuxMemory: List<ToolchainExtension>? = null

    private const val TERMUX_INDEX_PATH = "apt/termux-main/dists/stable/main/binary-aarch64/Packages"
    private const val TERMUX_CACHE_FILE = "extensions/termux_index.json"
    private const val TERMUX_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** Termux 索引镜像源（按速度排序，全部为当前可访问的国内镜像；官方兜底）。 */
    private val TERMUX_INDEX_SOURCES = listOf(
        "https://mirrors.ustc.edu.cn/termux/",      // 实测最快（~4.1MB/s）
        "https://mirror.nju.edu.cn/termux/",
        "https://mirror.sjtu.edu.cn/termux/",
        "https://mirrors.tuna.tsinghua.edu.cn/termux/",
        "https://mirrors.bfsu.edu.cn/termux/",
        "https://packages.termux.dev/",
    )

    // ======================== 内置精选扩展 ========================

    fun extensions(context: Context): List<ToolchainExtension> {
        cached?.let { return it }
        val list = runCatching {
            val text = context.assets.open("mobicode_extensions.json").bufferedReader().use { it.readText() }
            val file = json.decodeFromString(CatalogFile.serializer(), text)
            file.data.extensions
                .filter { it.extensionId != EXCLUDED_ID }
                .map { it.toModel() }
        }.getOrElse { emptyList() }
        cached = list
        return list
    }

    fun find(context: Context, id: String): ToolchainExtension? =
        extensions(context).firstOrNull { it.id == id }

    // ======================== Termux 在线索引 ========================

    private fun cacheFile(context: Context): File = File(context.filesDir, TERMUX_CACHE_FILE)

    /** 读取本地缓存的 Termux 索引（不联网）。 */
    fun cachedTermuxIndex(context: Context): List<ToolchainExtension> {
        termuxMemory?.let { return it }
        val list = runCatching {
            val f = cacheFile(context)
            if (!f.exists()) return emptyList()
            val cache = json.decodeFromString(TermuxIndexCache.serializer(), f.readText())
            cache.packages.map { it.toExtension() }
        }.getOrElse { emptyList() }
        if (list.isNotEmpty()) termuxMemory = list
        return list
    }

    /**
     * 确保 Termux 索引可用：
     * 1. 内存缓存 -> 2. 磁盘缓存（24h 内）-> 3. 从镜像站下载 Packages 索引并解析缓存。
     */
    suspend fun ensureTermuxIndex(context: Context, force: Boolean = false): List<ToolchainExtension> =
        withContext(Dispatchers.IO) {
            termuxMemory?.let { if (!force) return@withContext it }
            val file = cacheFile(context)
            if (!force) {
                val disk = runCatching {
                    if (!file.exists()) null
                    else json.decodeFromString(TermuxIndexCache.serializer(), file.readText())
                }.getOrNull()
                if (disk != null && disk.packages.isNotEmpty() && System.currentTimeMillis() - disk.updatedAt < TERMUX_CACHE_MAX_AGE_MS) {
                    val list = disk.packages.map { it.toExtension() }
                    termuxMemory = list
                    return@withContext list
                }
            }
            // 下载索引
            val text = downloadTermuxIndexText(context) ?: return@withContext cachedTermuxIndex(context)
            val packages = parseTermuxPackages(text)
            if (packages.isNotEmpty()) {
                runCatching {
                    file.parentFile?.mkdirs()
                    val cache = TermuxIndexCache(
                        updatedAt = System.currentTimeMillis(),
                        packages = packages.map { it.toCached() },
                    )
                    file.writeText(json.encodeToString(TermuxIndexCache.serializer(), cache))
                }
                termuxMemory = packages
            }
            return@withContext packages
        }

    private fun downloadTermuxIndexText(context: Context): String? {
        val sources = DownloadRoute.indexSources(context, TERMUX_INDEX_PATH, TERMUX_INDEX_SOURCES)
        for (url in sources) {
            try {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "RikkaHub-MobiCode-Toolchain/1.0")
                if (conn.responseCode in 200..299) {
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    if (text.contains("Package: ")) return text
                } else {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // 尝试下一个源
            }
        }
        return null
    }

    /** 解析 Debian Packages 索引（支持多行 Description）。 */
    private fun parseTermuxPackages(text: String): List<ToolchainExtension> {
        val records = mutableListOf<MutableMap<String, String>>()
        var current = LinkedHashMap<String, String>()
        var lastKey: String? = null
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) {
                if (current.isNotEmpty()) { records.add(current); current = LinkedHashMap(); lastKey = null }
                continue
            }
            val lk = lastKey
            if (raw.startsWith(" ") && lk != null) {
                current[lk] = (current[lk] ?: "") + "\n" + raw.trim()
                continue
            }
            val idx = raw.indexOf(": ")
            if (idx > 0) {
                val key = raw.substring(0, idx)
                current[key] = raw.substring(idx + 2)
                lastKey = key
            }
        }
        if (current.isNotEmpty()) records.add(current)

        val result = ArrayList<ToolchainExtension>(records.size)
        for (r in records) {
            val pkg = r["Package"]?.trim().orEmpty()
            if (pkg.isBlank()) continue
            val filename = r["Filename"]?.trim().orEmpty()
            if (filename.isBlank()) continue
            val descEn = (r["Description"] ?: "").replace('\n', ' ').trim()
            val cn = CN_DESCRIPTIONS[pkg]
            val displayName = if (cn != null) cn.first else pkg
            val description = if (cn != null) cn.second else "Termux 软件包：${descEn.ifBlank { pkg }}。"
            result.add(
                ToolchainExtension(
                    id = termuxId(pkg),
                    name = displayName,
                    version = r["Version"]?.trim().orEmpty(),
                    publisher = "Termux",
                    description = description,
                    category = categoryOf(pkg, descEn),
                    downloadSize = r["Size"]?.trim()?.toLongOrNull() ?: 0L,
                    downloadUrl = "https://packages.termux.dev/apt/termux-main/$filename",
                    sha256 = r["SHA256"]?.trim().orEmpty(),
                    repositoryUrl = r["Homepage"]?.trim().orEmpty(),
                    licenseName = "",
                    minimumAppVersion = 0,
                    format = "deb",
                    depends = parseDepends(r["Depends"]),
                )
            )
        }
        return result
    }

    private fun parseDepends(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").map { part ->
            part.trim().split("|").first().trim().replace(Regex("\\s*\\(.*$"), "").trim()
        }.filter { it.isNotBlank() }
    }

    private fun termuxId(pkg: String): String =
        "termux." + pkg.lowercase().replace(Regex("[^a-z0-9]+"), ".")

    /** 按 Termux 包名查找（用于依赖解析，仅查内存/磁盘缓存，不联网）。 */
    fun findTermuxPackage(context: Context, packageName: String): ToolchainExtension? {
        val id = termuxId(packageName)
        cachedTermuxIndex(context).firstOrNull { it.id == id }?.let { return it }
        termuxMemory?.firstOrNull { it.id == id }?.let { return it }
        return null
    }

    /** 搜索内置扩展 + Termux 索引（提供 Termux 索引时）。 */
    fun search(context: Context, query: String, termuxPackages: List<ToolchainExtension> = emptyList()): List<ToolchainExtension> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        fun matches(e: ToolchainExtension): Boolean =
            e.name.contains(q, true) || e.description.contains(q, true) ||
                e.id.contains(q, true) || e.category.contains(q, true) || e.publisher.contains(q, true)
        return (extensions(context) + termuxPackages).filter(::matches).distinctBy { it.id }
    }

    private fun RawExtension.toModel() = ToolchainExtension(
        id = extensionId,
        name = name.ifBlank { extensionId },
        version = version,
        publisher = publisher,
        description = description,
        category = category,
        downloadSize = downloadSize,
        downloadUrl = downloadUrl,
        sha256 = sha256,
        repositoryUrl = repositoryUrl,
        licenseName = licenseName,
        minimumAppVersion = minimumAppVersion,
        format = format,
        depends = depends,
        versions = versions.map {
            ExtensionVersion(it.version, it.downloadUrl, it.sha256, it.downloadSize, it.format)
        },
    )

    private fun ToolchainExtension.toCached() = CachedTermuxPackage(
        id = id, name = name, version = version, description = description,
        category = category, downloadSize = downloadSize, downloadUrl = downloadUrl,
        sha256 = sha256, depends = depends,
    )

    private fun CachedTermuxPackage.toExtension() = ToolchainExtension(
        id = id, name = name, version = version, publisher = "Termux",
        description = description, category = category, downloadSize = downloadSize,
        downloadUrl = downloadUrl, sha256 = sha256,
        repositoryUrl = "", licenseName = "", minimumAppVersion = 0,
        format = "deb", depends = depends,
    )

    // ======================== 分类 / 中文名 ========================

    /** 分类显示名（内部 category -> 商店分类 Tab）。 */
    fun categoryLabel(category: String): String = when (category) {
        "LANGUAGE" -> "语言环境"
        "TOOLCHAIN" -> "工具链"
        "AI" -> "工具链"
        "SOURCE_CONTROL" -> "工具链"
        "ENVIRONMENT" -> "工具链"
        "EDITOR" -> "编辑器"
        "NETWORK" -> "网络"
        "DATABASE" -> "数据库"
        "MEDIA" -> "媒体"
        "UTILITY" -> "实用工具"
        "MIRROR" -> "其他"
        "GAME" -> "游戏"
        "LIBRARY" -> "库"
        else -> "其他"
    }

    private fun categoryOf(pkg: String, desc: String): String {
        val p = pkg.lowercase()
        val d = desc.lowercase()
        return when {
            Regex("^(python|python3|nodejs|nodejs-lts|golang|rust|clang|llvm|openjdk-\\d+|php|ruby|perl|lua|luajit|tcl|deno|bun|nim|zig|julia|swift|kotlin|scala|haskell|ghc|erlang|elixir|clojure|groovy|ocaml|fortran|gnuplot|octave|dart|dotnet|mono)").containsMatchIn(p) -> "LANGUAGE"
            Regex("compiler|language|interpreter|sdk|jdk|runtime|toolchain").containsMatchIn(d) -> "LANGUAGE"
            Regex("^(vim|neovim|nano|micro|emacs|ed|joe|kakoune|helix)").containsMatchIn(p) -> "EDITOR"
            Regex("editor").containsMatchIn(d) -> "EDITOR"
            Regex("^(curl|wget|aria2|rsync|openssh|dropbear|nmap|netcat|socat|tcpdump|iperf|dnsutils|whois|traceroute|ping|mosh|tmate|sshfs|rclone|yt-dlp|sshpass|openvpn|wireguard|tor|sftp)").containsMatchIn(p) -> "NETWORK"
            Regex("network|ssh|ftp|http client|download|remote").containsMatchIn(d) -> "NETWORK"
            Regex("^(sqlite|mariadb|postgresql|redis|mongodb|mysql|leveldb|rocksdb)").containsMatchIn(p) -> "DATABASE"
            Regex("database").containsMatchIn(d) -> "DATABASE"
            Regex("^(ffmpeg|ffprobe|imagemagick|graphicsmagick|libjpeg|libpng|libwebp|qrencode|zbar|pandoc|exiftool|sox|opus|vlc)").containsMatchIn(p) -> "MEDIA"
            Regex("audio|video|image|media").containsMatchIn(d) -> "MEDIA"
            Regex("^(git|subversion|mercurial|fossil)").containsMatchIn(p) -> "SOURCE_CONTROL"
            Regex("version control").containsMatchIn(d) -> "SOURCE_CONTROL"
            Regex("^(make|cmake|meson|ninja|pkg-config|autoconf|automake|libtool|gdb|lldb|strace|ltrace|binutils|patchelf|apktool|aapt2|apksigner|zipalign|dex2jar|jadx|frida|radare2|rizin)").containsMatchIn(p) -> "TOOLCHAIN"
            Regex("build tool|debugger|reverse|binary").containsMatchIn(d) -> "TOOLCHAIN"
            Regex("^(jq|yq|ripgrep|fd|fzf|bat|eza|exa|tree|htop|btop|ncdu|duf|zip|unzip|tar|xz-utils|zstd|7zip|gzip|bzip2|file|xxd|hexdump|less|more|tmux|screen|coreutils|findutils)").containsMatchIn(p) -> "UTILITY"
            Regex("game|nethack|moon-buggy|vitetris|0verkill").containsMatchIn(p) || Regex("game").containsMatchIn(d) -> "GAME"
            Regex("^(lib|libandroid|libc|libcxx|libssl|libcrypto|libcurl|libexpat|libffi|libiconv|libicu|liblzma|libsqlite|libxml|libxslt|libzip|libbz2|libz|zlib|ncurses|readline|openssl)").containsMatchIn(p) -> "LIBRARY"
            else -> "UTILITY"
        }
    }

    /** 常见包中文名与说明（其余使用中文模板）。 */
    private val CN_DESCRIPTIONS: Map<String, Pair<String, String>> = mapOf(
        "python" to ("Python" to "Python 3 编程语言解释器，提供交互式解释器与 pip 包管理器。"),
        "nodejs" to ("Node.js" to "Node.js JavaScript 运行时环境，包含 npm 包管理器。"),
        "nodejs-lts" to ("Node.js LTS" to "Node.js 长期支持版 JavaScript 运行时，包含 npm 包管理器。"),
        "git" to ("Git" to "分布式版本控制系统，用于代码仓库克隆、提交、分支与协作。"),
        "golang" to ("Go" to "Go 编程语言编译器与标准库，支持本机构建 Go 项目。"),
        "rust" to ("Rust" to "Rust 编译器与 Cargo 包管理器，支持系统编程与 Android 交叉编译。"),
        "clang" to ("Clang/LLVM" to "Clang/LLVM 编译器前端，用于 C/C++ 原生编译。"),
        "openjdk-17" to ("OpenJDK 17" to "Java 17 开发工具包与运行时环境。"),
        "openjdk-21" to ("OpenJDK 21" to "Java 21 开发工具包与运行时环境。"),
        "php" to ("PHP" to "PHP 服务端脚本语言解释器与 CLI 工具。"),
        "ruby" to ("Ruby" to "Ruby 动态编程语言解释器与 gem 包管理器。"),
        "perl" to ("Perl" to "Perl 编程语言解释器。"),
        "lua" to ("Lua" to "Lua 编程语言解释器。"),
        "lua51" to ("Lua 5.1" to "Lua 5.1 编程语言解释器。"),
        "luajit" to ("LuaJIT" to "高性能 LuaJIT 运行时。"),
        "composer" to ("Composer" to "PHP 依赖管理工具。"),
        "npm" to ("npm" to "Node.js 包管理器。"),
        "yarn" to ("Yarn" to "Node.js 包管理器 Yarn。"),
        "pnpm" to ("pnpm" to "快速节省磁盘空间的 Node.js 包管理器。"),
        "deno" to ("Deno" to "安全的 JavaScript/TypeScript 运行时。"),
        "bun" to ("Bun" to "快速的 JavaScript 运行时与包管理器。"),
        "vim" to ("Vim" to "Vi IMproved 增强版文本编辑器。"),
        "neovim" to ("Neovim" to "现代化 Vim 分支，可扩展的终端文本编辑器。"),
        "nano" to ("nano" to "小巧友好的命令行文本编辑器。"),
        "micro" to ("micro" to "现代直观的终端文本编辑器。"),
        "emacs" to ("Emacs" to "可扩展的 GNU Emacs 文本编辑器。"),
        "less" to ("less" to "终端分页查看器，用于分屏阅读文本文件。"),
        "tmux" to ("tmux" to "终端复用器，支持多窗口与会话管理。"),
        "screen" to ("Screen" to "终端会话复用器。"),
        "curl" to ("cURL" to "命令行 HTTP/HTTPS/FTP 数据传输工具。"),
        "wget" to ("Wget" to "命令行文件下载工具，支持 HTTP/HTTPS/FTP。"),
        "aria2" to ("aria2" to "多协议多线程下载工具。"),
        "rsync" to ("rsync" to "快速增量文件同步与备份工具。"),
        "openssh" to ("OpenSSH" to "SSH 远程登录、SCP/SFTP 文件传输与密钥管理工具。"),
        "dropbear" to ("Dropbear" to "轻量级 SSH 服务器与客户端。"),
        "nmap" to ("Nmap" to "网络探测与安全审计工具。"),
        "netcat" to ("Netcat" to "网络调试与数据传输工具。"),
        "socat" to ("socat" to "多用途网络数据转发工具。"),
        "tcpdump" to ("tcpdump" to "命令行网络抓包分析工具。"),
        "iperf3" to ("iperf3" to "网络带宽性能测试工具。"),
        "dnsutils" to ("DNS 工具" to "DNS 查询与诊断工具集（dig/nslookup）。"),
        "whois" to ("whois" to "域名与 IP 注册信息查询工具。"),
        "traceroute" to ("traceroute" to "网络路由跟踪工具。"),
        "jq" to ("jq" to "命令行 JSON 处理与查询工具。"),
        "yq" to ("yq" to "命令行 YAML/JSON 处理工具。"),
        "ripgrep" to ("ripgrep (rg)" to "高速递归文本搜索工具，类似 grep。"),
        "fd" to ("fd" to "简单快速的 find 替代品，用于查找文件。"),
        "fzf" to ("fzf" to "命令行模糊查找工具。"),
        "bat" to ("bat" to "带语法高亮的 cat 替代品。"),
        "eza" to ("eza" to "现代化 ls 替代品，带颜色和图标。"),
        "exa" to ("exa" to "现代化 ls 替代品。"),
        "tree" to ("tree" to "以树状图递归列出目录结构。"),
        "htop" to ("htop" to "交互式系统进程查看器。"),
        "btop" to ("btop" to "现代化系统资源监视器。"),
        "ncdu" to ("ncdu" to "基于 ncurses 的磁盘使用分析器。"),
        "duf" to ("duf" to "磁盘使用情况查看工具。"),
        "bash" to ("Bash" to "GNU Bash shell。"),
        "zsh" to ("Zsh" to "Z shell，功能强大的交互式 shell。"),
        "fish" to ("Fish" to "友好交互式 shell。"),
        "zip" to ("zip" to "ZIP 压缩工具。"),
        "unzip" to ("unzip" to "ZIP 解压工具。"),
        "tar" to ("tar" to "GNU tar 归档工具。"),
        "xz-utils" to ("xz" to "XZ 格式压缩与解压工具。"),
        "zstd" to ("zstd" to "Zstandard 高速压缩工具。"),
        "7zip" to ("7-Zip" to "7-Zip 高压缩率归档工具。"),
        "sqlite" to ("SQLite" to "SQLite 命令行数据库工具。"),
        "mariadb" to ("MariaDB" to "MariaDB 数据库服务器与客户端。"),
        "postgresql" to ("PostgreSQL" to "PostgreSQL 数据库服务器与客户端。"),
        "redis" to ("Redis" to "Redis 内存键值数据库。"),
        "nginx" to ("Nginx" to "高性能 HTTP 与反向代理服务器。"),
        "apache2" to ("Apache HTTP Server" to "Apache Web 服务器。"),
        "caddy" to ("Caddy" to "现代化自动 HTTPS Web 服务器。"),
        "ffmpeg" to ("FFmpeg" to "音视频格式转换与处理工具。"),
        "ffprobe" to ("ffprobe" to "音视频文件信息分析工具。"),
        "imagemagick" to ("ImageMagick" to "图像创建、编辑、合成与格式转换工具集。"),
        "qrencode" to ("qrencode" to "二维码生成工具。"),
        "pandoc" to ("Pandoc" to "通用文档格式转换工具。"),
        "openssl" to ("OpenSSL" to "加密与 SSL/TLS 工具库（命令行 openssl）。"),
        "gnupg" to ("GnuPG" to "GNU 加密与签名工具（gpg）。"),
        "age" to ("age" to "现代化简单文件加密工具。"),
        "rclone" to ("rclone" to "云存储文件同步工具，支持数十种存储服务。"),
        "yt-dlp" to ("yt-dlp" to "视频下载工具（youtube-dl 增强版）。"),
        "apktool" to ("Apktool" to "APK 反编译与重打包工具。"),
        "aapt2" to ("AAPT2" to "Android 资源打包工具。"),
        "apksigner" to ("apksigner" to "APK 签名工具。"),
        "zipalign" to ("zipalign" to "APK 对齐优化工具。"),
        "dex2jar" to ("dex2jar" to "DEX 转 JAR 工具。"),
        "jadx" to ("jadx" to "DEX 反编译为 Java 源码工具。"),
        "frida" to ("Frida" to "动态代码插桩与逆向分析工具。"),
        "radare2" to ("Radare2" to "逆向工程框架与十六进制编辑器。"),
        "binutils" to ("Binutils" to "二进制工具集（ld/as/objdump 等）。"),
        "make" to ("Make" to "GNU Make 构建工具。"),
        "cmake" to ("CMake" to "跨平台构建系统生成器。"),
        "meson" to ("Meson" to "现代化构建系统。"),
        "ninja" to ("Ninja" to "小型高速构建工具。"),
        "pkg-config" to ("pkg-config" to "编译时库依赖查询工具。"),
        "gdb" to ("GDB" to "GNU 调试器。"),
        "strace" to ("strace" to "系统调用跟踪工具。"),
        "file" to ("file" to "文件类型识别工具。"),
        "xxd" to ("xxd" to "十六进制查看与转换工具。"),
        "patchelf" to ("patchelf" to "ELF 二进制修改工具（rpath/interpreter）。"),
        "termux-api" to ("Termux API" to "Termux 系统 API 工具（通知/传感器/相机等）。"),
        "termux-tools" to ("Termux Tools" to "Termux 基础工具集。"),
        "proot" to ("proot" to "用户态 chroot/文件系统隔离工具。"),
        "proot-distro" to ("proot-distro" to "在 Termux 中安装管理 Linux 发行版。"),
        "tmate" to ("tmate" to "即时终端共享工具。"),
        "mosh" to ("Mosh" to "移动场景下的远程 Shell。"),
        "sshfs" to ("sshfs" to "通过 SSH 挂载远程文件系统。"),
        "udocker" to ("udocker" to "无需 root 的 Docker 容器工具。"),
        "nethack" to ("NetHack" to "经典 ASCII 地牢探险游戏。"),
        "cmatrix" to ("cmatrix" to "终端 Matrix 数字雨效果。"),
        "figlet" to ("figlet" to "ASCII 艺术字生成工具。"),
        "lolcat" to ("lolcat" to "给终端文本上彩虹色。"),
        "cowsay" to ("cowsay" to "奶牛说话 ASCII 艺术工具。"),
        "sl" to ("sl" to "蒸汽火车动画（误输入 ls 时的彩蛋）。"),
    )
}
