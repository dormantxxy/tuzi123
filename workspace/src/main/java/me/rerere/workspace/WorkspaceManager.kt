package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class WorkspaceManager(
    private val baseDir: File,
    private val config: WorkspaceConfig = WorkspaceConfig(),
    private val shellRunner: WorkspaceShellRunner = HostShellRunner(),
    private val bindMounts: List<WorkspaceBindMount> = emptyList(),
    /** 工具链工作区（WorkspaceType.TOOLCHAIN）使用的执行器；为 null 时退回 [shellRunner]。 */
    private val toolchainShellRunner: WorkspaceShellRunner? = null,
    /**
     * 工具链工作区的公共根目录（如 /storage/emulated/0/RikkaHub/workspaces）。
     * 为 null 或不可写时退回 [baseDir]。有 root/Shizuku 时用户文件放外部，方便查看。
     */
    private val toolchainBaseDir: File? = null,
) {
    /** 按工作区类型选择执行器。 */
    private fun runnerFor(type: WorkspaceType): WorkspaceShellRunner =
        if (type == WorkspaceType.TOOLCHAIN) toolchainShellRunner ?: shellRunner else shellRunner
    private val fileSystem = WorkspaceFileSystem(config)

    // 按 target 长度降序, 保证 /a/b 优先于 /a 匹配
    private val sortedBindMounts = bindMounts.sortedByDescending { it.target.trimEnd('/').length }

    val layout = WorkspaceLayout(
        workspaceDir = ROOTFS_WORKSPACE_DIR,
        writableRoots = listOf(ROOTFS_WORKSPACE_DIR, "/tmp") +
            bindMounts.filter { it.writable }.map { it.target.trimEnd('/') },
    )

    init {
        baseDir.mkdirs()
    }

    fun backend(
        root: String,
        shellCompatibilityMode: Boolean = false,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): WorkspaceBackend =
        LocalWorkspaceBackend(this, root, shellCompatibilityMode, type)

    /**
     * 某个工作区应该用的根目录：工具链工作区优先用公共目录。
     * 公共目录在外部存储上，只有拿到「所有文件访问」才可写。
     * 拿不到时必须回退到 App 私有目录，否则工具链工作区的文件区完全不可用。
     */
    private fun baseDirFor(type: WorkspaceType): File {
        val external = toolchainBaseDir
        if (type == WorkspaceType.TOOLCHAIN && external != null && isWritableDirCached(external)) return external
        return baseDir
    }

    /**
     * 探针结果缓存：filesDir 在列表刷新等场景调用很频繁，不能每次都写盘探测。
     * 可写结果长期有效；不可写只保留 1 秒——否则用户刚授予「所有文件访问」权限时，
     * 缓存里的 false 会让工具链工作区一直回退到私有目录，直到进程重启才恢复。
     */
    @Volatile
    private var writableProbe: Triple<String, Boolean, Long>? = null

    private fun isWritableDirCached(dir: File): Boolean {
        val key = dir.absolutePath
        val now = System.currentTimeMillis()
        writableProbe?.let { (cachedKey, cachedResult, cachedAt) ->
            if (cachedKey == key && (cachedResult || now - cachedAt < 1_000L)) return cachedResult
        }
        val result = runCatching {
            dir.mkdirs()
            val probe = File(dir, ".probe_" + System.nanoTime())
            probe.writeText("x")
            probe.delete()
            true
        }.getOrDefault(false)
        writableProbe = Triple(key, result, now)
        return result
    }

    fun ensureWorkspace(root: String, type: WorkspaceType = WorkspaceType.LOCAL): File {
        val dir = workspaceDir(root, type)
        filesDir(root, type).mkdirs()
        // 工具链工作区没有 Rootfs，不创建 linux/ 和 tmp/
        if (type != WorkspaceType.TOOLCHAIN) {
            linuxDir(root, type).mkdirs()
            tempDir(root, type).mkdirs()
        }
        return dir
    }

    fun workspaceDir(root: String, type: WorkspaceType = WorkspaceType.LOCAL): File {
        requireValidRoot(root)
        return File(baseDirFor(type), root)
    }

    fun filesDir(root: String, type: WorkspaceType = WorkspaceType.LOCAL): File =
        File(workspaceDir(root, type), FILES_DIR)

    fun linuxDir(root: String, type: WorkspaceType = WorkspaceType.LOCAL): File =
        File(workspaceDir(root, type), LINUX_DIR)

    fun tempDir(root: String, type: WorkspaceType = WorkspaceType.LOCAL): File =
        File(workspaceDir(root, type), TEMP_DIR)

    fun hasRootfs(root: String, type: WorkspaceType = WorkspaceType.LOCAL): Boolean = isUsableRootfs(linuxDir(root, type))

    fun rootfsShell(root: String, type: WorkspaceType = WorkspaceType.LOCAL): String = rootfsShell(linuxDir(root, type))

    fun deleteWorkspace(root: String, type: WorkspaceType = WorkspaceType.LOCAL): Boolean = workspaceDir(root, type).deleteRecursively()

    fun listFiles(
        root: String,
        path: String = "",
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): List<WorkspaceFileEntry> =
        fileSystem.list(areaDir(root, area, type), path)

    fun readText(
        root: String,
        path: String,
        charset: Charset = StandardCharsets.UTF_8,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): String = fileSystem.readText(filesDir(root, type), path, charset)

    fun writeText(
        root: String,
        path: String,
        text: String,
        overwrite: Boolean = true,
        charset: Charset = StandardCharsets.UTF_8,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): WorkspaceFileEntry = fileSystem.writeText(filesDir(root, type), path, text, overwrite, charset)

    fun importFile(
        root: String,
        destinationPath: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        fileName: String,
        inputStream: InputStream,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): WorkspaceFileEntry {
        val areaRoot = areaDir(root, area, type)
        val targetPath = if (destinationPath.isBlank()) fileName else "$destinationPath/$fileName"
        return fileSystem.importBytes(areaRoot, targetPath, inputStream)
    }

    fun fileSize(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): Long {
        val file = fileSystem.resolve(areaDir(root, area, type), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file.length()
    }

    fun resolveFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): File {
        val file = fileSystem.resolve(areaDir(root, area, type), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        return file
    }

    fun exportFile(
        root: String,
        path: String,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        outputStream: OutputStream,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ) {
        val file = fileSystem.resolve(areaDir(root, area, type), path)
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a file: $path" }
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /**
     * 把 Rootfs 内的绝对路径映射到宿主机上的真实文件。
     *
     * bind mount 的 source 本身就是 Android 侧的普通目录, 因此 /skills 这类挂载路径
     * 可以直接用文件 IO 访问, 无需经过 PRoot; 只是 Rootfs 目录里对应位置是个空挂载点,
     * 按 [WorkspaceStorageArea.LINUX] 解析必然落空。
     */
    fun resolveRootfsPath(root: String, path: String, type: WorkspaceType = WorkspaceType.LOCAL): RootfsLocation {
        val trimmed = path.trim().trimEnd('/').ifBlank { "/" }
        require(trimmed.startsWith("/")) { "Rootfs path must be absolute: $path" }

        sortedBindMounts.forEach { mount ->
            val target = mount.target.trimEnd('/')
            if (trimmed == target) return RootfsLocation(mount.source, "")
            if (trimmed.startsWith("$target/")) {
                return RootfsLocation(mount.source, trimmed.removePrefix("$target/"))
            }
        }

        if (trimmed == ROOTFS_WORKSPACE_DIR || trimmed.startsWith("$ROOTFS_WORKSPACE_DIR/")) {
            return RootfsLocation(
                rootDir = filesDir(root, type),
                relativePath = trimmed.removePrefix(ROOTFS_WORKSPACE_DIR).trimStart('/'),
            )
        }

        // 内核伪文件系统: 显式拒绝, 而不是回落到一个必然读不到的物理路径
        KERNEL_FS_MOUNTS.firstOrNull { trimmed == it || trimmed.startsWith("$it/") }?.let {
            error("$it is a kernel filesystem and cannot be read as a file, use workspace_shell instead")
        }

        return RootfsLocation(linuxDir(root, type), trimmed.trimStart('/'))
    }

    fun rootfsFileSize(root: String, path: String, type: WorkspaceType = WorkspaceType.LOCAL): Long =
        resolveRootfsFile(root, path).also { it.requireReadableFile(path) }.length()

    fun exportRootfsFile(root: String, path: String, outputStream: OutputStream, type: WorkspaceType = WorkspaceType.LOCAL) {
        val file = resolveRootfsFile(root, path)
        file.requireReadableFile(path)
        outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
    }

    /** Rootfs 内绝对路径对应的宿主机文件, 不检查是否存在 */
    fun resolveRootfsFile(root: String, path: String, type: WorkspaceType = WorkspaceType.LOCAL): File {
        val location = resolveRootfsPath(root, path, type)
        return fileSystem.resolve(location.rootDir, location.relativePath)
    }

    private fun File.requireReadableFile(path: String) {
        require(exists()) { "File does not exist: $path" }
        require(isFile) { "Path is not a file: $path" }
    }

    fun deleteFile(
        root: String,
        path: String,
        recursive: Boolean = false,
        area: WorkspaceStorageArea = WorkspaceStorageArea.FILES,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): Boolean =
        fileSystem.delete(areaDir(root, area, type), path, recursive)

    fun moveFile(
        root: String,
        source: String,
        target: String,
        overwrite: Boolean = false,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): WorkspaceFileEntry = fileSystem.move(filesDir(root, type), source, target, overwrite)

    fun glob(
        root: String,
        pattern: String,
        path: String = "",
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): List<WorkspaceFileEntry> = fileSystem.glob(filesDir(root, type), pattern, path)

    fun grep(
        root: String,
        query: String,
        path: String = "",
        regex: Boolean = false,
        ignoreCase: Boolean = true,
        includeGlob: String? = null,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): List<WorkspaceSearchMatch> =
        fileSystem.grep(filesDir(root, type), query, path, regex, ignoreCase, includeGlob)

    fun executeCommand(
        root: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
        shellCompatibilityMode: Boolean = false,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): WorkspaceCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        val workingDir = fileSystem.resolve(filesDir(root, type), cwd)
        require(workingDir.exists()) { "Working directory does not exist: $cwd" }
        require(workingDir.isDirectory) { "Working path is not a directory: $cwd" }

        return runnerFor(type).execute(
            WorkspaceShellContext(
                root = root,
                command = command,
                cwd = cwd,
                filesDir = filesDir(root, type),
                linuxDir = linuxDir(root, type),
                tempDir = tempDir(root, type),
                workingDir = workingDir,
                timeoutMillis = timeoutMillis,
                stdin = stdin,
                bindMounts = bindMounts,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )
    }

    /** 交互式终端启动前的准备: 补齐目录, 并按当前网络刷新 Rootfs 的 DNS 等配置 */
    fun prepareTerminal(root: String, nameservers: List<String>, type: WorkspaceType = WorkspaceType.LOCAL) {
        ensureWorkspace(root, type)
        bindMounts.forEach { it.source.mkdirs() }
        RootfsPatcher().patch(linuxDir(root, type), RootfsPatchOptions(nameservers = nameservers))
    }

    fun terminalLaunch(
        root: String,
        shellCompatibilityMode: Boolean,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): WorkspaceTerminalLaunch? =
        runnerFor(type).terminalLaunch(
            WorkspaceTerminalContext(
                filesDir = filesDir(root, type),
                linuxDir = linuxDir(root, type),
                tempDir = tempDir(root, type),
                bindMounts = bindMounts,
                shellCompatibilityMode = shellCompatibilityMode,
            )
        )

    private fun requireValidRoot(root: String) {
        require(root.matches(ROOT_NAME_REGEX)) {
            "Invalid workspace root name: $root"
        }
    }

    private fun areaDir(
        root: String,
        area: WorkspaceStorageArea,
        type: WorkspaceType = WorkspaceType.LOCAL,
    ): File = when (area) {
        WorkspaceStorageArea.FILES -> filesDir(root, type)
        // 工具链工作区没有 Rootfs，第二个区域固定指向扩展安装目录（运行环境）
        WorkspaceStorageArea.LINUX ->
            if (type == WorkspaceType.TOOLCHAIN) TOOLCHAIN_ROOT_DIR else linuxDir(root, type)
    }


    fun cleanupAllTempDirs() {
        val dirs = listOfNotNull(
            baseDir to WorkspaceType.LOCAL,
            toolchainBaseDir?.takeIf { it.exists() }?.let { it to WorkspaceType.TOOLCHAIN },
        )
        for ((base, type) in dirs) {
            val roots = base.listFiles()?.filter { it.isDirectory } ?: continue
            for (dir in roots) {
                val root = dir.name
                if (!root.matches(ROOT_NAME_REGEX)) continue
                tempDir(root, type).let { if (it.exists()) it.deleteRecursively() }
                File(linuxDir(root, type), "tmp").let { if (it.exists()) it.deleteRecursively() }
                File(linuxDir(root, type), "var/tmp").let { if (it.exists()) it.deleteRecursively() }
            }
        }
    }

    companion object {
        /** 扩展安装目录（工具链运行环境）。 */
        val TOOLCHAIN_ROOT_DIR: File
            get() = File("/data/user/0/me.rerere.rikkahub/files/extensions")

        private const val FILES_DIR = "files"
        private const val LINUX_DIR = "linux"
        private const val TEMP_DIR = "tmp"
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L

        /** Rootfs 内工作区文件区的挂载点 */
        const val ROOTFS_WORKSPACE_DIR = "/workspace"

        /** Rootfs 内执行命令时的 HOME */
        const val ROOTFS_HOME_DIR = "/root"

        /** 由宿主机透传的内核伪文件系统, 只能通过 shell 访问 */
        val KERNEL_FS_MOUNTS = listOf("/dev", "/proc", "/sys")

        private val ROOT_NAME_REGEX = Regex("[A-Za-z0-9._-]+")

        /** Rootfs 是否可用, 以 /bin/sh 存在为准 */
        fun isUsableRootfs(linuxDir: File): Boolean =
            linuxDir.isDirectory && linuxDir.hasRootfsEntry("bin/sh")

        /** Rootfs 内使用的 shell: 优先 bash, 没有时 (如 Alpine minirootfs) 回退到 /bin/sh */
        fun rootfsShell(linuxDir: File): String =
            if (linuxDir.hasRootfsEntry("bin/bash")) "/bin/bash" else "/bin/sh"

        // Alpine 等发行版的 /bin/sh 是指向 /bin/busybox 的绝对路径软链, 在宿主机上解析不到目标,
        // 只有 proot 内才能正确解析, 所以软链本身存在即视为存在
        private fun File.hasRootfsEntry(path: String): Boolean {
            val entry = File(this, path)
            return entry.isFile || Files.isSymbolicLink(entry.toPath())
        }
    }
}

/** Rootfs 内绝对路径在宿主机上的落点 */
data class RootfsLocation(
    val rootDir: File,
    val relativePath: String,
)
