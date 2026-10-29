package com.selena.voice;

import android.util.Log;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Nick 2026-10-28 v1.55 (Phase 5, spec §8): the sender side of
 * playback-progress reporting. Phase 3's brain keeps per-sentence
 * fraction accounting on assistant_queue_items (played_sentences /
 * total) — that's what the interruption pull-back uses to know how
 * much of a message was actually DELIVERED vs still pending — but no
 * client ever reported progress, so the fractions never moved.
 *
 * The app knows the selena_voice_queue ROW it just finished playing,
 * not the brain item; the brain endpoint resolves row → item via
 * queue_row_ids and advances the count (action 'playback_row_played').
 * One report per completed row, fire-and-forget: a lost report only
 * makes the fraction conservative (the brain never moves the count
 * backwards), it can never corrupt it.
 *
 * Same posture as /api/selena-voice/input: the brain route is the
 * assistant's machine path (no user credential presented). The brain
 * routes live on the AR deployment (apk-ar branch), same as the
 * voiceprint endpoint.
 *
 * Top-level class per the standing d8 rule. Blocks on network IO —
 * run on a worker thread only.
 *
 * v1.56 (2026-10-29): generalized with an action parameter so the
 * same wire also reports a user STOP (action 'playback_stopped').
 * Delete-once-heard (Nick, 2026-10-29): a stopped message never
 * resumes, so the brain deletes the owning item's queue rows +
 * stored audio at stop. A plain PAUSE is never reported on this
 * wire — paused messages keep everything.
 *
 * (A 'playback_pause_dictate' action briefly existed in v1.57 under
 * an 08:40 draft rule; Nick revised the rule at 08:41 the same
 * morning — paused messages are held and delete server-side when
 * the rework's successor message is queued — so v1.58 removed the
 * wire again. This class carries played/stopped reports only.)
 */
public class PlaybackProgressTask implements Runnable {
    private static final String TAG = "SelenaVoice";
    private static final String ENDPOINT =
            "https://ar.siteviewoperations.com/api/selena-voice/brain";

    private final String queueRowId;
    private final String action;

    public PlaybackProgressTask(String queueRowId) {
        this(queueRowId, "playback_row_played");
    }

    public PlaybackProgressTask(String queueRowId, String action) {
        this.queueRowId = queueRowId;
        this.action = action;
    }

    @Override
    public void run() {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("action", action);
            body.put("queueRowId", queueRowId);
            URL url = new URL(ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = conn.getResponseCode();
            Log.i(TAG, "playback report (" + action + "): row " + queueRowId + " -> HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "playback report failed (" + action + ", row " + queueRowId + ")", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
