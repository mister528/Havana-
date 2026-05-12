package com.luxury.mobile.launcher.core

import android.util.Log
import com.luxury.mobile.launcher.BuildConfig
import com.luxury.mobile.launcher.model.LauncherManifest
import com.luxury.mobile.launcher.model.ManifestEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Fetches and parses the launcher manifest from the server, with a graceful
 * fallback to the legacy "single Dropbox ZIP" workflow when the manifest is
 * unavailable.
 *
 * Server JSON shape (designed to be backward-compatible with anything the
 * existing backend already exposes):
 *
 * ```
 * {
 *   "version": 24,
 *   "files": [
 *     {
 *       "url": "https://.../luxury.zip",
 *       "path": ".",
 *       "size": 367123456,
 *       "sha256": "abc…",
 *       "extract": true
 *     },
 *     {
 *       "url": "https://.../patches/handling.cfg",
 *       "path": "data/handling.cfg",
 *       "size": 28119,
 *       "sha256": "def…"
 *     }
 *   ]
 * }
 * ```
 *
 * Every field except `url`, `path` and `size` is optional. `extract` defaults
 * to `true` when the URL ends in `.zip` so older manifests work without
 * changes.
 */
class ManifestSource(
    private val client: OkHttpClient = ResumableDownloader.defaultClient(),
) {

    suspend fun fetch(): LauncherManifest = withContext(Dispatchers.IO) {
        val remote = runCatching { fetchRemote() }.getOrNull()
        remote ?: legacyFallback()
    }

    private fun fetchRemote(): LauncherManifest? {
        val request = Request.Builder()
            .url(BuildConfig.MANIFEST_URL)
            .header("User-Agent", "HavanaLauncher/1.0 (Android)")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.i(TAG, "Manifest endpoint returned HTTP ${response.code}; falling back")
                return null
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null
            return try {
                parse(body)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not parse manifest JSON", t)
                null
            }
        }
    }

    private fun parse(json: String): LauncherManifest {
        val root = JSONObject(json)
        val version = root.optInt("version", 1)
        val arr = root.optJSONArray("files") ?: return LauncherManifest(version, emptyList())
        val entries = (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = obj.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val path = obj.optString("path").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val size = obj.optLong("size", 0L)
            val sha256 = obj.optString("sha256").takeIf { it.isNotBlank() }
            val explicitExtract = obj.has("extract")
            val extract = if (explicitExtract) obj.optBoolean("extract") else url.endsWith(".zip", ignoreCase = true)
            ManifestEntry(url = url, relativePath = path, size = size, sha256 = sha256, extractFromZip = extract)
        }
        return LauncherManifest(version, entries)
    }

    /**
     * Legacy: when the server has no manifest, we still want a working launcher.
     * The bundled `BuildConfig.FALLBACK_ZIP_URL` reproduces the original
     * Dropbox-zip-into-`/LuxuryMobile/` flow but now uses the resumable
     * pipeline, so the player still benefits from resume + skip-existing.
     */
    private fun legacyFallback(): LauncherManifest = LauncherManifest(
        version = 0,
        entries = listOf(
            ManifestEntry(
                url = BuildConfig.FALLBACK_ZIP_URL,
                relativePath = ".",
                size = 0L,
                sha256 = null,
                extractFromZip = true,
            )
        ),
    )

    companion object {
        private const val TAG = "ManifestSource"
    }
}
