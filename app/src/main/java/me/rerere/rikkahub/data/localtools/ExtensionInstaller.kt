package me.rerere.rikkahub.data.localtools

import android.content.Context
import me.rerere.common.android.appTempFolder
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tukaani.xz.XZInputStream

@Serializable
data class InstalledRecord(
    val id: String,
    val version: String,
    val payloadDir: String,
    val installedAt: Long = 0L,
    val downloadBytes: Long = 0L,
    /** 显示名（远程安装的包不在内置目录时需要它重建列表项）。 */
    val name: String = "",
    val publisher: String = "",
    val description: String = "",
    val category: String = "",
    val downloadUrl: String = "",
    /** mcext 或 deb */
    val format: String = "mcext",
    /** 是否有可执行命令（false 表示只是库/运行时/插件包）。 */
    val hasCommands: Boolean = true,
    /** 安装时的依赖列表（Termux deb），卸载时用于级联清理。 */
    val depends: List<String> = emptyList(),
    /**
     * 安装时保存的扩展环境变量（照搬 MobiCode Studio 的 ExtensionEnvironmentGroup.variables）：
     * PATH / LD_LIBRARY_PATH / LIBRARY_PATH / CPATH / MOBICODE_SYSROOT
     * + OPENSSL_CONF / SSL_CERT_FILE / OPENSSL_MODULES / NODE_PATH（按文件存在性）。
     * 安装时计算一次并持久化，运行时不再扫描文件。
     */
    val envVars: Map<String, String> = emptyMap(),
)

@Serializable
data class InstalledManifest(
    val records: List<InstalledRecord> = emptyList(),
)

/** 安装阶段（用于实时进度）。 */
enum class InstallPhase { DOWNLOADING, EXTRACTING, INSTALLING }

/** 一次进度更新。 */
data class InstallUpdate(
    val phase: InstallPhase,
    val downloaded: Long = 0,
    val total: Long = 0,
    val extracted: Int = 0,
    val entries: Int = 0,
    /** 解压阶段：当前正在解压的文件名（与工作区 rootfs 安装一致的实时体验）。 */
    val currentEntry: String? = null,
    /** 依赖安装提示（如 "安装依赖 libssl（2/5）"）。 */
    val dependencyNote: String? = null,
)

/**
 * 在线扩展安装器。
 *
 * 支持两种包格式：
 * - .mcext（ZIP，MobiCode 工具链）：下载 -> sha256 校验 -> 解压 payload/ -> 登记
 * - .deb（Termux 软件包）：下载 -> sha256 校验 -> 解析 ar/data.tar.xz -> 解压到 payload/ -> 登记
 *
 * 下载全部走国内镜像站（GitHub 镜像 / 清华、中科大 Termux 镜像），官方地址兜底。
 * 通过 [install] 的 onUpdate 回调实时报告 下载/解压/安装 进度；Termux 包会自动安装依赖。
 */
