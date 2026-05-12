package com.luxury.mobile.launcher.core

import android.content.Context
import android.util.Log
import com.luxury.mobile.launcher.model.ManifestEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Single-file resumable downloader.
 *
 * Public contract:
 *   * Downloads [entry] using HTTP `Range` requests so that interruptions (app
 *     killed, network drop, screen off) never restart the transfer from zero.
 *   * Writes bytes into `<workDir>/<key>.part` and persists a [DownloadMeta]
 *     sidecar after every 256 KB so the next session knows exactly where to
 *     resume.
 *   * On completion verifies the file's SHA-256 against [ManifestEntry.sha256]
 *     when present, then atomically moves the `.part` into its final location
 *     (or, for zip entries, leaves it staged for [Installer] to extract).
 *
 * The downloader is *interruptible* — the caller is expected to invoke it from
 * a coroutine. If the coroutine is cancelled the partial file and meta sidecar
 * are left intact so the next launch resumes seamlessly.
 */
class ResumableDownloader(
    private val context: Context,
    private val client: OkHttpClient = defaultClient(),
) {

    fun interface ProgressCallback {
        fun onProgress(downloadedBytes: Long, totalBytes: Long, bytesPerSecond: Long)
    }

    sealed interface Result {
        /** The final on-disk file ready for verification / staging. */
        data class Success(val file: File) : Result

        /** Server changed the upstream file mid-resume; partial state was reset. */
        data class Reset(val reason: String) : Result

        /** Hard failure that won't be fixed by retrying right now. */
        data class Failure(val error: Throwable) : Result
    }

    suspend fun download(
        entry: ManifestEntry,
        key: String,
        progress: ProgressCallback,
    ): Result = withContext(Dispatchers.IO) {
        val partFile = PathResolver.partFile(context, key)
        val metaFile = PathResolver.metaFile(context, key)

        var attempt = 0
        var lastError: Throwable? = null
        // Bounded retry loop — transient socket / DNS errors should not surface
        // to the UI immediately. Persistent failures bubble up so the service
        // can re-schedule with a longer back-off.
        while (coroutineContext.isActive && attempt < MAX_ATTEMPTS) {
            attempt++
            try {
                val result = attempt(entry, partFile, metaFile, progress)
                return@withContext result
            } catch (ce: CancellationException) {
                // Cancellation must propagate; never treat it as a retryable
                // error or we leak the coroutine.
                throw ce
            } catch (t: Throwable) {
                lastError = t
                Log.w(
                    TAG,
                    "Attempt $attempt for ${entry.relativePath} failed: ${t.javaClass.simpleName}: ${t.message}",
                )
                val backoffMs = minOf(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS shl (attempt - 1))
                delay(backoffMs)
            }
        }
        Result.Failure(lastError ?: IOException("Unknown download error"))
    }

    private suspend fun attempt(
        entry: ManifestEntry,
        partFile: File,
        metaFile: File,
        progress: ProgressCallback,
    ): Result {
        partFile.parentFile?.mkdirs()
        val persisted = DownloadMeta.read(metaFile)
        val resumeFrom = if (
            persisted != null &&
            partFile.isFile &&
            persisted.url == entry.url
        ) {
            // Trust whichever is smaller — persisted meta and physical file
            // can briefly disagree if a previous run was killed between the
            // file write and the meta write.
            minOf(persisted.downloadedBytes, partFile.length())
        } else {
            // Different URL, missing part file, or first run — reset state.
            if (partFile.isFile) partFile.delete()
            if (metaFile.isFile) metaFile.delete()
            0L
        }

        val requestBuilder = Request.Builder().url(entry.url).header(
            "User-Agent",
            "HavanaLauncher/1.0 (Android)"
        )
        if (resumeFrom > 0) {
            requestBuilder.header("Range", "bytes=$resumeFrom-")
            persisted?.etag?.let { requestBuilder.header("If-Range", it) }
                ?: persisted?.lastModified?.let { requestBuilder.header("If-Range", it) }
        }
        val request = requestBuilder.build()

        client.newCall(request).execute().use { response ->
            return handleResponse(response, entry, partFile, metaFile, resumeFrom, progress)
        }
    }

    private suspend fun handleResponse(
        response: Response,
        entry: ManifestEntry,
        partFile: File,
        metaFile: File,
        resumeFromIn: Long,
        progress: ProgressCallback,
    ): Result {
        if (!response.isSuccessful && response.code != 206) {
            throw IOException("HTTP ${response.code} for ${entry.url}")
        }

        var resumeFrom = resumeFromIn
        val isPartial = response.code == 206
        // Some hosts (Dropbox direct-download endpoints) don't honour Range
        // and return the full body with code 200 even when we sent a Range
        // header. Detect that and reset the file so we don't end up with the
        // first <resumeFrom> bytes being garbage.
        if (resumeFrom > 0 && !isPartial) {
            Log.i(TAG, "Server ignored Range header; restarting download from 0 for ${entry.relativePath}")
            if (partFile.isFile) partFile.delete()
            if (metaFile.isFile) metaFile.delete()
            resumeFrom = 0L
        }

        val body = response.body ?: throw IOException("Empty response body")
        val declaredLength = body.contentLength()
        val totalBytes = when {
            declaredLength <= 0 && entry.size > 0 -> entry.size
            isPartial && declaredLength > 0 -> resumeFrom + declaredLength
            declaredLength > 0 -> declaredLength
            else -> entry.size
        }
        val etag = response.header("ETag")
        val lastMod = response.header("Last-Modified")

        // Write meta up-front so even the very first byte's worth of progress
        // is recoverable if the process dies immediately.
        var meta = DownloadMeta(
            url = entry.url,
            targetRelativePath = entry.relativePath,
            totalBytes = totalBytes,
            downloadedBytes = resumeFrom,
            etag = etag,
            lastModified = lastMod,
            expectedSha256 = entry.sha256,
        )
        meta.write(metaFile)

        RandomAccessFile(partFile, "rw").use { raf ->
            raf.setLength(maxOf(raf.length(), resumeFrom))
            raf.seek(resumeFrom)

            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var downloaded = resumeFrom
                var lastMetaFlush = resumeFrom
                var lastTickMs = System.currentTimeMillis()
                var lastTickBytes = resumeFrom
                while (coroutineContext.isActive) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    raf.write(buf, 0, read)
                    downloaded += read

                    // Persist progress every META_FLUSH_BYTES so even an OOM
                    // killer cannot wipe out more than that much progress.
                    if (downloaded - lastMetaFlush >= META_FLUSH_BYTES) {
                        meta = meta.copy(downloadedBytes = downloaded)
                        meta.write(metaFile)
                        lastMetaFlush = downloaded
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastTickMs
                    if (elapsed >= 500) {
                        val bps = ((downloaded - lastTickBytes) * 1000L) / elapsed
                        progress.onProgress(downloaded, totalBytes, bps)
                        lastTickMs = now
                        lastTickBytes = downloaded
                    }
                }
                // Final progress beat so the UI shows 100%.
                progress.onProgress(downloaded, totalBytes, 0L)
                meta = meta.copy(downloadedBytes = downloaded)
                meta.write(metaFile)

                if (!coroutineContext.isActive) {
                    // Caller cancelled — leave .part and meta on disk for resume.
                    throw CancellationException("Download cancelled by caller")
                }

                if (entry.size > 0 && downloaded != entry.size) {
                    return Result.Failure(
                        IOException(
                            "Truncated download for ${entry.relativePath}: " +
                                "got $downloaded bytes, expected ${entry.size}"
                        )
                    )
                }
            }
        }

        val expected = entry.sha256
        if (!expected.isNullOrBlank()) {
            val actual = Hasher.sha256(partFile)
            if (!actual.equals(expected, ignoreCase = true)) {
                Log.w(
                    TAG,
                    "SHA-256 mismatch for ${entry.relativePath}: expected $expected, got $actual",
                )
                partFile.delete()
                metaFile.delete()
                return Result.Reset("hash mismatch — re-downloading")
            }
        }

        // Caller is responsible for moving / extracting the part file. We keep
        // the meta sidecar around until the install step is complete so a crash
        // during extraction can still resume.
        return Result.Success(partFile)
    }

    companion object {
        private const val TAG = "ResumableDownloader"
        private const val MAX_ATTEMPTS = 5
        private const val INITIAL_BACKOFF_MS = 1_500L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val META_FLUSH_BYTES = 256L * 1024L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** Coalesces network-style errors so the UI shows a friendly message. */
        fun isNetworkError(t: Throwable): Boolean =
            t is UnknownHostException || t is IOException
    }
}
