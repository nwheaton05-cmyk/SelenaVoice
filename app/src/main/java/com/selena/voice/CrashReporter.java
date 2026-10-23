package com.selena.voice;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Nick 2026-10-23 v1.36: the 07:43 crash killed the app WITHOUT any crash
 * report reaching the server — we had the "has a bug" dialog and nothing
 * else. This global handler captures the stack trace for the NEXT crash:
 * it appends timestamp + app version + trace to crash_reports.log in the
 * app files dir (survives the kill), then best-efforts a POST to the
 * assistant_crash_reports table. The reporter itself can never throw —
 * everything is guarded, and the previous default handler always runs
 * last so the system dialog + process kill behave exactly as before.
 *
 * Top-level (avoids d8 inner-class bug).
 */
public class CrashReporter implements Thread.UncaughtExceptionHandler {
    private static final String TAG = "SelenaVoice";
    private static final String FILE_NAME = "crash_reports.log";
    private static final long MAX_FILE_BYTES = 256 * 1024;

    // Same project/key the service already uses for queue polling.
    private static final String SUPABASE_HOST = "pqkfqzioimkobuhcjwdk.supabase.co";
    private static final String SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBxa2ZxemlvaW1rb2J1aGNqd2RrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzMjg2NTIsImV4cCI6MjEwNjkwNDY1Mn0.eNem2zAIA4_MPtWMI0tiErtu7tGNAtkLTnsCA5lSPVw";

    private final Context appContext;
    private final Thread.UncaughtExceptionHandler prev;

    private CrashReporter(Context ctx, Thread.UncaughtExceptionHandler prev) {
        this.appContext = ctx.getApplicationContext();
        this.prev = prev;
    }

    /**
     * Install once. Safe to call from both MainActivity.onCreate and
     * VoiceService.onCreate — the second call is a no-op.
     */
    public static synchronized void install(Context ctx) {
        try {
            Thread.UncaughtExceptionHandler cur =
                    Thread.getDefaultUncaughtExceptionHandler();
            if (cur instanceof CrashReporter) return; // already installed
            Thread.setDefaultUncaughtExceptionHandler(new CrashReporter(ctx, cur));
            Log.i(TAG, "crash reporter installed");
        } catch (Throwable t) {
            // Never break startup.
            Log.w(TAG, "crash reporter install failed", t);
        }
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        try {
            String report = buildReport(t, e);
            appendToFile(report);
            postToServer(report, t);
        } catch (Throwable ignored) {
            // The reporter must never throw.
        } finally {
            // Always let the previous handler run: system "has a bug"
            // dialog + process kill behave exactly as before.
            if (prev != null) {
                try {
                    prev.uncaughtException(t, e);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private String buildReport(Thread t, Throwable e) {
        String when;
        try {
            when = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date());
        } catch (Exception ex) {
            when = "?";
        }
        String version = "?";
        try {
            version = appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        try {
            e.printStackTrace(pw);
        } catch (Exception ignored) {
        }
        pw.flush();
        StringBuilder sb = new StringBuilder();
        sb.append("=== crash ").append(when)
                .append(" v").append(version)
                .append(" thread=").append(t != null ? t.getName() : "?")
                .append(" ===\n");
        sb.append(sw.toString());
        sb.append("\n");
        return sb.toString();
    }

    private void appendToFile(String report) {
        try {
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            // Rotate: never let the log grow unbounded.
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
            w.write(report);
            w.flush();
            w.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * Best-effort POST to assistant_crash_reports. Runs on a worker thread
     * with a bounded join so a dead network can't delay process death.
     * Failures are swallowed — the file copy above is the durable record.
     */
    private void postToServer(final String report, final Thread t) {
        Thread net = new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                JSONObject body = new JSONObject();
                body.put("report_type", "crash");
                body.put("stack_trace", report);
                body.put("device_model", safe(Build.MODEL));
                body.put("android_version", safe(Build.VERSION.RELEASE));
                body.put("app_version", appVersion());
                body.put("screen", "unknown");
                body.put("details", "thread=" + (t != null ? t.getName() : "?"));
                body.put("status", "open");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);

                URL url = new URL("https://" + SUPABASE_HOST
                        + "/rest/v1/assistant_crash_reports");
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("apikey", SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + SUPABASE_ANON_KEY);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Prefer", "return=minimal");
                conn.getOutputStream().write(bytes);
                int code = conn.getResponseCode();
                Log.i(TAG, "crash report POST -> " + code);
            } catch (Exception ignored) {
            } finally {
                if (conn != null) {
                    try {
                        conn.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
        try {
            net.start();
            // Bounded wait: don't hold the dying process hostage on a
            // stalled network. The file copy is already durable.
            net.join(5000);
        } catch (Exception ignored) {
        }
    }

    private String appVersion() {
        try {
            return appContext.getPackageManager()
                    .getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            return "?";
        }
    }

    private static String safe(String s) {
        return s != null ? s : "?";
    }
}
