package com.luxury.mobile.launcher.core

import android.content.Context
import android.util.Log
import com.luxury.mobile.launcher.model.LauncherManifest
import com.luxury.mobile.launcher.model.ManifestEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

/**
 * Top-level state machine of the launcher.
 *
 * It exposes a single [state] StateFlow that the UI and the foreground service
 * observe. There is exactly one [DownloadController] per process (held by
 * [DownloadService]) so that resuming work after the activity is recreated
 * never starts a duplicate job.
 *
 * High-level flow on every launch:
 *   1. Fetch the manifest (or fallback to the legacy single-ZIP descriptor).
 *   2. For each manifest entry, ask [FileVerifier] whether it is already
 *      satisfied on disk. Skipped entries never touch the network.
 *   3. For the remaining entries, kick off [ResumableDownloader] in sequence,
 *      then hand the bytes to [Installer] which moves or extracts them.
 *   4. When the queue empties, emit [State.Done] and stop the foreground
 *      service.
 */
class DownloadController(
    private val appContext: Context,
    private val downloader: ResumableDownloader = ResumableDownloader(appContext),
    private val manifestSource: ManifestSource = ManifestSource(),
) {

    sealed interface State {
        data object Idle : State
        data object FetchingManifest : State
        data object CheckingFiles : State
        data class Downloading(
            val currentIndex: Int,
            val totalCount: Int,
            val currentName: String,
            val downloadedBytes: Long,
            val totalBytes: Long,
            val bytesPerSecond: Long,
        ) : State
        data class Extracting(
            val currentName: String,
            val extractedBytes: Long,
            val totalBytes: Long,
        ) : State
        data object Done : State
        data class Error(val message: String, val recoverable: Boolean) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private var activeJob: Job? = null

    /**
     * Idempotent — calling [start] while a run is already in flight is a no-op.
     * That makes it safe for the foreground service to re-trigger work on
     * `onStartCommand` (e.g. after a boot-resume intent) without spawning
     * duplicate downloads.
     */
    fun start() {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            runMutex.withLock {
                try {
                    runOnce()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.e(TAG, "Run failed", t)
                    _state.value = State.Error(
                        t.message ?: t.javaClass.simpleName,
                        recoverable = ResumableDownloader.isNetworkError(t),
                    )
                }
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
    }

    private suspend fun runOnce() {
        _state.value = State.FetchingManifest
        val manifest = fetchManifestWithRetry()

        _state.value = State.CheckingFiles
        val dataRoot = PathResolver.gameDataRoot(appContext)
        val installer = Installer(dataRoot)

        val pending = manifest.entries.filter { !FileVerifier.isSatisfied(dataRoot, it) }
        if (pending.isEmpty()) {
            Log.i(TAG, "All ${manifest.entries.size} entries already satisfied on disk")
            _state.value = State.Done
            return
        }
        Log.i(
            TAG,
            "Need to fetch ${pending.size}/${manifest.entries.size} entries " +
                "(others detected on disk and skipped)"
        )

        for ((index, entry) in pending.withIndex()) {
            val key = keyFor(entry)
            val displayName = entry.relativePath.ifEmpty { entry.url.substringAfterLast('/') }
            _state.value = State.Downloading(
                currentIndex = index,
                totalCount = pending.size,
                currentName = displayName,
                downloadedBytes = 0,
                totalBytes = entry.size,
                bytesPerSecond = 0,
            )

            val result = downloader.download(entry, key) { downloaded, total, bps ->
                _state.value = State.Downloading(
                    currentIndex = index,
                    totalCount = pending.size,
                    currentName = displayName,
                    downloadedBytes = downloaded,
                    totalBytes = total,
                    bytesPerSecond = bps,
                )
            }

            when (result) {
                is ResumableDownloader.Result.Failure -> {
                    _state.value = State.Error(
                        result.error.message ?: "download failed",
                        recoverable = ResumableDownloader.isNetworkError(result.error),
                    )
                    return
                }
                is ResumableDownloader.Result.Reset -> {
                    Log.i(TAG, "Re-downloading ${entry.relativePath}: ${result.reason}")
                    // The downloader already cleaned up state; re-enter the
                    // loop body for the same entry by decrementing the index.
                    return runOnce()
                }
                is ResumableDownloader.Result.Success -> {
                    _state.value = State.Extracting(
                        currentName = displayName,
                        extractedBytes = 0,
                        totalBytes = entry.size,
                    )
                    val ok = installer.install(entry, result.file) { extracted, total ->
                        _state.value = State.Extracting(
                            currentName = displayName,
                            extractedBytes = extracted,
                            totalBytes = total,
                        )
                    }
                    if (!ok) {
                        _state.value = State.Error(
                            "could not install ${entry.relativePath}",
                            recoverable = false,
                        )
                        return
                    }
                    // Re-verify the freshly installed entry. Any tampering of
                    // the data root by another process is caught here.
                    if (!FileVerifier.isSatisfied(dataRoot, entry)) {
                        _state.value = State.Error(
                            "verification failed after install for ${entry.relativePath}",
                            recoverable = true,
                        )
                        return
                    }
                }
            }
        }
        _state.value = State.Done
    }

    private suspend fun fetchManifestWithRetry(): LauncherManifest {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return manifestSource.fetch()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "Manifest fetch attempt $attempt failed: ${t.message}")
                if (attempt >= 5) throw t
                delay(minOf(15_000L, 1_000L shl attempt))
            }
        }
    }

    /** Stable key per entry — used as the filename for the `.part` sidecar. */
    private fun keyFor(entry: ManifestEntry): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(entry.url.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(entry.relativePath.toByteArray(Charsets.UTF_8))
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        return hex.take(16)
    }

    companion object {
        private const val TAG = "DownloadController"

        @Volatile
        private var instance: DownloadController? = null

        fun get(context: Context): DownloadController {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val again = instance
                if (again != null) return again
                val created = DownloadController(context.applicationContext)
                instance = created
                return created
            }
        }
    }
}
