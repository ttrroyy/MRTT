package com.mrtt.client

import android.util.Log
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.io.File
import java.security.MessageDigest

const val UPDATE_CHECK_NEVER = MrttConstants.Update.CHECK_NEVER
const val DEFAULT_UPDATE_CHECK_INTERVAL_HOURS = MrttConstants.Update.DEFAULT_CHECK_INTERVAL_HOURS
const val UPDATE_DIALOG_ACTION_POSTPONED = MrttConstants.Update.ACTION_POSTPONED
const val UPDATE_DIALOG_ACTION_UPDATE = MrttConstants.Update.ACTION_UPDATE

private const val UPDATE_LOG_TAG = MrttConstants.General.APP_NAME
private const val GITHUB_RELEASES_URL = MrttConstants.Update.GITHUB_RELEASES_URL
private const val GITHUB_API_RATE_LIMIT_FALLBACK_MS = MrttConstants.Timeouts.UPDATE_RATE_LIMIT_COOLDOWN_MS
private val VERSION_NUMBER_REGEX = MrttConstants.Patterns.VERSION_NUMBER
private val PRE_RELEASE_REGEX = Regex("""^v?\d+(?:\.\d+)*-[A-Za-z]+(\d*)""", RegexOption.IGNORE_CASE)

@Volatile
private var githubApiCooldownUntilMs = 0L

fun updateIntervalHoursToMillis(hours: Int): Long? = when {
    hours <= 0 -> null
    else -> hours * 60L * 60L * 1000L
}

data class AppReleaseInfo(
    val versionTag: String,
    val releaseUrl: String,
    val source: RemoteVersionSource,
    val apk: UpdateApk?,
)

data class UpdateApk(val url: String, val sha256: String, val size: Long)

enum class RemoteVersionSource {
    Release,
    Tag
}

suspend fun fetchLatestReleaseInfo(includePrereleases: Boolean): AppReleaseInfo? = withContext(Dispatchers.IO) {
    val response = fetchGitHubApi(GITHUB_RELEASES_URL) ?: return@withContext null
    val releases = try {
        JSONArray(response)
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: failed to parse releases list", e)
        return@withContext null
    }

    var bestRelease: AppReleaseInfo? = null
    for (i in 0 until releases.length()) {
        val json = releases.optJSONObject(i) ?: continue
        if (json.optBoolean("draft")) continue
        val release = json.toAppReleaseInfo() ?: continue
        if (!includePrereleases && (json.optBoolean("prerelease") || preReleaseNumber(release.versionTag) != null)) continue
        if (isLegacyRelease(release.versionTag)) continue
        if (bestRelease == null || isNewerVersion(bestRelease.versionTag, release.versionTag)) {
            bestRelease = release
        }
    }
    bestRelease
}

// MRTT legacy releases live in the same repository but are a separate product, never an update.
internal fun isLegacyRelease(versionTag: String): Boolean =
    versionTag.contains("legacy", ignoreCase = true)

fun isNewerVersion(local: String, remote: String): Boolean {
    val localParts = versionParts(local)
    val remoteParts = versionParts(remote)
    if (remoteParts.isEmpty()) return false

    val maxLen = maxOf(localParts.size, remoteParts.size)
    for (i in 0 until maxLen) {
        val localPart = localParts.getOrElse(i) { 0 }
        val remotePart = remoteParts.getOrElse(i) { 0 }
        if (remotePart > localPart) return true
        if (remotePart < localPart) return false
    }

    // Same numbers: 1.0-beta1 < 1.0-beta2 < 1.0.
    val localPre = preReleaseNumber(local)
    val remotePre = preReleaseNumber(remote)
    return when {
        localPre == null -> false
        remotePre == null -> true
        else -> remotePre > localPre
    }
}

private fun preReleaseNumber(version: String): Int? {
    val match = PRE_RELEASE_REGEX.find(version.trim()) ?: return null
    return match.groupValues[1].toIntOrNull() ?: 0
}

private fun fetchGitHubApi(url: String): String? {
    val now = System.currentTimeMillis()
    if (now < githubApiCooldownUntilMs) return null
    return fetchHttpText(
        url = url,
        sourceLabel = "GitHub API",
        accept = "application/vnd.github+json",
        isGitHubApi = true
    )
}

private fun fetchHttpText(
    url: String,
    sourceLabel: String,
    accept: String,
    isGitHubApi: Boolean = false
): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = URL(url).openConnection() as HttpURLConnection
        applyNoCacheHeaders(conn)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", accept)
        if (isGitHubApi) {
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        conn.setRequestProperty("User-Agent", "MRTTAndroid/${BuildConfig.VERSION_NAME}")
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        if (responseCode in 200..299) {
            if (isGitHubApi) githubApiCooldownUntilMs = 0L
            response
        } else {
            if (isGitHubApi) noteGitHubApiCooldown(conn, responseCode, response)
            Log.w(
                UPDATE_LOG_TAG,
                "[WARN] Update check: $sourceLabel returned $responseCode ${response.take(300)}"
            )
            null
        }
    } catch (e: Exception) {
        Log.w(UPDATE_LOG_TAG, "[WARN] Update check: $sourceLabel request failed", e)
        null
    } finally {
        conn?.disconnect()
    }
}

