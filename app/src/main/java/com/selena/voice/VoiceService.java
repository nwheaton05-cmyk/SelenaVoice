package com.selena.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

/**
 * Selena Voice foreground service (Nick, 2026-10-18).
 *
 * Nick 2026-10-19 v1.16: BACKGROUND OPERATION. Nick wants to hear replies
 * with the screen locked / app backgrounded. Everything outbound moved
 * back here from MainActivity: Realtime push subscription, the 3s queue
 * poll loop, the 30s heartbeat, and ALL playback (MediaPlayer MP3s for
 * Aria mode + on-device TTS for Phone mode). The service holds a partial
 * wake lock for its lifetime and the MediaPlayer uses setWakeMode, so
 * playback continues with the screen off. The activity is now a pure UI
 * shell (log, toggles, pulse dot) that binds via broadcasts.
 *
 * Two jobs:
 *  1. OUTBOUND (Selena -> Nick): Realtime push on selena_voice_queue
 *     INSERTs + 3s fallback poll of /api/selena-voice/queue?since=.
 *     Phone mode (default): speak text immediately with on-device TTS.
 *     Aria mode: download the server's MP3 and play it.
 *  2. INBOUND (Nick -> Selena): continuous SpeechRecognizer ("always
 *     listening"). On speech, transcribes and POSTs to
 *     /api/selena-voice/input. Auto-restarts on NO_MATCH / SPEECH_TIMEOUT
 *     with a 5s backoff. Beep suppressed via stream mute; visual only.
 *
 * NOTE: All helpers are top-level classes (no inner/anonymous classes
 * extending Android listener types) because d8 8.2.2 crashes on inner-class
 * bytecode from javac 21. Lambdas implementing *interfaces* are fine
 * (proven by v1.11+ builds).
 */
