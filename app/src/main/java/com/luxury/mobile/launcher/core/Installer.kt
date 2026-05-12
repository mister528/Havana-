package com.luxury.mobile.launcher.core

import android.util.Log
import com.luxury.mobile.launcher.model.ManifestEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Moves a finished download into its place in the game data folder.
 *
 * Two install modes:
 *   * **Plain file** — atomically rename the `.part` into the target path so a
 *     crash during the rename never leaves a half-written destination.
 *   * **ZIP archive** — stream-extract each entry to disk, write a `.installed`
 *     marker containing the source archive's SHA-256 so [FileVerifier] can
 *     skip the work on the next launch.
 *
 * Both modes are coroutine-cancellable. Cancellation during extraction leaves
 * a partial directory on disk *and* no `.installed` marker, so the next launch
 * detects the unfinished state and redoes the work.
 */
class Installer(private val dataRoot: File) {

    fun interface ProgressCallback {
        fun onExtractProgress(extractedBytes: Long, totalBytes: Long)
    }

    suspend fun install(
        entry: ManifestEntry,
        downloadedFile: File,
        progress: ProgressCallback,
    ): Boolean = withContext(Dispatchers.IO) {
        if (entry.extractFromZip) {
            extractZip(entry, downloadedFile, progress)
        } else {
            placePlainFile(entry, downloadedFile)
        }
    }

    private fun placePlainFile(entry: ManifestEntry, downloadedFile: File): Boolean {
        val target = File(dataRoot, entry.relativePath)
        target.parentFile?.mkdirs()
        if (target.exists()) target.delete()
        return if (downloadedFile.renameTo(target)) {
            true
        } else {
            // Cross-FS rename can fail (work dir on app private, target on
            // external storage). Fall back to copy + delete.
            try {
                downloadedFile.inputStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                downloadedFile.delete()
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to place ${entry.relativePath}", t)
                false
            }
        }
    }

    private suspend fun extractZip(
        entry: ManifestEntry,
        archive: File,
        progress: ProgressCallback,
    ): Boolean {
        val targetDir = File(dataRoot, entry.relativePath)
        targetDir.mkdirs()

        // Compute total uncompressed size up-front so the UI has a real ratio.
        val totalSize = try {
            ZipFile(archive).use { zf ->
                var sum = 0L
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    sum += entries.nextElement().size.coerceAtLeast(0L)
                }
                sum
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not compute uncompressed size for ${archive.name}", t)
            -1L
        }

        try {
            ZipFile(archive).use { zf ->
                val entries = zf.entries()
                var written = 0L
                val canonicalRoot = targetDir.canonicalPath
                while (entries.hasMoreElements()) {
                    if (!coroutineContext.isActive) throw CancellationException("Install cancelled")
                    val ze = entries.nextElement()
                    val outFile = File(targetDir, ze.name)
                    // Zip-slip protection — refuse to write anything outside
                    // the target directory.
                    val canonicalOut = outFile.canonicalPath
                    if (!canonicalOut.startsWith(canonicalRoot + File.separator) &&
                        canonicalOut != canonicalRoot
                    ) {
                        throw IOException("Zip-slip attempt blocked: ${ze.name}")
                    }
                    if (ze.isDirectory) {
                        outFile.mkdirs()
                        continue
                    }
                    outFile.parentFile?.mkdirs()
                    zf.getInputStream(ze).use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                if (!coroutineContext.isActive) {
                                    throw CancellationException("Install cancelled")
                                }
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                written += read
                                if (totalSize > 0) {
                                    progress.onExtractProgress(written, totalSize)
                                }
                            }
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "Extraction failed for ${archive.name}", t)
            return false
        }

        // Record what we extracted so [FileVerifier] can short-circuit later.
        val marker = FileVerifier.installedMarker(dataRoot, entry)
        marker.parentFile?.mkdirs()
        marker.writeText(entry.sha256 ?: Hasher.sha256(archive))

        // Remove the archive itself to free disk space — this matches the
        // existing HavanaRP behaviour where luxury.zip is deleted after
        // extraction.
        archive.delete()
        // Meta sidecar lived next to the part file; the part file is gone now,
        // so the meta is stale. Best-effort cleanup.
        val workDir = archive.parentFile
        if (workDir != null) {
            workDir.listFiles { f -> f.name == archive.name + ".meta" || f.name == archive.nameWithoutExtension + ".meta" }
                ?.forEach { it.delete() }
        }
        return true
    }

    companion object {
        private const val TAG = "Installer"
    }
}
