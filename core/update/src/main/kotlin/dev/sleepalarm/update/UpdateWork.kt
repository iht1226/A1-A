package dev.sleepalarm.update

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.work.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import java.io.File
import java.util.concurrent.TimeUnit

object UpdateWork {
    const val PERIODIC = "sleep-update-poll"
    fun enqueue(c: Context, action: String = "check", uri: String = "") {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>().setInputData(workDataOf("action" to action, "uri" to uri))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(c).enqueueUniqueWork("sleep-update-$action-$uri", ExistingWorkPolicy.KEEP, request)
    }
    fun automatic(c: Context, enabled: Boolean) {
        UpdateRepository(c).prefs.edit().putBoolean("automatic", enabled).apply()
        val wm = WorkManager.getInstance(c)
        if (!enabled) { wm.cancelUniqueWork(PERIODIC); return }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS).setConstraints(constraints)
                .setInputData(workDataOf("action" to "automatic")).build())
    }
}

class UpdateWorker(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result {
        val repo = UpdateRepository(applicationContext)
        return try {
            synchronized(UpdateRepository.LOCK) {
                when (inputData.getString("action")) {
                    "receive" -> if (repo.isWatch()) receive(repo)
                    "send" -> if (!repo.isWatch()) send(repo)
                    "automatic" -> if (!repo.isWatch() && repo.prefs.getBoolean("automatic", false)) check(repo)
                    else -> if (!repo.isWatch()) check(repo)
                }
            }
            Result.success()
        } catch (e: Exception) {
            repo.status("업데이트 실패: ${e.message ?: e.javaClass.simpleName}")
            // Permanent integrity/signature errors must not spin/re-download. User can retry explicitly.
            if (e is java.io.IOException && runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
    private fun check(repo: UpdateRepository) {
        repo.status("GitHub 공개 Release 확인 중…")
        val manifest = repo.latest(); val installed = repo.installed().longVersionCode
        if (manifest.phone.versionCode < installed) { repo.status("설치된 버전이 배포 버전보다 높습니다. 다운그레이드하지 않습니다."); return }
        if (manifest.phone.versionCode == installed && repo.prepared()?.phone?.versionCode == installed && repo.file("wear").isFile) {
            repo.status("휴대전화는 최신 버전입니다. 준비된 워치 APK를 다시 전송할 수 있습니다."); return
        }
        if (manifest.phone.versionCode > installed) repo.download(manifest.phone)
        repo.download(manifest.wear)
        repo.save(manifest)
        repo.status("${manifest.version} 다운로드·서명 확인 완료. 설치에는 사용자 승인이 필요합니다.")
        UpdateNotice.show(applicationContext, "업데이트 준비됨", "앱을 열어 휴대전화 설치 또는 워치 전송을 선택하세요.")
        runCatching { send(repo) }.onFailure { repo.status("다운로드·서명 확인 완료. 워치 전송 실패: ${it.message?.take(200)}. 전송 버튼으로 재시도하세요.") }
    }
    private fun send(repo: UpdateRepository) {
        val m = repo.prepared() ?: error("먼저 업데이트를 다운로드하세요.")
        repo.verify(repo.file("wear"), m.wear)
        val client = Wearable.getDataClient(applicationContext)
        ParcelFileDescriptor.open(repo.file("wear"), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            val request = PutDataMapRequest.create("/sleep/update/wear/${m.wear.versionCode}").apply {
                dataMap.putString("manifest", m.json()); dataMap.putLong("request", System.currentTimeMillis())
                dataMap.putAsset("apk", Asset.createFromFd(fd))
            }.asPutDataRequest()
            Tasks.await(client.putDataItem(request), 120, TimeUnit.SECONDS)
        }
        repo.status("워치 전송 요청 저장됨. 연결이 복구되면 전달됩니다. 워치 수신 확인은 별도 표시됩니다.")
    }
    private fun receive(repo: UpdateRepository) {
        val uri = Uri.parse(inputData.getString("uri") ?: error("전송 주소 없음"))
        val client = Wearable.getDataClient(applicationContext)
        val item = Tasks.await(client.getDataItem(uri), 30, TimeUnit.SECONDS) ?: error("워치 전송을 찾을 수 없습니다.")
        val map = DataMapItem.fromDataItem(item).dataMap
        val m = UpdateManifest.parse(map.getString("manifest") ?: error("배포 정보 없음"))
        check(uri.path == "/sleep/update/wear/${m.wear.versionCode}")
        if (m.wear.versionCode <= repo.installed().longVersionCode) { Tasks.await(client.deleteDataItems(uri)); return }
        val asset = map.getAsset("apk") ?: error("워치 APK 없음")
        val part = File(repo.directory, "wear.part")
        try {
            val response = Tasks.await(client.getFdForAsset(asset), 60, TimeUnit.SECONDS)
            try { response.inputStream.use { input -> checkNotNull(input); part.outputStream().use { UpdatePolicy.copyBounded(input, it, m.wear.bytes) } } }
            finally { response.release() }
            repo.verify(part, m.wear); check(part.renameTo(repo.file("wear"))); repo.save(m)
        } finally { part.delete() }
        repo.status("${m.version} 워치 APK 준비됨. 이 화면에서 설치를 승인하세요. OS가 외부 설치를 막으면 ADB/Play 경로가 필요합니다.")
        UpdateNotice.show(applicationContext, "워치 업데이트 준비됨", "앱 업데이트 화면에서 설치를 승인하세요.")
        Tasks.await(client.deleteDataItems(uri), 30, TimeUnit.SECONDS)
        val ack = PutDataMapRequest.create("/sleep/update/status").apply {
            dataMap.putString("status", "워치 ${m.version} 수신·서명 확인 완료. 설치 승인은 아직 하지 않았습니다.")
        }.asPutDataRequest()
        Tasks.await(client.putDataItem(ack), 30, TimeUnit.SECONDS)
    }
}

class UpdateListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val repo = UpdateRepository(this)
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            val uri = event.dataItem.uri
            if (repo.isWatch() && uri.path?.startsWith("/sleep/update/wear/") == true)
                UpdateWork.enqueue(this, "receive", uri.toString())
            else if (!repo.isWatch() && uri.path == "/sleep/update/status") {
                val status = DataMapItem.fromDataItem(event.dataItem).dataMap.getString("status").orEmpty().take(500)
                repo.prefs.edit().putString("watchStatus", status).apply()
            }
        }
    }
}
