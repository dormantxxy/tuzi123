package me.rerere.rikkahub.data.localtools

import android.content.Context
import java.io.File

/**
 * 聚合后的一体化"原生工具链环境"。
 * 把所有已装扩展的 payload/bin 汇总成 PATH、payload/lib 汇总成 LD_LIBRARY_PATH。
 * 工作目录/ HOME 统一放到 files/RikkaHub 下（RikkaHub 作为工作目录）。
 */
data class ToolchainEnv(
    val path: String,
    val ldLibraryPath: String,
    val home: String,
    val tmp: String,
    val workDir: String,
    val binDirs: List<String>,
    val libDirs: List<String>,
    // 每个扩展自动追加的环境变量（来自 payload/bin 下 wrapper 脚本的 export 声明）
    val envVars: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = binDirs.isEmpty() && libDirs.isEmpty()
}

object ToolchainEnvironment {
    const val HOST_PATH =
        "/system/bin:/system/xbin:/system/sbin:/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin"

    /** RikkaHub 工作目录（默认 CLI 工作目录）。 */
    fun workDir(context: Context): File =
        File(context.filesDir, "RikkaHub").apply { mkdirs() }

    /**
     * MobiCode Studio 同款基础运行时环境变量（RUNTIME_VARIABLE_ORDER，共 10 个）：
     * HOME / TMPDIR / GOTMPDIR / GOCACHE / GOMODCACHE / CARGO_HOME /
     * MOBICODE_PROJECT_ROOT / MOBICODE_EXEC_DIR / GIT_CONFIG_NOSYSTEM / GIT_CONFIG_GLOBAL。
     */
    fun baseEnvVars(
        context: Context,
        projectRoot: String? = null,
    ): Map<String, String> {
        val base = workDir(context)
        val home = File(base, ".home").apply { mkdirs() }
        val tmp = File(base, ".tmp").apply { mkdirs() }
        fun sub(name: String): String = File(tmp, name).apply { mkdirs() }.absolutePath
        val gitConfig = File(base, ".mobicode/gitconfig").apply {
            parentFile?.mkdirs()
            if (!exists()) {
                runCatching {
                    writeText("[init]\n    defaultBranch = main\n[core]\n    autocrlf = false\n[safe]\n    directory = *\n")
                }
            }
        }
        return linkedMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "GOTMPDIR" to sub("go-tmp"),
            "GOCACHE" to sub("go-cache"),
            "GOMODCACHE" to sub("go-mod-cache"),
            "CARGO_HOME" to sub("cargo-home"),
            "MOBICODE_PROJECT_ROOT" to (projectRoot ?: base.absolutePath),
            "MOBICODE_EXEC_DIR" to (projectRoot ?: base.absolutePath),
            "GIT_CONFIG_NOSYSTEM" to "1",
            "GIT_CONFIG_GLOBAL" to gitConfig.absolutePath,
        )
    }

    fun build(
        context: Context,
        installer: ExtensionInstaller,
        projectRoot: String? = null,
    ): ToolchainEnv {
        val base = workDir(context)
        // 新安装的扩展优先（同名命令以最新安装的为准，如 termux.git 覆盖旧 mobicode git）。
        val home = File(base, ".home").apply { mkdirs() }
        val tmp = File(base, ".tmp").apply { mkdirs() }
        // 按 installedAt 降序，但时间戳可能相同（同一批安装），再按 id 兜底，
        // 否则相同 key 的排序不稳定，同一命令可能在不同次运行里指向不同扩展。
        val records = installer.installed.value.values
            .sortedWith(compareByDescending<InstalledRecord> { it.installedAt }.thenBy { it.id })

        // Termux git 有完整 git-remote-http（>100KB 二进制）；MobiCode git 2.55 只是 180B 脚本（HTTP 不可用）
        fun hasUsableGit(rec: InstalledRecord): Boolean {
            val f = File(rec.payloadDir, "libexec/git-core/git-remote-http")
            return f.isFile && f.length() > 100_000
        }
        val hasUsableTermuxGit = records.any { it.id == "termux.git" && hasUsableGit(it) }

        // 聚合所有已装扩展的 PATH / LD_LIBRARY_PATH / CPATH（照搬 MobiCode enabledEnvironment*Directories）
        val binDirs = records.flatMap { rec ->
            // 已装可用的 Termux git 时，跳过 MobiCode git 的 PATH（其缺 remote-curl）
            if (hasUsableTermuxGit && rec.id == "mobicode.tool.git") emptyList()
            else rec.envVars["PATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        }.distinct()
        // Termux 的 libc++ 必须排在最前：其它扩展（如 ripgrep）自带旧版 libc++_shared.so，
        // 排在前面会导致 node 等 Termux 二进制找不到符号（_ZNSt6__ndk113__hash_memoryEPKvm）。
        val termuxLibcDirs = records
            .filter { it.id.startsWith("termux.libc") }
            .flatMap { rec -> rec.envVars["LD_LIBRARY_PATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList() }
        val otherLibDirs = records
            .filterNot { it.id.startsWith("termux.libc") }
            .flatMap { rec -> rec.envVars["LD_LIBRARY_PATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList() }
        val libDirs = (termuxLibcDirs + otherLibDirs).distinct()
        val includeDirs = records.flatMap { rec ->
            rec.envVars["CPATH"]?.split(":")?.filter { it.isNotBlank() } ?: emptyList()
        }.distinct()

        // 最终环境变量 = 基础 10 个 + 全局聚合 + 各扩展的独立变量
        val envVars = LinkedHashMap<String, String>()
        envVars.putAll(baseEnvVars(context, projectRoot))
        envVars["PATH"] = (binDirs + HOST_PATH).joinToString(":")
        if (libDirs.isNotEmpty()) {
            envVars["LD_LIBRARY_PATH"] = libDirs.joinToString(":")
            envVars["LIBRARY_PATH"] = libDirs.joinToString(":")
        }
        if (includeDirs.isNotEmpty()) envVars["CPATH"] = includeDirs.joinToString(":")
        // 扩展自己的非聚合变量（OPENSSL_CONF / SSL_CERT_FILE / OPENSSL_MODULES / NODE_PATH / MOBICODE_SYSROOT）
        for (rec in records) {
            // Termux git 需要 GIT_EXEC_PATH 才能找到 git-remote-https 等 helper
            // 只取有真实 git-remote-http 二进制的扩展（MobiCode git 的只是脚本，跳过）
            if (envVars["GIT_EXEC_PATH"].isNullOrBlank() && hasUsableGit(rec)) {
                envVars["GIT_EXEC_PATH"] = File(rec.payloadDir, "libexec/git-core").absolutePath
            }
            rec.envVars.forEach { (k, v) ->
                if (k != "PATH" && k != "LD_LIBRARY_PATH" && k != "LIBRARY_PATH" && k != "CPATH") {
                    // SSL_CERT_FILE 优先取 ca-certificates 的证书，避免被其他扩展覆盖
                    if (k == "SSL_CERT_FILE" && !envVars["SSL_CERT_FILE"].isNullOrBlank()) {
                        return@forEach
                    }
                    envVars[k] = v
                }
            }
        }
        // Termux git 硬编码 templates 路径 /data/data/com.termux/files/usr/share/git-core/templates，
        // 在 RikkaHub 里不存在，每次 git 操作都会 warning。指向一个存在的空目录即可消除。
        if (envVars["GIT_TEMPLATE_DIR"].isNullOrBlank()) {
            envVars["GIT_TEMPLATE_DIR"] = File(base, ".tmp/git-templates").apply { mkdirs() }.absolutePath
        }

        // 优先使用 ca-certificates 的证书（即使其他扩展自带 cert，也用它保证 HTTPS 兼容）
        val caCert = File(context.filesDir, "extensions/termux.ca.certificates")
            .listFiles()?.asSequence()
            ?.map { File(it, "payload/etc/tls/cert.pem") }
            ?.firstOrNull { it.isFile }
        if (caCert != null) {
            envVars["SSL_CERT_FILE"] = caCert.absolutePath
            // libcurl / git 使用单独的 CA 环境变量，缺一不可
            envVars["CURL_CA_BUNDLE"] = caCert.absolutePath
            envVars["GIT_SSL_CAINFO"] = caCert.absolutePath
        }

        return ToolchainEnv(
            path = envVars["PATH"] ?: HOST_PATH,
            ldLibraryPath = envVars["LD_LIBRARY_PATH"] ?: "",
            home = home.absolutePath,
            tmp = tmp.absolutePath,
            workDir = base.absolutePath,
            binDirs = binDirs,
            libDirs = libDirs,
            envVars = envVars,
        )
    }

}
