package dev.sleepalarm.update

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.io.File
import java.io.InputStream
import java.io.OutputStream

const val REPOSITORY = "iht1226/A1-A"
const val MAX_APK_BYTES = 100L * 1024 * 1024

data class ApkAsset(val module: String, val versionCode: Long, val sha256: String, val bytes: Long, val url: String) {
    fun json() = JSONObject().put("module", module).put("versionCode", versionCode)
        .put("sha256", sha256).put("bytes", bytes).put("url", url)
    companion object {
        fun parse(j: JSONObject): ApkAsset {
            val a = ApkAsset(j.getString("module"), j.getLong("versionCode"), j.getString("sha256"), j.getLong("bytes"), j.getString("url"))
            require(a.module in listOf("phone", "wear") && a.versionCode > 0 && a.bytes in 1..MAX_APK_BYTES)
            require(a.sha256.matches(Regex("[0-9a-f]{64}")))
            requireReleaseUrl(a.url)
            return a
        }
    }
}

data class UpdateManifest(val version: String, val phone: ApkAsset, val wear: ApkAsset) {
    fun json() = JSONObject().put("schema", 1).put("version", version).put("phone", phone.json()).put("wear", wear.json()).toString()
    companion object {
        fun parse(text: String): UpdateManifest {
            require(text.length <= 32_768)
            val j = JSONObject(text)
            require(j.getInt("schema") == 1)
            val phone = ApkAsset.parse(j.getJSONObject("phone")); val wear = ApkAsset.parse(j.getJSONObject("wear"))
            val version = j.getString("version")
            require(version.length in 1..80 && phone.module == "phone" && wear.module == "wear" && phone.versionCode == wear.versionCode)
            return UpdateManifest(version, phone, wear)
        }
    }
}

fun requireReleaseUrl(url: String) {
    val u = URI(url)
    require(u.scheme == "https" && u.host == "github.com" && u.port == -1 && u.userInfo == null && u.fragment == null &&
        u.rawPath.startsWith("/$REPOSITORY/releases/download/") && !u.rawPath.contains("..") && !u.rawPath.contains('%')) { "허용된 저장소의 HTTPS Release 파일만 받습니다." }
}

/** No blind downgrade, trust-on-first-use or replacement with an unrelated signing key. */
object UpdatePolicy {
    fun compatible(installedVersion: Long, candidateVersion: Long, installedSigners: Set<String>, candidateSigners: Set<String>,
                   packageName: String, watchApk: Boolean, expectedWatch: Boolean): Boolean =
        candidateVersion > installedVersion && packageName == "dev.sleepalarm.app" && watchApk == expectedWatch &&
            installedSigners.isNotEmpty() && candidateSigners == installedSigners

    fun copyBounded(input: InputStream, output: OutputStream, expected: Long) {
        require(expected in 1..MAX_APK_BYTES)
        val buffer = ByteArray(64 * 1024); var count = 0L
        while (true) {
            val n = input.read(buffer); if (n < 0) break
            count += n; require(count <= expected) { "APK 크기가 선언보다 큽니다." }; output.write(buffer, 0, n)
        }
        require(count == expected) { "APK 다운로드가 불완전합니다." }
    }
    fun digest(file: File): String = file.inputStream().use { stream ->
        val d = MessageDigest.getInstance("SHA-256"); val b = ByteArray(64 * 1024)
        while (true) { val n = stream.read(b); if (n < 0) break; d.update(b, 0, n) }
        d.digest().joinToString("") { "%02x".format(it) }
    }
}