class ExtensionInstaller(
    private val context: Context,
    private val shellExecutor: ShellExecutor,
) {
    companion object {
        private const val EXT_DIR = "extensions"
        private const val MANIFEST = "installed.json"
        private const val USER_AGENT = "RikkaHub-MobiCode-Toolchain/1.0"

        private val GITHUB_MIRRORS = listOf(
            "https://gh-proxy.com/",       // 实测最快（~1.8MB/s）
            "https://ghproxy.net/",
            "https://ghfast.top/",
        )

        /** Termux 官方仓库的国内镜像（下载走这里，索引内置在 APK 中）。 */
        private val TERMUX_MIRRORS = listOf(
            "https://mirrors.ustc.edu.cn/termux/apt/termux-main/",      // 实测最快
            "https://mirror.nju.edu.cn/termux/apt/termux-main/",
            "https://mirror.sjtu.edu.cn/termux/apt/termux-main/",
            "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main/",
            "https://mirrors.bfsu.edu.cn/termux/apt/termux-main/",
        )
    }

    /**
     * 安装串行化。同一扩展被并发安装时（例如模型连续调用 pkg_install）会共用
     * appTempFolder/ext_<id> 这个临时目录与同一个 archive，互相覆盖导致解压损坏。
     * 安装本身是重操作，串行没有明显损失。
     */
    private val installMutex = Mutex()
    /** 预下载按包 id 加锁：不同主包依赖同一个包时，不能同时写同一个 archive。 */
    private val downloadLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; isLenient = true }
    private val extRoot: File get() = File(context.filesDir, EXT_DIR)
    private val manifestFile: File get() = File(extRoot, MANIFEST)

    private val _installed = MutableStateFlow<Map<String, InstalledRecord>>(emptyMap())
    val installed: StateFlow<Map<String, InstalledRecord>> = _installed.asStateFlow()

    init {
        _installed.value = loadManifest()
        migrateLegacyEnvVars()
    }

    fun isInstalled(id: String): Boolean = _installed.value.containsKey(id)
    fun payloadDirOf(id: String): String? = _installed.value[id]?.payloadDir

    @Synchronized fun refresh() { _installed.value = loadManifest() }

    /** 旧版本安装的扩展没有 envVars，这里一次性补算并持久化（不是运行时扫描）。 */
    private fun migrateLegacyEnvVars() {
        val map = _installed.value.toMutableMap()
        var changed = false
        for ((id, rec) in map) {
            if (rec.envVars.isEmpty()) {
                val payloadDir = File(rec.payloadDir)
                if (payloadDir.isDirectory) {
                    map[id] = rec.copy(envVars = buildExtensionEnvVars(payloadDir))
                    changed = true
                }
            }
        }
        if (changed) {
            writeManifest(map)
            _installed.value = map
        }
    }

    private fun loadManifest(): Map<String, InstalledRecord> = runCatching {
        if (!manifestFile.exists()) return emptyMap()
        val data = json.decodeFromString(InstalledManifest.serializer(), manifestFile.readText())
        data.records.filter { File(it.payloadDir, "bin").exists() || File(it.payloadDir, "lib").exists() }
            .associateBy { it.id }
    }.getOrElse { emptyMap() }

    /**
     * 写入一条安装记录。
     *
     * installed.json 是「读当前 map → 改 → 整体写回」，并发安装时两个协程会各自基于
     * 旧快照写回，后写的把先写的记录覆盖掉（实测并发装 git 和 python 时丢过记录）。
     * 这里在同一把锁内完成 读-改-写，并按 atomic rename 落盘。
     */
    @Synchronized
    private fun putRecord(record: InstalledRecord) {
        val map = _installed.value.toMutableMap()
        map[record.id] = record
        writeManifest(map)
        _installed.value = map
    }

    /** 删除一条安装记录，同样在锁内读-改-写。 */
    @Synchronized
    private fun removeRecord(id: String) {
        val map = _installed.value.toMutableMap()
        map.remove(id)
        writeManifest(map)
        _installed.value = map
    }

    private fun writeManifest(map: Map<String, InstalledRecord>) {
        runCatching {
            extRoot.mkdirs()
            val tmp = File(extRoot, "$MANIFEST.tmp")
            tmp.writeText(json.encodeToString(InstalledManifest.serializer(), InstalledManifest(map.values.toList())))
            if (manifestFile.exists()) manifestFile.delete()
            tmp.renameTo(manifestFile)
        }
    }

    /**
     * 预下载：只把包体（含依赖）下载到临时目录，不做解压/登记。
     * 多个 prepare() 可以并发跑（下载阶段不持 installMutex），供商店队列实现
     * 「并行下载、串行安装」。install() 检测到已下载的 archive 会跳过下载。
     */
    suspend fun prepare(
        entry: ToolchainExtension,
        onUpdate: (InstallUpdate) -> Unit = {},
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(entry.downloadUrl.isNotBlank()) { "扩展 ${entry.name} 缺少下载地址" }
            if (entry.format.equals("deb", ignoreCase = true)) {
                MobiCodeCatalog.ensureTermuxIndex(context)
            }
            val entries = buildList {
                resolveDependencies(entry).forEach { add(it) }
                add(entry)
            }
            entries.forEachIndexed { index, e ->
                if (isInstalled(e.id)) return@forEachIndexed
                val lock = downloadLocks.getOrPut(e.id) { Mutex() }
                lock.withLock {
                    val workDir = File(context.appTempFolder, "ext_${e.id}").apply { mkdirs() }
                    val archive = File(workDir, if (e.format.equals("deb", true)) "archive.deb" else "archive.mcext")
                    // 已经下载过（别的任务依赖同一个包）就跳过，避免覆盖
                    if (archive.exists() && archive.length() > 0) return@withLock
                    onUpdate(InstallUpdate(
                        InstallPhase.DOWNLOADING,
                        downloaded = 0,
                        total = e.downloadSize,
                        dependencyNote = "并行下载 ${index + 1}/${entries.size} ${e.name}",
                    ))
                    downloadWithMirrors(e.downloadUrl, archive, e.sha256) { d, t ->
                        onUpdate(InstallUpdate(InstallPhase.DOWNLOADING, downloaded = d, total = t))
                    }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** 安装扩展（含自动依赖安装）。 */
    suspend fun install(
        entry: ToolchainExtension,
        onUpdate: (InstallUpdate) -> Unit = {},
    ): Result<InstalledRecord> = withContext(Dispatchers.IO) {
        // 串行化安装：同一扩展被并发安装时共用 ext_<id> 临时目录与 archive，会互相覆盖。
        installMutex.withLock {
            try {
                require(entry.downloadUrl.isNotBlank()) { "扩展 ${entry.name} 缺少下载地址" }

                // 0) 确保 Termux 索引已加载（依赖解析需要）
                if (entry.format.equals("deb", ignoreCase = true)) {
                    MobiCodeCatalog.ensureTermuxIndex(context)
                }

                // 1) 先安装依赖（Termux deb）
                val dependencies = resolveDependencies(entry)
                dependencies.forEachIndexed { index, dep ->
                    if (isInstalled(dep.id)) return@forEachIndexed
                    onUpdate(InstallUpdate(InstallPhase.INSTALLING, dependencyNote = "安装依赖 ${dep.name}（${index + 1}/${dependencies.size}）"))
                    installSingle(dep, onUpdate).getOrThrow()
                }

                // 2) 安装自身
                Result.success(installSingle(entry, onUpdate).getOrThrow())
            } catch (e: CancellationException) {
                File(context.appTempFolder, "ext_${entry.id}").deleteRecursively()
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    /** 递归收集依赖（BFS，已安装的跳过）。 */
    private fun resolveDependencies(entry: ToolchainExtension): List<ToolchainExtension> {
        if (entry.format != "deb" || entry.depends.isEmpty()) return emptyList()
        val result = LinkedHashMap<String, ToolchainExtension>()
        val queue = ArrayDeque<String>()
        entry.depends.forEach { queue.add(it) }
        // git/curl/openssl 类需要 ca-certificates 提供 HTTPS 证书
        if (entry.id == "termux.git" || entry.depends.any { it.contains("curl", true) || it.contains("openssl", true) || it == "git" }) {
            queue.add("ca-certificates")
        }
        // Termux 把 pip 拆成了独立包（python-pip），装 python 不会自动带 pip，
        // 结果 `pip` / `python3 -m pip` 全都不可用（实测）。这里一并装上。
        if (entry.id == "termux.python") {
            queue.add("python-pip")
        }
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name.isBlank()) continue
            val dep = MobiCodeCatalog.findTermuxPackage(context, name) ?: continue
            if (dep.id == entry.id) continue
            if (_installed.value.containsKey(dep.id)) continue
            if (result.containsKey(dep.id)) continue
            result[dep.id] = dep
            dep.depends.forEach { queue.add(it) }
        }
        return result.values.toList()
    }

    /** 版本目录名安全化：Termux 版本号可能含 epoch 冒号（如 1:3.6.5），冒号会破坏 LD_LIBRARY_PATH。 */
    private fun safeVersionDirName(version: String): String = version.replace(":", "_")

    private suspend fun installSingle(
        entry: ToolchainExtension,
        onUpdate: (InstallUpdate) -> Unit,
    ): Result<InstalledRecord> = try {
        extRoot.mkdirs()
        val workDir = File(context.appTempFolder, "ext_${entry.id}")
        workDir.mkdirs()
        val isDeb = entry.format.equals("deb", ignoreCase = true)
        val archiveName = if (isDeb) "archive.deb" else "archive.mcext"
        val archive = File(workDir, archiveName)
        // 提到 try 外：失败时 catch 里要清理这个半成品目录
        val versionDir = File(extRoot, "${entry.id}/${safeVersionDirName(entry.version)}")

        try {
            // 1) 下载（镜像优先，实时进度）。商店队列可能已经并行预下载过，
            //    此时直接复用 archive，避免重复下载。
            val preDownloaded = archive.exists() && archive.length() > 0 &&
                (entry.sha256.isBlank() || sha256Of(archive).equals(entry.sha256.trim(), true))
            if (!preDownloaded) {
                downloadWithMirrors(entry.downloadUrl, archive, entry.sha256) { d, t ->
                    onUpdate(InstallUpdate(InstallPhase.DOWNLOADING, downloaded = d, total = t))
                }
            } else {
                onUpdate(InstallUpdate(InstallPhase.DOWNLOADING, downloaded = archive.length(), total = archive.length()))
            }

            // 2) 解压（实时进度）
            versionDir.deleteRecursively()
            val payloadDir = File(versionDir, "payload")
            payloadDir.mkdirs()
            val count = if (isDeb) {
                extractDebPayload(archive, payloadDir) { done, total, current ->
                    onUpdate(InstallUpdate(InstallPhase.EXTRACTING, extracted = done, entries = total, currentEntry = current))
                }
            } else {
                extractMcextPayload(archive, payloadDir) { done, total, current ->
                    onUpdate(InstallUpdate(InstallPhase.EXTRACTING, extracted = done, entries = total, currentEntry = current))
                }
            }
            if (count <= 0) {
                versionDir.deleteRecursively()
                error("扩展包内未找到可安装内容")
            }

            // 3) 赋可执行权限（root 优先，其次 Shizuku；都无则用普通权限，app 对自己的文件有权限）
            onUpdate(InstallUpdate(InstallPhase.INSTALLING))
            runCatching {
                shellExecutor.execute(
                    "chmod -R 0755 ${payloadDir.absolutePath.shellQuote()} 2>/dev/null; find ${payloadDir.absolutePath.shellQuote()} -type f -exec chmod 0755 {} + 2>/dev/null",
                    timeoutMillis = 120_000,
                    mode = shellExecutor.preferredMode(),
                )
            }

            // 4) 计算并保存扩展环境变量（安装时保存，运行时不再扫描）
            val envVars = buildExtensionEnvVars(payloadDir)

            val record = InstalledRecord(
                id = entry.id,
                version = entry.version,
                payloadDir = payloadDir.absolutePath,
                installedAt = System.currentTimeMillis(),
                downloadBytes = entry.downloadSize,
                name = entry.name,
                publisher = entry.publisher,
                description = entry.description,
                category = entry.category,
                downloadUrl = entry.downloadUrl,
                format = if (isDeb) "deb" else "mcext",
                hasCommands = File(payloadDir, "bin").listFiles()?.any { it.isFile } == true,
                depends = entry.depends.map { dep -> dep.substringBefore('(').trim() }.mapNotNull { name -> MobiCodeCatalog.findTermuxPackage(context, name)?.id },
                envVars = envVars,
            )
            putRecord(record)
            // 升级到新版本后，旧版本目录没人删会一直占着磁盘，这里顺手清理。
            runCatching {
                File(extRoot, entry.id).listFiles()
                    ?.filter { it.isDirectory && it != versionDir }
                    ?.forEach { it.deleteRecursively() }
            }
            Result.success(record)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // 解压或授权中途失败时 payload 可能是残缺的：不会写入安装记录，
            // 但半成品目录会留在磁盘上白占空间，这里一并清掉。
            runCatching { versionDir.deleteRecursively() }
            Result.failure(e)
        } finally {
            archive.delete()
            workDir.deleteRecursively()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    suspend fun uninstall(id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!_installed.value.containsKey(id)) return@runCatching
            // 保守模式：只删主包本身，不级联删依赖。
            // 原因：历史安装数据的 depends 可能缺失/不完整，级联判断会误删共享依赖（实测删过 git 的 libiconv）。
            File(extRoot, id).deleteRecursively()
            removeRecord(id)
        }
    }

    // ======================== 下载（镜像站优先） ========================

    private fun mirrorCandidates(urlStr: String): List<String> {
        val fallback = when {
            urlStr.contains("github.com", ignoreCase = true) ->
                // 实测直连 GitHub 重定向到 CDN (release-assets.githubusercontent.com) ~3.9MB/s 最快，
                // 镜像反而较慢 (~0.1-0.3MB/s)，所以原始 URL 优先
                listOf(urlStr) + GITHUB_MIRRORS.map { it + urlStr }
            urlStr.contains("packages.termux.dev", ignoreCase = true) -> {
                val path = urlStr.substringAfter("packages.termux.dev/apt/termux-main/", "")
                if (path.isBlank()) listOf(urlStr)
                else TERMUX_MIRRORS.map { it + path } + urlStr
            }
            else -> listOf(urlStr)
        }
        // 用户在商店长按安装选定的路线全局生效；auto 时保持上面的多候选顺序。
        return DownloadRoute.candidates(context, urlStr, fallback)
    }

    private suspend fun downloadWithMirrors(
        urlStr: String,
        dest: File,
        expectedSha: String,
        onProgress: (Long, Long) -> Unit,
    ) {
        val candidates = mirrorCandidates(urlStr)
        var lastError: Throwable? = null
        for (candidate in candidates) {
            try {
                downloadOne(candidate, dest, onProgress)
                val sha = sha256Of(dest)
                if (expectedSha.isBlank() || sha.equals(expectedSha.trim(), ignoreCase = true)) return
                lastError = IllegalStateException("sha256 校验失败：$candidate")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("下载失败：$urlStr")
    }

    private suspend fun downloadOne(urlStr: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlStr)
        var conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.requestMethod = "GET"
        var code = conn.responseCode
        var redirects = 0
        while (code in 300..399 && redirects < 6) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = (URL(loc).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000; readTimeout = 60_000; instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT); requestMethod = "GET"
            }
            code = conn.responseCode
            redirects++
        }
        if (code !in 200..299) { conn.disconnect(); error("下载失败 HTTP $code：$urlStr") }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L
        var lastReported = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { output ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buf); if (n < 0) break
                    output.write(buf, 0, n); downloaded += n
                    // 每 512KB 报告一次，避免高频刷新 UI（与 RootfsInstaller 一致）
                    if (downloaded - lastReported >= 512 * 1024 || downloaded == total) {
                        lastReported = downloaded
                        onProgress(downloaded, total)
                    }
                }
                if (lastReported != downloaded || downloaded == 0L) onProgress(downloaded, total)
            }
        }
        conn.disconnect()
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ======================== mcext（ZIP）解压 ========================

    private suspend fun extractMcextPayload(archive: File, payloadDir: File, onProgress: (Int, Int, String?) -> Unit): Int {
        var count = 0
        ZipFile(archive).use { zf ->
            val all = zf.entries().toList()
            val payloadEntries = all.filter { e ->
                val n = e.name.replace('\\', '/')
                n.startsWith("payload/") && n.removePrefix("payload/").isNotEmpty()
            }
            val total = payloadEntries.size
            for (e in payloadEntries) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val name = e.name.replace('\\', '/')
                val rel = name.removePrefix("payload/")
                if (rel.contains("..")) continue
                val outFile = File(payloadDir, rel)
                if (e.isDirectory) { outFile.mkdirs(); continue }
                outFile.parentFile?.mkdirs()
                zf.getInputStream(e).use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                count++
                if (count % 10 == 0 || count == total) onProgress(count, total, rel)
            }
            if (total == 0) onProgress(0, 0, null)
        }
        return count
    }

    // ======================== deb（ar + data.tar.xz）解压 ========================

    /** Termux deb 内 payload 的实际前缀。 */
    private val debPayloadPrefixes = listOf(
        "data/data/com.termux/files/usr/",
        "./data/data/com.termux/files/usr/",
        "data/data/com.termux/files/home/",
    )

    private suspend fun extractDebPayload(archive: File, payloadDir: File, onProgress: (Int, Int, String?) -> Unit): Int {
        // 1) 解析 ar 格式，找到 data.tar.*
        val dataTarBytes = run {
            val bytes = archive.readBytes()
            require(bytes.size > 8 && bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII) == "!<arch>\n") {
                "不是有效的 deb 包"
            }
            var offset = 8
            var found: ByteArray? = null
            while (offset + 60 <= bytes.size) {
                val name = bytes.copyOfRange(offset, offset + 16).toString(Charsets.US_ASCII).trim()
                val size = bytes.copyOfRange(offset + 48, offset + 58).toString(Charsets.US_ASCII).trim().toIntOrNull() ?: 0
                val dataStart = offset + 60
                if (name.startsWith("data.tar")) {
                    found = bytes.copyOfRange(dataStart, minOf(dataStart + size, bytes.size))
                    break
                }
                offset = dataStart + size
                if (offset % 2 == 1) offset++
            }
            found ?: error("deb 包内未找到 data.tar")
        }

        // 2) 解压 data.tar（xz/gz）
        val tarStream: InputStream = when {
            archive.name.endsWith(".deb") -> {
                // 判断压缩格式：xz magic 0xFD 37 7A 58 5A 00 / gzip 1F 8B
                when {
                    dataTarBytes.size > 6 && dataTarBytes[0] == 0xFD.toByte() && dataTarBytes[1] == 0x37.toByte() ->
                        XZInputStream(dataTarBytes.inputStream())
                    dataTarBytes.size > 2 && dataTarBytes[0] == 0x1F.toByte() && dataTarBytes[1] == 0x8B.toByte() ->
                        GZIPInputStream(dataTarBytes.inputStream())
                    else -> dataTarBytes.inputStream()
                }
            }
            else -> dataTarBytes.inputStream()
        }

        // 3) 解析 tar 并映射到 payload
        var count = 0
        tarStream.use { input ->
            val reader = TarReader(input)
            var totalEntries = 0
            var lastReported = 0
            var currentName: String? = null
            while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val header = reader.nextHeader() ?: break
                val path = normalizeTarPath(header.name)
                val mapped = mapDebPath(path) ?: continue
                // tar 里的路径可能含 ..（构造包/恶意包），必须挡在 payload 目录之外，
                // 否则能写到任意位置。mcext 的 ZIP 分支已有同样的检查，deb 这里漏了。
                if (mapped.split('/').any { it == ".." }) continue
                val outFile = File(payloadDir, mapped)
                when (header.type) {
                    TarEntryType.DIRECTORY -> outFile.mkdirs()
                    TarEntryType.SYMLINK -> {
                        outFile.parentFile?.mkdirs()
                        outFile.delete()
                        runCatching { java.nio.file.Files.createSymbolicLink(outFile.toPath(), File(header.linkName).toPath()) }
                    }
                    TarEntryType.FILE -> {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { output -> reader.copyEntryData(output, header.size) }
                        outFile.setExecutable(true, false)
                        count++
                    }
                    else -> reader.skipEntryData(header.size)
                }
                totalEntries++
                if (count % 10 == 0 || count == totalEntries) onProgress(count, totalEntries, mapped)
            }
        }
        return count
    }

    private fun normalizeTarPath(path: String): String =
        path.replace('\\', '/').trim().removePrefix("./").split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /** 把 deb 内 Termux 路径映射到 payload 相对路径。 */
    private fun mapDebPath(path: String): String? {
        for (prefix in debPayloadPrefixes) {
            val normalizedPrefix = prefix.removePrefix("./")
            if (path.startsWith(normalizedPrefix)) {
                return path.removePrefix(normalizedPrefix)
            }
        }
        return null
    }

    // ======================== Tar 解析 ========================

    private enum class TarEntryType { FILE, DIRECTORY, SYMLINK, OTHER }

    private data class TarHeader(
        val name: String,
        val size: Long,
        val type: TarEntryType,
        val linkName: String,
        val mode: Int,
    )

    private class TarReader(private val input: InputStream) {
        private var pendingLongName: String? = null
        private var pendingLongLink: String? = null

        fun nextHeader(): TarHeader? {
            while (true) {
                val block = ByteArray(512)
                val read = readFully(block)
                if (read == 0) return null
                if (read < 512) return null
                if (block.all { it == 0.toByte() }) return null

                val name = block.string(0, 100)
                val prefix = block.string(345, 155)
                val fullName = listOf(prefix, name).filter { it.isNotBlank() }.joinToString("/")
                val typeChar = block[156].toInt().toChar()
                val size = block.octal(124, 12)
                val link = block.string(157, 100)
                val mode = block.octal(100, 8).toInt()

                when (typeChar) {
                    'L' -> { // GNU long name
                        pendingLongName = input.readExactly(size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        input.skipFully(((size + 511) / 512) * 512 - size)
                        continue
                    }
                    'K' -> { // GNU long link
                        pendingLongLink = input.readExactly(size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                        input.skipFully(((size + 511) / 512) * 512 - size)
                        continue
                    }
                    'x' -> { // PAX header: 简单跳过（名称以常规字段为准）
                        input.skipFully(((size + 511) / 512) * 512)
                        continue
                    }
                }

                val finalName = pendingLongName ?: fullName
                val finalLink = pendingLongLink ?: link
                pendingLongName = null
                pendingLongLink = null
                val type = when (typeChar) {
                    '5' -> TarEntryType.DIRECTORY
                    '2' -> TarEntryType.SYMLINK
                    '0', '\u0000' -> TarEntryType.FILE
                    else -> TarEntryType.OTHER
                }
                return TarHeader(finalName, size, type, finalLink, mode)
            }
        }

        fun copyEntryData(output: java.io.OutputStream, size: Long) {
            var remaining = size
            val buf = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) error("tar 数据意外结束")
                output.write(buf, 0, n)
                remaining -= n
            }
            skipPadding(size)
        }

        fun skipEntryData(size: Long) {
            input.skipFully(size)
            skipPadding(size)
        }

        private fun skipPadding(size: Long) {
            val pad = ((size + 511) / 512) * 512 - size
            if (pad > 0) input.skipFully(pad)
        }

        private fun readFully(buffer: ByteArray): Int {
            var offset = 0
            while (offset < buffer.size) {
                val n = input.read(buffer, offset, buffer.size - offset)
                if (n < 0) break
                offset += n
            }
            return offset
        }

        private fun InputStream.readExactly(size: Long): ByteArray {
            require(size <= Int.MAX_VALUE) { "tar 条目过大" }
            val data = ByteArray(size.toInt())
            val read = readFully(data)
            require(read == data.size) { "tar 数据意外结束" }
            return data
        }

        private fun InputStream.skipFully(size: Long) {
            var remaining = size
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped > 0) remaining -= skipped
                else if (read() >= 0) remaining--
                else return
            }
        }

        private fun ByteArray.string(offset: Int, length: Int): String {
            val end = (offset until minOf(offset + length, size)).firstOrNull { this[it] == 0.toByte() } ?: minOf(offset + length, size)
            return copyOfRange(offset, end).toString(Charsets.UTF_8).trim()
        }

        private fun ByteArray.octal(offset: Int, length: Int): Long {
            val text = string(offset, length).trim().trimEnd('\u0000')
            return if (text.isBlank()) 0L else text.toLongOrNull(8) ?: 0L
        }
    }

    // ======================== 环境变量 ========================

    /**
     * 照搬 MobiCode Studio 的 ExtensionEnvironmentGroup.variables 构建逻辑，
     * 在安装时计算并保存每个扩展的环境变量（运行时不再扫描文件）。
     */
    private fun buildExtensionEnvVars(payload: File): Map<String, String> {
        val vars = LinkedHashMap<String, String>()

        // PATH = payload/bin
        val binDir = File(payload, "bin")
        if (binDir.isDirectory) vars["PATH"] = binDir.absolutePath

        // LD_LIBRARY_PATH / LIBRARY_PATH = libraryDirectoriesOf(payload)
        val libDirs = libraryDirectoriesOf(payload)
        if (libDirs.isNotEmpty()) {
            vars["LD_LIBRARY_PATH"] = libDirs.joinToString(":") { it.absolutePath }
            val linkerDirs = (libDirs + androidSystemLibraryDirectories())
                .filter { it.isDirectory }
                .distinctBy { it.absolutePath }
            vars["LIBRARY_PATH"] = linkerDirs.joinToString(":") { it.absolutePath }
        }

        // CPATH = includeDirectoriesOf(payload)
        val includeDirs = includeDirectoriesOf(payload)
        if (includeDirs.isNotEmpty()) vars["CPATH"] = includeDirs.joinToString(":") { it.absolutePath }

        // Termux git 等包需要 GIT_EXEC_PATH 才能找到 git-remote-* helper
        val gitExec = File(payload, "libexec/git-core")
        if (gitExec.isDirectory) vars["GIT_EXEC_PATH"] = gitExec.absolutePath

        // MOBICODE_SYSROOT = sysrootOf(payload)
        val sysroot = listOf("sysroot", "ndk/sysroot")
            .map { File(payload, it) }
            .firstOrNull { it.isDirectory }
        if (sysroot != null) vars["MOBICODE_SYSROOT"] = sysroot.absolutePath

        // EXTENSION_CONFIG_VARIABLES（照搬 MobiCode）
        listOf(
            "etc/tls/openssl.cnf" to "OPENSSL_CONF",
            "etc/tls/cert.pem" to "SSL_CERT_FILE",
            "lib/ossl-modules" to "OPENSSL_MODULES",
            "lib/node_modules" to "NODE_PATH",
        ).forEach { (rel, key) ->
            val f = File(payload, rel)
            if (f.exists()) vars[key] = f.absolutePath
        }

        return vars
    }

    private fun libraryDirectoriesOf(payload: File): List<File> {
        val dirs = mutableListOf<File>()
        listOf(
            "lib",
            "sysroot/usr/lib/aarch64-linux-android",
            "sysroot/usr/lib/arm64-v8a",
            "ndk/sysroot/usr/lib/aarch64-linux-android",
            "ndk/sysroot/usr/lib/arm64-v8a",
            "lib/rustlib/aarch64-linux-android/lib",
        ).forEach { rel -> File(payload, rel).takeIf { it.isDirectory }?.let { dirs += it } }
        File(payload, "lib/clang").takeIf { it.isDirectory }
            ?.listFiles()?.filter { it.isDirectory }?.forEach { dirs += it }
        return dirs
    }

    private fun includeDirectoriesOf(payload: File): List<File> {
        val dirs = mutableListOf<File>()
        listOf(
            "include",
            "include/c++/v1",
            "sysroot/usr/include",
            "sysroot/usr/include/aarch64-linux-android",
            "ndk/sysroot/usr/include",
            "ndk/sysroot/usr/include/aarch64-linux-android",
        ).forEach { rel -> File(payload, rel).takeIf { it.isDirectory }?.let { dirs += it } }
        File(payload, "lib/clang").takeIf { it.isDirectory }
            ?.listFiles()?.filter { it.isDirectory }?.forEach { clangDir ->
                File(clangDir, "include").takeIf { it.isDirectory }?.let { dirs += it }
            }
        return dirs
    }

    private fun androidSystemLibraryDirectories(): List<File> =
        listOf(File("/system/lib64"), File("/system/lib")).filter { it.isDirectory }
}
