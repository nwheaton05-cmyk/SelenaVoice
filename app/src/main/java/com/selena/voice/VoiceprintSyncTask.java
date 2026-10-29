package com.selena.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Nick 2026-10-28 v1.53 (Phase 4, spec §5): the voiceprint archive
 * pipeline between the phone and the server store
 * (/api/selena-voice/voiceprint, JWT-authenticated — the archive
 * lives UNDER the signed-in user's profile, inside the per-user wall).
 *
 * MODE_SYNC (opportunistic; throttled to one attempt per 6h):
 *   1. Upload local measurement records older than a week; only when
 *      the server confirms (HTTP 200) are they removed locally — the
 *      local week is the working set, the server holds the archive.
 *   2. If the local profile has no vote yet and the archive hasn't
 *      been folded in, fetch the archive and re-seed the profile
 *      (new phone / reinstall: the learning is referenced, not lost).
 * The scheduled midnight runner that drives the server-side aging is
 * Phase 5; this task is the callable client half.
 *
 * MODE_RESET: ask the server to delete the user's whole archive
 * (paired with the local reset in VoiceService).
 *
 * Measurements only ever cross the wire — features, class, weight,
 * day. There is no audio to send (none is stored).
 *
 * Top-level class per the standing d8 rule. All methods block on
 * network IO — run on a worker thread only.
 */
public class VoiceprintSyncTask implements Runnable {
    private static final String TAG = "SelenaVoice";
    // The assistant brain and its routes live on the AR deployment
    // (apk-ar branch); the main domain's build does not carry them.
    private static final String ENDPOINT =
            "https://ar.siteviewoperations.com/api/selena-voice/voiceprint";
    private static final String PREFS = "voiceprint_sync";
    private static final String K_LAST = "last_attempt_ms";
    private static final long THROTTLE_MS = 6 * 3600 * 1000L;

    public static final String MODE_SYNC = "sync";
    public static final String MODE_RESET = "reset";

    private final Context ctx;
    private final String mode;
    private final Voiceprint voiceprint; // may be null in MODE_RESET from UI

    public VoiceprintSyncTask(Context ctx, String mode) {
        this(ctx, mode, null);
    }

    public VoiceprintSyncTask(Context ctx, String mode, Voiceprint vp) {
        this.ctx = ctx.getApplicationContext();
        this.mode = mode;
        this.voiceprint = vp;
    }

    @Override
    public void run() {
        try {
            String token = SupabaseAuth.validAccessToken(ctx);
            if (token == null) {
                Log.i(TAG, "voiceprint sync: no valid token (signed out?) — skipped");
                return;
            }
            if (MODE_RESET.equals(mode)) {
                JSONObject body = new JSONObject();
                body.put("action", "reset");
                JSONObject resp = post(body, token);
                Log.i(TAG, "voiceprint sync: server archive reset -> " + resp);
                return;
            }
            // MODE_SYNC with throttle.
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            if (now - p.getLong(K_LAST, 0) < THROTTLE_MS) return;
            p.edit().putLong(K_LAST, now).apply();

            Voiceprint vp = (voiceprint != null) ? voiceprint : new Voiceprint(ctx);
            // 1) Upload aged records (> 1 week local, §5 lifecycle).
            List<JSONObject> aged = vp.agedRecords(Voiceprint.UPLOAD_AGE_DAYS);
            if (!aged.isEmpty()) {
                JSONArray recs = new JSONArray();
                for (JSONObject r : aged) {
                    JSONObject o = new JSONObject();
                    o.put("ts", r.optLong("ts", 0));
                    o.put("day", r.optString("day", ""));
                    o.put("cls", r.optString("cls", "normal"));
                    o.put("w", r.optDouble("w", 0.7));
                    o.put("f", r.optJSONArray("f"));
                    recs.put(o);
                }
                JSONObject body = new JSONObject();
                body.put("action", "upload");
                body.put("records", recs);
                JSONObject resp = post(body, token);
                if (resp != null && resp.optBoolean("ok", false)) {
                    vp.removeRecords(aged);
                    Log.i(TAG, "voiceprint sync: uploaded " + aged.size()
                            + " aged measurements; local copies removed");
                } else {
                    Log.w(TAG, "voiceprint sync: upload not confirmed (" + resp
                            + ") — records kept locally");
                }
            }
            // 2) Re-seed an empty profile from the archive.
            if (!vp.voiceHasVote() && !vp.archiveAlreadyFolded()) {
                JSONObject body = new JSONObject();
                body.put("action", "archive");
                JSONObject resp = post(body, token);
                if (resp != null && resp.optBoolean("ok", false)) {
                    JSONArray arr = resp.optJSONArray("records");
                    if (arr != null && arr.length() > 0) {
                        List<JSONObject> list = new ArrayList<>();
                        for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
                        vp.foldArchiveIntoProfile(list);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "voiceprint sync failed", e);
        }
    }

    private JSONObject post(JSONObject body, String token) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300)
                    ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            if (code < 200 || code >= 300) {
                Log.w(TAG, "voiceprint sync: HTTP " + code + ": " + sb);
                return null;
            }
            return new JSONObject(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "voiceprint sync: post failed", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
