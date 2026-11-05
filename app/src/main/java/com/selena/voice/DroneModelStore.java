package com.selena.voice;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Nick 2026-10-29 v1.61: the Drone Dictation ASR model — a streaming
 * Zipformer (LibriSpeech 20M, int8) downloaded ONCE into app-private
 * storage and SHA-256 verified, NOT baked into the APK (the design
 * rule: the v1.60 APK is 16 MB and Nick notices size; the model is
 * 43.6 MB and never changes between app updates). Checkpoint chosen
 * and load-tested 2026-10-29 against the sherpa-onnx runtime
 * family: both bundled test wavs decoded essentially perfectly
 * (1.wav exact; 0.wav one rare-word error).
 *
 * Files live in the public selena-voice storage bucket beside the
 * APKs, under asr-en-20m/. Any failure (offline, checksum mismatch,
 * partial write) leaves the model "not ready" and Drone Dictation
 * simply never arms — the gate behaves exactly as v1.60 (fail
 * open). Downloads are staged to .tmp and renamed only after the
 * checksum passes, so a half-download can never look complete.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class DroneModelStore {
    private static final String TAG = "SelenaVoice";
    public static final String DIR_NAME = "asr-en-20m";
    private static final String BASE_URL =
            "https://api.siteviewoperations.com/storage/v1/object/public/"
                    + "selena-voice/asr-en-20m/";

    public static final String ENCODER = "encoder-epoch-99-avg-1.int8.onnx";
    public static final String DECODER = "decoder-epoch-99-avg-1.int8.onnx";
    public static final String JOINER = "joiner-epoch-99-avg-1.int8.onnx";
    public static final String TOKENS = "tokens.txt";

    private static final String[][] FILES = {
            {ENCODER, "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3"},
            {DECODER, "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c"},
            {JOINER, "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e"},
            {TOKENS, "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"},
    };
    private static final String MARKER = ".verified";

    public static File dir(Context ctx) {
        File d = new File(ctx.getFilesDir(), DIR_NAME);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static File file(Context ctx, String name) {
        return new File(dir(ctx), name);
    }

    /** True only when every file is present AND checksum-verified. */
    public static boolean isReady(Context ctx) {
        if (!new File(dir(ctx), MARKER).exists()) return false;
        for (String[] f : FILES) {
            File fp = file(ctx, f[0]);
            if (!fp.exists() || fp.length() == 0) return false;
        }
        return true;
    }

    /**
     * Ensure the model is present, downloading on a worker thread if
     * needed. onDone runs on that worker thread with the readiness
     * result. Safe to call repeatedly; a downloader already running
     * is not duplicated (the caller serializes via its own flag).
     */
    public static void ensureAsync(Context ctx, Runnable onDone) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            boolean ok = isReady(app);
            if (!ok) {
                ok = downloadAll(app);
            }
            Log.i(TAG, "drone: ASR model " + (ok ? "ready" : "NOT ready")
                    + " (streaming zipformer en-20M int8, 43.6 MB)");
            if (onDone != null) onDone.run();
        }, "SelenaDroneModel").start();
    }

    private static boolean downloadAll(Context ctx) {
        try {
            // A stale marker from a partial state must not survive.
            File marker = new File(dir(ctx), MARKER);
            if (marker.exists()) //noinspection ResultOfMethodCallIgnored
                marker.delete();
            for (String[] f : FILES) {
                File dest = file(ctx, f[0]);
                if (dest.exists() && sha256(dest).equals(f[1])) continue;
                File tmp = new File(dir(ctx), f[0] + ".tmp");
                Log.i(TAG, "drone: downloading ASR model file " + f[0]);
                if (!download(BASE_URL + f[0], tmp)) return false;
                String got = sha256(tmp);
                if (!got.equals(f[1])) {
                    Log.w(TAG, "drone: checksum mismatch for " + f[0]
                            + " — discarding");
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return false;
                }
                if (dest.exists()) //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                if (!tmp.renameTo(dest)) return false;
            }
            FileOutputStream out = new FileOutputStream(marker);
            out.write("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.close();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "drone: model download failed", e);
            return false;
        }
    }

    private static boolean download(String urlStr, File dest) {
        HttpURLConnection conn = null;
        InputStream in = null;
        FileOutputStream out = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "drone: model GET " + urlStr + " -> " + code);
                return false;
            }
            in = conn.getInputStream();
            out = new FileOutputStream(dest);
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            out.flush();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "drone: download failed " + urlStr, e);
            return false;
        } finally {
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = new java.io.FileInputStream(f);
        byte[] buf = new byte[65536];
        int r;
        while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
