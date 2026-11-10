package me.rerere.rikkahub.data.ai.tools.local

import java.io.File
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.localtools.ShellExecutor

/**
 * 代码检索：纯 Kotlin 文件遍历实现（不依赖系统 grep）。
 * Android 的 toybox grep 在部分 ROM / FUSE 存储上会无输出或异常，改为 Java File API 保证 APK 内一致可用。
 */
internal fun buildSearchCodeTool(@Suppress("UNUSED_PARAMETER") executor: ShellExecutor, defaultDir: String): Tool = Tool(
    name = "search_code",
    description = "Search text across files on the device (pure Kotlin, no system grep). Params: pattern(required, text or regex), path(default temporary workspace: $defaultDir), include(glob like *.kt), max_results(default 100), case_sensitive(default false).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("pattern", buildJsonObject { put("type", "string"); put("description", "Text or regex") })
                put("path", buildJsonObject { put("type", "string"); put("description", "Directory to search") })
                put("include", buildJsonObject { put("type", "string"); put("description", "File filter e.g. *.kt") })
                put("max_results", buildJsonObject { put("type", "number"); put("description", "Max matches (default 100)") })
                put("case_sensitive", buildJsonObject { put("type", "boolean"); put("description", "Case sensitive (default false)") })
            },
            required = listOf("pattern"),
        )
    },
    execute = {
        val p = it.jsonObject
        val pattern = p["pattern"]?.jsonPrimitive?.contentOrNull ?: error("pattern is required")
        // 相对路径按默认工作区解析：shell 进程的当前目录不可预期，
        // 直接 File("src") 会落到 / 下找不到文件。
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?.let { if (it.startsWith("/")) it else java.io.File(defaultDir, it).path }
            ?: defaultDir
        val include = p["include"]?.jsonPrimitive?.contentOrNull
        val maxResults = (p["max_results"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100).coerceIn(1, 500)
        val caseSensitive = p["case_sensitive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val output = searchFiles(path, pattern, include, maxResults, caseSensitive)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("pattern", pattern); put("path", path); put("output", output.take(60_000))
        }.toString()))
    }
)

/** 文件树：纯 Kotlin 实现（不依赖 shell find）。 */
internal fun buildFileTreeTool(@Suppress("UNUSED_PARAMETER") executor: ShellExecutor, defaultDir: String): Tool = Tool(
    name = "list_file_tree",
    description = "List a directory tree on the device (pure Kotlin). Params: path(default temporary workspace: $defaultDir), depth(default 3), max_entries(default 300).",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("path", buildJsonObject { put("type", "string"); put("description", "Root dir") })
                put("depth", buildJsonObject { put("type", "number"); put("description", "Max depth (default 3)") })
                put("max_entries", buildJsonObject { put("type", "number"); put("description", "Max entries (default 300)") })
            }
        )
    },
    execute = {
        val p = it.jsonObject
        // 相对路径按默认工作区解析：shell 进程的当前目录不可预期，
        // 直接 File("src") 会落到 / 下找不到文件。
        val path = p["path"]?.jsonPrimitive?.contentOrNull?.takeIf { s -> s.isNotBlank() }
            ?.let { if (it.startsWith("/")) it else java.io.File(defaultDir, it).path }
            ?: defaultDir
        val depth = (p["depth"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 3).coerceIn(1, 10)
        val maxEntries = (p["max_entries"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 300).coerceIn(1, 2000)
        val output = listTree(path, depth, maxEntries)
        listOf(UIMessagePart.Text(buildJsonObject {
            put("path", path); put("output", output.take(60_000))
        }.toString()))
    }
)

/** 遍历目录、按 include glob 过滤并正则匹配行。 */
internal fun searchFiles(
    rootPath: String,
    pattern: String,
    include: String?,
    maxResults: Int,
    caseSensitive: Boolean,
): String {
    val root = File(rootPath)
    if (!root.exists()) return "路径不存在：$rootPath"
    if (!root.isDirectory) return "不是目录：$rootPath"
    // 空正则会匹配每一行，返回满屏无用结果并刷爆上下文，直接拒绝
    if (pattern.isBlank()) return "pattern 不能为空"
    val regex = try {
        if (caseSensitive) Regex(pattern) else Regex(pattern, RegexOption.IGNORE_CASE)
    } catch (e: Exception) {
        return "正则表达式错误：${e.message}"
    }
    val includeRegex = include?.takeIf { it.isNotBlank() }?.let { globToRegex(it) }
    val results = mutableListOf<String>()
    var scanned = 0
    val maxFileSize = 2L * 1024 * 1024
    // 没有匹配时会遍历整棵树，搜 /sdcard 这种大目录能卡几分钟；
    // 限制扫描文件数，到顶就停并说明，避免工具长时间无响应。
    val maxScanFiles = 20_000
    var scanLimitHit = false
    var skippedLarge = 0
    try {
        for (file in root.walkTopDown().onEnter { shouldEnter(it) }) {
            if (results.size >= maxResults) break
            if (scanned >= maxScanFiles) { scanLimitHit = true; break }
            if (!file.isFile) continue
            if (file.length() > maxFileSize) { skippedLarge++; continue }
            if (includeRegex != null && !includeRegex.matches(file.name)) continue
            if (!isLikelyTextFile(file)) continue
            scanned++
            try {
                file.useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (results.size < maxResults && regex.containsMatchIn(line)) {
                            results.add("${file.path}:${index + 1}: ${line.take(300)}")
                        }
                    }
                }
            } catch (_: Exception) {
                // 跳过无法读取的文件
            }
        }
    } catch (_: Exception) {
        // 遍历异常时返回已收集结果
    }
    val body = if (results.isEmpty()) "未找到匹配（扫描 $scanned 个文件）" else results.joinToString("\n")
    // 达到上限要明说，否则模型会把"只看到前 N 条"当成"一共只有 N 条"
    val largeNote = if (skippedLarge > 0) "\n[skipped $skippedLarge file(s) larger than 2MB]" else ""
    return when {
        results.size >= maxResults ->
            "$body\n[truncated: reached max_results=$maxResults, there may be more matches]"
        scanLimitHit ->
            "$body\n[truncated: stopped after scanning $maxScanFiles files, narrow the path or use include]$largeNote"
        else -> body + largeNote
    }
}

