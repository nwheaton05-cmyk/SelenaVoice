package com.selena.voice;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.Html;
import android.text.TextUtils;
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
import java.util.Locale;
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
public class MainActivity extends Activity
        implements TtsProgressListener.Callback, RealtimeClient.Listener {
    private static final String TAG = "SelenaVoice";
    private static final String BASE_URL = "https://siteview-pi.vercel.app";
    // Nick 2026-10-18 v1.14: Supabase Realtime push (LEG 1). The anon
    // key is public by design; RLS limits it to SELECT on the voice
    // queue only (Nick's voice inputs stay service-role-only).
    private static final String SUPABASE_HOST = "pqkfqzioimkobuhcjwdk.supabase.co";
    private static final String SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBxa2ZxemlvaW1rb2J1aGNqd2RrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzMjg2NTIsImV4cCI6MjEwNjkwNDY1Mn0.eNem2zAIA4_MPtWMI0tiErtu7tGNAtkLTnsCA5lSPVw";
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
    private RealtimeClient realtimeClient; // v1.14 push, null when paused

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
    // Nick 2026-10-18 v1.7: remember the speakerphone state so we can put
    // it back after playback — leaving it forced on would make Nick's next
    // real phone call blast out of the speaker.
    private boolean speakerWasOn = false;
    // Nick 2026-10-18 v1.11: true when the current playback was routed to
    // Bluetooth (speakerphone left untouched). Set by routeAudioForPlayback.
    private boolean btRouted = false;
    // Nick 2026-10-18 v1.8: the mic button is a real on/off toggle (manual
    // fallback when always-listening is off). One press starts, next stops.
    private Button micButton;
    private boolean manualMicOn = false;

    // Nick 2026-10-18 v1.11: on-device TTS for speed. The server sends
    // text-only replies and the phone speaks them locally (~5s round-trip)
    // instead of waiting for server-generated Aria MP3s (~48s). Messages
    // that DO carry an audioUrl still download and play the MP3 (Aria
    // quality mode). Default is phone/fast.
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private static final String KEY_VOICE_MODE = "voice_mode";
    private static final String VOICE_PHONE = "phone"; // fast, on-device
    private static final String VOICE_ARIA = "aria";   // quality, server MP3
    private TextView voiceModeLabel;
    private Switch voiceModeSwitch;
    private final Queue<String> ttsQueue = new LinkedList<>();
    // Nick 2026-10-18 v1.13: dedupe set for two-phase server delivery.
    // The server queues TEXT immediately, then queues the SAME text with an
    // MP3 audioUrl seconds later when TTS finishes. Without dedupe the app
    // would speak (Phone mode) or hold-then-play (Aria mode) the same reply
    // twice. Texts are added only when actually spoken/played, so Aria mode
    // holding a text-only message doesn't consume the dedupe entry the MP3
    // version still needs. Bounded FIFO (20 entries), main thread only.
    private final LinkedList<String> spokenTexts = new LinkedList<>();
    private boolean ttsSpeaking = false;

    public MainActivity() {
        receiver = new MessageReceiver(this);
    }

    // Nick 2026-10-18 v1.9: the log shows the full conversation. Nick's own
    // words appear as "You:" in muted purple; Selena's replies as "Selena:"
    // in dark. Nick's inputs are NEVER spoken aloud — the audio queue only
    // ever plays audioUrl MP3s from /api/selena-voice/queue (Selena's
    // replies). His inputs go to /api/selena-voice/input as text only.
    private static final String COLOR_SELENA = "#2D2A32"; // selena_dark
    private static final String COLOR_NICK = "#6B4FA1";   // selena_purple

    /** Called by MessageReceiver when Selena has a new voice message. */
    public void onSelenaMessage(String text) {
        appendLogHtml("Selena", text, COLOR_SELENA);
    }

    /** Called by MessageReceiver when Nick's voice input was accepted. */
    public void onInputSent(String text) {
        log("log: showing Nick's message");
        appendLogHtml("You", text, COLOR_NICK);
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
        // Nick 2026-10-19 v1.15: show the installed version so we can
        // verify which build is on the phone (kills version-guessing).
        try {
            String vn = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.versionText)).setText("v" + vn);
        } catch (Exception ignored) {}
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        alwaysOnSwitch = findViewById(R.id.alwaysOnSwitch);
        pulseDot = findViewById(R.id.pulseDot);
        micButton = findViewById(R.id.micButton);

        alwaysOnSwitch.setChecked(VoiceService.getAlwaysListening(this));
        alwaysOnSwitch.setOnCheckedChangeListener((v, on) -> {
            VoiceService.setAlwaysListening(this, on);
            if (on) {
                // The switch owns the mic now — drop any manual toggle state.
                manualMicOn = false;
            }
            updateMicButton();
        });

        micButton.setOnClickListener(v -> {
            if (alwaysOnSwitch.isChecked()) {
                Toast.makeText(this, "Already always listening — mic is live",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!manualMicOn) {
                Intent i = new Intent(this, VoiceService.class);
                i.setAction("MANUAL_LISTEN");
                startForegroundService(i);
                manualMicOn = true;
                log("mic: manual toggle ON");
                Toast.makeText(this, "Listening… tap mic again to stop",
                        Toast.LENGTH_SHORT).show();
            } else {
                Intent i = new Intent(this, VoiceService.class);
                i.setAction("MANUAL_STOP");
                startForegroundService(i);
                manualMicOn = false;
                log("mic: manual toggle OFF");
                Toast.makeText(this, "Mic off", Toast.LENGTH_SHORT).show();
            }
            updateMicButton();
        });

        // Nick 2026-10-18 v1.11: voice mode toggle — Phone (fast, on-device
        // TTS, default) vs Aria (quality, server-generated MP3). Stored in
        // SharedPreferences so it survives restarts.
        voiceModeLabel = findViewById(R.id.voiceModeLabel);
        voiceModeSwitch = findViewById(R.id.voiceModeSwitch);
        voiceModeSwitch.setChecked(isPhoneVoiceMode());
        updateVoiceModeLabel();
        voiceModeSwitch.setOnCheckedChangeListener((v, phone) -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_VOICE_MODE, phone ? VOICE_PHONE : VOICE_ARIA)
                    .apply();
            updateVoiceModeLabel();
            log("voice: mode=" + (phone ? "phone (fast)" : "aria (quality)"));
        });

        initOnDeviceTts();

        requestPermissions();
        // Service starts after permission grant (see onRequestPermissionsResult).
        // If already granted, start now.
        if (hasAllPermissions()) {
            startVoiceService();
        }
    }

    private boolean isPhoneVoiceMode() {
        return VOICE_PHONE.equals(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_VOICE_MODE, VOICE_PHONE));
    }

    private void updateVoiceModeLabel() {
        final boolean phone = isPhoneVoiceMode();
        runOnUiThread(() -> voiceModeLabel.setText(
                phone ? "Voice: Phone (fast)" : "Voice: Aria (quality)"));
    }

    /** Nick 2026-10-18 v1.11: init Android's built-in TTS for speed mode. */
    private void initOnDeviceTts() {
        try {
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(Locale.US);
                    ttsReady = (r != TextToSpeech.LANG_MISSING_DATA
                            && r != TextToSpeech.LANG_NOT_SUPPORTED);
                    if (ttsReady) {
                        tts.setOnUtteranceProgressListener(
                                new TtsProgressListener(MainActivity.this));
                        log("tts: on-device engine ready");
                    } else {
                        log("tts: on-device language not available");
                    }
                } else {
                    log("tts: on-device init failed status=" + status);
                }
            });
        } catch (Exception e) {
            log("tts: on-device init exception: " + e);
        }
    }

    // TtsProgressListener.Callback — onStart already raised the echo guard
    // directly, so onTtsStarted is a no-op.
    @Override
    public void onTtsStarted() {
    }

    @Override
    public void onTtsFinished() {
        // Binder thread — marshal to main thread before touching UI/audio.
        runOnUiThread(this::onTtsDone);
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
        startRealtime(); // v1.14 push, poll stays as fallback
        // Nick 2026-10-18 v1.8: if the activity was recreated while the
        // service was in manual-listen mode, pick up the real state.
        manualMicOn = VoiceService.isManualListening();
        updateMicButton();
    }

    @Override
    protected void onPause() {
        stopPolling();
        stopHeartbeat();
        stopRealtime(); // v1.14
        super.onPause();
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ v1.14 Realtime push (LEG 1)

    /**
     * Nick 2026-10-18 v1.14: called on the Realtime reader thread when
     * Supabase pushes a new selena_voice_queue row. Maps the DB record
     * (snake_case columns) onto the API message shape and runs the
     * exact same handling as a poll result. The 3s poll stays as a
     * fallback in case the push drops.
     */
    // RealtimeClient.Listener — fires on the Realtime reader thread.
    @Override
    public void onInsert(JSONObject record) {
        onRealtimeInsert(record);
    }

    @Override
    public void onStatus(String msg) {
        log(msg);
    }

    private void onRealtimeInsert(JSONObject record) {
        try {
            log("rt: push received id=" + record.optString("id", "?"));
            JSONObject m = new JSONObject();
            m.put("text", record.optString("text", ""));
            if (record.isNull("audio_url")) {
                m.put("audioUrl", JSONObject.NULL);
            } else {
                m.put("audioUrl", record.optString("audio_url", ""));
            }
            // created_at is "YYYY-MM-DD HH:MM:SS.ffffff+00"; poll's
            // last_ts advances on wall-clock so the fallback poll
            // won't refetch this row (dedupe via spokenTexts covers
            // any overlap anyway).
            m.put("timestamp", System.currentTimeMillis());
            handleQueueMessage(m, getLastTs());
            setLastTs(System.currentTimeMillis());
        } catch (Exception e) {
            log("rt: push handling failed: " + e);
        }
    }

    /** Start the Realtime push listener while the app is visible. */
    private void startRealtime() {
        if (realtimeClient != null) return;
        // v1.14: MainActivity implements RealtimeClient.Listener directly.
        // (Anonymous inner classes crash this project's d8/R8 build — the
        // v1.11 TtsProgressListener lesson.)
        realtimeClient = new RealtimeClient(
                SUPABASE_HOST, SUPABASE_ANON_KEY,
                "realtime:public:selena_voice_queue", "selena_voice_queue",
                this);
        realtimeClient.start();
        Log.i(TAG, "realtime push started");
    }

    /** Stop the Realtime push listener when the app goes to background. */
    private void stopRealtime() {
        if (realtimeClient != null) {
            realtimeClient.stop();
            realtimeClient = null;
        }
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
        // Nick 2026-10-18 v1.11: stop on-device TTS when backgrounding so
        // it never talks over another app.
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {}
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        ttsSpeaking = false;
        Log.i(TAG, "activity polling stopped");
    }

    @Override
    protected void onDestroy() {
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        tts = null;
        super.onDestroy();
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
                maxTs = handleQueueMessage(arr.getJSONObject(i), maxTs);
            }
            // Nick 2026-10-18 v1.7: ALWAYS advance last_ts PAST the newest
            // message processed (+1). The API returned a message whose
            // timestamp exactly equaled `since` (16:09:05 and 16:09:09 both
            // fetched the same 249-byte reply), so a strict greater-than is
            // needed on the next poll. Fallback to now() when no timestamp
            // parsed (parseTimestamp -> 0) so we always make forward
            // progress instead of re-fetching forever.
            if (count > 0) {
                long newTs = maxTs > since ? maxTs + 1 : System.currentTimeMillis();
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


    /**
     * Nick 2026-10-18 v1.14: per-message queue handling, extracted from
     * doPollWork so the Realtime push path reuses the identical logic
     * (v1.12 null-audioUrl fix, v1.13 two-phase dedupe, Phone/Aria
     * routing). Runs on a worker thread. Returns the updated maxTs.
     */
    private long handleQueueMessage(JSONObject m, long maxTs) {
        long ts = parseTimestamp(m);
        String text = m.optString("text", "");
        // Nick 2026-10-18 v1.12: explicit JSON-null check. The server
        // now sends "audioUrl": null for text-only speed-mode replies;
        // optString on a JSON null returns the literal string "null"
        // (not ""), which then fails the isEmpty() check and sends us
        // into a doomed download of URL "null". isNull() handles it.
        String audioUrl = m.isNull("audioUrl") ? "" : m.optString("audioUrl", "");
        log("audio: audioUrl='" + audioUrl + "'");
        if (ts > maxTs) maxTs = ts;
        // Nick 2026-10-18 v1.13: two-phase server delivery dedupe.
        // The server queues TEXT immediately, then queues the SAME
        // text with an MP3 audioUrl seconds later when TTS finishes.
        // Without this, Phone mode would speak the reply twice and
        // Aria mode would hold-then-play it twice. Texts are added
        // to spokenTexts only when actually spoken/played — Aria
        // mode holding a text-only message must NOT consume the
        // entry the MP3 version still needs.
        if (text.isEmpty()) {
            return maxTs;
        }
        if (spokenTexts.contains(text)) {
            log("dedupe: skipping duplicate text '" +
                    (text.length() > 30 ? text.substring(0, 30) + "…" : text) + "'");
            return maxTs;
        }
        if (isPhoneVoiceMode()) {
            // Nick 2026-10-18 v1.11/v1.12 SPEED MODE. Phone voice mode
            // speaks the text IMMEDIATELY with on-device TTS and
            // ignores audioUrl entirely — no download, no wait for a
            // server-generated Aria MP3. This is the fast path.
            // v1.13: mark spoken FIRST so the second (MP3) phase
            // dedupes against it.
            markSpoken(text);
            onSelenaMessage(text);
            final String speakText = text;
            runOnUiThread(() -> enqueueTts(speakText));
        } else if (!audioUrl.isEmpty()) {
            // ARIA QUALITY MODE. Download and play the server's MP3.
            // Guard against junk URLs (e.g. literal "null") — don't
            // attempt a download that can't succeed.
            if (!audioUrl.startsWith("http")) {
                log("audio: skipping invalid URL: " + audioUrl);
            } else {
                // Nick 2026-10-18 v1.9: playback is strictly
                // queue-only. Only Selena's replies (with
                // server-generated audioUrl MP3s) are ever enqueued
                // and played. Nick's own voice inputs go to
                // /api/selena-voice/input as text and are never
                // read aloud.
                markSpoken(text);
                onSelenaMessage(text);
                String full = audioUrl;
                try {
                    File f = downloadToCache(full);
                    enqueueAudio(f);
                } catch (Exception e) {
                    log("audio download failed: " + full + ": " + e);
                }
            }
        } else {
            // Aria quality mode with no audioUrl yet — the Aria MP3
            // will arrive as its own queue message. Don't speak the
            // text on-device; that would double up. v1.13: don't
            // markSpoken here; the MP3 phase will mark it when it
            // actually plays.
            onSelenaMessage(text);
            log("tts: text-only message held for Aria MP3 (quality mode)");
        }
        return maxTs;
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
        long total = 0;
        try {
            in = conn.getInputStream();
            fos = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
            conn.disconnect();
        }
        // Nick 2026-10-18 v1.7: verify the download is a real file, not an
        // empty/corrupt stub — silent playback with a 0-byte file looks
        // identical to the audio-routing bug in the logs.
        log("audio: downloaded " + total + " bytes to " + out.getAbsolutePath());
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

    /**
     * Nick 2026-10-18 v1.11: Bluetooth-aware audio routing. v1.7 forced the
     * speakerphone on for every playback, which steals audio from Bluetooth
     * earbuds (sound blasts out of the phone speaker instead). If a BT
     * output device is connected, leave routing alone and let Android use
     * it. Otherwise fall back to the v1.7 speakerphone behavior. Sets
     * btRouted so restoreAudioRouting() knows what to undo.
     */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn / isBluetoothA2dpOn
    private boolean routeAudioForPlayback() {
        boolean btConnected = false;
        try {
            // minSdk 26 — getDevices() is always available.
            for (AudioDeviceInfo d :
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || t == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    btConnected = true;
                    break;
                }
            }
        } catch (Exception e) {
            log("audio: BT check failed: " + e);
        }
        btRouted = btConnected;
        if (btConnected) {
            log("audio: bluetooth connected, routing to BT");
            return true;
        }
        try {
            speakerWasOn = audioManager.isSpeakerphoneOn();
            audioManager.setSpeakerphoneOn(true);
        } catch (Exception e) {
            log("audio: setSpeakerphoneOn failed: " + e);
        }
        log("audio: no BT, using speakerphone");
        return false;
    }

    /** Undo what routeAudioForPlayback() did (no-op when BT routed). */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn
    private void restoreAudioRouting() {
        if (btRouted) {
            btRouted = false;
            return; // speakerphone was never touched
        }
        try {
            audioManager.setSpeakerphoneOn(speakerWasOn);
        } catch (Exception e) {
            log("audio: speakerphone restore failed: " + e);
        }
    }

    /** Runs on main thread: starts MediaPlayer for the downloaded file. */
    // setSpeakerphoneOn is deprecated (API 31+) but still functional through
    // API 34; its replacement (setCommunicationDevice) is for voice-call
    // routing — the wrong semantic for media playback.
    @SuppressWarnings("deprecation")
    private void playFileNow(File f) {
        try {
            // Nick 2026-10-18 v1.7: THE SILENT-PLAYBACK FIX. v1.5/v1.6 used
            // USAGE_VOICE_COMMUNICATION, which Android routes to the EARPIECE
            // (like a phone call) — MediaPlayer reported "started"/"done"
            // with no errors while Nick heard nothing. Route through the
            // loudspeaker instead: USAGE_MEDIA (= STREAM_MUSIC) +
            // setSpeakerphoneOn(true).
            // Nick 2026-10-18 v1.10: respect his device volume — do NOT
            // touch setStreamVolume at all. If he's got it muted, it plays
            // muted. His choice.
            //
            // One catch: the service's mic-beep suppression keeps streams
            // zeroed while idle-listening (quiet restart). Undo that first
            // so playback uses his REAL volume, not 0 and not a forced
            // level.
            try {
                Intent restore = new Intent(this, VoiceService.class);
                restore.setAction("RESTORE_VOLUMES");
                startService(restore);
                log("audio: restored device volumes for playback");
            } catch (Exception e) {
                log("audio: volume restore failed: " + e);
            }
            log("audio: using device volume (no override)");
            // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win over the
            // speakerphone when connected.
            routeAudioForPlayback();
            log("audio: stream=MUSIC (USAGE_MEDIA)");
            requestAudioFocus();
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setOnCompletionListener(mp -> onPlaybackDone());
            player.setOnErrorListener((mp, what, extra) -> {
                log("audio: MediaPlayer error what=" + what + " extra=" + extra);
                onPlaybackDone();
                return true;
            });
            player.setDataSource(f.getAbsolutePath());
            log("audio: MediaPlayer setDataSource " + f.getAbsolutePath());
            // Synchronous prepare on main thread for a local file is fast
            // (<100ms).
            player.prepare();
            log("audio: MediaPlayer prepared, duration=" + player.getDuration() + "ms");
            player.start();
            VoiceService.setPlayingAudio(true);
            log("playback started (echo guard on)");
            currentAudioFile = f;
        } catch (Exception e) {
            log("play failed: " + e);
            onPlaybackDone();
        }
    }

    /** Runs on main thread after playback finishes or fails. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    private void onPlaybackDone() {
        abandonAudioFocus();
        releasePlayer();
        playing = false;
        VoiceService.setPlayingAudio(false);
        // Nick 2026-10-18 v1.7: put the speakerphone back the way we found
        // it, so Nick's next real phone call doesn't start on speaker.
        // Nick 2026-10-18 v1.11: no-op when Bluetooth handled the routing.
        restoreAudioRouting();
        log("playback done (echo guard off)");
        if (currentAudioFile != null) {
            //noinspection ResultOfMethodCallIgnored
            currentAudioFile.delete();
            currentAudioFile = null;
        }
        pumpAudioQueue();
        pumpTtsQueue();
    }

    // ------------------------------------------------------------ v1.11 on-device TTS (speed mode)

    /** Runs on main thread: queue text for on-device speech. */
    private void enqueueTts(String text) {
        synchronized (ttsQueue) {
            ttsQueue.add(text);
        }
        pumpTtsQueue();
    }

    /** Runs on main thread: speak the next queued text if nothing is playing. */
    /**
     * Nick 2026-10-18 v1.13: record a message text as actually spoken or
     * played (two-phase delivery dedupe). Bounded FIFO of 20 entries;
     * called only from the poll worker thread, so no locking needed.
     */
    private void markSpoken(String text) {
        spokenTexts.add(text);
        while (spokenTexts.size() > 20) spokenTexts.removeFirst();
    }

    private void pumpTtsQueue() {
        if (ttsSpeaking || playing) return;
        String text;
        synchronized (ttsQueue) {
            text = ttsQueue.poll();
        }
        if (text == null) return;
        ttsSpeaking = true;
        speakOnDevice(text);
    }

    /** Runs on main thread: speak text with Android's built-in TTS. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    private void speakOnDevice(String text) {
        if (tts == null || !ttsReady) {
            log("tts: on-device not ready, dropping text");
            onTtsDone();
            return;
        }
        String preview = text.length() > 30 ? text.substring(0, 30) + "…" : text;
        log("tts: on-device speak '" + preview + "'");
        // Same routing as MP3 playback (v1.7): loudspeaker, and undo the
        // service's mic-beep volume zeroing first so we use Nick's real
        // device volume (v1.10: no override, respect his setting).
        try {
            Intent restore = new Intent(this, VoiceService.class);
            restore.setAction("RESTORE_VOLUMES");
            startService(restore);
        } catch (Exception e) {
            log("tts: volume restore failed: " + e);
        }
        // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win over the
        // speakerphone when connected, same as MP3 playback.
        routeAudioForPlayback();
        log("tts: stream=MUSIC");
        requestAudioFocus();
        VoiceService.setPlayingAudio(true);
        log("tts: echo guard on");
        try {
            // minSdk is 26, so the API-21+ speak() with utterance ID is
            // always available.
            Bundle params = new Bundle();
            String utteranceId = "selena-" + System.currentTimeMillis();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                    utteranceId);
            int rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params,
                    utteranceId);
            if (rc != TextToSpeech.SUCCESS) {
                log("tts: speak() returned " + rc);
                onTtsDone();
            }
        } catch (Exception e) {
            log("tts: speak failed: " + e);
            onTtsDone();
        }
    }

    /** Runs on main thread after on-device TTS finishes or fails. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    private void onTtsDone() {
        abandonAudioFocus();
        ttsSpeaking = false;
        VoiceService.setPlayingAudio(false);
        // Put the speakerphone back the way we found it (v1.7), same as MP3.
        // v1.11: no-op when Bluetooth handled the routing.
        restoreAudioRouting();
        log("tts: echo guard off");
        pumpTtsQueue();
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
            // Nick 2026-10-18 v1.7: match the playback usage (USAGE_MEDIA)
            // so focus is requested for the music stream, not voice-call.
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
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
        // Nick 2026-10-19 v1.15: fresh install used to default to 0, so the
        // first poll asked for ?since=0 and the server returned EVERY message
        // ever queued — the app read the entire history aloud on reinstall.
        // On first run (key absent), anchor to now so only new messages arrive.
        android.content.SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(KEY_LAST_TS)) {
            long now = System.currentTimeMillis();
            prefs.edit().putLong(KEY_LAST_TS, now).apply();
            log("fresh install: last_ts anchored to now, skipping history");
            return now;
        }
        return prefs.getLong(KEY_LAST_TS, 0);
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

    private void appendLogHtml(String who, String text, String color) {
        final CharSequence html = Html.fromHtml(
                "<font color=\"" + color + "\"><b>" + who + ":</b> "
                        + TextUtils.htmlEncode(text) + "</font><br><br>",
                Html.FROM_HTML_MODE_LEGACY);
        runOnUiThread(() -> {
            logText.append(html);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void updateListeningUi(boolean listening) {
        runOnUiThread(() -> statusText.setText(
                listening ? "🎙️ Listening for Selena…" : "⏸️ Paused — toggle to resume"));
    }

    /**
     * Nick 2026-10-18 v1.8: the mic button is a real toggle. Green = manual
     * listening is ON (tap to stop), purple/default = OFF (tap to talk).
     * When always-listening owns the mic, the button stays in its OFF look.
     */
    private void updateMicButton() {
        runOnUiThread(() -> {
            if (manualMicOn) {
                micButton.setText("🎤 ON\ntap to stop");
                micButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF2E7D32));
            } else {
                micButton.setText("🎤");
                micButton.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(0xFF6B4FA1));
            }
        });
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