public class VoiceService extends Service
        implements TtsProgressListener.Callback, RealtimeClient.Listener {
    private static final String TAG = "SelenaVoice";
    private static final String CHANNEL_ID = "selena_voice_channel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_MESSAGE = "com.selena.voice.MESSAGE";
    public static final String ACTION_SPEECH_DETECTED = "com.selena.voice.SPEECH_DETECTED";
    public static final String ACTION_INPUT_SENT = "com.selena.voice.INPUT_SENT";
    public static final String ACTION_LISTENING_STATE = "com.selena.voice.LISTENING_STATE";
    public static final String ACTION_POLL_STATUS = "com.selena.voice.POLL_STATUS";
    public static final String EXTRA_POLL_STATUS = "poll_status";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_STATE = "listening";

    private static final String PREFS = "selena_voice";
    private static final String KEY_LAST_TS = "last_ts";
    private static final String KEY_ALWAYS_ON = "always_listening";
    // Nick 2026-10-18 v1.11: voice mode lives in SharedPreferences so the
    // activity toggle and the service read the same value.
    public static final String KEY_VOICE_MODE = "voice_mode";
    public static final String VOICE_PHONE = "phone"; // fast, on-device TTS
    public static final String VOICE_ARIA = "aria";   // quality, server MP3
    // Nick 2026-10-19 v1.17: no-auto-readout + output-route toggles.
    // The two output toggles are mutually exclusive.
    public static final String KEY_NO_AUTO_READOUT = "no_auto_readout";
    public static final String KEY_FORCE_BLUETOOTH = "force_bluetooth";
    public static final String KEY_FORCE_SPEAKER = "force_speaker";

    private static final String BASE_URL = "https://siteview-pi.vercel.app";
    // Nick 2026-10-18 v1.14: Supabase Realtime push (LEG 1). The anon
    // key is public by design; RLS limits it to SELECT on the voice
    // queue only (Nick's voice inputs stay service-role-only).
    private static final String SUPABASE_HOST = "pqkfqzioimkobuhcjwdk.supabase.co";
    private static final String SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBxa2ZxemlvaW1rb2J1aGNqd2RrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTEzMjg2NTIsImV4cCI6MjEwNjkwNDY1Mn0.eNem2zAIA4_MPtWMI0tiErtu7tGNAtkLTnsCA5lSPVw";
    private static final long POLL_MS = 3000;
    private static final long HEARTBEAT_MS = 30000;
    // Nick 2026-10-18 v1.3: was 500ms — the Samsung A17 plays a system
    // beep on every recognizer start, so 500ms restarts = constant beeping.
    // 5s backoff = far fewer beeps. NO_MATCH/TIMEOUT use quiet restart.
    private static final long RESTART_DELAY_MS = 5000;

    // Nick 2026-10-18 v1.2: ADJUST_MUTE did NOT stop the beep on the
    // Samsung A17 — the recognizer beep apparently plays on a stream that
    // ignores the mute flag. New approach: save each stream's volume and
    // set it to 0 (not mute), then restore. STREAM_VOICE_CALL added too.
    private static final int[] BEEP_STREAMS = {
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
    };
    private final int[] savedVolumes = new int[BEEP_STREAMS.length];
    private boolean beepMuted = false;

    // Nick 2026-10-19 v1.17: audio-ducking boost. While Selena's reply
    // plays, STREAM_MUSIC goes to max so she overpowers background audio
    // (Spotify); the MAY_DUCK focus request keeps Spotify ducked. The
    // user's volume is restored when playback ends. -1 = no boost active.
    private int savedMusicVolume = -1;

    // Nick 2026-10-18 v1.2: echo prevention. True while Selena's audio
    // response is playing — recognition results arriving during playback
    // are the speaker, not Nick, and get discarded.
    private static volatile boolean isPlayingAudio = false;

    public boolean isPlayingAudio() {
        return isPlayingAudio;
    }

    /** Called while Selena's voice messages play (echo guard). */
    public static void setPlayingAudio(boolean playing) {
        isPlayingAudio = playing;
    }

    // Nick 2026-10-18 v1.8: manual mic toggle state. The mic button starts a
    // one-shot listening session when always-listening is off; a second press
    // stops it. Tracked here (not just in the activity) so it survives
    // activity recreation.
    private static volatile boolean manualListening = false;

    /** True while a manual (mic-button) listening session is active. */
    public static boolean isManualListening() {
        return manualListening;
    }

    // Nick 2026-10-18 v1.6: remote-visible log buffer. The service is
    // Selena's "ears and mouth" — she needs to see poll/download/playback
    // internals without Nick sending screenshots. Entries are uploaded
    // every 30s alongside the heartbeat, then cleared.
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

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean recognizerActive = false;
    private boolean destroyed = false;
    private MediaPlayer player;
    // Nick 2026-10-19 v1.18: LoudnessEnhancer on the player's audio session
    // pushes her voice ~+4dB past max stream volume (MP3/Aria path only —
    // on-device TTS exposes no audio session). Released in releasePlayer().
    private LoudnessEnhancer loudnessEnhancer = null;
    private File currentAudioFile;
    private final Queue<File> audioQueue = new LinkedList<>();
    private boolean playing = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private NotificationManager notificationManager;
    private ServiceRecognitionListener recognitionListener;
    private PowerManager.WakeLock wakeLock;

    // Nick 2026-10-19 v1.16: outbound (moved from MainActivity).
    private RealtimeClient realtimeClient;
    private boolean polling = false;
    private boolean heartbeating = false;

    // Nick 2026-10-18 v1.11: on-device TTS for speed mode (moved here v1.16).
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Queue<String> ttsQueue = new LinkedList<>();
    private boolean ttsSpeaking = false;
    // Nick 2026-10-18 v1.13: dedupe set for two-phase server delivery.
    // The server queues TEXT immediately, then queues the SAME text with an
    // MP3 audioUrl seconds later. Texts are added only when actually
    // spoken/played, so Aria mode holding a text-only message doesn't
    // consume the entry the MP3 version still needs. Bounded FIFO (20).
    // v1.16: guarded — handleQueueMessage can now run on the poll worker
    // thread OR the Realtime reader thread.
    private final LinkedList<String> spokenTexts = new LinkedList<>();
    // Nick 2026-10-18 v1.7: remember the speakerphone state so we can put
    // it back after playback — leaving it forced on would make Nick's next
    // real phone call blast out of the speaker.
    private boolean speakerWasOn = false;
    // Nick 2026-10-18 v1.11: true when the current playback was routed to
    // Bluetooth (speakerphone left untouched). Set by routeAudioForPlayback.
    private boolean btRouted = false;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        recognitionListener = new ServiceRecognitionListener(this);
        createChannel();
        startForeground(NOTIF_ID, buildNotification("Listening for Selena…"));
        // Nick 2026-10-19 v1.16: hold a partial wake lock for the service's
        // lifetime so the poll loop, Realtime socket, recognizer, and TTS
        // keep running with the screen locked. (MediaPlayer additionally
        // uses setWakeMode per-playback.)
        acquireWakeLock();
        initOnDeviceTts();
        // Nick 2026-10-19 v1.16: outbound moved back here from MainActivity
        // — the service now works with the screen locked / app backgrounded.
        startPolling();
        startHeartbeat();
        startRealtime();
        if (isAlwaysListening()) {
            startContinuousListening();
        }
        log("service created (v1.16 background mode)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("TOGGLE_LISTENING".equals(intent.getAction())) {
                boolean on = intent.getBooleanExtra("on", true);
                setAlwaysListening(on);
                if (on) {
                    manualListening = false; // switch owns the mic now
                    startContinuousListening();
                } else {
                    manualListening = false;
                    stopContinuousListening();
                }
                broadcastListeningState();
            } else if ("MANUAL_LISTEN".equals(intent.getAction())) {
                if (!recognizerActive) {
                    manualListening = true;
                    startContinuousListening();
                }
            } else if ("RESTORE_VOLUMES".equals(intent.getAction())) {
                // Kept for compatibility; playback now calls unmuteBeep()
                // directly since it lives in the service.
                unmuteBeep();
            } else if ("MANUAL_STOP".equals(intent.getAction())) {
                // Safety: a manual stop must never kill the always-on loop.
                if (isAlwaysListening()) {
                    log("MANUAL_STOP ignored — always-listening is on");
                    return START_STICKY;
                }
                manualListening = false;
                if (recognizerActive) stopContinuousListening();
                else broadcastListeningState();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        stopRealtime();
        stopPolling();
        heartbeating = false;
        handler.removeCallbacksAndMessages(null);
        restoreMusicVolume(); // Nick 2026-10-19 v1.17: safety net — never
        // leave the music stream stuck at the boosted max.
        unmuteBeep();
        recognizerActive = false;
        destroyRecognizer();
        releasePlayer();
        try {
            if (tts != null) {
                tts.stop();
                tts.shutdown();
            }
        } catch (Exception ignored) {}
        tts = null;
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ wake lock

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "SelenaVoice:background");
            wakeLock.acquire();
            log("wakelock: partial held for service lifetime");
        } catch (Exception e) {
            Log.w(TAG, "wakelock acquire failed", e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    // ------------------------------------------------------------ notification

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Selena Voice", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Hands-free voice link to Selena");
        notificationManager.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Selena Voice")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        try {
            notificationManager.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ beep suppression

    private void muteBeep() {
        if (beepMuted) return;
        try {
            for (int i = 0; i < BEEP_STREAMS.length; i++) {
                // Nick 2026-10-19 v1.18: never zero STREAM_MUSIC while she is
                // talking. Recognizer restarts can fire mid-playback and would
                // knock her max-volume boost down. The Samsung beep is masked
                // by her voice during playback anyway, so suppression loses
                // nothing here.
                if (isPlayingAudio && BEEP_STREAMS[i] == AudioManager.STREAM_MUSIC) {
                    log("audio: muteBeep skipped STREAM_MUSIC (playback active)");
                    continue;
                }
                savedVolumes[i] = audioManager.getStreamVolume(BEEP_STREAMS[i]);
                audioManager.setStreamVolume(BEEP_STREAMS[i], 0, 0);
            }
            beepMuted = true;
            Log.d(TAG, "beep streams zeroed");
        } catch (Exception e) {
            Log.w(TAG, "muteBeep failed", e);
        }
    }

    private void unmuteBeep() {
        if (!beepMuted) return;
        try {
            for (int i = 0; i < BEEP_STREAMS.length; i++) {
                // Nick 2026-10-18 v1.10: only restore a stream we actually
                // zeroed. If Nick changed the volume himself while the
                // streams were zeroed, his new setting stays — we never
                // clobber it with a stale saved value.
                if (audioManager.getStreamVolume(BEEP_STREAMS[i]) == 0) {
                    audioManager.setStreamVolume(BEEP_STREAMS[i], savedVolumes[i], 0);
                }
            }
            beepMuted = false;
            Log.d(TAG, "beep streams restored");
        } catch (Exception e) {
            Log.w(TAG, "unmuteBeep failed", e);
        }
    }

    // Nick 2026-10-19 v1.17: temporarily raise STREAM_MUSIC to max while
    // Selena talks, so her voice overpowers background audio like Spotify.
    // The existing AUDIOFOCUS_GAIN_TRANSIENT request makes Spotify
    // duck at the same time. Called AFTER unmuteBeep() so the saved value
    // is the user's real volume, not a zeroed beep stream.
    private void boostMusicVolume() {
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur >= max) {
                log("audio: music already at max, no boost");
                return; // savedMusicVolume stays -1: nothing to restore
            }
            savedMusicVolume = cur;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
            log("audio: music boosted " + cur + " -> " + max);
        } catch (Exception e) {
            log("audio: boost failed: " + e);
        }
    }

    // Nick 2026-10-19 v1.17: undo boostMusicVolume(). Only restores when
    // the stream is still sitting at the boosted max — if the user (or a
    // recognizer muteBeep cycle) changed it mid-playback, we never clobber
    // their setting with a stale saved value (same rule as unmuteBeep).
    private void restoreMusicVolume() {
        if (savedMusicVolume < 0) return;
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur == max) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                        savedMusicVolume, 0);
                log("audio: music restored to " + savedMusicVolume);
            } else {
                log("audio: music changed mid-playback (" + cur + "), keeping it");
            }
        } catch (Exception e) {
            log("audio: restore failed: " + e);
        }
        savedMusicVolume = -1;
    }

    // Nick 2026-10-19 v1.18: re-assert max immediately before start()/speak()
    // so nothing that ran between boostMusicVolume() and playback (e.g. a
    // recognizer cycle) can knock her down. Does not touch savedMusicVolume —
    // restoreMusicVolume() still owns the undo.
    private void ensureMaxMusic() {
        try {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) < max) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
                log("audio: re-asserted music max right before playback");
            }
        } catch (Exception e) {
            log("audio: re-assert failed: " + e);
        }
    }

    // ------------------------------------------------------------ v1.14 Realtime push (moved here v1.16)

    // RealtimeClient.Listener — fires on the Realtime reader thread.
    @Override
    public void onInsert(JSONObject record) {
        onRealtimeInsert(record);
    }

    @Override
    public void onStatus(String msg) {
        log(msg);
    }

    /**
     * Called on the Realtime reader thread when Supabase pushes a new
     * selena_voice_queue row. Maps the DB record (snake_case columns) onto
     * the API message shape and runs the exact same handling as a poll
     * result. The 3s poll stays as a fallback in case the push drops.
     */
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
            // Nick 2026-10-21: use the ROW's created_at for the cursor, never
            // wall-clock. setLastTs(now()) here used to strand rows: the
            // cursor jumped ahead of messages the poll hadn't seen yet, and
            // they were never fetched or played. The cursor tracks messages,
            // not the clock — advance past this row's timestamp only.
            long rowTs = parseRowTimestamp(record.optString("created_at", ""));
            m.put("timestamp", rowTs > 0 ? rowTs : System.currentTimeMillis());
            long beforeTs = getLastTs();
            handleQueueMessage(m, beforeTs);
            if (rowTs > beforeTs) {
                setLastTs(rowTs + 1);
            }
        } catch (Exception e) {
            log("rt: push handling failed: " + e);
        }
    }

    /** Start the Realtime push listener for the service's lifetime. */
    private void startRealtime() {
        if (realtimeClient != null) return;
        // The service implements RealtimeClient.Listener directly
        // (anonymous inner classes crash this project's d8/R8 build).
        realtimeClient = new RealtimeClient(
                SUPABASE_HOST, SUPABASE_ANON_KEY,
                "realtime:public:selena_voice_queue", "selena_voice_queue",
                this);
        realtimeClient.start();
        log("realtime push started");
    }

    /** Stop the Realtime push listener when the service dies. */
    private void stopRealtime() {
        if (realtimeClient != null) {
            realtimeClient.stop();
            realtimeClient = null;
        }
    }

    // ------------------------------------------------------------ outbound: poll

    /** Start the 3s queue poll for the service's lifetime. */
    private void startPolling() {
        if (polling) return;
        polling = true;
        handler.post(new PollTask(this));
        log("service polling started");
    }

    /** Stop polling and drop any queued-but-unplayed audio. */
    private void stopPolling() {
        polling = false;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        synchronized (audioQueue) {
            File f;
            while ((f = audioQueue.poll()) != null) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        playing = false;
        isPlayingAudio = false;
        try {
            if (tts != null) tts.stop();
        } catch (Exception ignored) {}
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        ttsSpeaking = false;
        log("service polling stopped");
    }

    public boolean isDestroyedFlag() {
        return destroyed;
    }

    /**
     * Called by PollTask on the main thread. Network work goes to a
     * worker thread (Android forbids network on the main thread); the
     * worker schedules the next poll when it finishes.
     */
    public void pollOnce() {
        broadcastPollStatus("Poll: starting…");
        new Thread(() -> doPollWork()).start();
    }

    /** Called by the poll worker thread to schedule the next poll. */
    public void scheduleNextPoll() {
        if (!polling || destroyed) return;
        handler.postDelayed(new PollTask(this), POLL_MS);
    }

    /** Runs on a worker thread: HTTP GET the queue, handle messages. */
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
                broadcastPollStatus("Last poll: HTTP " + code);
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
                broadcastPollStatus("Last poll: JSON parse error");
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
            // timestamp exactly equaled `since`, so a strict greater-than
            // is needed on the next poll.
            // Nick 2026-10-21: NEVER fall back to wall-clock here. A
            // wall-clock cursor strands rows created between polls — they
            // sit behind the cursor forever and are never fetched or played.
            // If timestamps didn't parse, hold the cursor and refetch.
            if (count > 0) {
                long newTs = maxTs > since ? maxTs + 1 : since;
                setLastTs(newTs);
            }
            broadcastPollStatus("Last poll: 200 OK, " + count + " message(s)");
        } catch (Exception e) {
            log("poll: exception: " + e);
            broadcastPollStatus("Last poll: failed - " + e.getClass().getSimpleName());
        } finally {
            if (conn != null) conn.disconnect();
            scheduleNextPoll();
        }
    }

    /**
     * Per-message queue handling (v1.12 null-audioUrl fix, v1.13 two-phase
     * dedupe, Phone/Aria routing). Runs on a worker thread (poll) or the
     * Realtime reader thread. Returns the updated maxTs.
     */
    private long handleQueueMessage(JSONObject m, long maxTs) {
        long ts = parseTimestamp(m);
        String text = m.optString("text", "");
        // Nick 2026-10-18 v1.12: explicit JSON-null check. The server
        // sends "audioUrl": null for text-only speed-mode replies;
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
        synchronized (spokenTexts) {
            if (spokenTexts.contains(text)) {
                log("dedupe: skipping duplicate text");
                return maxTs;
            }
            // Nick 2026-10-19 v1.17: no-auto-readout toggle. The message
            // is still marked spoken (so the two-phase MP3 redelivery
            // dedupes), logged to the app, and last_ts advances via the
            // returned maxTs — but nothing is ever played or spoken, and
            // the volume boost never engages (no playback at all).
            if (isNoAutoReadout()) {
                markSpokenLocked(text);
                broadcastMessage(text);
                log("readout skipped (no-auto-readout on)");
                return maxTs;
            }
            if (isPhoneVoiceMode()) {
                // SPEED MODE. Phone voice mode speaks the text
                // IMMEDIATELY with on-device TTS and ignores audioUrl
                // entirely — no download, no wait for a server Aria MP3.
                // Mark spoken FIRST so the second (MP3) phase dedupes.
                markSpokenLocked(text);
                broadcastMessage(text);
                final String speakText = text;
                handler.post(() -> enqueueTts(speakText));
            } else if (!audioUrl.isEmpty()) {
                // ARIA QUALITY MODE. Download and play the server's MP3.
                if (!audioUrl.startsWith("http")) {
                    log("audio: skipping invalid URL: " + audioUrl);
                } else {
                    // Playback is strictly queue-only: only Selena's
                    // replies (with server-generated audioUrl MP3s) are
                    // ever enqueued and played. Nick's own voice inputs
                    // go to /api/selena-voice/input as text and are
                    // never read aloud.
                    markSpokenLocked(text);
                    broadcastMessage(text);
                    try {
                        File f = downloadToCache(audioUrl);
                        final File df = f;
                        handler.post(() -> enqueueAudio(df));
                    } catch (Exception e) {
                        log("audio download failed: " + audioUrl + ": " + e);
                    }
                }
            } else {
                // Aria quality mode with no audioUrl yet — the Aria MP3
                // will arrive as its own queue message. Don't speak the
                // text on-device; that would double up. Don't markSpoken
                // here; the MP3 phase will mark it when it plays.
                broadcastMessage(text);
                log("tts: text-only message held for Aria MP3 (quality mode)");
            }
        }
        return maxTs;
    }

    /** Must be called with spokenTexts locked. */
    private void markSpokenLocked(String text) {
        spokenTexts.add(text);
        while (spokenTexts.size() > 20) spokenTexts.removeFirst();
    }

    // ------------------------------------------------------------ outbound: MP3 playback

    /** Runs on main thread: queue a downloaded file for playback. */
    private void enqueueAudio(File f) {
        synchronized (audioQueue) {
            audioQueue.add(f);
        }
        pumpAudioQueue();
    }

    /** Runs on main thread: start the next queued file if idle. */
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
     * Nick 2026-10-18 v1.11: Bluetooth-aware audio routing. If a BT
     * output device is connected, leave routing alone and let Android use
     * it. Otherwise fall back to speakerphone. Sets btRouted so
     * restoreAudioRouting() knows what to undo.
     *
     * Nick 2026-10-19 v1.17: output-toggle overrides, EXCLUSIVE —
     * - force_speaker on: ONLY the phone loudspeaker, never Bluetooth.
     * - force_bluetooth on: ONLY Bluetooth, never the phone speaker.
     *   If no BT device is actually connected, falls back to the
     *   speakerphone (can't play to nothing) — never both at once.
     * Neither on = the v1.11 auto behavior. btRouted is false whenever
     * we touched the speakerphone, so restoreAudioRouting() undoes it.
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
        try {
            speakerWasOn = audioManager.isSpeakerphoneOn();
        } catch (Exception ignored) {}
        if (isForceSpeaker()) {
            try {
                audioManager.setSpeakerphoneOn(true);
            } catch (Exception e) {
                log("audio: setSpeakerphoneOn failed: " + e);
            }
            btRouted = false;
            log("audio: output toggle -> phone speaker only");
            return false;
        }
        if (isForceBluetooth()) {
            if (btConnected) {
                // Exclusive BT: kill the speakerphone so nothing leaks
                // to it; restoreAudioRouting() puts it back after.
                try {
                    audioManager.setSpeakerphoneOn(false);
                } catch (Exception e) {
                    log("audio: setSpeakerphoneOn(false) failed: " + e);
                }
                btRouted = false;
                log("audio: output toggle -> Bluetooth only");
                return true;
            }
            log("audio: BT toggle on but no BT connected, using speaker");
            try {
                audioManager.setSpeakerphoneOn(true);
            } catch (Exception e) {
                log("audio: setSpeakerphoneOn failed: " + e);
            }
            btRouted = false;
            return false;
        }
        btRouted = btConnected;
        if (btConnected) {
            log("audio: bluetooth connected, routing to BT");
            return true;
        }
        try {
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
            // USAGE_VOICE_COMMUNICATION, which Android routes to the
            // EARPIECE (like a phone call) — MediaPlayer reported
            // "started"/"done" with no errors while Nick heard nothing.
            // Route through the loudspeaker instead: USAGE_MEDIA
            // (= STREAM_MUSIC) + setSpeakerphoneOn(true).
            // Nick 2026-10-18 v1.10: respect his device volume — do NOT
            // touch setStreamVolume at all. If he's got it muted, it
            // plays muted. His choice.
            //
            // Undo the mic-beep volume zeroing first so playback uses
            // his REAL volume, not 0 and not a forced level.
            // Nick 2026-10-19 v1.17: then boost to max for the duration of
            // playback so Selena overpowers background audio (Spotify);
            // restored when playback ends. His explicit request, supersedes
            // the v1.10 no-override rule for the playback window only.
            unmuteBeep();
            log("audio: using device volume (no override)");
            // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win over the
            // speakerphone when connected.
            routeAudioForPlayback();
            log("audio: stream=MUSIC (USAGE_MEDIA)");
            requestAudioFocus();
            boostMusicVolume();
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            // Nick 2026-10-19 v1.16: keep the CPU awake through playback
            // with the screen locked.
            player.setWakeMode(getApplicationContext(),
                    PowerManager.PARTIAL_WAKE_LOCK);
            player.setOnCompletionListener(new PlayerCompletionListener(this));
            player.setOnErrorListener(new PlayerErrorListener(this));
            player.setDataSource(f.getAbsolutePath());
            log("audio: MediaPlayer setDataSource " + f.getAbsolutePath());
            // Synchronous prepare on main thread for a local file is fast
            // (<100ms).
            player.prepare();
            log("audio: MediaPlayer prepared, duration=" + player.getDuration() + "ms");
            // Nick 2026-10-19 v1.18: LoudnessEnhancer on this player's audio
            // session — pushes her past max stream volume for a wider
            // margin over Spotify. MP3/Aria path only; TTS has no session.
            // Nick 2026-10-19 v1.19: gain raised to +600mB (the cap); he
            // asked for a little more margin.
            try {
                if (loudnessEnhancer != null) {
                    try { loudnessEnhancer.release(); } catch (Exception ignored) {}
                    loudnessEnhancer = null;
                }
                int sessionId = player.getAudioSessionId();
                if (sessionId == 0) {
                    // Session 0 = global output mix: attaching here would boost
                    // Spotify too. Skip rather than widen the effect.
                    log("audio: LoudnessEnhancer skipped (session 0)");
                } else {
                    loudnessEnhancer = new LoudnessEnhancer(sessionId);
                    loudnessEnhancer.setEnabled(true);
                    loudnessEnhancer.setTargetGain(600); // +6dB (cap)
                    log("audio: LoudnessEnhancer +600mB on session " + sessionId);
                }
            } catch (Exception e) {
                log("audio: LoudnessEnhancer failed: " + e);
                loudnessEnhancer = null;
            }
            ensureMaxMusic(); // Nick 2026-10-19 v1.18: re-assert before start()
            player.start();
            isPlayingAudio = true;
            log("playback started (echo guard on)");
            currentAudioFile = f;
        } catch (Exception e) {
            log("play failed: " + e);
            onPlaybackDone();
        }
    }

    /** Called by PlayerCompletionListener / PlayerErrorListener. */
    public void onPlaybackDone() {
        handler.post(new PlaybackDoneTask(this));
    }

    /** Called by PlaybackDoneTask on main thread after playback finishes or fails. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    public void onPlaybackDoneNow() {
        abandonAudioFocus();
        restoreMusicVolume(); // Nick 2026-10-19 v1.17: undo the playback boost
        releasePlayer();
        playing = false;
        isPlayingAudio = false;
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

    /** Runs on a worker thread: download one audio file to the cache dir. */
    public File downloadToCache(String urlStr) throws Exception {
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

    // ------------------------------------------------------------ outbound: on-device TTS (speed mode)

    /** Nick 2026-10-18 v1.11: init Android's built-in TTS for speed mode. */
    private void initOnDeviceTts() {
        try {
            // Lambda implements TextToSpeech.OnInitListener (an interface,
            // not a class extension — safe for this project's d8).
            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int r = tts.setLanguage(Locale.US);
                    ttsReady = (r != TextToSpeech.LANG_MISSING_DATA
                            && r != TextToSpeech.LANG_NOT_SUPPORTED);
                    if (ttsReady) {
                        tts.setOnUtteranceProgressListener(
                                new TtsProgressListener(VoiceService.this));
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
        handler.post(this::onTtsDone);
    }

    /** Runs on main thread: queue text for on-device speech. */
    private void enqueueTts(String text) {
        synchronized (ttsQueue) {
            ttsQueue.add(text);
        }
        pumpTtsQueue();
    }

    /** Runs on main thread: speak the next queued text if nothing is playing. */
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
        // Same routing as MP3 playback: loudspeaker, and undo the
        // mic-beep volume zeroing first so we use Nick's real device
        // volume (v1.10: no override, respect his setting).
        // Nick 2026-10-19 v1.17: then boost to max while she talks so she
        // overpowers Spotify; restored in onTtsDone. His explicit request.
        unmuteBeep();
        // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win over the
        // speakerphone when connected, same as MP3 playback.
        routeAudioForPlayback();
        log("tts: stream=MUSIC");
        requestAudioFocus();
        boostMusicVolume();
        isPlayingAudio = true;
        log("tts: echo guard on");
        try {
            // minSdk is 26, so the API-21+ speak() with utterance ID is
            // always available.
            Bundle params = new Bundle();
            String utteranceId = "selena-" + System.currentTimeMillis();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID,
                    utteranceId);
            ensureMaxMusic(); // Nick 2026-10-19 v1.18: re-assert before speak()
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
        restoreMusicVolume(); // Nick 2026-10-19 v1.17: undo the speech boost
        ttsSpeaking = false;
        isPlayingAudio = false;
        // Put the speakerphone back the way we found it, same as MP3.
        // No-op when Bluetooth handled the routing.
        restoreAudioRouting();
        log("tts: echo guard off");
        pumpTtsQueue();
        pumpAudioQueue();
    }

    private void releasePlayer() {
        // Nick 2026-10-19 v1.18: release the LoudnessEnhancer with the player
        // so it never lingers on a dead audio session.
        if (loudnessEnhancer != null) {
            try { loudnessEnhancer.setEnabled(false); } catch (Exception ignored) {}
            try { loudnessEnhancer.release(); } catch (Exception ignored) {}
            loudnessEnhancer = null;
        }
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
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attrs)
                    .build();
            // Nick 2026-10-19 v1.18: capture the result and retry once on
            // failure — a failed focus means Spotify never ducks.
            int rc = audioManager.requestAudioFocus(focusRequest);
            log("audio: focus request rc=" + rc + " (1=granted)");
            if (rc == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                focusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attrs)
                        .build();
                rc = audioManager.requestAudioFocus(focusRequest);
                log("audio: focus retry rc=" + rc + " (1=granted)");
            }
        } catch (Exception e) {
            log("audio: focus request failed: " + e);
        }
    }

    private void abandonAudioFocus() {
        try {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } catch (Exception ignored) {}
    }

    private boolean isPhoneVoiceMode() {
        return VOICE_PHONE.equals(getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_VOICE_MODE, VOICE_PHONE));
    }

    // ------------------------------------------------------------ heartbeat + remote logs

    /** Start the 30s heartbeat for the service's lifetime. */
    private void startHeartbeat() {
        if (heartbeating) return;
        heartbeating = true;
        handler.post(new ServiceHeartbeatTask(this));
        log("heartbeat started");
    }

    public boolean isHeartbeating() {
        return heartbeating;
    }

    /**
     * Called by ServiceHeartbeatTask on the main thread. POSTs to the
     * heartbeat endpoint on a worker thread (Android forbids network on
     * main thread).
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
        if (!heartbeating || destroyed) return;
        handler.postDelayed(new ServiceHeartbeatTask(this), HEARTBEAT_MS);
    }

    // ------------------------------------------------------------ inbound: always listening

    private void startContinuousListening() {
        if (recognizerActive || destroyed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("speech recognition not available");
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
            recognizerActive = true;
            launchRecognizer();
            broadcastListeningState();
            log("continuous listening started");
        } catch (Exception e) {
            Log.w(TAG, "startContinuousListening failed", e);
            recognizerActive = false;
        }
    }

    private void stopContinuousListening() {
        recognizerActive = false;
        unmuteBeep();
        destroyRecognizer();
        updateNotification("⏸️ Paused");
        broadcastListeningState();
    }

    private void launchRecognizer() {
        if (!recognizerActive || recognizer == null || destroyed) return;
        try {
            muteBeep();
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(intent);
            updateNotification("🎤 Listening…");
        } catch (Exception e) {
            Log.w(TAG, "launchRecognizer failed", e);
            unmuteBeep();
            scheduleRecognizerRestart();
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void scheduleRecognizerRestart() {
        if (destroyed || !recognizerActive) return;
        handler.postDelayed(new RecognizerRestartTask(this), RESTART_DELAY_MS);
    }

    /**
     * Nick 2026-10-18 v1.3: for NO_MATCH/SPEECH_TIMEOUT (routine silence,
     * not a real error). Skips the unmuteBeep() — volumes stay zeroed from
     * the last mute, so there's no window where the beep can slip through.
     * launchRecognizer() will re-mute (no-op) before startListening().
     */
    public void scheduleQuietRestart() {
        if (destroyed || !recognizerActive) return;
        Log.d(TAG, "quiet restart scheduled (" + RESTART_DELAY_MS + "ms)");
        handler.postDelayed(new RecognizerRestartTask(this), RESTART_DELAY_MS);
    }

    /** Called by RecognizerRestartTask on main thread. */
    public void restartRecognizerNow() {
        if (destroyed || !recognizerActive) return;
        destroyRecognizer();
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
            launchRecognizer();
        } catch (Exception e) {
            Log.w(TAG, "restart failed", e);
            scheduleRecognizerRestart();
        }
    }

    // Callbacks from ServiceRecognitionListener (main thread):

    public void onSpeechDetected() {
        sendBroadcast(new Intent(ACTION_SPEECH_DETECTED));
    }

    public void onSpeechEnd() {
        unmuteBeep();
    }

    public void onRecognizerDone() {
        unmuteBeep();
        scheduleRecognizerRestart();
    }

    public void onVoiceInput(String text) {
        // Nick 2026-10-18 v1.9: tell the UI BEFORE the network POST so
        // Nick's words appear in the message log instantly, even if the
        // POST later fails (offline, timeout). His inputs are never spoken
        // aloud — playback only plays Selena's reply MP3s from the queue.
        Intent sent = new Intent(ACTION_INPUT_SENT);
        sent.putExtra(EXTRA_TEXT, text);
        sendBroadcast(sent);
        new Thread(new InputPostTask(this, text)).start();
    }

    /** Called by InputPostTask on background thread. */
    public void doPostInput(String text) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_URL + "/api/selena-voice/input");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            JSONObject body = new JSONObject();
            body.put("text", text);
            body.put("timestamp", System.currentTimeMillis());
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignored) {}
            }
            int code = conn.getResponseCode();
            log("input posted (" + code + "): " + text);
            // Nick 2026-10-18 v1.9: the INPUT_SENT broadcast now fires in
            // onVoiceInput() before the POST, so the log is instant even
            // when the network is down. Nothing to broadcast here anymore.
        } catch (Exception e) {
            Log.w(TAG, "postInput failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------ helpers

    private void broadcastPollStatus(String status) {
        Intent i = new Intent(ACTION_POLL_STATUS);
        i.putExtra(EXTRA_POLL_STATUS, status);
        sendBroadcast(i);
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

    /**
     * Parse a Supabase row created_at ("YYYY-MM-DD HH:MM:SS.ffffff+00")
     * to epoch millis. Returns 0 when unparseable. (Nick 2026-10-21:
     * the realtime cursor must track the row's time, not wall-clock.)
     */
    private static long parseRowTimestamp(String createdAt) {
        if (createdAt == null || createdAt.isEmpty()) return 0;
        try {
            return java.time.OffsetDateTime.parse(
                createdAt.replace(' ', 'T')).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    private void broadcastMessage(String text) {
        Intent i = new Intent(ACTION_MESSAGE);
        i.putExtra(EXTRA_TEXT, text);
        sendBroadcast(i);
    }

    private void broadcastListeningState() {
        Intent i = new Intent(ACTION_LISTENING_STATE);
        i.putExtra(EXTRA_STATE, recognizerActive);
        sendBroadcast(i);
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

    private boolean isAlwaysListening() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ALWAYS_ON, true);
    }

    private void setAlwaysListening(boolean on) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ALWAYS_ON, on).apply();
    }

    public static void setAlwaysListening(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ALWAYS_ON, on).apply();
        Intent i = new Intent(ctx, VoiceService.class);
        i.setAction("TOGGLE_LISTENING");
        i.putExtra("on", on);
        ctx.startForegroundService(i);
    }

    public static boolean getAlwaysListening(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ALWAYS_ON, true);
    }

    // Nick 2026-10-19 v1.17: no-auto-readout toggle. When on, incoming
    // queue messages are logged + deduped but never played aloud.
    public static boolean getNoAutoReadout(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_NO_AUTO_READOUT, false);
    }

    public static void setNoAutoReadout(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_NO_AUTO_READOUT, on).apply();
    }

    // Nick 2026-10-19 v1.17: output-route toggles. Mutually exclusive —
    // turning one on turns the other off. Neither on = auto routing.
    public static boolean getForceBluetooth(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCE_BLUETOOTH, false);
    }

    public static void setForceBluetooth(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FORCE_BLUETOOTH, on)
                .putBoolean(KEY_FORCE_SPEAKER, false).apply();
    }

    public static boolean getForceSpeaker(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_FORCE_SPEAKER, false);
    }

    public static void setForceSpeaker(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FORCE_SPEAKER, on)
                .putBoolean(KEY_FORCE_BLUETOOTH, false).apply();
    }

    private boolean isNoAutoReadout() {
        return getNoAutoReadout(this);
    }

    private boolean isForceBluetooth() {
        return getForceBluetooth(this);
    }

    private boolean isForceSpeaker() {
        return getForceSpeaker(this);
    }

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }
}
