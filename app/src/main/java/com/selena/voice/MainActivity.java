package com.selena.voice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Selena Voice main UI (Nick, 2026-10-18).
 *
 * - Status line, always-listening toggle, pulse dot (flashes when speech
 *   is detected), message log, and a mic button as manual fallback.
 * - Starts the VoiceService foreground service on launch (inbound mic only).
 * - Requests RECORD_AUDIO + POST_NOTIFICATIONS at runtime.
 *
 * Nick 2026-10-18 v1.4: OUTBOUND polling (Selena -> Nick) moved here from
 * VoiceService. Samsung's background execution limits kill the service's
 * Handler loop, so the service never polled ("Poll: not started" forever).
 * The activity polls /api/selena-voice/queue every 3s while it is in the
 * foreground and plays Selena's voice messages itself. The app must be
 * open for her messages to arrive — better than nothing working at all.
 */
public class MainActivity extends Activity {
    private static final String TAG = "SelenaVoice";
    private static final String BASE_URL = "https://siteview-pi.vercel.app";
    private static final long POLL_MS = 3000;

    // Nick 2026-10-18 v1.6: remote-visible log buffer. The app is Selena's
    // "ears and mouth" — she needs to see poll/download/playback internals
    // without Nick sending screenshots. Entries are uploaded every 30s
    // alongside the heartbeat, then cleared.
    private static final int MAX_LOG_ENTRIES = 200;
    private static final java.util.List<String> LOG_BUFFER = new java.util.ArrayList<>();

    /**
     * Remote-visible log: appends a timestamped entry to the in-memory
     * buffer (uploaded every 30s) AND writes to logcat as usual.
     */
    public static void log(String msg) {
        String entry = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + " " + msg;
        synchronized (LOG_BUFFER) {
            LOG_BUFFER.add(entry);
            while (LOG_BUFFER.size() > MAX_LOG_ENTRIES) LOG_BUFFER.remove(0);
        }
        Log.i(TAG, msg);
    }
    private static final String PREFS = "selena_voice";
    private static final String KEY_LAST_TS = "last_ts";

    private TextView statusText;
    private TextView pollStatusText;
    private TextView logText;
    private ScrollView logScroll;
    private Switch alwaysOnSwitch;
    private View pulseDot;
    private ObjectAnimator pulseAnimator;

    private final MessageReceiver receiver;

    // v1.4: outbound polling + playback owned by the activity
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private boolean polling = false;

    // v1.6: app-open heartbeat — tells the server the app is visible so the
    // fast responder only burns tokens while Nick can hear replies.
    private static final long HEARTBEAT_MS = 30000;
    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private boolean heartbeating = false;
    private MediaPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private final Queue<File> audioQueue = new LinkedList<>();
    private boolean playing = false;
    private File currentAudioFile;

    public MainActivity() {
        receiver = new MessageReceiver(this);
    }

    /** Called by MessageReceiver when Selena has a new voice message. */
    public void onSelenaMessage(String text) {
        appendLog("Selena: " + text);
    }

    /** Called by MessageReceiver when Nick's voice input was sent. */
    public void onInputSent(String text) {
        appendLog("You: " + text);
        runOnUiThread(() ->
                Toast.makeText(this, "Sent to Selena", Toast.LENGTH_SHORT).show());
    }

    /** Called by MessageReceiver when speech is detected (pulse the dot). */
    public void onSpeechDetected() {
        pulse();
    }

    /** Called by MessageReceiver when the listening state changes. */
    public void onListeningState(boolean listening) {
        updateListeningUi(listening);
    }

    /** Called by MessageReceiver with the last queue-poll result. */
    public void onPollStatus(String status) {
        if (status == null) return;
        setPollStatus(status);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.statusText);
        pollStatusText = findViewById(R.id.pollStatusText);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        alwaysOnSwitch = findViewById(R.id.alwaysOnSwitch);
        pulseDot = findViewById(R.id.pulseDot);
        Button micButton = findViewById(R.id.micButton);

        alwaysOnSwitch.setChecked(VoiceService.getAlwaysListening(this));
        alwaysOnSwitch.setOnCheckedChangeListener((v, on) ->
                VoiceService.setAlwaysListening(this, on));

        micButton.setOnClickListener(v -> {
            Intent i = new Intent(this, VoiceService.class);
            i.setAction("MANUAL_LISTEN");
            startForegroundService(i);
            Toast.makeText(this, "Listening… speak now", Toast.LENGTH_SHORT).show();
        });

