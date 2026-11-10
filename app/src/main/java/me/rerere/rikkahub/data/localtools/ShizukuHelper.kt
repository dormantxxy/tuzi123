package me.rerere.rikkahub.data.localtools

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 授权与探测封装。
 *
 * 关键点（确保 Shizuku 正常使用）：
 * 1. 必须注册 [Shizuku.OnRequestPermissionResultListener] 才能收到授权结果，否则 requestPermission 后应用永远不知道用户是否授权。
 * 2. 必须注册 [Shizuku.OnBinderReceivedListener] / [Shizuku.OnBinderDeadListener]，Shizuku 服务重启后自动重新检测。
 * 3. [permissionGranted] 是全局可观察状态，工具链页面会自动刷新。
 */
object ShizukuHelper {
    private const val TAG = "ShizukuHelper"

    private val _permissionGranted = MutableStateFlow(false)
    /** 当前是否已获得 Shizuku 授权（全局可观察）。 */
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _binderAlive = MutableStateFlow(false)
    /** Shizuku 服务 binder 是否存活（全局可观察）。 */
    val binderAlive: StateFlow<Boolean> = _binderAlive.asStateFlow()

    @Volatile
    private var listenersRegistered = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received, version=" + runCatching { Shizuku.getVersion() }.getOrDefault(-1))
        _binderAlive.value = true
        refreshPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        _binderAlive.value = false
        _permissionGranted.value = false
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.i(TAG, "Shizuku permission result: requestCode=" + requestCode + ", grantResult=" + grantResult)
        _permissionGranted.value = grantResult == PackageManager.PERMISSION_GRANTED
    }

    /** 在 Application.onCreate 调用一次，注册全部监听器。 */
    fun ensureListeners() {
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching { Shizuku.addBinderReceivedListener(binderReceivedListener) }
            .onFailure { Log.e(TAG, "addBinderReceivedListener failed", it) }
        runCatching { Shizuku.addBinderDeadListener(binderDeadListener) }
            .onFailure { Log.e(TAG, "addBinderDeadListener failed", it) }
        runCatching { Shizuku.addRequestPermissionResultListener(permissionResultListener) }
            .onFailure { Log.e(TAG, "addRequestPermissionResultListener failed", it) }
        _binderAlive.value = isBinderAlive()
        refreshPermission()
    }

    /** Shizuku 服务是否可连（服务在运行且 binder 可用）。 */
    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** 是否已经拿到 Shizuku 授权。 */
    fun hasPermission(): Boolean = runCatching {
        isBinderAlive() && (Shizuku.isPreV11() || Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    /** 主动刷新权限状态（授权弹框关闭后调用）。 */
    fun refreshPermission() {
        _binderAlive.value = isBinderAlive()
        _permissionGranted.value = hasPermission()
    }

    /**
     * 主动申请 Shizuku 权限（弹出授权框）。返回是否已具备/已发起。
     */
    fun requestPermission(requestCode: Int = 10086): Boolean = runCatching {
        if (!isBinderAlive()) {
            Log.w(TAG, "requestPermission: Shizuku binder not alive")
            return false
        }
        if (hasPermission()) {
            _permissionGranted.value = true
            return true
        }
        Log.i(TAG, "requestPermission: requesting Shizuku permission, requestCode=" + requestCode)
        Shizuku.requestPermission(requestCode)
        true
    }.getOrDefault(false)

    fun version(): Int = runCatching { if (isBinderAlive()) Shizuku.getVersion() else -1 }.getOrDefault(-1)

    /** 诊断信息（用于界面显示/排查）。 */
    fun diagnose(): String = buildString {
        append("binder=").append(isBinderAlive())
        append(", version=").append(version())
        append(", granted=").append(hasPermission())
    }
}
