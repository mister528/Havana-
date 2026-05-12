package com.luxury.mobile.util;

import android.app.Activity;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart resumable downloader for HavanaRP launcher.
 *
 * <p>Replaces the original truncate-and-restart download loop with one that
 * supports HTTP Range requests so the launcher can resume after the user
 * leaves the activity, switches networks, or the process is killed.
 *
 * <p>Solves three player-reported problems:
 * <ol>
 *   <li>If a download reaches 50% and the player exits, the next launch
 *       resumes from the saved byte offset instead of re-downloading.</li>
 *   <li>Because the work happens on a background thread inside a foreground
 *       service, the download keeps progressing while the player is in another
 *       app.</li>
 *   <li>{@link HavanaInstallCheck#isAlreadyInstalled(File)} short-circuits the
 *       whole flow when {@code /sdcard/LuxuryMobile/} already has the data.</li>
 * </ol>
 */
public final class HavanaSmartDownload {

    private static final String TAG = "HavanaDL";
    private static final int BUFFER = 64 * 1024;
    private static final long META_FLUSH_BYTES = 256 * 1024;
    private static final int MAX_ATTEMPTS = 6;
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    private HavanaSmartDownload() {}

    /**
     * Callback fired from the worker thread.  All UI work must be marshalled
     * to the main thread by the caller.
     */
    public interface ProgressCallback {
        void onProgress(long downloadedBytes, long totalBytes);
        void onCompleted(File downloadedFile);
        void onFailed(String message);
    }

    /**
     * Runs the download synchronously on the calling thread.  The caller is
     * expected to invoke this from a background {@link Thread} (preserving the
     * existing launcher architecture).
     *
     * @param activity the install activity, used only for callback marshaling
     *                 of UI updates
     * @param url      the source URL (Dropbox / CDN)
     * @param pathZip  the absolute destination path of the finished archive
     * @param cb       progress / completion sink
     */
    public static void run(Activity activity, String url, String pathZip, ProgressCallback cb) {
        File target = new File(pathZip);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }

        File partFile = new File(pathZip + ".part");
        File metaFile = new File(pathZip + ".meta");

        long resumeFrom = readResumeOffset(metaFile, partFile);
        long totalBytes = -1L;
        AtomicBoolean stop = new AtomicBoolean(false);
        Exception lastError = null;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !stop.get(); attempt++) {
            HttpURLConnection conn = null;
            InputStream in = null;
            RandomAccessFile out = null;
            try {
                URL link = new URL(url);
                conn = (HttpURLConnection) link.openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) HavanaRP-Launcher");
                conn.setRequestProperty("Accept", "*/*");
                if (resumeFrom > 0) {
                    conn.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
                }
                conn.connect();
                int code = conn.getResponseCode();

                if (resumeFrom > 0 && code != HttpURLConnection.HTTP_PARTIAL) {
                    // Server ignored Range header — discard partial bytes and
                    // restart from byte 0 to avoid producing a corrupt file.
                    Log.w(TAG, "Server ignored Range (code=" + code + "); restarting");
                    closeQuietly(in);
                    closeQuietly(out);
                    conn.disconnect();
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                    //noinspection ResultOfMethodCallIgnored
                    metaFile.delete();
                    resumeFrom = 0L;
                    attempt = 0;
                    continue;
                }

                if (code != HttpURLConnection.HTTP_OK
                        && code != HttpURLConnection.HTTP_PARTIAL) {
                    throw new IOException("HTTP " + code + " for " + url);
                }

                long contentLength = conn.getContentLengthLong();
                totalBytes = contentLength >= 0
                        ? contentLength + resumeFrom
                        : -1L;

                // Already-installed check: target file exists with matching
                // size on disk?  Skip the network entirely.
                if (totalBytes > 0 && target.exists() && target.length() == totalBytes) {
                    conn.disconnect();
                    Log.i(TAG, "Target already complete at " + pathZip);
                    cb.onCompleted(target);
                    return;
                }

                in = conn.getInputStream();
                out = new RandomAccessFile(partFile, "rw");
                out.seek(resumeFrom);

                byte[] buf = new byte[BUFFER];
                long downloaded = resumeFrom;
                long bytesSinceFlush = 0;
                int read;
                while ((read = in.read(buf)) != -1) {
                    out.write(buf, 0, read);
                    downloaded += read;
                    bytesSinceFlush += read;
                    if (bytesSinceFlush >= META_FLUSH_BYTES) {
                        writeMeta(metaFile, url, downloaded, totalBytes);
                        bytesSinceFlush = 0;
                    }
                    cb.onProgress(downloaded, totalBytes);
                }

                closeQuietly(in);
                in = null;
                try {
                    out.getFD().sync();
                } catch (IOException ignored) {
                    // best effort
                }
                closeQuietly(out);
                out = null;
                conn.disconnect();
                conn = null;

                // Final atomic move: .part -> target.
                if (target.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                }
                if (!partFile.renameTo(target)) {
                    // Fall back to copy + delete (cross-filesystem case).
                    copy(partFile, target);
                    //noinspection ResultOfMethodCallIgnored
                    partFile.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                metaFile.delete();

                Log.i(TAG, "Download complete: " + pathZip + " (" + downloaded + " bytes)");
                cb.onCompleted(target);
                return;

            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "Attempt " + attempt + " failed: " + e.getMessage());
                // Preserve current progress; do not delete .part.
                resumeFrom = partFile.length();
                writeMeta(metaFile, url, resumeFrom, totalBytes);
                long backoff = Math.min(30_000L, 1500L * (1L << Math.min(attempt - 1, 4)));
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    stop.set(true);
                }
            } finally {
                closeQuietly(in);
                closeQuietly(out);
                if (conn != null) {
                    try { conn.disconnect(); } catch (Exception ignored) {}
                }
            }
        }

        String msg = lastError != null ? lastError.getMessage() : "Unknown error";
        Log.e(TAG, "Download permanently failed: " + msg);
        cb.onFailed(msg == null ? "Network error" : msg);
    }

    /** Read the byte offset saved in the .meta sidecar (or 0 if none). */
    private static long readResumeOffset(File metaFile, File partFile) {
        if (!partFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            metaFile.delete();
            return 0L;
        }
        long partLen = partFile.length();
        if (!metaFile.exists()) {
            return partLen;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(metaFile);
            byte[] all = readAll(fis);
            String text = new String(all, "UTF-8").trim();
            // Format: downloaded|total|url
            int firstBar = text.indexOf('|');
            if (firstBar > 0) {
                long downloaded = Long.parseLong(text.substring(0, firstBar));
                return Math.min(downloaded, partLen);
            }
        } catch (Exception e) {
            Log.w(TAG, "Bad meta file: " + e.getMessage());
        } finally {
            closeQuietly(fis);
        }
        return partLen;
    }

    /** Atomic-ish meta write: write to .tmp then rename. */
    private static void writeMeta(File metaFile, String url, long downloaded, long total) {
        File tmp = new File(metaFile.getAbsolutePath() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp);
            String body = downloaded + "|" + total + "|" + url;
            fos.write(body.getBytes("UTF-8"));
            fos.getFD().sync();
            fos.close();
            fos = null;
            if (metaFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                metaFile.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(metaFile);
        } catch (Exception e) {
            Log.w(TAG, "Cannot persist meta: " + e.getMessage());
        } finally {
            closeQuietly(fos);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private static void copy(File from, File to) throws IOException {
        FileInputStream fin = null;
        FileOutputStream fout = null;
        try {
            fin = new FileInputStream(from);
            fout = new FileOutputStream(to);
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = fin.read(buf)) != -1) {
                fout.write(buf, 0, n);
            }
            fout.getFD().sync();
        } finally {
            closeQuietly(fin);
            closeQuietly(fout);
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    /** Convenience: returns the default /sdcard/LuxuryMobile data root. */
    public static File defaultDataRoot() {
        return new File(Environment.getExternalStorageDirectory(), "LuxuryMobile");
    }
}
