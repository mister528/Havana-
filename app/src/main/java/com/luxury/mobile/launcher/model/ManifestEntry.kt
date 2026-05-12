package com.luxury.mobile.launcher.model

/**
 * Describes a single file that must exist locally before the game can be launched.
 *
 * Three fields are required at minimum:
 *   * [url] — direct download URL (must accept HTTP `Range` requests for resume support).
 *   * [relativePath] — destination path relative to the game data root.
 *   * [size] — expected byte size after download.
 *
 * [sha256] is optional but strongly recommended. When present it is used both to
 * verify newly downloaded files and to detect files the player already has on
 * disk so the launcher can skip them entirely.
 *
 * When [extractFromZip] is true the file is treated as an archive: after
 * downloading it is extracted into [relativePath] (which must be a directory)
 * and then the archive is deleted. This matches the existing HavanaRP layout
 * where a single `luxury.zip` is unpacked into the game data folder.
 */
data class ManifestEntry(
    val url: String,
    val relativePath: String,
    val size: Long,
    val sha256: String? = null,
    val extractFromZip: Boolean = false,
)

/**
 * Whole-launcher manifest. Versioning lets the launcher detect when the server
 * has pushed a new content drop and trigger a re-check of all entries.
 */
data class LauncherManifest(
    val version: Int,
    val entries: List<ManifestEntry>,
)
