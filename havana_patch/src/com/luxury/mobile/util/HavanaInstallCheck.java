package com.luxury.mobile.util;

import android.os.Environment;
import android.util.Log;

import java.io.File;

/**
 * Checks whether the game data directory already contains a complete install.
 *
 * <p>This solves the player-reported issue where manually copying data files
 * into {@code /sdcard/LuxuryMobile/} is ignored and the launcher re-downloads
 * everything from scratch.
 *
 * <p>Heuristic: the directory exists, contains at least 20 files recursively,
 * and their total size exceeds 100 MB — which any legitimate install (or
 * manual copy) of the ~300 MB data pack will satisfy.
 */
public final class HavanaInstallCheck {

    private static final String TAG = "HavanaCheck";
    private static final long MIN_SIZE_BYTES = 100_000_000L;
    private static final int MIN_FILE_COUNT = 20;

    private HavanaInstallCheck() {}

    /**
     * Returns {@code true} if the given directory already contains enough data
     * to be considered a valid install (automatic or manual), meaning the
     * download step can be skipped entirely.
     */
    public static boolean isAlreadyInstalled(File dataDir) {
        if (dataDir == null || !dataDir.isDirectory()) {
            return false;
        }
        long[] sizeAndCount = {0L, 0L};
        countRecursive(dataDir, sizeAndCount);
        boolean installed = sizeAndCount[0] >= MIN_SIZE_BYTES
                && sizeAndCount[1] >= MIN_FILE_COUNT;
        Log.i(TAG, dataDir.getAbsolutePath() + ": size="
                + sizeAndCount[0] + " files=" + (int) sizeAndCount[1]
                + " installed=" + installed);
        return installed;
    }

    /** Quick check using the default data root ({@code /sdcard/LuxuryMobile/}). */
    public static boolean isDefaultInstallPresent() {
        File root = new File(Environment.getExternalStorageDirectory(), "LuxuryMobile");
        return isAlreadyInstalled(root);
    }

    private static void countRecursive(File dir, long[] out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) {
                countRecursive(f, out);
            } else {
                out[0] += f.length();
                out[1] += 1;
            }
            // Early exit: no need to keep counting once thresholds are met.
            if (out[0] >= MIN_SIZE_BYTES && out[1] >= MIN_FILE_COUNT) return;
        }
    }
}