private fun applyNoCacheHeaders(conn: HttpURLConnection) {
    conn.useCaches = false
    conn.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
    conn.setRequestProperty("Pragma", "no-cache")
    conn.setRequestProperty("Expires", "0")
}

private fun noteGitHubApiCooldown(conn: HttpURLConnection, responseCode: Int, response: String) {
    if (responseCode != HttpURLConnection.HTTP_FORBIDDEN && responseCode != 429) return
    val now = System.currentTimeMillis()
    val retryAfterUntil = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { now + it * 1000L }
    val rateLimitResetUntil = conn.getHeaderField("X-RateLimit-Reset")?.trim()?.toLongOrNull()?.takeIf { it > 0L }?.let { it * 1000L }
    val fallbackUntil = now + if (response.contains("rate limit", ignoreCase = true)) GITHUB_API_RATE_LIMIT_FALLBACK_MS else 5L * 60L * 1000L
    val cooldownUntil = listOfNotNull(retryAfterUntil, rateLimitResetUntil).filter { it > now }.minOrNull() ?: fallbackUntil
    if (cooldownUntil > githubApiCooldownUntilMs) {
        githubApiCooldownUntilMs = cooldownUntil
        Log.w(
            UPDATE_LOG_TAG,
            "[WARN] Update check: GitHub API cooldown ${(cooldownUntil - now) / 1000}s after HTTP $responseCode"
        )
    }
}

private fun JSONObject.toAppReleaseInfo(): AppReleaseInfo? {
    val versionTag = normalizeVersionTag(optString("tag_name"))
    val releaseUrl = optString("html_url").trim()
    if (versionTag.isBlank() || releaseUrl.isBlank()) return null
    val assets = optJSONArray("assets") ?: JSONArray()
    val apkNames = (Build.SUPPORTED_ABIS.toList() + "universal").flatMap { listOf("MRTT-$it.apk", "app-$it-maxTest.apk") }
    val build = apkNames.firstNotNullOfOrNull { name ->
        (0 until assets.length()).mapNotNull(assets::optJSONObject).firstOrNull { it.optString("name") == name }
    }
    val apk = build?.let {
        val url = it.optString("browser_download_url")
        val digest = it.optString("digest").removePrefix("sha256:")
        val size = it.optLong("size")
        if (url.startsWith("https://github.com/ttrroyy/MRTT/releases/download/", ignoreCase = true) &&
            digest.matches(Regex("[a-f0-9]{64}")) && size in 1..400_000_000L) UpdateApk(url, digest, size) else null
    }
    return AppReleaseInfo(versionTag, releaseUrl, RemoteVersionSource.Release, apk)
}

suspend fun installUpdate(context: Context, release: AppReleaseInfo) {
    val apk = release.apk
    if (apk == null) {
        Toast.makeText(context, "Для этой версии нет проверенного APK", Toast.LENGTH_LONG).show()
        return
    }
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        Toast.makeText(context, "Разрешите установку из MRTT и нажмите «Обновить» ещё раз", Toast.LENGTH_LONG).show()
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
        return
    }
    Toast.makeText(context, "Скачиваем обновление MRTT", Toast.LENGTH_SHORT).show()
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val directory = File(context.cacheDir, "updates")
            directory.mkdirs()
            val file = File.createTempFile("mrtt-", ".apk", directory)
            try {
                val connection = URL(apk.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                try {
                    val hash = MessageDigest.getInstance("SHA-256")
                    var downloaded = 0L
                    connection.inputStream.use { input ->
                        file.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                downloaded += size
                                check(downloaded <= apk.size)
                                hash.update(buffer, 0, size)
                                out.write(buffer, 0, size)
                            }
                        }
                    }
                    check(downloaded == apk.size && hash.digest().joinToString("") { "%02x".format(it) } == apk.sha256)
                } finally {
                    connection.disconnect()
                }
                check(context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName == context.packageName)
                directory.listFiles()?.filter { it != file && it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
                FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
            } catch (error: Exception) {
                file.delete()
                throw error
            }
        }.getOrNull()
    }
    if (uri == null || runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.isFailure) Toast.makeText(context, "Не удалось скачать или открыть обновление MRTT", Toast.LENGTH_LONG).show()
}

private fun versionParts(version: String): List<Int> {
    val normalized = VERSION_NUMBER_REGEX.find(version.trim())?.value ?: return emptyList()
    return normalized.split(".").mapNotNull { it.toIntOrNull() }
}

internal fun normalizeVersionTag(version: String): String {
    val trimmed = version.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
}
