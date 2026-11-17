package dev.sleepalarm.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Build
import androidx.core.content.ContextCompat

object SensorPermissionStatus {
    private fun preferences(context: Context) = context.getSharedPreferences("sensor-permission-requests", Context.MODE_PRIVATE)

    fun markBackgroundRequested(context: Context) {
        preferences(context).edit().putBoolean("backgroundRequested", true).commit()
    }

    fun inspect(context: Context): SensorAccess {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val body = granted(Manifest.permission.BODY_SENSORS)
        val background = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.BODY_SENSORS_BACKGROUND)
        val restriction: Boolean? = if (Build.VERSION.SDK_INT >= 33 && body && !background) {
            runCatching {
                val pm = context.packageManager
                val permission = Manifest.permission.BODY_SENSORS_BACKGROUND
                @Suppress("DEPRECATION")
                val hardRestricted = pm.getPermissionInfo(permission, 0).flags and PermissionInfo.FLAG_HARD_RESTRICTED != 0
                if (!hardRestricted) false else {
                    // Some installers/OS builds do not allow this query. Unknown stays unknown.
                    val flags = PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER or
                        PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM or PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE
                    pm.getWhitelistedRestrictedPermissions(context.packageName, flags)?.let { permission !in it }
                }
            }.getOrNull()
        } else null
        return SensorAccessPolicy.evaluate(Build.VERSION.SDK_INT, body, background,
            preferences(context).getBoolean("backgroundRequested", false), restriction)
    }
}
