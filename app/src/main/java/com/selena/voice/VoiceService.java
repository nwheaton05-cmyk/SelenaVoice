package com.selena.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
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
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;

/**
 * Selena Voice foreground service (Nick, 2026-10-18).
 *
 * Two jobs:
 *  1. OUTBOUND (Selena -> Nick): polls /api/selena-voice/queue?since= every
 *     3 seconds, downloads new voice-message audio, plays immediately via
 *     MediaPlayer (prepareAsync for low latency).
 *  2. INBOUND (Nick -> Selena): continuous SpeechRecognizer ("always
 *     listening"). On speech, transcribes and POSTs to
 *     /api/selena-voice/input. Auto-restarts on NO_MATCH / SPEECH_TIMEOUT
 *     with a 500ms backoff to avoid tight loops.
 *
 * Token-lean by design: polling is plain HTTP delta fetches, STT is
 * on-device, and input POSTs carry only {text, timestamp}.
 */
public class VoiceService extends Service {
    private static final String TAG = "SelenaVoice";
    private static final String CHANNEL_ID = "selena_voice_channel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_MESSAGE = "com.selena.voice.MESSAGE";
    public static final String ACTION_SPEECH_DETECTED = "com.selena.voice.SPEECH_DETECTED";
    public static final String ACTION_INPUT_SENT = "com.selena.voice.INPUT_SENT";
    public static final String ACTION_LISTENING_STATE = "com.selena.voice.LISTENING_STATE";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_STATE = "listening";

    private static final String PREFS = "selena_voice";
    private static final String KEY_LAST_TS = "last_ts";
    private static final String KEY_ALWAYS_ON = "always_listening";

    private static final String BASE_URL = "https://siteview-pi.vercel.app";
    private static final long POLL_MS = 3000;       // Nick 2026-10-18: 3s, not 5
    private static final long RESTART_DELAY_MS = 500;

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
    // Nick 2026-10-18: streams to mute to suppress the SpeechRecognizer
    // start/stop beep. Different manufacturers use different streams.
    private static final int[] BEEP_STREAMS = {
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
        createChannel();
        startForeground(NOTIF_ID, buildNotification("Listening for Selena…"));
        handler.post(pollRunnable);
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
                // One-shot manual fallback (mic button).
                startOneShotListening();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        unmuteBeep();
        stopContinuousListening();
        releasePlayer();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ notification

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Selena Voice", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Hands-free voice link to Selena");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
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

    /** Nick 2026-10-18: update the persistent notification text. */
    private void updateNotification(String text) {
        try {
            notificationManager.notify(NOTIF_ID, buildNotification(text));
        } catch (Exception ignored) {}
    }

