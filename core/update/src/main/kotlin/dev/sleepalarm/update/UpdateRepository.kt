package dev.sleepalarm.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.net.HttpURLConnection
import java.security.MessageDigest

class UpdateRepository(private val c: Context) {
    val directory = File(c.filesDir, "updates").apply { mkdirs() }
    val prefs = c.getSharedPreferences("updates-v1", Context.MODE_PRIVATE)
    fun isWatch() = c.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH)
    fun installed(): PackageInfo = c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    fun file(module: String) = File(directory, "$module.apk")
    fun prepared(): UpdateManifest? = runCatching { UpdateManifest.parse(File(directory, "manifest.json").readText()) }.getOrNull()
    fun save(manifest: UpdateManifest) {
        val temp = File(directory, "manifest.part"); temp.writeText(manifest.json())
        check(temp.renameTo(File(directory, "manifest.json")))
    }
    fun status(message: String) { prefs.edit().putString("status", message.take(1000)).apply() }
    fun signers(info: PackageInfo) = info.signingInfo?.apkContentsSigners?.map {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }
    }?.toSet().orEmpty()
    fun verify(file: File, asset: ApkAsset) {
        check(file.length() == asset.bytes && UpdatePolicy.digest(file) == asset.sha256) { "APK 크기/체크섬 불일치. 설치하지 않습니다." }
        val archive = c.packageManager.getPackageArchiveInfo(file.path,
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_CONFIGURATIONS) ?: error("APK를 읽을 수 없습니다.")
        val own = installed()
        val watch = archive.reqFeatures?.any { it.name == PackageManager.FEATURE_WATCH } == true
        check(archive.longVersionCode == asset.versionCode) { "APK 버전이 배포 정보와 다릅니다." }
        check(UpdatePolicy.compatible(if (!isWatch() && asset.module == "wear" && own.longVersionCode == asset.versionCode) own.longVersionCode - 1 else own.longVersionCode, archive.longVersionCode, signers(own), signers(archive),
            archive.packageName, watch, asset.module == "wear")) {
            "업데이트 불가: 서명·패키지·기기 종류 불일치 또는 현재보다 높지 않은 버전입니다. 이전 Actions 임시 서명은 새 고정 키와 호환되지 않을 수 있습니다. 기존 앱을 자동 삭제하지 않습니다."
        }
    }
    private fun connection(url: String): HttpURLConnection {
        var next = URL(url)
        repeat(6) {
            check(next.protocol == "https" && next.userInfo == null) { "HTTPS 다운로드만 허용합니다." }
            val host = next.host
            check(host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"))
            val conn = (next.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 20_000; readTimeout = 45_000
                setRequestProperty("User-Agent", "GalaxyWatchSleepAlarm-Updater")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val redirect = conn.getHeaderField("Location") ?: error("잘못된 다운로드 경로")
                next = URL(next, redirect); conn.disconnect()
            } else { check(code == 200) { "GitHub 응답 $code (미배포/요청 제한/네트워크를 확인하세요)." }; return conn }
        }
        error("다운로드 경로 변경이 너무 많습니다.")
    }
    private fun text(url: String): String {
        val conn = connection(url)
        return try { conn.inputStream.use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(bytes.size() + n <= 65_536); bytes.write(buffer, 0, n) }
            bytes.toString("UTF-8")
        } } finally { conn.disconnect() }
    }
    fun latest(): UpdateManifest {
        val release = JSONObject(text("https://api.github.com/repos/$REPOSITORY/releases/latest"))
        val assets = release.getJSONArray("assets")
        val url = (0 until assets.length()).map { assets.getJSONObject(it) }.single { it.getString("name") == "update.json" }.getString("browser_download_url")
        requireReleaseUrl(url)
        return UpdateManifest.parse(text(url))
    }
    fun download(asset: ApkAsset) {
        val target = file(asset.module)
        if (target.isFile && target.length() == asset.bytes && UpdatePolicy.digest(target) == asset.sha256) { verify(target, asset); return }
        val part = File(directory, "${asset.module}.part"); val conn = connection(asset.url)
        try {
            conn.inputStream.use { input -> part.outputStream().use { UpdatePolicy.copyBounded(input, it, asset.bytes) } }
            verify(part, asset)
            check(part.renameTo(target)) { "APK 저장 실패" }
        } finally { conn.disconnect(); part.delete() }
    }
    companion object { val LOCK = Any() }
}
