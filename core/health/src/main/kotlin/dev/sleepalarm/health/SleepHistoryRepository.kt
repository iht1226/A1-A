package dev.sleepalarm.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.sleepalarm.model.*
import java.time.Instant

/** Phone-only adapter. Samsung Health sleep is historical data, never a live sensor input. */
class SleepHistoryRepository(private val context: Context) {
    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)
    val readPermissions = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))
    private fun client() = HealthConnectClient.getOrCreate(context)
    suspend fun granted(): Boolean = sdkStatus() == HealthConnectClient.SDK_AVAILABLE &&
        client().permissionController.getGrantedPermissions().containsAll(readPermissions)

    suspend fun readSamsungSleep(start: Instant, end: Instant): List<SleepHistory> {
        check(granted()) { "Health Connect 수면 읽기 권한이 필요합니다." }
        val result = mutableListOf<SleepHistory>()
        var token: String? = null
        do {
            val page = client().readRecords(ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end),
                dataOriginFilter = setOf(DataOrigin("com.sec.android.app.shealth")),
                pageSize = 100,
                pageToken = token,
            ))
            result += page.records.map { r ->
                SleepHistory(r.metadata.id, r.startTime, r.endTime, r.metadata.dataOrigin.packageName,
                    r.stages.map { StageInterval(it.startTime, it.endTime, stage(it.stage)) })
            }
            token = page.pageToken
        } while (token != null)
        return result.distinctBy { it.id }.sortedByDescending { it.start }
    }

    suspend fun revoke() { if (sdkStatus() == HealthConnectClient.SDK_AVAILABLE) client().permissionController.revokeAllPermissions() }

    private fun stage(value: Int): HistoricalStage = when (value) {
        SleepSessionRecord.STAGE_TYPE_AWAKE -> HistoricalStage.AWAKE
        SleepSessionRecord.STAGE_TYPE_SLEEPING -> HistoricalStage.SLEEPING
        SleepSessionRecord.STAGE_TYPE_LIGHT -> HistoricalStage.LIGHT
        SleepSessionRecord.STAGE_TYPE_DEEP -> HistoricalStage.DEEP
        SleepSessionRecord.STAGE_TYPE_REM -> HistoricalStage.REM
        SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> HistoricalStage.OUT_OF_BED
        else -> HistoricalStage.UNKNOWN
    }
}