    /**
     * Nick 2026-10-18: suppress the SpeechRecognizer start/stop beep.
     * Mute the streams that carry it BEFORE startListening(); unmute after
     * the utterance ends (results/error). Visual indication only — no sound.
     */
    private void muteBeep() {
        try {
            for (int stream : BEEP_STREAMS) {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0);
            }
        } catch (Exception ignored) {}
    }

    private void unmuteBeep() {
        try {
            for (int stream : BEEP_STREAMS) {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0);
            }
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ outbound: poll & play

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            new Thread(() -> {
                try {
                    pollOnce();
                } catch (Exception e) {
                    Log.w(TAG, "poll failed", e);
                }
                if (!destroyed) handler.postDelayed(pollRunnable, POLL_MS);
            }).start();
        }
    };

    private void pollOnce() throws Exception {
        long since = getLastTs();
        String urlStr = BASE_URL + "/api/selena-voice/queue?since="
                + URLEncoder.encode(String.valueOf(since), "UTF-8");
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        try {
            if (conn.getResponseCode() != 200) return;
            String body = readAll(conn.getInputStream());
            JSONArray arr = new JSONArray(body);
            long maxTs = since;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                long ts = m.optLong("timestamp", 0);
                String text = m.optString("text", "");
                String audioUrl = m.optString("audioUrl", "");
                if (ts > maxTs) maxTs = ts;
                final String fText = text;
                handler.post(() -> broadcastMessage(fText));
                if (!audioUrl.isEmpty()) {
                    String full = audioUrl.startsWith("http") ? audioUrl : BASE_URL + audioUrl;
                    synchronized (audioQueue) {
                        audioQueue.add(full);
                    }
                    handler.post(this::pumpAudioQueue);
                }
            }
            if (maxTs > since) setLastTs(maxTs);
        } finally {
            conn.disconnect();
        }
    }

    private void pumpAudioQueue() {
        if (playing) return;
        String url;
        synchronized (audioQueue) {
            url = audioQueue.poll();
        }
        if (url == null) return;
        playing = true;
        new Thread(() -> {
            try {
                File f = downloadToCache(url);
                handler.post(() -> playFile(f));
            } catch (Exception e) {
                Log.w(TAG, "audio download failed", e);
                handler.post(() -> {
                    playing = false;
                    pumpAudioQueue();
                });
            }
        }).start();
    }

    private File downloadToCache(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        File out = File.createTempFile("selena-", ".mp3", getCacheDir());
        try (InputStream in = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
        } finally {
            conn.disconnect();
        }
        return out;
    }

    private void playFile(File f) {
        try {
            requestAudioFocus();
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            // Nick 2026-10-18: prepareAsync for lowest playback latency.
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> {
                abandonAudioFocus();
                playing = false;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                pumpAudioQueue();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                abandonAudioFocus();
                playing = false;
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                pumpAudioQueue();
                return true;
            });
            player.setDataSource(f.getAbsolutePath());
            player.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "play failed", e);
            playing = false;
            pumpAudioQueue();
        }
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
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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

    // ------------------------------------------------------------------ inbound: always listening

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

    private void startOneShotListening() {
        // Manual fallback: single utterance even if always-on is off.
        if (recognizerActive) return; // already listening
        startContinuousListening();
    }

    private void launchRecognizer() {
        if (!recognizerActive || recognizer == null || destroyed) return;
        try {
            // Nick 2026-10-18: mute BEFORE startListening to kill the beep.
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
            scheduleRestart();
        }
    }

    private void destroyRecognizer() {
        if (recognizer != null) {
            try { recognizer.destroy(); } catch (Exception ignored) {}
            recognizer = null;
        }
    }

    private void scheduleRestart() {
        if (destroyed || !recognizerActive) return;
        handler.postDelayed(() -> {
            if (destroyed || !recognizerActive) return;
            // Recreate (not just startListening) — the recognizer is
            // single-shot; a fresh instance is the reliable restart path.
            destroyRecognizer();
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(this);
                recognizer.setRecognitionListener(recognitionListener);
                launchRecognizer();
            } catch (Exception e) {
                Log.w(TAG, "restart failed", e);
                scheduleRestart();
            }
        }, RESTART_DELAY_MS);
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onRmsChanged(float rmsdB) {}

        @Override
        public void onBeginningOfSpeech() {
            // Nick 2026-10-18: pulse the UI dot so he knows it's hearing him.
            sendBroadcast(new Intent(ACTION_SPEECH_DETECTED));
        }

        @Override public void onEndOfSpeech() {
            unmuteBeep();
        }

        @Override
        public void onError(int error) {
            // NO_MATCH / SPEECH_TIMEOUT in a quiet room are normal — just
            // restart. Any error restarts; 500ms backoff avoids tight loops.
            Log.d(TAG, "recognizer error: " + error);
            unmuteBeep();
            scheduleRestart();
        }

        @Override
        public void onResults(Bundle results) {
            unmuteBeep();
            ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
            if (!text.isEmpty()) {
                postInput(text);
            }
            scheduleRestart();
        }

        @Override public void onPartialResults(Bundle partialResults) {}
        @Override public void onEvent(int eventType, Bundle params) {}
    };

    /** POST transcribed text to Selena. Minimal payload: {text, timestamp}. */
    private void postInput(final String text) {
        new Thread(() -> {
            try {
                URL url = new URL(BASE_URL + "/api/selena-voice/input");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("timestamp", System.currentTimeMillis());
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes("UTF-8"));
                }
                int code = conn.getResponseCode();
                conn.disconnect();
                Log.i(TAG, "input posted (" + code + "): " + text);
                Intent i = new Intent(ACTION_INPUT_SENT);
                i.putExtra(EXTRA_TEXT, text);
                sendBroadcast(i);
            } catch (Exception e) {
                Log.w(TAG, "postInput failed", e);
            }
        }).start();
    }

    // ------------------------------------------------------------------ helpers

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

    /** Called by MainActivity toggle. */
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
        return sb.toString();
    }
}
