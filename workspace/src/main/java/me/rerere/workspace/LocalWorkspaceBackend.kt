package me.rerere.workspace

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** 本机上的工作区: 文件落在 [WorkspaceManager] 管理的目录下, 命令在 PRoot Rootfs 内执行 */
class LocalWorkspaceBackend(
    private val manager: WorkspaceManager,
    private val root: String,
    private val shellCompatibilityMode: Boolean = false,
    private val type: WorkspaceType = WorkspaceType.LOCAL,
) : WorkspaceBackend {
    override val layout: WorkspaceLayout
        get() = manager.layout

    override fun shell(): String = manager.rootfsShell(root, type)

    override fun listFiles(area: WorkspaceStorageArea, path: String): List<WorkspaceFileEntry> {
        manager.ensureWorkspace(root, type)
        return manager.listFiles(root, path, area, type)
    }

    override fun readText(path: String): String {
        manager.ensureWorkspace(root, type)
        return manager.readText(root, path, type = type)
    }

    override fun writeText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        manager.ensureWorkspace(root, type)
        return manager.writeText(root, path, text, overwrite, type = type)
    }

    override fun importFile(
        area: WorkspaceStorageArea,
        destinationPath: String,
        fileName: String,
        inputStream: InputStream,
    ): WorkspaceFileEntry {
        manager.ensureWorkspace(root, type)
        return manager.importFile(root, destinationPath, area, fileName, inputStream, type)
    }

    override fun fileSize(area: WorkspaceStorageArea, path: String): Long =
        manager.fileSize(root, path, area, type)

    override fun exportFile(area: WorkspaceStorageArea, path: String, outputStream: OutputStream) =
        manager.exportFile(root, path, area, outputStream, type)

    override fun deleteFile(area: WorkspaceStorageArea, path: String, recursive: Boolean): Boolean =
        manager.deleteFile(root, path, recursive, area, type)

    override fun absoluteFileSize(path: String): Long {
        manager.ensureWorkspace(root, type)
        return manager.rootfsFileSize(root, path, type)
    }

    override fun exportAbsoluteFile(path: String, outputStream: OutputStream) {
        manager.ensureWorkspace(root, type)
        manager.exportRootfsFile(root, path, outputStream, type)
    }

    // 在 Rootfs 内用 shell 写入: Rootfs 里的绝对路径软链只有 PRoot 内才能正确解析
    override fun writeAbsoluteText(path: String, text: String, overwrite: Boolean): WorkspaceFileEntry {
        val pathArg = path.shellQuote()
        val result = runCommand(
            action = "Write file",
            command = """
                if [ -e $pathArg ] && [ ${if (overwrite) 0 else 1} = 1 ]; then
                  printf '%s\n' ${"File already exists: $path".shellQuote()} >&2
                  exit 1
                fi
                if [ -e $pathArg ] && [ ! -f $pathArg ]; then
                  printf '%s\n' ${"Path is not a file: $path".shellQuote()} >&2
                  exit 1
                fi
                parent=${'$'}(dirname -- $pathArg) || exit 1
                mkdir -p -- "${'$'}parent" || exit 1
                cat > $pathArg || exit 1
                if [ -d $pathArg ]; then entry_type=d; else entry_type=f; fi
                entry_size=${'$'}(stat -c '%s' -- $pathArg) || exit 1
                entry_mtime=${'$'}(stat -c '%Y' -- $pathArg) || exit 1
                printf '%s\0%s\0%s\0%s\0' "${'$'}entry_type" "${'$'}entry_size" "${'$'}entry_mtime" $pathArg
            """.trimIndent(),
            stdin = text.toByteArray(Charsets.UTF_8),
        )
        return result.stdout.parseEntry()
    }

    override fun localFile(area: WorkspaceStorageArea, path: String): File {
        manager.ensureWorkspace(root, type)
        return manager.resolveFile(root, path, area, type)
    }

    override fun localAbsoluteFile(path: String): File = manager.resolveRootfsFile(root, path, type)

    override fun executeCommand(
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray?,
    ): WorkspaceCommandResult {
        manager.ensureWorkspace(root, type)
        return manager.executeCommand(
            root = root,
            command = command,
            cwd = cwd,
            timeoutMillis = timeoutMillis,
            stdin = stdin,
            shellCompatibilityMode = shellCompatibilityMode,
            type = type,
        )
    }

    private fun runCommand(action: String, command: String, stdin: ByteArray?): WorkspaceCommandResult {
        val result = executeCommand(command, cwd = "", WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS, stdin)
        if (result.timedOut) {
            error("$action timed out")
        }
        if (result.exitCode != 0) {
            val message = result.stderr.ifBlank { result.stdout }.trim()
            error(if (message.isBlank()) "$action failed with exit code ${result.exitCode}" else message)
        }
        if (result.truncated) {
            error("$action output is too large")
        }
        return result
    }

    private fun String.parseEntry(): WorkspaceFileEntry {
        val fields = split('\u0000').dropLastWhile { it.isEmpty() }
        require(fields.size == 4) { "Invalid file metadata output" }
        val (type, size, mtime, path) = fields
        return WorkspaceFileEntry(
            path = path,
            name = path.trimEnd('/').substringAfterLast('/').ifBlank { "/" },
            isDirectory = type == "d",
            sizeBytes = size.toLongOrNull() ?: error("Invalid file size: $size"),
            updatedAt = (mtime.toLongOrNull() ?: error("Invalid file mtime: $mtime")) * 1_000L,
        )
    }
}
