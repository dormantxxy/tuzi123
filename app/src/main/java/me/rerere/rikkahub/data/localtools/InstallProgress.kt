package me.rerere.rikkahub.data.localtools

/**
 * 商店安装/卸载进度。放在 data 层，进程级队列和 UI 都能共用，
 * 避免 data 层反向依赖 ui.pages 包。
 */
data class InstallProgress(
    val id: String,
    val phase: InstallPhase,
    val downloaded: Long = 0,
    val total: Long = 0,
    val extracted: Int = 0,
    val entries: Int = 0,
    val currentEntry: String? = null,
    val dependencyNote: String? = null,
) {
    val percent: Int get() = when (phase) {
        InstallPhase.DOWNLOADING -> if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        InstallPhase.EXTRACTING -> if (entries > 0) ((extracted * 100) / entries).toInt().coerceIn(0, 100) else 0
        InstallPhase.INSTALLING -> 100
    }
    val text: String get() = when (phase) {
        InstallPhase.DOWNLOADING -> if (total > 0) "下载中 ${percent}%  ${humanSize(downloaded)}/${humanSize(total)}" else "下载中 ${humanSize(downloaded)}"
        InstallPhase.EXTRACTING -> if (entries > 0) {
            val entry = currentEntry?.let { " · " + it.take(40) } ?: ""
            "解压中 ${percent}%  $extracted/$entries$entry"
        } else "解压中…"
        InstallPhase.INSTALLING -> dependencyNote ?: "安装中（赋权限/登记）…"
    }
}

fun humanSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0; val mb = kb / 1024.0; val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> "%.2f GB".format(gb)
        mb >= 1.0 -> "%.1f MB".format(mb)
        kb >= 1.0 -> "%.0f KB".format(kb)
        else -> "$bytes B"
    }
}
