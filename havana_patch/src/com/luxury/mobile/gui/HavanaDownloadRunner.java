package com.luxury.mobile.gui;

import android.app.Activity;
import android.content.Intent;
import android.os.Environment;
import android.util.Log;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.luxury.mobile.util.HavanaInstallCheck;
import com.luxury.mobile.util.HavanaSmartDownload;

import java.io.File;

/**
 * Runnable that drives the install pipeline for HavanaRP:
 *
 * <ol>
 *   <li>If {@code /sdcard/LuxuryMobile/} already contains a valid install
 *       (auto or manual), skip the network entirely and forward to
 *       {@link MenuActivity}.</li>
 *   <li>Otherwise download the archive with {@link HavanaSmartDownload},
 *       which supports HTTP Range so partial downloads resume from the saved
 *       byte offset on the next launch.</li>
 *   <li>On success run the existing {@code UnZip()} method on the activity to
 *       extract the data into the game directory and forward to
 *       {@link MenuActivity}.</li>
 * </ol>
 *
 * <p>All UI updates are marshalled to the main thread via
 * {@link Activity#runOnUiThread(Runnable)}.  Logic runs entirely on the
 * background thread that the install activity spawns from {@code getFileSize}.
 */
public final class HavanaDownloadRunner implements Runnable {

    private static final String TAG = "HavanaRunner";
    private static final long PROGRESS_THROTTLE_MS = 250L;

    private final InstallActivity activity;
    private final String url;

    private long lastProgressPostMs = 0L;

    public HavanaDownloadRunner(InstallActivity activity, String url) {
        this.activity = activity;
        this.url = url;
    }

    @Override
    public void run() {
        // ---- skip-existing check ----
        File dataRoot = new File(Environment.getExternalStorageDirectory(),
                "LuxuryMobile");
        if (HavanaInstallCheck.isAlreadyInstalled(dataRoot)) {
            Log.i(TAG, "Skip-existing: data already present, jumping to MenuActivity");
            launchMenu();
            return;
        }

        final String pathZip = activity.path_zip;

        // ---- progress + completion sink ----
        HavanaSmartDownload.ProgressCallback cb = new HavanaSmartDownload.ProgressCallback() {
            @Override
            public void onProgress(final long downloaded, final long total) {
                long now = System.currentTimeMillis();
                if (now - lastProgressPostMs < PROGRESS_THROTTLE_MS) {
                    return;
                }
                lastProgressPostMs = now;
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updateProgressUi(downloaded, total);
                    }
                });
            }

            @Override
            public void onCompleted(File downloadedFile) {
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showExtractingUi();
                    }
                });
                try {
                    activity.UnZip();
                } catch (Exception e) {
                    Log.e(TAG, "UnZip failed: " + e.getMessage());
                }
            }

            @Override
            public void onFailed(String message) {
                Log.e(TAG, "Download failed: " + message);
                activity.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showFailureUi(message);
                    }
                });
            }
        };

        HavanaSmartDownload.run(activity, url, pathZip, cb);
    }

    private void updateProgressUi(long downloaded, long total) {
        TextView t7 = activity.textView7;
        TextView t6 = activity.textView6;
        ProgressBar bar = activity.progressBarInstall;
        if (t7 != null) {
            t7.setText(humanMb(downloaded) + " MB / " + humanMb(total) + " MB");
        }
        if (total > 0) {
            int pct = (int) ((downloaded * 100L) / total);
            if (t6 != null) t6.setText(pct + "%");
            if (bar != null) bar.setProgress(pct);
        }
    }

    private void showExtractingUi() {
        if (activity.textview5 != null) {
            activity.textview5.setText("\u062c\u0627\u0631\u064d \u0641\u0643"
                    + " \u0636\u063a\u0637 \u0645\u0644\u0641\u0627\u062a"
                    + " \u0627\u0644\u0644\u0639\u0628\u0629...");
        }
        if (activity.textView6 != null) activity.textView6.setVisibility(android.view.View.INVISIBLE);
        if (activity.textView7 != null) activity.textView7.setVisibility(android.view.View.INVISIBLE);
        if (activity.progressBarInstall != null) activity.progressBarInstall.setVisibility(android.view.View.INVISIBLE);
        if (activity.progressBar != null) activity.progressBar.setVisibility(android.view.View.VISIBLE);
    }

    private void showFailureUi(String message) {
        if (activity.textview5 != null) {
            activity.textview5.setText("\u062a\u0639\u0630\u0631 \u0627\u0644\u062a\u062d\u0645\u064a\u0644: " + message);
        }
    }

    private void launchMenu() {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(activity,
                            Class.forName("com.luxury.mobile.gui.MenuActivity"));
                    activity.startActivity(intent);
                    activity.overridePendingTransition(0, 0);
                    activity.finish();
                } catch (Throwable t) {
                    Log.e(TAG, "Cannot launch MenuActivity: " + t.getMessage());
                }
            }
        });
    }

    private static long humanMb(long bytes) {
        return Math.max(0L, bytes) / (1024L * 1024L);
    }
}
