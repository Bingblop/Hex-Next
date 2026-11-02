package com.hexnext.themer.device

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * The Samsung-compatible root tools the "No root yet?" guide links to. [latestApkUrl] resolves
 * the newest release APK of a tool from the GitHub releases API and falls back to the pinned
 * APK when the network or the API is unavailable; the answer is cached per process.
 */
object RootTools {

    /**
     * @property name display name.
     * @property repo GitHub `owner/repo`.
     * @property pinnedApk a known-good universal APK, used when the latest release cannot be read.
     */
    data class Tool(val name: String, val repo: String, val pinnedApk: String)

    val DFROOT: Tool = Tool(
        "DFRoot",
        "diabl0w/DFRoot",
        "https://github.com/diabl0w/DFRoot/releases/download/v4.7/DFRoot_4.7.apk",
    )

    val KERNELSU: Tool = Tool(
        "KernelSU",
        "diabl0w/KernelSU",
        "https://github.com/diabl0w/KernelSU/releases/download/samsung-v2.2/KernelSU_samsung-v2.2_32687-release.apk",
    )

    val ALL: List<Tool> = listOf(DFROOT, KERNELSU)

    private const val TIMEOUT_MS = 8_000
    private const val MAX_BODY = 1 shl 20

    private val cache = ConcurrentHashMap<String, String>()

    /** The `browser_download_url` of the first `.apk` asset of the latest release of [tool], else [Tool.pinnedApk]. */
    suspend fun latestApkUrl(tool: Tool): String {
        cache[tool.repo]?.let { return it }
        val resolved = withContext(Dispatchers.IO) {
            runCatching { fetchLatestApk(tool.repo) }.getOrNull() ?: tool.pinnedApk
        }
        cache[tool.repo] = resolved
        return resolved
    }

    private fun fetchLatestApk(repo: String): String? {
        val connection = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "HexNext")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val body = connection.inputStream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BODY) throw IOException("release body too large")
                String(bytes, Charsets.UTF_8)
            }
            val assets = JSONObject(body).optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val url = assets.optJSONObject(i)?.optString("browser_download_url").orEmpty()
                if (url.startsWith("https://") && url.endsWith(".apk", ignoreCase = true)) return url
            }
            return null
        } finally {
            connection.disconnect()
        }
    }
}
