package com.luxury.mobile.launcher.core

import android.content.Context
import android.os.Environment
import com.luxury.mobile.launcher.BuildConfig
import java.io.File

/**
 * Resolves the on-disk locations used by the launcher.
 *
 * Two roots matter:
 *   * The **game data root** is where extracted game files live. It mirrors the
 *     directory the existing HavanaRP build uses so that players who already
 *     have data on disk are detected and *not* re-downloaded.
 *   * The **work directory** holds in-progress downloads (`*.part`) and their
 *     sidecar metadata. It must be writable without runtime permissions so we
 *     keep it inside the app-private external area on modern Android, falling
 *     back to legacy external storage when available.
 */
object PathResolver {

    /** Name of the data folder relative to external storage. */
    private val DATA_FOLDER: String = BuildConfig.DATA_DIR_NAME

    /**
     * Best effort: prefer the public legacy path so players who manually copied
     * their game data still get detected. Falls back to the app-private folder.
     */
    fun gameDataRoot(context: Context): File {
        val legacy = File(Environment.getExternalStorageDirectory(), DATA_FOLDER)
        if (legacy.exists() && legacy.canRead()) return legacy
        if (canUseLegacyStorage()) {
            legacy.mkdirs()
            if (legacy.exists()) return legacy
        }
        val privateRoot = context.getExternalFilesDir(null) ?: context.filesDir
        return File(privateRoot, DATA_FOLDER).also { it.mkdirs() }
    }

    /**
     * Directory for in-flight downloads. Kept inside app-private storage so it
     * survives reboots and does not require external-storage permissions.
     */
    fun workDir(context: Context): File {
        val parent = context.getExternalFilesDir(null) ?: context.filesDir
        return File(parent, "downloads").also { it.mkdirs() }
    }

    fun partFile(context: Context, key: String): File =
        File(workDir(context), "$key.part")

    fun metaFile(context: Context, key: String): File =
        File(workDir(context), "$key.meta")

    private fun canUseLegacyStorage(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }
}
