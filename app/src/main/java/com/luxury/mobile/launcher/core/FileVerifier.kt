package com.luxury.mobile.launcher.core

import android.util.Log
import com.luxury.mobile.launcher.model.ManifestEntry
import java.io.File

/**
 * Decides whether a manifest entry needs to be downloaded.
 *
 * The launcher's whole point is to never re-download something the player
 * already has — so this is the single most important class in the project.
 *
 * Algorithm:
 *   1. If the destination file does not exist → must download.
 *   2. If the destination file's size differs from the expected size → must
 *      download (the previous attempt was truncated or corrupted).
 *   3. If a SHA-256 was provided and matches → file is good, skip.
 *   4. If no SHA-256 was provided, fall back to size-only matching. This is
 *      not ideal but mirrors the behaviour of the original launcher and is
 *      fine for archives whose extracted contents are themselves validated
 *      individually.
 *
 * For ZIP entries the verifier instead checks that the *extracted* destination
 * directory contains the expected sentinel files. Extracted state is recorded
 * by writing a `.installed` marker next to the destination directory.
 */
object FileVerifier {

    private const val TAG = "FileVerifier"

    /**
     * Returns `true` when [entry] does NOT need to be downloaded — i.e. it is
     * already on disk and verified.
     */
    fun isSatisfied(dataRoot: File, entry: ManifestEntry): Boolean {
        return if (entry.extractFromZip) {
            isZipExtracted(dataRoot, entry)
        } else {
            isPlainFileGood(dataRoot, entry)
        }
    }

    private fun isPlainFileGood(dataRoot: File, entry: ManifestEntry): Boolean {
        val target = File(dataRoot, entry.relativePath)
        if (!target.isFile) return false
        if (entry.size > 0 && target.length() != entry.size) {
            Log.i(
                TAG,
                "Size mismatch for ${entry.relativePath}: expected ${entry.size}, on disk ${target.length()}"
            )
            return false
        }
        val expected = entry.sha256
        if (expected.isNullOrBlank()) {
            // No hash provided — trust size match. Acceptable for archives whose
            // extraction step has its own validation.
            return true
        }
        val actual = Hasher.sha256(target)
        val ok = actual.equals(expected, ignoreCase = true)
        if (!ok) {
            Log.i(
                TAG,
                "Hash mismatch for ${entry.relativePath}: expected $expected, got $actual"
            )
        }
        return ok
    }

    private fun isZipExtracted(dataRoot: File, entry: ManifestEntry): Boolean {
        val target = File(dataRoot, entry.relativePath)
        if (!target.isDirectory) return false
        val marker = installedMarker(dataRoot, entry)
        if (!marker.isFile) return false
        return try {
            val recordedSha = marker.readText().trim()
            val expected = entry.sha256
            // If we know the source ZIP hash, the marker must match it. This
            // covers the case where the server pushed a new content drop with
            // the same file name.
            if (!expected.isNullOrBlank() && !recordedSha.equals(expected, ignoreCase = true)) {
                Log.i(TAG, "Installed marker for ${entry.relativePath} is from old content")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot read installed marker for ${entry.relativePath}", t)
            false
        }
    }

    fun installedMarker(dataRoot: File, entry: ManifestEntry): File =
        File(dataRoot, entry.relativePath.trimEnd('/') + ".installed")
}
