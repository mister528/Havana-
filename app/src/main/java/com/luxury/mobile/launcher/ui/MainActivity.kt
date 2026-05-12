package com.luxury.mobile.launcher.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.luxury.mobile.launcher.R

/**
 * Entry point. Its job is to (1) collect any runtime permissions the launcher
 * needs and (2) hand off to [InstallActivity] which handles the heavy lifting.
 *
 * The activity is `noHistory` so the user can never end up back here after a
 * successful launch — pressing back from the install screen exits the app
 * cleanly.
 */
class MainActivity : AppCompatActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { proceedToInstall() }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { proceedToInstall() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        // Android 11+: prefer MANAGE_EXTERNAL_STORAGE so we can write into the
        // legacy /sdcard/LuxuryMobile/ folder the existing game data lives in.
        if (Build.VERSION.SDK_INT >= 30) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        .setData(Uri.parse("package:$packageName"))
                    manageStorageLauncher.launch(intent)
                    return
                } catch (_: Throwable) {
                    // Fall through to legacy permission request.
                }
            }
            // Permission already granted or activity unavailable — keep going.
            proceedToInstall()
            return
        }

        val toRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            toRequest += Manifest.permission.POST_NOTIFICATIONS
        }
        if (toRequest.isEmpty()) {
            proceedToInstall()
        } else {
            permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    private fun proceedToInstall() {
        startActivity(Intent(this, InstallActivity::class.java))
        overridePendingTransition(0, 0)
        finish()
    }
}
