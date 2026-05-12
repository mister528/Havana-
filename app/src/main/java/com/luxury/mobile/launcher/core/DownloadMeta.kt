package com.luxury.mobile.launcher.core

import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Sidecar metadata persisted next to every `.part` file.
 *
 * The goal is to make resume robust against three failure modes:
 *
 *  1. **App killed mid-download** — on next start we read this file, send a
 *     `Range: bytes=<downloadedBytes>-` request, and append to the existing
 *     `.part` file instead of starting over.
 *  2. **Server file changed between sessions** — we compare [etag] / [lastModified]
 *     and [totalBytes] from the new HEAD response with the persisted values.
 *     Any mismatch means the upstream file was replaced and we start fresh.
 *  3. **Disk corruption** — when [expectedSha256] is set and the resumed
 *     download completes, the final hash is verified before the file is moved
 *     into place.
 */
data class DownloadMeta(
    val url: String,
    val targetRelativePath: String,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val expectedSha256: String? = null,
) {

    fun toJson(): String = JSONObject().apply {
        put(KEY_URL, url)
        put(KEY_PATH, targetRelativePath)
        put(KEY_TOTAL, totalBytes)
        put(KEY_DOWNLOADED, downloadedBytes)
        if (etag != null) put(KEY_ETAG, etag)
        if (lastModified != null) put(KEY_LAST_MOD, lastModified)
        if (expectedSha256 != null) put(KEY_SHA256, expectedSha256)
    }.toString()

    fun write(metaFile: File) {
        try {
            metaFile.parentFile?.mkdirs()
            // Write atomically: temp file + rename so a crash mid-write cannot
            // leave an unreadable meta sidecar.
            val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
            tmp.writeText(toJson())
            if (!tmp.renameTo(metaFile)) {
                metaFile.writeText(toJson())
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist download meta to ${metaFile.absolutePath}", t)
        }
    }

    companion object {
        private const val TAG = "DownloadMeta"
        private const val KEY_URL = "url"
        private const val KEY_PATH = "path"
        private const val KEY_TOTAL = "total"
        private const val KEY_DOWNLOADED = "downloaded"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_MOD = "lastMod"
        private const val KEY_SHA256 = "sha256"

        fun read(metaFile: File): DownloadMeta? {
            if (!metaFile.isFile) return null
            return try {
                val json = JSONObject(metaFile.readText())
                DownloadMeta(
                    url = json.getString(KEY_URL),
                    targetRelativePath = json.getString(KEY_PATH),
                    totalBytes = json.optLong(KEY_TOTAL, 0L),
                    downloadedBytes = json.optLong(KEY_DOWNLOADED, 0L),
                    etag = json.optString(KEY_ETAG).takeIf { it.isNotEmpty() },
                    lastModified = json.optString(KEY_LAST_MOD).takeIf { it.isNotEmpty() },
                    expectedSha256 = json.optString(KEY_SHA256).takeIf { it.isNotEmpty() },
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Corrupt meta file ${metaFile.absolutePath}, ignoring", t)
                null
            }
        }
    }
}
