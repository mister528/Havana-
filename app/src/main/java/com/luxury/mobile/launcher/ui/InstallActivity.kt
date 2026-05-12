package com.luxury.mobile.launcher.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.luxury.mobile.launcher.BuildConfig
import com.luxury.mobile.launcher.R
import com.luxury.mobile.launcher.core.DownloadController
import com.luxury.mobile.launcher.service.DownloadService
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Single-screen UI that mirrors the state owned by [DownloadController].
 *
 * Note that we never *do* download work here — the activity only renders the
 * controller's [DownloadController.state] flow. When the user backs out of
 * this activity, [DownloadService] keeps the work going and the activity will
 * resync with the live state when (and if) the user returns.
 */
class InstallActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var sizeText: TextView
    private lateinit var speedText: TextView
    private lateinit var actionButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_install)

        status = findViewById(R.id.status)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        sizeText = findViewById(R.id.size_text)
        speedText = findViewById(R.id.speed_text)
        actionButton = findViewById(R.id.action_button)

        // Start the foreground service that owns the download. The controller
        // is reused (singleton), so reopening this activity later just re-attaches
        // to the same state machine without duplicating work.
        DownloadService.start(this)

        val controller = DownloadController.get(applicationContext)
        controller.start()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                controller.state.collect { render(it) }
            }
        }

        actionButton.setOnClickListener {
            val tag = actionButton.tag
            if (tag == TAG_RETRY) {
                actionButton.visibility = View.GONE
                controller.start()
            } else if (tag == TAG_PLAY) {
                launchGame()
            }
        }
    }

    private fun render(state: DownloadController.State) {
        when (state) {
            DownloadController.State.Idle,
            DownloadController.State.FetchingManifest -> {
                status.setText(R.string.status_checking)
                showIndeterminate()
                hideActionButton()
            }
            DownloadController.State.CheckingFiles -> {
                status.setText(R.string.status_checking)
                showIndeterminate()
                hideActionButton()
            }
            is DownloadController.State.Downloading -> {
                status.text = getString(
                    R.string.status_downloading_indexed,
                    state.currentIndex + 1,
                    state.totalCount,
                )
                progressBar.isIndeterminate = false
                val pct = if (state.totalBytes > 0) {
                    ((state.downloadedBytes * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
                } else 0
                progressBar.progress = pct
                progressText.text = getString(R.string.progress_percent, pct)
                sizeText.text = getString(
                    R.string.progress_template,
                    toMegabytes(state.downloadedBytes),
                    toMegabytes(state.totalBytes),
                )
                speedText.text = getString(
                    R.string.speed_template,
                    humanBytes(state.bytesPerSecond),
                )
                hideActionButton()
            }
            is DownloadController.State.Extracting -> {
                status.setText(R.string.status_extracting)
                progressBar.isIndeterminate = false
                val pct = if (state.totalBytes > 0) {
                    ((state.extractedBytes * 100L) / state.totalBytes).toInt().coerceIn(0, 100)
                } else 0
                progressBar.progress = pct
                progressText.text = getString(R.string.progress_percent, pct)
                sizeText.text = getString(
                    R.string.progress_template,
                    toMegabytes(state.extractedBytes),
                    toMegabytes(state.totalBytes),
                )
                speedText.text = ""
                hideActionButton()
            }
            DownloadController.State.Done -> {
                status.setText(R.string.status_finished)
                progressBar.isIndeterminate = false
                progressBar.progress = 100
                progressText.text = getString(R.string.progress_percent, 100)
                speedText.text = ""
                showPlayButton()
            }
            is DownloadController.State.Error -> {
                val message = if (state.recoverable) {
                    getString(R.string.status_paused)
                } else {
                    getString(R.string.status_error, state.message)
                }
                status.text = message
                speedText.text = ""
                showRetryButton()
            }
        }
    }

    private fun showIndeterminate() {
        progressBar.isIndeterminate = true
        progressText.text = ""
        sizeText.text = ""
        speedText.text = ""
    }

    private fun hideActionButton() {
        actionButton.visibility = View.GONE
        actionButton.tag = null
    }

    private fun showPlayButton() {
        actionButton.visibility = View.VISIBLE
        actionButton.setText(R.string.action_play)
        actionButton.tag = TAG_PLAY
    }

    private fun showRetryButton() {
        actionButton.visibility = View.VISIBLE
        actionButton.setText(R.string.action_retry)
        actionButton.tag = TAG_RETRY
    }

    private fun launchGame() {
        // Best-effort: try the published game package first. If it is not
        // installed (this APK is being run standalone), tell the user.
        val pm = packageManager
        val intent = try {
            pm.getLaunchIntentForPackage(BuildConfig.GAME_PACKAGE)
        } catch (_: Throwable) {
            null
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
            return
        }
        // Fallback: try to launch by component name in case the launcher and
        // the game are merged into a single APK.
        runCatching {
            val direct = Intent().apply {
                setClassName(BuildConfig.GAME_PACKAGE, BuildConfig.GAME_ACTIVITY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(direct)
            finish()
        }.onFailure {
            Log.w(TAG, "Game is not installed: ${BuildConfig.GAME_PACKAGE}", it)
            status.setText(R.string.error_game_not_installed)
        }
    }

    private fun toMegabytes(bytes: Long): Int =
        ((bytes / (1024.0 * 1024.0)) + 0.5).toInt()

    private fun humanBytes(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytesPerSecond.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.size - 1) {
            value /= 1024.0
            idx++
        }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[idx])
    }

    @Deprecated("Match parent behavior — we want back press to leave the launcher running in the background")
    override fun onBackPressed() {
        // The user can leave; the service keeps downloading.
        if (Build.VERSION.SDK_INT >= 21) {
            moveTaskToBack(true)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    companion object {
        private const val TAG = "InstallActivity"
        private const val TAG_RETRY = "retry"
        private const val TAG_PLAY = "play"
    }
}
