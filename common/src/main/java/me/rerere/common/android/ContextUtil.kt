package me.rerere.common.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * App 临时文件目录，统一放在外部存储，不再散落在私有 cache。
 * 优先 /storage/emulated/0/RikkaHub/tmp/（需要「所有文件访问」权限）；
 * 普通权限自动回退到 Android/data/me.rerere.rikkahub/files/tmp（无需权限，普通 App 可写）。
 */
val Context.appTempFolder: File
    get() {
        val publicDir = File("/storage/emulated/0/RikkaHub/tmp")
        val canUsePublic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        val fallback = File(getExternalFilesDir(null) ?: cacheDir, "tmp")
        val preferred = if (canUsePublic) publicDir else fallback
        return runCatching {
            if (!preferred.exists() && !preferred.mkdirs()) error("mkdir failed")
            val probe = File(preferred, ".write_probe_${System.nanoTime()}")
            probe.writeText("x")
            probe.delete()
            preferred
        }.getOrElse {
            fallback.apply { mkdirs() }
        }
    }

fun Context.getCacheDirectory(namespace: String): File {
    val dir = File(cacheDir, "disk_cache/$namespace")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}
