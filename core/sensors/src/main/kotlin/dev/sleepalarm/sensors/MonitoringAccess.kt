package dev.sleepalarm.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object MonitoringAccess {
    fun motionPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    // ACTIVITY_RECOGNITION is a documented health-FGS prerequisite; no restricted-permission bypass.
    fun canMonitor(context: Context) = motionPermission(context) || PassiveHeartMonitor.permitted(context)
}
