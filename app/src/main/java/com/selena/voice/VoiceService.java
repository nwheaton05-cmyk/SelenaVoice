package com.selena.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
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
 * Two jobs (Nick 2026-10-18):
 *  1. OUTBOUND (Selena -> Nick): polls /api/selena-voice/queue?since= every
 *     3 seconds, downloads new voice-message audio, plays immediately via
 *     MediaPlayer.
 *     [v1.4: MOVED to MainActivity — Samsung kills this service's Handler
 *     loop, so the service never polled. The activity polls while visible.]
 *  2. INBOUND (Nick -> Selena): continuous SpeechRecognizer ("always
 *     listening"). On speech, transcribes and POSTs to
 *     /api/selena-voice/input. Auto-restarts on NO_MATCH / SPEECH_TIMEOUT
 *     with a 500ms backoff to avoid tight loops. Beep suppressed via
 *     stream mute; visual indication only.
 *
 * Token-lean: polling is plain HTTP delta fetches, STT is on-device,
 * input POSTs carry only {text, timestamp}.
 *
 * NOTE: All helpers are top-level classes (no inner/anonymous classes)
 * because d8 8.2.2 crashes on inner-class bytecode from javac 21.
 */
public class VoiceService extends Service {
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

    private static final String BASE_URL = "https://siteview-pi.vercel.app";
    private static final long POLL_MS = 3000;
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

    // Nick 2026-10-18 v1.2: echo prevention. True while Selena's audio
    // response is playing via MediaPlayer — recognition results arriving
    // during playback are the speaker, not Nick, and get discarded.
    // Nick 2026-10-18 v1.4: static — MainActivity now owns outbound
    // polling + playback, so it sets this flag; the service is mic-only.
    private static volatile boolean isPlayingAudio = false;

    public boolean isPlayingAudio() {
        return isPlayingAudio;
    }

    /** Called by MainActivity while it plays Selena's voice messages. */
    public static void setPlayingAudio(boolean playing) {
        isPlayingAudio = playing;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean recognizerActive = false;
    private boolean destroyed = false;
    private MediaPlayer player;
    private final Queue<String> audioQueue = new LinkedList<>();
    private boolean playing = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private NotificationManager notificationManager;
    private ServiceRecognitionListener recognitionListener;

    // ------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        recognitionListener = new ServiceRecognitionListener(this);
        createChannel();
        startForeground(NOTIF_ID, buildNotification("Listening for Selena…"));
        // Nick 2026-10-18 v1.4: outbound polling moved to MainActivity.
        // Samsung's background execution limits kill this service's Handler
        // loop, so the service never polled ("Poll: not started" forever).
        // The service is now INBOUND (mic) only.
        // scheduleNextPoll();
        if (isAlwaysListening()) {
            startContinuousListening();
        }
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("TOGGLE_LISTENING".equals(intent.getAction())) {
                boolean on = intent.getBooleanExtra("on", true);
                setAlwaysListening(on);
                if (on) startContinuousListening();
                else stopContinuousListening();
                broadcastListeningState();
            } else if ("MANUAL_LISTEN".equals(intent.getAction())) {
                if (!recognizerActive) startContinuousListening();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        unmuteBeep();
        recognizerActive = false;
        destroyRecognizer();
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
                audioManager.setStreamVolume(BEEP_STREAMS[i], savedVolumes[i], 0);
            }
            beepMuted = false;
            Log.d(TAG, "beep streams restored");
        } catch (Exception e) {
            Log.w(TAG, "unmuteBeep failed", e);
        }
    }

    // ------------------------------------------------------------ outbound: poll & play

    /** Called by PollTask (runs on background thread). */
    public void pollOnce() throws Exception {
        // Nick 2026-10-18 v1.3: broadcast at START so the UI can tell
        // whether poll is even being called (was stuck on "not started").
        broadcastPollStatus("Poll: starting…");
        long since = getLastTs();
        String urlStr = BASE_URL + "/api/selena-voice/queue?since="
                + URLEncoder.encode(String.valueOf(since), "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        try {
            int code = conn.getResponseCode();
            Log.i(TAG, "poll: HTTP " + code + " (since=" + since + ")");
            if (code != 200) {
                Log.w(TAG, "poll: non-200 response: " + code);
                broadcastPollStatus("Last poll: HTTP " + code);
                return;
            }
            String body = readAll(conn.getInputStream());
            Log.d(TAG, "poll: body length=" + body.length());
            JSONArray arr;
            try {
                String trimmed = body.trim();
                if (trimmed.startsWith("{")) {
                    // Tolerant: API may return {"messages": [...]}
                    JSONObject obj = new JSONObject(trimmed);
                    arr = obj.optJSONArray("messages");
                    if (arr == null) arr = new JSONArray();
                    Log.w(TAG, "poll: wrapped object, extracted " + arr.length()
                            + " messages");
                } else {
                    arr = new JSONArray(trimmed);
                }
            } catch (Exception e) {
                Log.e(TAG, "poll: JSON parse failed. Body: " + body, e);
                broadcastPollStatus("Last poll: JSON parse error");
                return;
            }
            int count = arr.length();
            Log.i(TAG, "poll: " + count + " message(s)");
            long maxTs = since;
            for (int i = 0; i < count; i++) {
                JSONObject m = arr.getJSONObject(i);
                long ts = parseTimestamp(m);
                String text = m.optString("text", "");
                String audioUrl = m.optString("audioUrl", "");
                if (ts > maxTs) maxTs = ts;
                broadcastMessage(text);
                if (!audioUrl.isEmpty()) {
                    String full = audioUrl.startsWith("http") ? audioUrl : BASE_URL + audioUrl;
                    synchronized (audioQueue) {
                        audioQueue.add(full);
                    }
                    pumpAudioQueue();
                }
            }
            if (maxTs > since) setLastTs(maxTs);
            broadcastPollStatus("Last poll: 200 OK, " + count + " message(s)");
        } catch (Exception e) {
            Log.e(TAG, "poll: exception", e);
            broadcastPollStatus("Last poll: failed - " + e.getClass().getSimpleName());
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    /** Called by PollTask after pollOnce (runs on background thread). */
    public void scheduleNextPoll() {
        if (destroyed) return;
        handler.postDelayed(new PollTask(this), POLL_MS);
    }

    public boolean isDestroyedFlag() {
        return destroyed;
    }

    private void pumpAudioQueue() {
        handler.post(new PumpTask(this));
    }

    /** Called on main thread to start the next download if idle. */
    public void pumpAudioQueueNow() {
        if (playing) return;
        String url;
        synchronized (audioQueue) {
            url = audioQueue.poll();
        }
        if (url == null) return;
        playing = true;
        new Thread(new AudioDownloadTask(this, url)).start();
    }

    /** Called by AudioDownloadTask on background thread. */
    public File downloadToCache(String urlStr) throws Exception {
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

    /** Called by AudioDownloadTask on background thread. Plays immediately. */
    public void playDownloadedFile(final File f) {
        handler.post(new PlayTask(this, f));
    }

    /** Runs on main thread: starts MediaPlayer for the downloaded file. */
    public void playFileNow(File f) {
        try {
            requestAudioFocus();
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setOnCompletionListener(new PlayerCompletionListener(this));
            player.setOnErrorListener(new PlayerErrorListener(this));
            player.setDataSource(f.getAbsolutePath());
            // Synchronous prepare on main thread for a local file is fast
            // (<100ms); avoids the prepareAsync listener (d8 inner-class bug).
            player.prepare();
            player.start();
            isPlayingAudio = true;
            Log.d(TAG, "playback started (echo guard on)");
            currentAudioFile = f;
        } catch (Exception e) {
            Log.w(TAG, "play failed", e);
            onPlaybackDone();
        }
    }

    private File currentAudioFile;

    /** Called by PlayerCompletionListener / PlayerErrorListener. */
    public void onPlaybackDone() {
        handler.post(new PlaybackDoneTask(this));
    }

    /** Runs on main thread after playback finishes or fails. */
    public void onPlaybackDoneNow() {
        abandonAudioFocus();
        releasePlayer();
        playing = false;
        isPlayingAudio = false;
        Log.d(TAG, "playback done (echo guard off)");
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

    // ------------------------------------------------------------ inbound: always listening

    private void startContinuousListening() {
        if (recognizerActive || destroyed) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "speech recognition not available");
            return;
        }
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
            recognizerActive = true;
            launchRecognizer();
            broadcastListeningState();
            Log.i(TAG, "continuous listening started");
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
            Log.i(TAG, "input posted (" + code + "): " + text);
            Intent i = new Intent(ACTION_INPUT_SENT);
            i.putExtra(EXTRA_TEXT, text);
            sendBroadcast(i);
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
            Log.w(TAG, "parseTimestamp: unparseable '" + s + "'");
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
        return getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_LAST_TS, 0);
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

    private static String readAll(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }
}