/** 遍历目录树（带缩进）。 */
internal fun listTree(rootPath: String, depth: Int, maxEntries: Int): String {
    val root = File(rootPath)
    if (!root.exists()) return "路径不存在：$rootPath"
    if (!root.isDirectory) return "不是目录：$rootPath"
    val sb = StringBuilder()
    var count = 0
    var truncated = false
    val rootDepth = root.absolutePath.count { it == '/' }
    try {
        for (f in root.walkTopDown().maxDepth(depth).onEnter { shouldEnter(it) }) {
            if (count >= maxEntries) { truncated = true; break }
            count++
            val relDepth = f.absolutePath.count { it == '/' } - rootDepth
            val indent = "  ".repeat(relDepth.coerceAtLeast(0))
            sb.append(indent).append(if (f.isDirectory) "[${f.name}]" else f.name).append('\n')
        }
    } catch (_: Exception) {
    }
    val body = sb.toString().ifBlank { "(空目录)" }
    // 达到上限要明说，否则模型会把"只列了前 N 项"当成"只有 N 项"
    return if (truncated) "$body\n[truncated: stopped after $maxEntries entries, there may be more]" else body
}

/** 跳过 VCS/构建产物目录，避免搜索噪音与卡顿。 */
private fun shouldEnter(dir: File): Boolean {
    val name = dir.name
    return name != ".git" && name != "node_modules" && name != "build" && name != ".gradle" && name != ".kotlin"
}

/** 只搜索常见文本文件，跳过二进制（大幅减少无效 IO）。 */
private val TEXT_EXTENSIONS = setOf(
    "kt", "kts", "java", "xml", "json", "md", "txt", "js", "mjs", "cjs", "ts", "tsx", "jsx",
    "html", "htm", "css", "scss", "py", "go", "rs", "c", "h", "cpp", "hpp", "cc", "sh", "bash",
    "yml", "yaml", "toml", "ini", "conf", "properties", "gradle", "sql", "log", "csv", "proto",
    "gitignore", "env", "lua", "php", "rb", "pl", "swift", "dart", "vue", "svelte"
)

private fun isLikelyTextFile(file: File): Boolean {
    val ext = file.extension.lowercase()
    if (ext.isBlank()) return true
    return ext in TEXT_EXTENSIONS
}

/** 简单 glob -> Regex（* 与 ?）。 */
private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    for (ch in glob) {
        when (ch) {
            '*' -> sb.append(".*")
            '?' -> sb.append('.')
            '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> sb.append('\\').append(ch)
            else -> sb.append(ch)
        }
    }
    return Regex(sb.toString(), RegexOption.IGNORE_CASE)
}