        requestPermissions();
        // Service starts after permission grant (see onRequestPermissionsResult).
        // If already granted, start now.
        if (hasAllPermissions()) {
            startVoiceService();
        }
    }

    private boolean hasAllPermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (hasAllPermissions()) {
            startVoiceService();
        } else {
            Toast.makeText(this,
                    "Selena Voice needs mic + notification permissions to work",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), 1);
        }
    }

    private void startVoiceService() {
        Intent i = new Intent(this, VoiceService.class);
        startForegroundService(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(VoiceService.ACTION_MESSAGE);
        f.addAction(VoiceService.ACTION_INPUT_SENT);
        f.addAction(VoiceService.ACTION_SPEECH_DETECTED);
        f.addAction(VoiceService.ACTION_LISTENING_STATE);
        f.addAction(VoiceService.ACTION_POLL_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, f);
        }
        startPolling();
        startHeartbeat();
    }

    @Override
    protected void onPause() {
        stopPolling();
        stopHeartbeat();
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ v1.4 outbound polling

    /** Start the 3s queue poll while the app is visible. */
    private void startPolling() {
        if (polling) return;
        polling = true;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        pollHandler.post(new ActivityPollTask(this));
        Log.i(TAG, "activity polling started");
    }

    /** Stop polling when the app goes to background. */
    private void stopPolling() {
        polling = false;
        pollHandler.removeCallbacksAndMessages(null);
        releasePlayer();
        synchronized (audioQueue) {
            File f;
            while ((f = audioQueue.poll()) != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        playing = false;
        VoiceService.setPlayingAudio(false);
        Log.i(TAG, "activity polling stopped");
    }

    public boolean isPolling() {
        return polling;
    }

    // ------------------------------------------------------------ v1.6 app-open heartbeat

    /** Start the 30s heartbeat while the app is visible. */
    private void startHeartbeat() {
        if (heartbeating) return;
        heartbeating = true;
        heartbeatHandler.post(new HeartbeatTask(this));
        Log.i(TAG, "heartbeat started");
    }

    /** Stop the heartbeat when the app goes to background. */
    private void stopHeartbeat() {
        heartbeating = false;
        heartbeatHandler.removeCallbacksAndMessages(null);
        Log.i(TAG, "heartbeat stopped");
    }

    public boolean isHeartbeating() {
        return heartbeating;
    }

    /**
     * Called by HeartbeatTask on the main thread. POSTs to the heartbeat
     * endpoint on a worker thread (Android forbids network on main thread).
     */
    public void sendHeartbeat() {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BASE_URL + "/api/selena-voice/heartbeat");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                byte[] body = "{}".getBytes("UTF-8");
                conn.getOutputStream().write(body);
                int code = conn.getResponseCode();
                log("heartbeat: HTTP " + code);
            } catch (Exception e) {
                log("heartbeat failed: " + e);
            } finally {
                if (conn != null) conn.disconnect();
                uploadLogs();
                scheduleNextHeartbeat();
            }
        }).start();
    }

    /**
     * Nick 2026-10-18 v1.6: POST the buffered remote logs (runs on the
     * heartbeat's worker thread, every 30s). Buffer is cleared only after
     * a successful upload so failed uploads are retried next cycle.
     */
    private void uploadLogs() {
        java.util.List<String> pending;
        synchronized (LOG_BUFFER) {
            if (LOG_BUFFER.isEmpty()) return;
            pending = new java.util.ArrayList<>(LOG_BUFFER);
        }
        HttpURLConnection logConn = null;
        try {
            URL logUrl = new URL(BASE_URL + "/api/selena-voice/logs");
            logConn = (HttpURLConnection) logUrl.openConnection();
            logConn.setRequestMethod("POST");
            logConn.setRequestProperty("Content-Type", "application/json");
            logConn.setConnectTimeout(8000);
            logConn.setReadTimeout(8000);
            logConn.setDoOutput(true);
            JSONObject payload = new JSONObject();
            payload.put("logs", new JSONArray(pending));
            logConn.getOutputStream().write(payload.toString().getBytes("UTF-8"));
            int logCode = logConn.getResponseCode();
            if (logCode == 200) {
                synchronized (LOG_BUFFER) {
                    LOG_BUFFER.removeAll(pending);
                }
                Log.d(TAG, "log upload: HTTP 200 (" + pending.size() + " entries)");
            } else {
                Log.w(TAG, "log upload: HTTP " + logCode + " (" + pending.size()
                        + " entries kept for retry)");
            }
        } catch (Exception e) {
            Log.w(TAG, "log upload failed (" + pending.size() + " entries kept)", e);
        } finally {
            if (logConn != null) logConn.disconnect();
        }
    }

    /** Called after each heartbeat to schedule the next (worker thread). */
    private void scheduleNextHeartbeat() {
        if (!heartbeating) return;
        heartbeatHandler.postDelayed(new HeartbeatTask(this), HEARTBEAT_MS);
    }

    /**
     * Called by ActivityPollTask on the main thread. Network work goes to a
     * worker thread (Android forbids network on the main thread).
     */
    public void pollOnce() {
        try {
            setPollStatus("Poll: starting…");
            new Thread(() -> doPollWork()).start();
        } catch (Exception e) {
            Log.w(TAG, "pollOnce failed", e);
            scheduleNextPoll();
        }
    }

    /** Called after each poll (from the worker thread) to schedule the next. */
    public void scheduleNextPoll() {
        if (!polling) return;
        pollHandler.postDelayed(new ActivityPollTask(this), POLL_MS);
    }

    /** Runs on a worker thread: HTTP GET the queue, update UI, fetch audio. */
    private void doPollWork() {
        long since = getLastTs();
        HttpURLConnection conn = null;
        try {
            String urlStr = BASE_URL + "/api/selena-voice/queue?since="
                    + URLEncoder.encode(String.valueOf(since), "UTF-8");
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            log("poll: HTTP " + code + " (since=" + since + ")");
            if (code != 200) {
                setPollStatus("Last poll: HTTP " + code);
                return;
            }
            String body = readAll(conn.getInputStream());
            log("poll: body length=" + body.length());
            JSONArray arr;
            try {
                String trimmed = body.trim();
                if (trimmed.startsWith("{")) {
                    // Tolerant: API may return {"messages": [...]}
                    JSONObject obj = new JSONObject(trimmed);
                    arr = obj.optJSONArray("messages");
                    if (arr == null) arr = new JSONArray();
                } else {
                    arr = new JSONArray(trimmed);
                }
            } catch (Exception e) {
                log("poll: JSON parse failed: " + e + ". Body: " + body);
                setPollStatus("Last poll: JSON parse error");
                return;
            }
            int count = arr.length();
            log("poll: " + count + " message(s)");
            long maxTs = since;
            for (int i = 0; i < count; i++) {
                JSONObject m = arr.getJSONObject(i);
                long ts = parseTimestamp(m);
                String text = m.optString("text", "");
                String audioUrl = m.optString("audioUrl", "");
                if (ts > maxTs) maxTs = ts;
                if (!text.isEmpty()) {
                    onSelenaMessage(text);
                }
                if (!audioUrl.isEmpty()) {
                    String full = audioUrl.startsWith("http")
                            ? audioUrl : BASE_URL + audioUrl;
                    try {
                        File f = downloadToCache(full);
                        enqueueAudio(f);
                    } catch (Exception e) {
                        log("audio download failed: " + full + ": " + e);
                    }
                }
            }
            // Nick 2026-10-18 v1.5: ALWAYS advance last_ts after a poll that
            // returned messages. The old conditional (maxTs > since) never
            // fired when a timestamp came back unparseable (parseTimestamp
            // -> 0), leaving last_ts at 0 forever and re-fetching the same
            // messages on every poll -> the 7x duplicate log Nick saw.
            // Fallback to now() so we always make forward progress.
            if (count > 0) {
                long newTs = maxTs > since ? maxTs : System.currentTimeMillis();
                setLastTs(newTs);
            }
            setPollStatus("Last poll: 200 OK, " + count + " message(s)");
        } catch (Exception e) {
            log("poll: exception: " + e);
            setPollStatus("Last poll: failed - " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
            scheduleNextPoll();
        }
    }

    private void setPollStatus(String status) {
        final String s = status;
        runOnUiThread(() -> pollStatusText.setText(s));
    }

    /** Runs on a worker thread: download one audio file to the cache dir. */
    private File downloadToCache(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        File out = File.createTempFile("selena-", ".mp3", getCacheDir());
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            conn.disconnect();
        }
        return out;
    }

    /** Called on a worker thread after download; hands playback to the main thread. */
    private void enqueueAudio(File f) {
        synchronized (audioQueue) {
            audioQueue.add(f);
        }
        runOnUiThread(this::pumpAudioQueue);
    }

    /** Runs on main thread: start the next download's playback if idle. */
    private void pumpAudioQueue() {
        if (playing) return;
        File f;
        synchronized (audioQueue) {
            f = audioQueue.poll();
        }
        if (f == null) return;
        playing = true;
        playFileNow(f);
    }

    /** Runs on main thread: starts MediaPlayer for the downloaded file. */
    private void playFileNow(File f) {
        try {
            // Nick 2026-10-18 v1.5: VoiceService.muteBeep() zeroes
            // STREAM_VOICE_CALL (and others) for beep suppression and the
            // volumes can stay zeroed — this player's USAGE_VOICE_COMMUNICATION
            // routes through that stream, so playback was silent. Ensure an
            // audible level here independently; do NOT rely on the service's
            // unmuteBeep().
            ensureAudibleVolume();
            requestAudioFocus();
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setOnCompletionListener(mp -> onPlaybackDone());
            player.setOnErrorListener((mp, what, extra) -> {
                onPlaybackDone();
                return true;
            });
            player.setDataSource(f.getAbsolutePath());
            // Synchronous prepare on main thread for a local file is fast
            // (<100ms).
            player.prepare();
            player.start();
            VoiceService.setPlayingAudio(true);
            log("playback started (echo guard on)");
            currentAudioFile = f;
        } catch (Exception e) {
            log("play failed: " + e);
            onPlaybackDone();
        }
    }

    /** Nick 2026-10-18 v1.5: guarantee audible playback volume. */
    private void ensureAudibleVolume() {
        try {
            int maxVoice = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
            int maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            // 80% of max — loud and clear without blasting.
            audioManager.setStreamVolume(
                    AudioManager.STREAM_VOICE_CALL, (int) (maxVoice * 0.8f), 0);
            audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC, (int) (maxMusic * 0.8f), 0);
            log("playback volumes ensured at 80% (voice_call/music)");
        } catch (Exception e) {
            log("ensureAudibleVolume failed: " + e);
        }
    }

    /** Runs on main thread after playback finishes or fails. */
    private void onPlaybackDone() {
        abandonAudioFocus();
        releasePlayer();
        playing = false;
        VoiceService.setPlayingAudio(false);
        log("playback done (echo guard off)");
        if (currentAudioFile != null) {
            //noinspection ResultOfMethodCallIgnored
            currentAudioFile.delete();
            currentAudioFile = null;
        }
        pumpAudioQueue();
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void requestAudioFocus() {
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
            audioManager.requestAudioFocus(focusRequest);
        } catch (Exception ignored) {}
    }

    private void abandonAudioFocus() {
        try {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } catch (Exception ignored) {}
    }

    private long getLastTs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_TS, 0);
    }

    private void setLastTs(long ts) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_LAST_TS, ts).apply();
    }

    /** Tolerant timestamp parse: epoch millis (long) or ISO-8601 string. */
    private static long parseTimestamp(JSONObject m) {
        long ts = m.optLong("timestamp", -1);
        if (ts >= 0) return ts;
        String s = m.optString("timestamp", "");
        if (s.isEmpty()) return 0;
        try {
            // Try ISO-8601 via java.time (API 26+)
            return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli();
        } catch (Exception e) {
            log("parseTimestamp: unparseable '" + s + "'");
            return 0;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    // ------------------------------------------------------------ existing UI helpers

    private void appendLog(String line) {
        runOnUiThread(() -> {
            logText.append(line + "\n\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void updateListeningUi(boolean listening) {
        runOnUiThread(() -> statusText.setText(
                listening ? "🎙️ Listening for Selena…" : "⏸️ Paused — toggle to resume"));
    }

    /** Nick 2026-10-18: visual pulse so he knows the app hears him. */
    private void pulse() {
        runOnUiThread(() -> {
            if (pulseAnimator != null && pulseAnimator.isRunning()) return;
            pulseAnimator = ObjectAnimator.ofFloat(pulseDot, "alpha", 1f, 0.2f, 1f);
            pulseAnimator.setDuration(600);
            pulseAnimator.start();
        });
    }
}
