package me.rerere.rikkahub.data.localtools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.AppScope

/**
 * 扩展安装/卸载的进程级队列：并行下载、串行安装。
 *
 * - 页面关掉也不中断：任务跑在 AppScope，不挂 viewModelScope。
 * - 多个包同时点安装：各自开协程并行预下载（ExtensionInstaller.prepare，按包 id 加锁防同包冲突）。
 * - 谁下载完谁进安装队列，pump() 一次只跑一个 installer.install()（解压/赋权/登记串行）。
 * - 状态放单例 StateFlow，重进商店仍能看到进度与结果。
 */
class ToolchainInstallQueue(
    private val context: Context,
    private val appScope: AppScope,
    private val installer: ExtensionInstaller,
) {
    private val _progress = MutableStateFlow<Map<String, InstallProgress>>(emptyMap())
    val progress: StateFlow<Map<String, InstallProgress>> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 等待/正在安装（已下载完）的任务，串行 pump。 */
    private val installPending = java.util.ArrayDeque<InstallTask>()
    /** 所有任务的取消句柄：id -> 下载或安装 job。 */
    private val jobs = mutableMapOf<String, Job>()
    @Volatile private var installing: String? = null

    private data class InstallTask(
        val id: String,
        val label: String,
        val ext: ToolchainExtension,
    )

    /** 点安装：立刻并行开始下载，下载完成后排队等安装。 */
    @Synchronized
    fun enqueueInstall(ext: ToolchainExtension, version: ExtensionVersion) {
        if (_progress.value.containsKey(ext.id)) return
        val target = ext.copy(
            version = version.version,
            downloadUrl = version.downloadUrl,
            sha256 = version.sha256,
            downloadSize = version.downloadSize,
            format = version.format,
        )
        _progress.update { it + (ext.id to InstallProgress(
            id = ext.id,
            phase = InstallPhase.DOWNLOADING,
            downloaded = 0,
            total = version.downloadSize,
            dependencyNote = "准备并行下载…",
        )) }
        // 下载阶段并发：每个任务一个 AppScope 协程，互不等待。
        val job = appScope.launch {
            try {
                val prep = installer.prepare(target) { upd ->
                    _progress.update { it + (ext.id to InstallProgress(
                        id = ext.id, phase = upd.phase,
                        downloaded = upd.downloaded, total = upd.total,
                        dependencyNote = upd.dependencyNote,
                    )) }
                }
                prep.onFailure { throw it }
                // 下载完成，进入串行安装队列
                synchronized(this@ToolchainInstallQueue) {
                    if (_progress.value.containsKey(ext.id)) {
                        _progress.update { it + (ext.id to InstallProgress(
                            id = ext.id,
                            phase = InstallPhase.INSTALLING,
                            dependencyNote = "下载完成，排队安装…",
                        )) }
                        installPending.add(InstallTask(ext.id, ext.name, target))
                    }
                }
                pump()
            } catch (e: CancellationException) {
                cleanup(ext.id, cancelled = true)
            } catch (e: Throwable) {
                cleanup(ext.id, cancelled = false)
                _message.value = "下载失败：${e.message ?: "未知错误"}"
            }
        }
        jobs[ext.id] = job
    }

    /** 卸载直接进串行队列（没有下载阶段）。 */
    @Synchronized
    fun enqueueUninstall(ext: ToolchainExtension) {
        if (_progress.value.containsKey(ext.id)) return
        _progress.update { it + (ext.id to InstallProgress(
            id = ext.id,
            phase = InstallPhase.INSTALLING,
            dependencyNote = "排队卸载…",
        )) }
        // 复用 installPending，用一个特殊标记靠 ext.id 判断；简单起见卸载也走同一泵，
        // 但 uninstall 不需要 prepare，所以单独放一个队列更清楚。
        uninstallPending.add(ext.id to ext.name)
        pump()
    }
    private val uninstallPending = java.util.ArrayDeque<Pair<String, String>>()

    /** 取消：下载中的直接杀协程，安装中的杀 install job。 */
    @Synchronized
    fun cancel(id: String? = null) {
        val targetId = id ?: installing ?: installPending.firstOrNull()?.id
            ?: uninstallPending.firstOrNull()?.first ?: jobs.keys.firstOrNull() ?: return
        val job = jobs[targetId]
        job?.cancel()
        jobs.remove(targetId)
        installPending.removeAll { it.id == targetId }
        uninstallPending.removeAll { it.first == targetId }
        _progress.update { it - targetId }
        appScope.launch(Dispatchers.IO) {
            runCatching { java.io.File(context.appTempFolder, "ext_$targetId").deleteRecursively() }
        }
        _message.value = "已取消安装"
        // 安装 job 的 finally 会清 installing 并 pump；这里兜底：若 job 已不在 map
        //（例如刚结束还没清），手动恢复泵，避免后续任务永远排队。
        if (installing == targetId && job?.isActive != true) {
            installing = null
            pump()
        }
    }

    fun clearMessage() { _message.value = null }

    /** 串行安装泵：一次只跑一个 install()/uninstall()。 */
    @Synchronized
    private fun pump() {
        if (installing != null) return
        val task = installPending.pollFirst()
        if (task == null) {
            val u = uninstallPending.pollFirst() ?: return
            startUninstall(u.first, u.second)
            return
        }
        installing = task.id
        val job = appScope.launch {
            try {
                _message.value = null
                val result = installer.install(task.ext) { upd ->
                    _progress.update { it + (task.id to InstallProgress(
                        id = task.id, phase = upd.phase,
                        downloaded = upd.downloaded, total = upd.total,
                        extracted = upd.extracted, entries = upd.entries,
                        currentEntry = upd.currentEntry,
                        dependencyNote = upd.dependencyNote,
                    )) }
                }
                _progress.update { it - task.id }
                _message.value = result.fold(
                    onSuccess = { "已安装 ${task.label} ${task.ext.version}" },
                    onFailure = { e -> "安装失败：${e.message ?: "未知错误"}" },
                )
            } catch (e: CancellationException) {
                _progress.update { it - task.id }
                _message.value = "已取消安装 ${task.label}"
            } finally {
                synchronized(this@ToolchainInstallQueue) {
                    jobs.remove(task.id)
                    if (installing == task.id) installing = null
                }
                pump()
            }
        }
        jobs[task.id] = job
    }

    private fun startUninstall(id: String, label: String) {
        installing = id
        val job = appScope.launch {
            try {
                _message.value = null
                val result = withContext(Dispatchers.IO) { installer.uninstall(id) }
                _progress.update { it - id }
                _message.value = result.fold(
                    onSuccess = { "已卸载 $label" },
                    onFailure = { e -> "卸载失败：${e.message ?: "未知错误"}" },
                )
            } catch (e: CancellationException) {
                _progress.update { it - id }
                _message.value = "已取消卸载 $label"
            } finally {
                synchronized(this@ToolchainInstallQueue) {
                    jobs.remove(id)
                    if (installing == id) installing = null
                }
                pump()
            }
        }
        jobs[id] = job
    }

    private fun cleanup(id: String, cancelled: Boolean) {
        _progress.update { it - id }
        synchronized(this@ToolchainInstallQueue) { jobs.remove(id) }
        if (cancelled) _message.value = "已取消安装"
    }
}
