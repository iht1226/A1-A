package dev.sleepalarm.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.guava.await

object PassiveHeartMonitor {
    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_GRANTED &&
            (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
                context, Manifest.permission.BODY_SENSORS_BACKGROUND,
            ) == PackageManager.PERMISSION_GRANTED)

    suspend fun register(context: Context) {
        check(permitted(context)) { SensorPermissionStatus.inspect(context).explanation }
        val client = HealthServices.getClient(context).passiveMonitoringClient
        val capabilities = client.getCapabilitiesAsync().await()
        check(DataType.HEART_RATE_BPM in capabilities.supportedDataTypesPassiveMonitoring) { "수동 심박 측정 미지원" }
        client.setPassiveListenerServiceAsync(HeartListenerService::class.java,
            PassiveListenerConfig.builder().setDataTypes(setOf(DataType.HEART_RATE_BPM)).build()).await()
    }
    suspend fun unregister(context: Context) {
        HealthServices.getClient(context).passiveMonitoringClient.clearPassiveListenerServiceAsync().await()
    }
}
