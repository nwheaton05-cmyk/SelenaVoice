package com.selena.voice;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

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
 *     Aria mode: synthesize text in-app via Google Cloud TTS (Achernar,
 *     v1.43+) and play the result. Server MP3s still play when a row
 *     arrives carrying one whose text was never spoken (transition).
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
    // Nick 2026-10-21: playback pause/resume state for the pause button.
    public static final String ACTION_PLAYBACK_STATE = "com.selena.voice.PLAYBACK_STATE";
    // Nick 2026-10-22 v1.28: short user-facing feedback ("Paused",
    // "Nothing playing to pause") so a tap never silently dies.
    public static final String ACTION_TOAST = "com.selena.voice.TOAST";
    public static final String EXTRA_TOAST = "toast_text";
    // Nick 2026-10-21 v1.24: replay a cached message MP3 from the UI.
    public static final String ACTION_REPLAY = "com.selena.voice.REPLAY";
    // Nick 2026-10-22 v1.35: hard-stop all playback from the UI.
    public static final String ACTION_STOP_ALL = "com.selena.voice.STOP_ALL";
    public static final String ACTION_REQUEST_STATUS = "com.selena.voice.REQUEST_STATUS";
    // Nick 2026-10-23 v1.41: service -> activity request to surface the
    // RECORD_AUDIO permission prompt (system dialog or Settings).
    public static final String ACTION_REQUEST_MIC_PERMISSION = "com.selena.voice.REQUEST_MIC_PERMISSION";
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_POLL_STATUS = "poll_status";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_STATE = "listening";
    // Nick 2026-10-23 v1.46: live dictation — the recognizer's partial
    // (in-progress) transcription, broadcast for the dictation popup.
    public static final String ACTION_PARTIAL_INPUT = "com.selena.voice.PARTIAL_INPUT";

    private static final String PREFS = "selena_voice";
    private static final String KEY_LAST_TS = "last_ts";
    private static final String KEY_ALWAYS_ON = "always_listening";
    // Nick 2026-10-18 v1.11: voice mode lives in SharedPreferences so the
    // activity toggle and the service read the same value.
    public static final String KEY_VOICE_MODE = "voice_mode";
    public static final String VOICE_PHONE = "phone"; // fast, on-device TTS
    public static final String VOICE_ARIA = "aria";   // quality, in-app Google Cloud TTS (Achernar).
    // (Pref value "aria" kept for stored-prefs compat; the server-MP3 path it
    // once meant was replaced by in-app synthesis in v1.43.)

    // Nick 2026-10-23 v1.43: Google Cloud Text-to-Speech, called IN THE APP
    // ("you send Selena Voice text, Selena Voice talks"). The API key is
    // injected at build time by build-v143.sh — the source keeps only the
    // placeholder below so the key never lands in git. The key is
    // restricted to the Cloud Text-to-Speech API only.
    private static final String GOOGLE_TTS_API_KEY = "__GOOGLE_TTS_API_KEY__";
    private static final String GOOGLE_TTS_VOICE = "en-US-Chirp3-HD-Achernar";
    private static final String GOOGLE_TTS_URL =
            "https://texttospeech.googleapis.com/v1/text:synthesize";
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
    // Nick 2026-10-23 v1.47: mic mute during Selena playback — true
    // between muteMicForPlayback() (playback starting) and
    // unmuteMicAfterPlayback() (playback fully done). While true the
    // recognizer is stopped entirely so her voice can't be transcribed
    // as Nick's input. Inverse of the v1.46 polite-interruption hold
    // (which holds PLAYBACK while Nick speaks).
    private boolean micMutedForPlayback = false;
    private MediaPlayer player;
    // Nick 2026-10-22 v1.34: path of the MP3 currently loaded in the
    // player (null when idle). Lets CLEAR_UNLOCKED stop playback only
    // when the playing message is unlocked.
    private String currentAudioPath;
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

    // Nick 2026-10-23 v1.46: polite interruption — true between the
    // recognizer's onBeginningOfSpeech and onEndOfSpeech. Entirely
    // on-device: the app holds Selena's playback while this is true and
    // releases it on his pause. Never stuck: reset in onSpeechEnd and
    // onRecognizerDone (every utterance ends in one of those, onResults,
    // or onError).
    private volatile boolean nickSpeaking = false;

    // Nick 2026-10-19 v1.16: outbound (moved from MainActivity).
    private RealtimeClient realtimeClient;
    private boolean polling = false;
    private boolean heartbeating = false;

    // Nick 2026-10-18 v1.11: on-device TTS for speed mode (moved here v1.16).
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private final Queue<String> ttsQueue = new LinkedList<>();
    private boolean ttsSpeaking = false;
    // Nick 2026-10-22 v1.27: pause/resume for phone (on-device TTS) mode.
    // Android TTS has no pause API — pause = tts.stop(), resume = re-speak
    // from the last word boundary reported by onRangeStart.
    private String ttsCurrentText = null;
    private int ttsResumeAtChar = 0;
    private boolean ttsPaused = false;
    private boolean ttsPauseRequested = false;
    // Nick 2026-10-18 v1.13: dedupe set for two-phase server delivery.
    // The server queues TEXT immediately, then queues the SAME text with an
    // MP3 audioUrl seconds later. Texts are added only when actually
    // spoken/played, so Aria mode holding a text-only message doesn't
    // consume the entry the MP3 version still needs. Bounded FIFO (20).
    // v1.16: guarded — handleQueueMessage can now run on the poll worker
    // thread OR the Realtime reader thread.
    private final LinkedList<String> spokenTexts = new LinkedList<>();

    /**
     * Nick 2026-10-23 v1.40: priority interrupt. High-priority messages
     * (the default — every normal message) break through whatever is
     * playing: the current output is paused with its exact position saved,
     * the priority plays immediately, then the interrupted output resumes
     * from where it left off and the queue continues. Low-priority
     * messages (segments of one long message) never interrupt — they queue
     * politely and play in order.
     *
     * Interrupted playback is a STACK: each interrupt pushes the current
     * state; each completion pops. Nick 2026-10-23 v1.42: a priority
     * arriving during another priority does NOT interrupt it — priority
     * items form their own FIFO queue (see waitingPriority); each waits
     * its turn and plays right after the current one finishes, before
     * any interrupted output resumes.
     */
    private static class InterruptedState {
        // MP3/Aria path (null audioFile => TTS was playing)
        File audioFile;
        int audioPositionMs;
        // On-device TTS/phone path (null ttsText => MP3 was playing)
        String ttsText;
        int ttsChar;
        // True if it was manually paused when interrupted — after the
        // priority, restore the paused state instead of autoplaying.
        boolean wasPaused;
        String serverId;
    }
    // LIFO stack of interrupted playbacks. Non-empty only during an
    // interrupt/resume flow; the completion handlers check it.
    private final java.util.ArrayDeque<InterruptedState> interruptStack =
            new java.util.ArrayDeque<>();
    // True while a priority item is being set up or played. Suppresses
    // normal queue pumps in the completion handlers.
    private boolean priorityMode = false;
    /**
     * Nick 2026-10-23 v1.42: FIFO queue of high-priority items that arrived
     * while another priority item was playing. Priority items never
     * interrupt each other — each newcomer waits its turn and plays right
     * after the current priority item finishes, ahead of everything
     * non-priority. Main thread only (all writes/reads happen in posted
     * runnables and completion handlers).
     */
    private static class WaitingPriority {
        // MP3/Aria path (null audioFile => on-device TTS path)
        final File audioFile;
        final String text;
        final String serverId;
        WaitingPriority(File audioFile, String text, String serverId) {
            this.audioFile = audioFile;
            this.text = text;
            this.serverId = serverId;
        }
    }
    private final java.util.ArrayDeque<WaitingPriority> waitingPriority =
            new java.util.ArrayDeque<>();
    // Set while halting TTS for an interrupt so the async onTtsStopped
    // doesn't fire onTtsDone (which would pump the queues mid-interrupt).
    private boolean suppressNextTtsDone = false;
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
        // FOREGROUND FIRST (Nick 2026-10-23): Android 12+ kills the service
        // with ForegroundServiceDidNotStartInTimeException if startForeground()
        // doesn't land within seconds of startForegroundService(). Everything
        // else in onCreate must trail this call — nothing may ever be inserted
        // above it. (Escalation 2026-10-23: one such crash on Operator 2's
        // phone under v1.37; the old order was fast too, but belt and
        // suspenders — this ordering is the platform's stated requirement.)
        notificationManager = getSystemService(NotificationManager.class);
        createChannel();
        // Nick 2026-10-23 v1.41: start as MEDIA_PLAYBACK only (explicit type).
        // mediaPlayback needs no runtime permission, so the service can
        // always start — even with RECORD_AUDIO denied or never granted.
        // The microphone type is claimed ONLY while a listening session is
        // live (see upgradeToMicrophone/downgradeToMediaPlayback).
        startForegroundAs("Selena Voice ready",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        // Nick 2026-10-23 v1.36: global crash reporter (service process).
        CrashReporter.install(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        recognitionListener = new ServiceRecognitionListener(this);
        // Nick 2026-10-19 v1.16: hold a partial wake lock for the service's
        // lifetime so the poll loop, Realtime socket, recognizer, and TTS
        // keep running with the screen locked. (MediaPlayer additionally
        // uses setWakeMode per-playback.)
        acquireWakeLock();
        initOnDeviceTts();
        // Nick 2026-10-21 v1.24: persistent 3-day message history.
        MessageHistory.init(this);
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
            } else if ("PAUSE_PLAYBACK".equals(intent.getAction())) {
                // Nick 2026-10-21: pause button — halts the current audio,
                // keeps the position so resume picks up where it left off.
                // v1.28: log receipt — a tap must never vanish silently.
                // v1.28: TOGGLE — if already paused, the tap resumes. The
                // activity's label can go stale (missed broadcast); the
                // service is the source of truth.
                log("intent: PAUSE_PLAYBACK received");
                if (ttsPaused || playbackPaused) resumePlayback();
                else pausePlayback();
            } else if ("RESUME_PLAYBACK".equals(intent.getAction())) {
                // Nick 2026-10-21: resume from the paused position.
                // v1.28: alias for the toggle above.
                log("intent: RESUME_PLAYBACK received");
                if (ttsPaused || playbackPaused) resumePlayback();
                else pausePlayback();
            } else if ("SKIP_TO_NEWEST".equals(intent.getAction())) {
                // Nick 2026-10-22 v1.33: skip the rest of the current
                // (possibly paused) message and play the newest queued
                // message immediately.
                log("intent: SKIP_TO_NEWEST received");
                skipToNewest();
            } else if ("CLEAR_UNLOCKED".equals(intent.getAction())) {
                // Nick 2026-10-22 v1.34: drop unlocked messages from the
                // service playback queues (locked ones keep playing/queued).
                log("intent: CLEAR_UNLOCKED received");
                clearUnlockedQueues();
            } else if (ACTION_REQUEST_STATUS.equals(intent.getAction())) {
                // Nick 2026-10-22 v1.25: activity re-opened — send it the
                // current state instead of leaving stale layout defaults.
                broadcastPollStatus(lastPollStatus);
                broadcastPlaybackState();
            } else if (ACTION_STOP_ALL.equals(intent.getAction())) {
                // Nick 2026-10-22 v1.35: hard stop — kill whatever is
                // playing/speaking right now and drop unlocked queued
                // items. His tap always wins.
                log("intent: STOP_ALL received");
                stopAllPlayback();
            } else if (ACTION_REPLAY.equals(intent.getAction())) {
                // Nick 2026-10-21 v1.24: replay a cached message MP3.
                // v1.28: toast when the file is gone instead of failing
                // silently.
                // v1.35: INTERRUPT — kill current playback first, then play
                // the selected message immediately (front of queue), instead
                // of lining it up behind whatever is playing.
                String path = intent.getStringExtra(EXTRA_FILE_PATH);
                if (path != null) {
                    java.io.File f = new java.io.File(path);
                    if (f.exists()) {
                        log("replay: " + f.getName());
                        handler.post(() -> {
                            stopCurrentPlayback();
                            synchronized (audioQueue) {
                                ((java.util.LinkedList<java.io.File>) audioQueue)
                                        .addFirst(f);
                            }
                            pumpAudioQueue();
                        });
                    } else {
                        log("replay: file gone: " + path);
                        toast("Replay audio expired");
                    }
                }
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

    // ------------------------------------------------------------ foreground service type mgmt
    // Nick 2026-10-23 v1.41 (mic-permission crash fix): the manifest declares
    // microphone|mediaPlayback, but the 2-arg startForeground() implies ALL
    // declared types — on Android 14 (targetSdk 34) a foreground service
    // started with the microphone type dies without RECORD_AUDIO granted.
    // So: start (and live) as MEDIA_PLAYBACK, which needs no permission.
    // The microphone type is claimed only while actually listening, right
    // before SpeechRecognizer.startListening(), and dropped the moment the
    // last listening session stops. A missing/denied mic can never again
    // kill the service at startup.

    private void startForegroundAs(String text, int type) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void upgradeToMicrophone() {
        startForegroundAs("🎤 Listening…",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private void downgradeToMediaPlayback() {
        startForegroundAs("Selena Voice ready",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Listening was refused for lack of RECORD_AUDIO: tell the user and
    // ask the activity to surface the permission prompt (system dialog or
    // Settings). Never start the recognizer, never touch the mic type.
    private void micDeniedFeedback() {
        Log.w(TAG, "listening refused: RECORD_AUDIO not granted");
        try {
            handler.post(() -> Toast.makeText(getApplicationContext(),
                    "Mic permission needed — grant it in Settings > Apps > Selena Voice",
                    Toast.LENGTH_LONG).show());
        } catch (Exception ignored) {}
        sendAppBroadcast(new Intent(ACTION_REQUEST_MIC_PERMISSION));
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
            // Nick 2026-10-22 v1.34: carry the server row ID so
            // MessageHistory can link the local entry for delete-unlocked.
            m.put("serverId", record.optString("id", ""));
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
            // Nick 2026-10-23 v1.40: carry the priority for interrupt
            // handling (defaults to 'high' like the server).
            m.put("priority", record.optString("priority", "high"));
            long beforeTs = getLastTs();
            // v1.30: advance the cursor only past CONSUMED messages.
            // handleQueueMessage returns beforeTs unchanged when it skips
            // (e.g. invalid URL), so a fixed row is retried, not stranded.
            long newTs = handleQueueMessage(m, beforeTs);
            if (newTs > beforeTs) {
                setLastTs(newTs + 1);
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
                // Nick 2026-10-22 v1.28: keep replay-cached MP3s — only
                // drop files no message card references.
                if (!MessageHistory.isAudioReferenced(f.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
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
        // Nick 2026-10-22 v1.34: server row ID for delete-unlocked.
        // Realtime path carries it as "serverId"; poll API returns "id".
        String serverId = m.optString("serverId", "");
        if (serverId.isEmpty()) serverId = m.optString("id", "");
        if (serverId.isEmpty()) serverId = null;
        final String sid = serverId;
        // Nick 2026-10-18 v1.12: explicit JSON-null check. The server
        // sends "audioUrl": null for text-only speed-mode replies;
        // optString on a JSON null returns the literal string "null"
        // (not ""), which then fails the isEmpty() check and sends us
        // into a doomed download of URL "null". isNull() handles it.
        String audioUrl = m.isNull("audioUrl") ? "" : m.optString("audioUrl", "");
        log("audio: audioUrl='" + audioUrl + "'");
        // Nick 2026-10-22 v1.30: the cursor only advances past messages we
        // actually consume. A message skipped for an invalid/unplayable URL
        // is NOT consumed — the old unconditional advance stranded it when
        // the URL was fixed later (rhinoceros test 08:36: cursor jumped past
        // a $AUDIOURL placeholder row, the fixed row never played). An
        // unconsumed message is refetched until it's playable.
        boolean consumed = false;
        // Nick 2026-10-18 v1.13: two-phase server delivery dedupe.
        // The server queues TEXT immediately, then queues the SAME
        // text with an MP3 audioUrl seconds later when TTS finishes.
        // Without this, Phone mode would speak the reply twice and
        // Aria mode would hold-then-play it twice. Texts are added
        // to spokenTexts only when actually spoken/played — Aria
        // mode holding a text-only message must NOT consume the
        // entry the MP3 version still needs.
        if (text.isEmpty()) {
            if (ts > maxTs) maxTs = ts;
            return maxTs;
        }
        synchronized (spokenTexts) {
            if (spokenTexts.contains(text)) {
                log("dedupe: skipping duplicate text");
                if (ts > maxTs) maxTs = ts;
                return maxTs;
            }
            // Nick 2026-10-23 v1.40: priority interrupt. High-priority
            // (the default — every normal message) breaks through
            // non-priority playback, including a manual pause and the
            // no-auto-readout toggle (an urgent message that never plays
            // is a contradiction). Nick 2026-10-23 v1.42: it does NOT
            // break through another priority item — those queue FIFO
            // behind each other (see onPriorityArrivalMp3/Tts).
            // Low-priority (segments of one long message) falls through
            // to the normal polite queueing below.
            String priority = m.optString("priority", "high");
            if (!"low".equals(priority)) {
                markSpokenLocked(text);
                if (isPhoneVoiceMode()) {
                    final String speakText = text;
                    handler.post(() -> onPriorityArrivalTts(speakText));
                } else if (!audioUrl.isEmpty() && audioUrl.startsWith("http")) {
                    // Nick 2026-10-23 v1.43: backward-compat MP3 path. The
                    // server still renders MP3s during transition; a row
                    // that arrives WITH an mp3 whose text was never spoken
                    // (phase 1 missed) plays the MP3. The normal case —
                    // phase 1 synthesized, phase 2 deduped via spokenTexts
                    // above — never reaches here.
                    try {
                        final File pf = downloadToCache(audioUrl);
                        final String ptext = text;
                        final String psid = sid;
                        handler.post(() -> onPriorityArrivalMp3(pf, ptext, psid));
                    } catch (Exception e) {
                        log("priority: MP3 download failed: "
                                + e.getMessage() + " — TTS fallback");
                        final String speakText = text;
                        handler.post(() -> onPriorityArrivalTts(speakText));
                    }
                } else {
                    // Nick 2026-10-23 v1.43: in-app synthesis (Achernar since v1.45; Callirrhoe v1.44).
                    // Text rows synthesize HERE via the Google Cloud TTS API
                    // on a worker thread, then feed the unchanged v1.40/v1.42
                    // priority machinery. Synthesis failure (after one
                    // retry) falls back to on-device TTS — never silent.
                    final String stext = text;
                    final String ssid = sid;
                    new Thread(() -> {
                        try {
                            final File sf = synthesizeToCache(stext);
                            handler.post(() -> onPriorityArrivalMp3(sf, stext, ssid));
                        } catch (Exception e) {
                            log("priority: synthesis failed, on-device TTS "
                                    + "fallback: " + e.getMessage());
                            handler.post(() -> onPriorityArrivalTts(stext));
                        }
                    }).start();
                }
                if (ts > maxTs) maxTs = ts;
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
                MessageHistory.add(text, null, sid);
                log("readout skipped (no-auto-readout on)");
                if (ts > maxTs) maxTs = ts;
                return maxTs;
            }
            if (isPhoneVoiceMode()) {
                // SPEED MODE. Phone voice mode speaks the text
                // IMMEDIATELY with on-device TTS — no wait for a server
                // Aria MP3. Mark spoken FIRST so the second (MP3) phase
                // dedupes.
                markSpokenLocked(text);
                broadcastMessage(text);
                final MessageHistory.Entry histEntry = MessageHistory.add(text, null, sid);
                final String speakText = text;
                handler.post(() -> enqueueTts(speakText));
                // Nick 2026-10-22 v1.25: still fetch the server MP3 in the
                // background so the message card grows a replay button.
                if (!audioUrl.isEmpty() && audioUrl.startsWith("http")) {
                    final String dlUrl = audioUrl;
                    new Thread(() -> {
                        try {
                            File f = downloadToCache(dlUrl);
                            MessageHistory.setAudioPath(histEntry.id, f);
                            log("replay cached for phone-mode message");
                            broadcastMessage(speakText);
                        } catch (Exception e) {
                            log("replay cache download failed: " + e.getMessage());
                        }
                    }).start();
                }
                consumed = true;
            } else if (!audioUrl.isEmpty()) {
                // ARIA QUALITY MODE. Download and play the server's MP3.
                if (!audioUrl.startsWith("http")) {
                    log("audio: skipping invalid URL: " + audioUrl);
                    // v1.30: NOT consumed — the cursor holds so a fixed URL
                    // is retried instead of stranded.
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
                        // Nick 2026-10-21 v1.24: cache for replay.
                        MessageHistory.add(text, f, sid);
                        handler.post(() -> enqueueAudio(df));
                    } catch (Exception e) {
                        log("audio download failed: " + audioUrl + ": " + e);
                    }
                    consumed = true;
                }
            } else {
                // Nick 2026-10-23 v1.43: text-only row — synthesize IN THE
                // APP via Google Cloud TTS (Achernar) and enqueue for
                // polite playback. The phase-2 server MP3 dedupes via
                // spokenTexts. Synthesis failure falls back to the polite
                // on-device TTS queue — never silent, never doubled.
                markSpokenLocked(text);
                broadcastMessage(text);
                final MessageHistory.Entry histEntry =
                        MessageHistory.add(text, null, sid);
                final String stext = text;
                new Thread(() -> {
                    try {
                        final File sf = synthesizeToCache(stext);
                        MessageHistory.setAudioPath(histEntry.id, sf);
                        handler.post(() -> enqueueAudio(sf));
                    } catch (Exception e) {
                        log("synth: low-priority synthesis failed, "
                                + "on-device fallback: " + e.getMessage());
                        handler.post(() -> enqueueTts(stext));
                    }
                }).start();
                consumed = true;
            }
        }
        if (consumed && ts > maxTs) maxTs = ts;
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
        // Nick 2026-10-23 v1.46: polite interruption — hold while Nick
        // speaks in always-listening mode. Stays queued; released on his
        // pause via releasePoliteHold().
        if (politeHoldActive()) {
            log("polite: holding MP3 playback for Nick's pause");
            return;
        }
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
            // Nick 2026-10-22 v1.34: track for CLEAR_UNLOCKED.
            currentAudioPath = f.getAbsolutePath();
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
            // Nick 2026-10-23 v1.47: stop the mic BEFORE her voice
            // comes out, so it can't be transcribed as Nick's input.
            muteMicForPlayback();
            player.start();
            isPlayingAudio = true;
            playbackPaused = false;
            log("playback started (echo guard on)");
            currentAudioFile = f;
            broadcastPlaybackState();
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
            // Nick 2026-10-22 v1.28: DON'T delete MP3s a message card still
            // references for replay — deleting after every play is what
            // broke replay after the first listen (v1.24 cache). Unreferenced
            // one-shot files are still cleaned up; expired entries' MP3s go
            // via purgeExpired.
            String path = currentAudioFile.getAbsolutePath();
            if (MessageHistory.isAudioReferenced(path)) {
                log("playback: keeping replay-cached file");
            } else {
                //noinspection ResultOfMethodCallIgnored
                currentAudioFile.delete();
            }
            currentAudioFile = null;
        }
        // Nick 2026-10-23 v1.42: priority FIFO — a priority item that just
        // finished yields to waiting priority items first. Priority items
        // queue behind each other and never interrupt each other;
        // interrupted output only resumes once no priority items remain.
        if (priorityMode && !waitingPriority.isEmpty()) {
            playNextWaitingPriority();
            return;
        }
        // Nick 2026-10-23 v1.40: priority interrupt unwinding. A non-empty
        // stack means we're mid-flow — pop and resume the next interrupted
        // item instead of pumping normal queues. A priority that finished
        // with an empty stack falls through to the normal pumps.
        if (!interruptStack.isEmpty()) {
            log("priority: MP3 completion, unwinding interrupt stack ("
                    + interruptStack.size() + " left)");
            priorityMode = false;
            resumeNextInterrupted();
            return;
        }
        if (priorityMode) {
            priorityMode = false;
            log("priority: flow done, resuming normal queues");
        }
        pumpAudioQueue();
        pumpTtsQueue();
        // Nick 2026-10-23 v1.47: if the pumps didn't start new
        // playback, she's fully done — restart the mic if we muted it.
        if (!isPlayingAudio) unmuteMicAfterPlayback();
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

    /**
     * Nick 2026-10-23 v1.43: synthesize text IN THE APP via Google Cloud
     * Text-to-Speech (Achernar). Runs on a worker thread — never call on
     * the main thread. One retry, then throws; the caller falls back to
     * on-device TTS so the message is never dropped silently.
     */
    private File synthesizeToCache(String text) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return synthesizeOnce(text);
            } catch (Exception e) {
                last = e;
                log("synth: attempt " + (attempt + 1) + " failed: "
                        + e.getMessage());
            }
        }
        throw last;
    }

    /** Single Google Cloud TTS request. Runs on a worker thread. */
    private File synthesizeOnce(String text) throws Exception {
        long t0 = System.currentTimeMillis();
        HttpURLConnection conn = (HttpURLConnection) new URL(
                GOOGLE_TTS_URL + "?key=" + GOOGLE_TTS_API_KEY).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type",
                "application/json; charset=utf-8");
        conn.setDoOutput(true);
        JSONObject req = new JSONObject();
        req.put("input", new JSONObject().put("text", text));
        req.put("voice", new JSONObject()
                .put("languageCode", "en-US")
                .put("name", GOOGLE_TTS_VOICE));
        JSONObject audioConfig = new JSONObject()
                .put("audioEncoding", "MP3")
                // Nick 2026-10-23: a hair slower than Google default 1.0.
                .put("speakingRate", 0.95);
        req.put("audioConfig", audioConfig);
        byte[] body = req.toString().getBytes("UTF-8");
        OutputStream os = null;
        try {
            os = conn.getOutputStream();
            os.write(body);
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) {}
        }
        int code = conn.getResponseCode();
        InputStream in = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            if (br != null) try { br.close(); } catch (Exception ignored) {}
            conn.disconnect();
        }
        if (code < 200 || code >= 300) {
            String detail = sb.length() > 200
                    ? sb.substring(0, 200) : sb.toString();
            throw new Exception("TTS API HTTP " + code + ": " + detail);
        }
        String audioB64 = new JSONObject(sb.toString())
                .optString("audioContent", "");
        if (audioB64.isEmpty()) {
            throw new Exception("TTS API returned no audioContent");
        }
        byte[] audio = Base64.decode(audioB64, Base64.DEFAULT);
        File out = File.createTempFile("selena-synth-", ".mp3", getCacheDir());
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            fos.write(audio);
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
        log("synth: " + audio.length + " bytes in "
                + (System.currentTimeMillis() - t0) + "ms ("
                + GOOGLE_TTS_VOICE + ")");
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

    @Override
    public void onTtsRangeStart(int startChar) {
        // Binder thread — just record the offset; the main thread reads it
        // on pause. Snap back to the nearest word start so resume never
        // begins mid-word.
        final String text = ttsCurrentText;
        final int at = startChar;
        handler.post(() -> {
            if (text != null && text == ttsCurrentText) {
                ttsResumeAtChar = snapToWordStart(text, at);
            }
        });
    }

    @Override
    public void onTtsStopped() {
        // Binder thread — marshal to main thread.
        handler.post(() -> {
            if (suppressNextTtsDone) {
                // Nick 2026-10-23 v1.40: TTS was halted for a priority
                // interrupt — swallow the completion so the normal queues
                // don't pump mid-interrupt.
                suppressNextTtsDone = false;
                log("priority: suppressed halted-TTS completion");
                return;
            }
            if (!ttsPauseRequested) {
                // Not our pause (shouldn't happen) — treat as finished.
                onTtsDone();
                return;
            }
            ttsPauseRequested = false;
            ttsSpeaking = false;
            // Mic can listen again while paused; the boost/focus stays so
            // resume is instant.
            setPlayingAudio(false);
            log("tts: paused at char " + ttsResumeAtChar + " of "
                    + (ttsCurrentText != null ? ttsCurrentText.length() : 0));
            broadcastPlaybackState();
        });
    }

    /** Snap a character offset back to the start of its word. */
    private int snapToWordStart(String text, int at) {
        if (text == null) return 0;
        int i = Math.max(0, Math.min(at, text.length()));
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
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
        // Nick 2026-10-22 v1.27: ttsPaused blocks the pump — a paused
        // message holds its place until resume.
        if (ttsSpeaking || playing || ttsPaused) return;
        // Nick 2026-10-23 v1.46: polite interruption — hold while Nick
        // speaks in always-listening mode. Stays queued; released on his
        // pause via releasePoliteHold().
        if (politeHoldActive()) {
            log("polite: holding TTS playback for Nick's pause");
            return;
        }
        String text;
        synchronized (ttsQueue) {
            text = ttsQueue.poll();
        }
        if (text == null) return;
        ttsSpeaking = true;
        speakOnDevice(text, false);
    }

    /** Runs on main thread: speak text with Android's built-in TTS. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    private void speakOnDevice(String text, boolean isResume) {
        if (tts == null || !ttsReady) {
            log("tts: on-device not ready, dropping text");
            onTtsDone();
            return;
        }
        if (!isResume) {
            // Fresh utterance — this is the text pause/resume tracks.
            ttsCurrentText = text;
            ttsResumeAtChar = 0;
            ttsPaused = false;
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
        // Nick 2026-10-23 v1.47: stop the mic BEFORE she speaks, so
        // her voice can't be transcribed as Nick's input.
        muteMicForPlayback();
        isPlayingAudio = true;
        log("tts: echo guard on");
        broadcastPlaybackState();
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
        ttsPaused = false;
        ttsCurrentText = null;
        ttsResumeAtChar = 0;
        isPlayingAudio = false;
        // Put the speakerphone back the way we found it, same as MP3.
        // No-op when Bluetooth handled the routing.
        restoreAudioRouting();
        log("tts: echo guard off");
        broadcastPlaybackState();
        // Nick 2026-10-23 v1.42: priority FIFO — a priority item that just
        // finished yields to waiting priority items first (same rule as
        // onPlaybackDoneNow).
        if (priorityMode && !waitingPriority.isEmpty()) {
            playNextWaitingPriority();
            return;
        }
        // Nick 2026-10-23 v1.40: priority interrupt unwinding (same as
        // onPlaybackDoneNow).
        if (!interruptStack.isEmpty()) {
            log("priority: TTS completion, unwinding interrupt stack ("
                    + interruptStack.size() + " left)");
            priorityMode = false;
            resumeNextInterrupted();
            return;
        }
        if (priorityMode) {
            priorityMode = false;
            log("priority: flow done, resuming normal queues");
        }
        pumpTtsQueue();
        pumpAudioQueue();
        // Nick 2026-10-23 v1.47: if the pumps didn't start new
        // playback, she's fully done — restart the mic if we muted it.
        if (!isPlayingAudio) unmuteMicAfterPlayback();
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
        currentAudioPath = null;
        playbackPaused = false;
        broadcastPlaybackState();
    }

    // Nick 2026-10-21: pause/resume for the playback pause button.
    // Pause keeps the MediaPlayer alive at the current position;
    // resume continues from there. The queue is untouched.
    private boolean playbackPaused = false;

    private void pausePlayback() {
        // Nick 2026-10-22 v1.28: UNCONDITIONAL pause — works in phone
        // (on-device TTS) mode, Aria (MP3) mode, whatever is playing.
        // Every tap logs what it found and toasts the outcome, so a tap
        // can never silently die again.
        boolean mp3Playing = safeIsPlaying();
        log("pause: requested mp3Playing=" + mp3Playing
                + " ttsSpeaking=" + ttsSpeaking
                + " ttsPaused=" + ttsPaused
                + " mp3Paused=" + playbackPaused);
        boolean didPause = false;
        // MP3 path (Aria/quality mode, replays).
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                    playbackPaused = true;
                    didPause = true;
                    log("playback: paused at " + safePosition() + "ms");
                }
            } catch (Exception e) {
                log("playback: pause failed: " + e.getMessage());
            }
        }
        // Phone (on-device TTS) path — TTS has no pause API; stop() it and
        // resume from the last word boundary tracked via onRangeStart.
        // onTtsStopped confirms the stop; the watchdog covers engines that
        // never call onStop.
        if (ttsSpeaking && tts != null && !ttsPaused) {
            ttsPauseRequested = true;
            try {
                tts.stop();
                log("tts: pause requested");
                didPause = true;
            } catch (Exception e) {
                ttsPauseRequested = false;
                log("tts: pause failed: " + e.getMessage());
            }
            handler.postDelayed(() -> {
                if (ttsPauseRequested) {
                    ttsPauseRequested = false;
                    ttsSpeaking = false;
                    ttsPaused = true;
                    setPlayingAudio(false);
                    log("tts: paused (watchdog, no onStop) at char "
                            + ttsResumeAtChar);
                    broadcastPlaybackState();
                }
            }, 1500);
        }
        if (didPause) {
            toast("Paused");
            // Nick 2026-10-23 v1.47: paused = she's not speaking —
            // the mic should be live so Nick can talk.
            unmuteMicAfterPlayback();
        } else {
            log("pause: nothing was playing");
            toast("Nothing playing to pause");
        }
        broadcastPlaybackState();
    }

    private void resumePlayback() {
        log("resume: requested ttsPaused=" + ttsPaused
                + " mp3Paused=" + playbackPaused);
        boolean didResume = false;
        // MP3 path (Aria/quality mode, replays).
        if (player != null && playbackPaused) {
            try {
                player.start();
                playbackPaused = false;
                didResume = true;
                log("playback: resumed at " + safePosition() + "ms");
            } catch (Exception e) {
                log("playback: resume failed: " + e.getMessage());
            }
        }
        // Phone (on-device TTS) path — re-speak from the last word
        // boundary before the pause.
        if (ttsPaused && ttsCurrentText != null) {
            ttsPaused = false;
            String rest = ttsCurrentText.substring(
                    Math.min(ttsResumeAtChar, ttsCurrentText.length()));
            if (rest.trim().isEmpty()) {
                // Paused at the very end — nothing left to say.
                ttsCurrentText = null;
                onTtsDone();
            } else {
                ttsSpeaking = true;
                log("tts: resuming from char " + ttsResumeAtChar);
                speakOnDevice(rest, true);
                didResume = true;
            }
        }
        if (didResume) {
            toast("Resumed");
            // Nick 2026-10-23 v1.47: resuming = she's speaking again —
            // stop the mic so her voice isn't transcribed. (TTS resume
            // goes through speakOnDevice which mutes; this covers the
            // MP3 player.start() path.)
            muteMicForPlayback();
        } else {
            log("resume: nothing was paused");
            toast("Nothing paused");
        }
        broadcastPlaybackState();
    }

    /**
     * Nick 2026-10-22 v1.35: kill whatever is currently playing or
     * speaking (MP3 player or on-device TTS), clear the paused state.
     * The queues are left alone — callers decide what to do with them.
     * Runs on the main thread (called from onStartCommand).
     */
    private void stopCurrentPlayback() {
        log("stop: halting current playback");
        // Nick 2026-10-23 v1.40: stop cancels any priority flow too.
        priorityMode = false;
        // Nick 2026-10-23 v1.42: stop also drops queued priority items.
        waitingPriority.clear();
        suppressNextTtsDone = false;
        interruptStack.clear();
        // Disarm the TTS pause watchdog first — with ttsPauseRequested
        // false, the engine's onStop routes to onTtsDone(), which pumps
        // the queues: a clean no-op when we cleared them, or continues
        // normally when the caller re-queued.
        ttsPauseRequested = false;
        try {
            if (tts != null) tts.stop();
        } catch (Exception e) {
            log("stop: tts.stop failed: " + e.getMessage());
        }
        ttsSpeaking = false;
        ttsPaused = false;
        ttsCurrentText = null;
        ttsResumeAtChar = 0;
        // Release the MP3 player (also clears playbackPaused and
        // broadcasts state).
        releasePlayer();
        playing = false;
        isPlayingAudio = false;
        setPlayingAudio(false);
        // Nick 2026-10-23 v1.47: stopped = she's not speaking —
        // restart the mic if we muted it.
        unmuteMicAfterPlayback();
        broadcastPlaybackState();
    }

    // ------------------------------------------------------------ v1.40 priority interrupt

    /**
     * Nick 2026-10-23 v1.40: save the current playback state onto the
     * interrupt stack and halt output quietly (no toast, no queue pumps).
     * Runs on the main thread. After this returns, the caller plays the
     * priority item immediately.
     */
    private void interruptCurrentPlayback() {
        InterruptedState st = new InterruptedState();
        st.wasPaused = playbackPaused || ttsPaused;
        boolean something = false;
        // MP3/Aria path: save file + position.
        if (player != null && (safeIsPlaying() || playbackPaused)
                && currentAudioFile != null) {
            st.audioFile = currentAudioFile;
            try {
                st.audioPositionMs = player.getCurrentPosition();
            } catch (Exception e) {
                st.audioPositionMs = 0;
            }
            something = true;
            log("priority: interrupting MP3 at " + st.audioPositionMs + "ms"
                    + (st.wasPaused ? " (was paused)" : ""));
        }
        // On-device TTS/phone path: save text + char offset.
        if ((ttsSpeaking || ttsPaused) && ttsCurrentText != null) {
            st.ttsText = ttsCurrentText;
            st.ttsChar = ttsResumeAtChar;
            something = true;
            log("priority: interrupting TTS at char " + st.ttsChar
                    + (st.wasPaused ? " (was paused)" : ""));
        }
        if (something) {
            interruptStack.push(st);
        } else {
            log("priority: nothing playing to interrupt");
        }
        // Halt output without firing completion pumps. For TTS, suppress
        // the async onTtsStopped -> onTtsDone chain; for MP3, a synchronous
        // release fires no completion callback.
        if (ttsSpeaking) {
            suppressNextTtsDone = true;
            try {
                if (tts != null) tts.stop();
            } catch (Exception e) {
                log("priority: tts.stop failed: " + e.getMessage());
            }
            ttsSpeaking = false;
        }
        ttsPaused = false;
        ttsCurrentText = null;
        ttsResumeAtChar = 0;
        ttsPauseRequested = false;
        releasePlayer();
        playing = false;
        isPlayingAudio = false;
        setPlayingAudio(false);
        broadcastPlaybackState();
    }

    /**
     * Nick 2026-10-23 v1.40: play a high-priority MP3 item immediately,
     * interrupting whatever is playing. Runs on the main thread; the file
     * must already be downloaded.
     */
    private void playPriorityMp3(final File f, final String text,
            final String serverId) {
        log("priority: playing high-priority MP3"
                + (serverId != null ? " id=" + serverId : ""));
        priorityMode = true;
        interruptCurrentPlayback();
        markSpokenLocked(text);
        // MessageHistory entry so the card exists for replay.
        MessageHistory.add(text, f, serverId);
        broadcastMessage(text);
        toast("Priority message");
        playFileNow(f);
    }

    /**
     * Nick 2026-10-23 v1.40: speak a high-priority text immediately via
     * on-device TTS, interrupting whatever is playing. Runs on main thread.
     */
    private void playPriorityTts(final String text) {
        log("priority: speaking high-priority text via on-device TTS");
        priorityMode = true;
        interruptCurrentPlayback();
        markSpokenLocked(text);
        broadcastMessage(text);
        MessageHistory.add(text, null, null);
        toast("Priority message");
        ttsSpeaking = true;
        speakOnDevice(text, false);
    }

    /**
     * Nick 2026-10-23 v1.42: priority FIFO decision point for MP3 items.
     * Runs on the main thread. If a priority item is already playing, the
     * newcomer does NOT interrupt it — it queues behind it and plays
     * right after it finishes.
     */
    private void onPriorityArrivalMp3(final File f, final String text,
            final String serverId) {
        if (priorityMode) {
            waitingPriority.offer(new WaitingPriority(f, text, serverId));
            log("priority: queued behind playing priority ("
                    + waitingPriority.size() + " waiting)"
                    + (serverId != null ? " id=" + serverId : ""));
            toast("Priority message queued");
            return;
        }
        // Nick 2026-10-23 v1.46: polite interruption — park behind the
        // gate instead of breaking in over Nick's voice. Released on his
        // pause via releasePoliteHold().
        if (politeHoldActive()) {
            waitingPriority.offer(new WaitingPriority(f, text, serverId));
            log("priority: held for Nick's pause ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        playPriorityMp3(f, text, serverId);
    }

    /**
     * Nick 2026-10-23 v1.42: priority FIFO decision point for on-device
     * TTS items. Runs on the main thread. Same rule as
     * {@link #onPriorityArrivalMp3} — queue behind a playing priority
     * item, never interrupt it.
     */
    private void onPriorityArrivalTts(final String text) {
        if (priorityMode) {
            waitingPriority.offer(new WaitingPriority(null, text, null));
            log("priority: queued behind playing priority ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        // Nick 2026-10-23 v1.46: polite interruption — park behind the
        // gate instead of breaking in over Nick's voice. Released on his
        // pause via releasePoliteHold().
        if (politeHoldActive()) {
            waitingPriority.offer(new WaitingPriority(null, text, null));
            log("priority: held for Nick's pause ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        playPriorityTts(text);
    }

    /**
     * Nick 2026-10-23 v1.42: play the head of the waiting-priority FIFO.
     * Called from the completion handlers when a priority item finishes
     * and more are waiting. Runs on the main thread; the previous
     * priority item has already released the player, so there is nothing
     * to interrupt — the flow simply continues with priorityMode held.
     */
    private void playNextWaitingPriority() {
        // Nick 2026-10-23 v1.46: polite interruption — never start a
        // queued priority item over Nick's voice. It stays queued; the
        // hold releases on his pause.
        if (politeHoldActive()) {
            log("priority: holding queued item for Nick's pause");
            return;
        }
        WaitingPriority wp = waitingPriority.poll();
        if (wp == null) return;
        log("priority: playing queued priority item ("
                + waitingPriority.size() + " still waiting)"
                + (wp.serverId != null ? " id=" + wp.serverId : ""));
        if (wp.audioFile != null) {
            playPriorityMp3(wp.audioFile, wp.text, wp.serverId);
        } else {
            playPriorityTts(wp.text);
        }
    }

    /**
     * Nick 2026-10-23 v1.40: called from onPlaybackDoneNow/onTtsDone when
     * priorityMode is true. Returns true if the priority flow handled the
     * completion (caller must NOT pump normal queues).
     */
    private boolean onPriorityPlaybackDone() {
        if (!priorityMode) return false;
        priorityMode = false;
        if (!interruptStack.isEmpty()) {
            resumeNextInterrupted();
        } else {
            log("priority: flow done, nothing was interrupted");
            pumpAudioQueue();
            pumpTtsQueue();
        }
        return true;
    }

    /**
     * Nick 2026-10-23 v1.40: pop the next interrupted state and resume it.
     * If it was manually paused, restore the paused state (no autoplay —
     * Nick: "let it resume from where I paused", i.e. HE resumes).
     * Runs on the main thread.
     */
    private void resumeNextInterrupted() {
        InterruptedState st = interruptStack.poll();
        if (st == null) {
            pumpAudioQueue();
            pumpTtsQueue();
            return;
        }
        if (st.wasPaused) {
            restorePausedState(st);
            toast("Priority done — paused where you left off");
            log("priority: restored paused state"
                    + (st.serverId != null ? " id=" + st.serverId : ""));
            broadcastPlaybackState();
            // Stack may hold more, but the user took manual control —
            // the chain continues when THEY resume (completion handlers
            // check the non-empty stack).
            return;
        }
        toast("Resuming where you left off");
        if (st.audioFile != null) {
            resumeInterruptedMp3(st);
        } else if (st.ttsText != null) {
            resumeInterruptedTts(st);
        } else {
            // Nothing to resume (shouldn't happen) — continue chain.
            resumeNextInterrupted();
        }
    }

    /** Rebuild the MP3 player for the interrupted file at saved position. */
    private void resumeInterruptedMp3(InterruptedState st) {
        File f = st.audioFile;
        if (f == null || !f.exists()) {
            log("priority: interrupted MP3 file gone, skipping resume");
            resumeNextInterrupted();
            return;
        }
        log("priority: resuming MP3 at " + st.audioPositionMs + "ms");
        // Same setup as playFileNow, then seek before start.
        playFileNow(f);
        if (player != null && st.audioPositionMs > 0) {
            try {
                player.seekTo(st.audioPositionMs);
            } catch (Exception e) {
                log("priority: seek failed: " + e.getMessage());
            }
        }
    }

    /** Re-speak the interrupted TTS text from the saved char offset. */
    private void resumeInterruptedTts(InterruptedState st) {
        String text = st.ttsText;
        if (text == null || text.isEmpty()) {
            resumeNextInterrupted();
            return;
        }
        int at = Math.min(st.ttsChar, text.length());
        String rest = text.substring(at);
        if (rest.trim().isEmpty()) {
            log("priority: interrupted TTS was at end, skipping resume");
            resumeNextInterrupted();
            return;
        }
        log("priority: resuming TTS from char " + at);
        ttsCurrentText = text;
        ttsResumeAtChar = at;
        ttsSpeaking = true;
        speakOnDevice(rest, true);
    }

    /**
     * Restore the exact paused state after a priority that broke through
     * a manual pause. MP3: rebuild paused at position. TTS: restore
     * paused-at-char. No autoplay.
     */
    private void restorePausedState(InterruptedState st) {
        if (st.audioFile != null && st.audioFile.exists()) {
            // Rebuild the player paused at the saved position.
            playFileNow(st.audioFile);
            if (player != null) {
                try {
                    if (st.audioPositionMs > 0) {
                        player.seekTo(st.audioPositionMs);
                    }
                    player.pause();
                    playbackPaused = true;
                } catch (Exception e) {
                    log("priority: restore-paused MP3 failed: " + e.getMessage());
                }
            }
        } else if (st.ttsText != null) {
            ttsCurrentText = st.ttsText;
            ttsResumeAtChar = Math.min(st.ttsChar, st.ttsText.length());
            ttsPaused = true;
        }
    }

    /**
     * Nick 2026-10-22 v1.35: STOP_ALL — stopCurrentPlayback plus drop
     * every unlocked item waiting in the queues (locked ones keep their
     * place, per the v1.34 rule). Runs on the main thread.
     */
    private void stopAllPlayback() {
        stopCurrentPlayback();
        synchronized (audioQueue) {
            File f;
            while ((f = audioQueue.poll()) != null) {
                if (!MessageHistory.isAudioReferenced(f.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        broadcastPlaybackState();
        toast("⏹ Stopped");
        log("stop: queues cleared (unlocked only)");
    }

    /**
     * Nick 2026-10-22 v1.33: SKIP_TO_NEWEST — abandon the current
     * (possibly paused) message and play the newest queued message.
     * Runs on the main thread (called from onStartCommand).
     */
    private void skipToNewest() {
        log("skip: abandoning current playback, clearing queues");
        // Nick 2026-10-23 v1.40: skip takes manual control — cancel any
        // priority interrupt flow and drop the saved resume stack.
        priorityMode = false;
        suppressNextTtsDone = false;
        interruptStack.clear();
        // Disarm the TTS pause watchdog first — with ttsPauseRequested
        // false, the engine's onStop routes to onTtsDone(), which pumps
        // the (now empty) queues: a clean no-op.
        ttsPauseRequested = false;
        try {
            if (tts != null) tts.stop();
        } catch (Exception e) {
            log("skip: tts.stop failed: " + e.getMessage());
        }
        ttsSpeaking = false;
        ttsPaused = false;
        ttsCurrentText = null;
        ttsResumeAtChar = 0;
        // Release the MP3 player (also clears playbackPaused and
        // broadcasts state).
        releasePlayer();
        playing = false;
        isPlayingAudio = false;
        setPlayingAudio(false);
        // Drop everything queued-but-unplayed (v1.28 rule: keep
        // replay-cached MP3s, delete the rest).
        synchronized (audioQueue) {
            File f;
            while ((f = audioQueue.poll()) != null) {
                if (!MessageHistory.isAudioReferenced(f.getAbsolutePath())) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        }
        synchronized (ttsQueue) {
            ttsQueue.clear();
        }
        broadcastPlaybackState();
        toast("Skipping to newest…");
        // Network on a worker thread (Android forbids it on main).
        new Thread(() -> doSkipToNewestWork()).start();
    }

    /**
     * Nick 2026-10-22 v1.34: CLEAR_UNLOCKED — drop unlocked messages from
     * the service playback queues. Locked messages keep their place.
     * Currently-playing item is stopped only if unlocked. Runs on the
     * main thread (called from onStartCommand).
     */
    private void clearUnlockedQueues() {
        int droppedAudio = 0;
        int droppedTts = 0;
        boolean stoppedCurrent = false;

        // Currently playing MP3 — stop only if unlocked.
        if (playing && currentAudioPath != null
                && !MessageHistory.isAudioLocked(currentAudioPath)) {
            log("clear-unlocked: stopping unlocked playing audio");
            ttsPauseRequested = false;
            releasePlayer();
            playing = false;
            isPlayingAudio = false;
            setPlayingAudio(false);
            stoppedCurrent = true;
        }
        // Currently speaking TTS — stop only if unlocked.
        if (ttsSpeaking && ttsCurrentText != null
                && !MessageHistory.isTextLocked(ttsCurrentText)) {
            log("clear-unlocked: stopping unlocked TTS");
            ttsPauseRequested = false;
            try {
                if (tts != null) tts.stop();
            } catch (Exception e) {
                log("clear-unlocked: tts.stop failed: " + e.getMessage());
            }
            ttsSpeaking = false;
            ttsPaused = false;
            ttsCurrentText = null;
            ttsResumeAtChar = 0;
            stoppedCurrent = true;
        }

        // Queued-but-unplayed audio: keep locked, drop the rest.
        // Locked files stay (their history entries are locked); unlocked
        // files are deleted unless another entry still references them
        // (v1.28 replay-cache rule).
        synchronized (audioQueue) {
            java.util.Queue<java.io.File> kept = new java.util.LinkedList<>();
            java.io.File f;
            while ((f = audioQueue.poll()) != null) {
                String path = f.getAbsolutePath();
                if (MessageHistory.isAudioLocked(path)) {
                    kept.add(f);
                } else {
                    droppedAudio++;
                    if (!MessageHistory.isAudioReferenced(path)) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            }
            audioQueue.addAll(kept);
        }
        // Queued-but-unspoken TTS texts: keep locked, drop the rest.
        synchronized (ttsQueue) {
            java.util.Queue<String> kept = new java.util.LinkedList<>();
            String t;
            while ((t = ttsQueue.poll()) != null) {
                if (MessageHistory.isTextLocked(t)) {
                    kept.add(t);
                } else {
                    droppedTts++;
                }
            }
            ttsQueue.addAll(kept);
        }

        broadcastPlaybackState();
        // Pump in case a locked item is now at the head.
        pumpAudioQueue();
        pumpTtsQueue();
        log("clear-unlocked: dropped audio=" + droppedAudio
                + " tts=" + droppedTts + " stoppedCurrent=" + stoppedCurrent);
    }

    /** Worker thread: fetch the queue, play the newest message. */
    private void doSkipToNewestWork() {
        HttpURLConnection conn = null;
        try {
            // since=0 returns the full queue; we take the newest by
            // created_at. (v1.15's fresh-install lesson doesn't apply —
            // we play exactly one message, never the history.)
            String urlStr = BASE_URL + "/api/selena-voice/queue?since=0";
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            log("skip: HTTP " + code);
            if (code != 200) {
                log("skip: failed, HTTP " + code);
                toast("Skip failed");
                return;
            }
            String body = readAll(conn.getInputStream());
            JSONArray arr;
            try {
                String trimmed = body.trim();
                if (trimmed.startsWith("{")) {
                    JSONObject obj = new JSONObject(trimmed);
                    arr = obj.optJSONArray("messages");
                    if (arr == null) arr = new JSONArray();
                } else {
                    arr = new JSONArray(trimmed);
                }
            } catch (Exception e) {
                log("skip: JSON parse failed: " + e);
                toast("Skip failed");
                return;
            }
            if (arr.length() == 0) {
                log("skip: queue empty");
                toast("No newer messages");
                return;
            }
            // Newest by created_at (v1.30: parseTimestamp handles the
            // server's formats; unparseable rows sort to the bottom).
            JSONObject newest = null;
            long newestTs = Long.MIN_VALUE;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                long ts = parseTimestamp(m);
                if (ts >= newestTs) {
                    newestTs = ts;
                    newest = m;
                }
            }
            if (newest == null) {
                toast("No newer messages");
                return;
            }
            String text = newest.optString("text", "");
            if (text.isEmpty()) {
                log("skip: newest message has no text");
                toast("No newer messages");
                return;
            }
            log("skip: newest=\"" + text.substring(0, Math.min(40, text.length())) + "…\"");
            // Bypass the spokenTexts dedupe — the newest message was
            // likely already consumed (it's why we're skipping to it);
            // without this, handleQueueMessage would drop it silently.
            synchronized (spokenTexts) {
                spokenTexts.remove(text);
            }
            long maxTs = handleQueueMessage(newest, getLastTs());
            // Advance the cursor past the newest so the next regular
            // poll doesn't refetch it (v1.18 rule: strict greater-than).
            long since = getLastTs();
            long newTs = maxTs > since ? maxTs + 1 : since;
            if (newestTs >= newTs) newTs = newestTs + 1;
            setLastTs(newTs);
            log("skip: playing newest, cursor -> " + newTs);
        } catch (Exception e) {
            log("skip: exception: " + e);
            toast("Skip failed");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** isPlaying() without the IllegalStateException landmine. */
    private boolean safeIsPlaying() {
        try {
            return player != null && player.isPlaying();
        } catch (Exception e) {
            return false;
        }
    }

    /** getCurrentPosition() without the IllegalStateException landmine. */
    private int safePosition() {
        try {
            return player != null ? player.getCurrentPosition() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** User-visible one-line feedback for pause/resume taps. */
    private void toast(String msg) {
        Intent i = new Intent(ACTION_TOAST);
        i.putExtra(EXTRA_TOAST, msg);
        sendAppBroadcast(i);
    }

    /**
     * Nick 2026-10-22 v1.31: explicit package on all broadcasts. Implicit
     * broadcasts from the service were not reaching the activity's
     * dynamically-registered receiver (confirmed by "Poll: not started"
     * stuck on screen and missing pause toasts/labels on v1.28-1.30).
     * Setting the package makes delivery explicit and robust.
     */
    private void sendAppBroadcast(Intent i) {
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void broadcastPlaybackState() {
        Intent i = new Intent(ACTION_PLAYBACK_STATE);
        boolean mp3Playing = safeIsPlaying();
        i.putExtra("playing", mp3Playing || ttsSpeaking);
        i.putExtra("paused", playbackPaused || ttsPaused);
        sendAppBroadcast(i);
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
        // Nick 2026-10-23 v1.41: never claim the mic (recognizer or FGS
        // type) without RECORD_AUDIO. Refuse loudly instead of crashing.
        if (!hasMicPermission()) {
            micDeniedFeedback();
            broadcastListeningState();
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("speech recognition not available");
            return;
        }
        try {
            // v1.41: claim the microphone FGS type BEFORE startListening().
            upgradeToMicrophone();
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
        // Nick 2026-10-23 v1.41: last listening session ended — drop the
        // microphone FGS type immediately; live on mediaPlayback only.
        downgradeToMediaPlayback();
        unmuteBeep();
        destroyRecognizer();
        updateNotification("⏸️ Paused");
        broadcastListeningState();
    }

    private void launchRecognizer() {
        // Nick 2026-10-23 v1.41: belt and suspenders — no permission, no
        // recognizer, ever. Refuse rather than risk a SecurityException from
        // any path that reaches startListening() unguarded.
        if (!hasMicPermission()) {
            Log.w(TAG, "launchRecognizer refused: RECORD_AUDIO not granted");
            recognizerActive = false;
            destroyRecognizer();
            downgradeToMediaPlayback();
            broadcastListeningState();
            return;
        }
        if (!recognizerActive || recognizer == null || destroyed) return;
        // Nick 2026-10-23 v1.47: never start listening while muted for
        // Selena playback — unmuteMicAfterPlayback() restarts when done.
        if (micMutedForPlayback) return;
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
        // Nick 2026-10-23 v1.47: never restart while muted for Selena
        // playback — the unmute path restarts when she's done.
        if (destroyed || !recognizerActive || micMutedForPlayback) return;
        handler.postDelayed(new RecognizerRestartTask(this), RESTART_DELAY_MS);
    }

    /**
     * Nick 2026-10-18 v1.3: for NO_MATCH/SPEECH_TIMEOUT (routine silence,
     * not a real error). Skips the unmuteBeep() — volumes stay zeroed from
     * the last mute, so there's no window where the beep can slip through.
     * launchRecognizer() will re-mute (no-op) before startListening().
     */
    public void scheduleQuietRestart() {
        // Nick 2026-10-23 v1.47: never restart while muted for Selena
        // playback — the unmute path restarts when she's done.
        if (destroyed || !recognizerActive || micMutedForPlayback) return;
        Log.d(TAG, "quiet restart scheduled (" + RESTART_DELAY_MS + "ms)");
        handler.postDelayed(new RecognizerRestartTask(this), RESTART_DELAY_MS);
    }

    /** Called by RecognizerRestartTask on main thread. */
    public void restartRecognizerNow() {
        if (destroyed || !recognizerActive || micMutedForPlayback) return;
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

    /**
     * Nick 2026-10-23 v1.47: true while the mic is muted for Selena
     * playback. Used by ServiceRecognitionListener to swallow the
     * cancel() error without scheduling a restart.
     */
    public boolean isMicMutedForPlayback() {
        return micMutedForPlayback;
    }

    /**
     * Nick 2026-10-23 v1.47: stop the recognizer entirely when Selena
     * starts speaking, so her voice can't be transcribed as Nick's
     * input. Uses cancel() (not stopListening()) so no partial result
     * fires. The onError(ERROR_CLIENT) this triggers is swallowed by
     * the micMutedForPlayback guards — no restart is scheduled.
     * Idempotent: safe to call when already muted or not listening.
     */
    private void muteMicForPlayback() {
        if (micMutedForPlayback) return;
        if (!recognizerActive || recognizer == null) return;
        micMutedForPlayback = true;
        log("mic: muted for Selena playback");
        try {
            recognizer.cancel();
        } catch (Exception e) {
            Log.w(TAG, "mic mute cancel failed", e);
        }
        broadcastListeningState();
    }

    /**
     * Nick 2026-10-23 v1.47: restart the recognizer after Selena
     * finishes speaking, if we muted it. Uses the existing clean
     * restart path (destroy + recreate) since cancel() can leave
     * the recognizer in an uncertain state. No-op if not muted or
     * the listening mode was turned off while she spoke.
     */
    private void unmuteMicAfterPlayback() {
        if (!micMutedForPlayback) return;
        micMutedForPlayback = false;
        if (destroyed || !recognizerActive) {
            log("mic: unmute skipped (not listening anymore)");
            broadcastListeningState();
            return;
        }
        log("mic: unmuted after Selena playback, restarting recognizer");
        restartRecognizerNow();
    }

    // Callbacks from ServiceRecognitionListener (main thread):

    public void onSpeechDetected() {
        // Nick 2026-10-23 v1.46: polite interruption — Nick started
        // talking; Selena holds any pending playback until his pause.
        nickSpeaking = true;
        sendBroadcast(new Intent(ACTION_SPEECH_DETECTED));
    }

    public void onSpeechEnd() {
        nickSpeaking = false;
        unmuteBeep();
        // Nick 2026-10-23 v1.46: polite interruption — Nick just paused.
        // onResults (with his chunk) usually lands within ~1s; delay the
        // release so the chunk is POSTed first — otherwise the echo guard
        // would discard it as "during playback". Also the fallback for
        // chunks the filters discard. The gate is re-checked at play
        // time, so a re-started utterance re-holds.
        handler.postDelayed(() -> releasePoliteHold(), 2000);
    }

    public void onRecognizerDone() {
        // Nick 2026-10-23 v1.46: the recognizer session ended (results or
        // error) — the utterance is over, so the speech flag must not
        // stick. Safety-net release for error paths where onEndOfSpeech
        // never fired; harmless otherwise (pumps are idempotent).
        nickSpeaking = false;
        unmuteBeep();
        scheduleRecognizerRestart();
        handler.postDelayed(() -> releasePoliteHold(), 2000);
    }

    /**
     * Nick 2026-10-23 v1.46: live dictation popup. Called by
     * ServiceRecognitionListener.onPartialResults on the main thread.
     * The popup shows the in-progress sentence = buffered chunks plus
     * the live partial. Unfiltered by design — the final accept/reject
     * still happens in onResults.
     */
    public void onPartialVoiceInput(String text) {
        String display = sentenceBuffer.length() > 0
                ? sentenceBuffer.toString() + " " + text
                : text;
        Intent i = new Intent(ACTION_PARTIAL_INPUT);
        i.putExtra(EXTRA_TEXT, display);
        sendAppBroadcast(i);
    }

    /**
     * Nick 2026-10-23 v1.46: polite interruption, decided ENTIRELY
     * ON-DEVICE (no cloud round-trip — by the time the cloud answered,
     * Nick would be talking again). In always-listening mode Selena never
     * STARTS speaking while Nick is talking: neither on the utterance
     * flag from the recognizer's real-time speech detector, nor on a hot
     * live mic RMS (covers the detector's lag). Callers keep the message
     * queued; the hold releases on his pause.
     */
    private boolean politeHoldActive() {
        if (!isAlwaysListening()) return false;
        if (nickSpeaking) return true;
        return recognitionListener != null && recognitionListener.isMicHot();
    }

    /**
     * Nick 2026-10-23 v1.46: polite interruption — Nick paused, so play
     * anything held. Priority items first (they were parked in the
     * waiting FIFO), then the normal queues. Every start point re-checks
     * the gate, so a re-started utterance re-holds. Never starts over
     * active playback — completion handlers drive the next item there.
     */
    private void releasePoliteHold() {
        if (playing || ttsSpeaking) return;
        playNextWaitingPriority(); // no-op when empty; gate re-checked inside
        pumpAudioQueue();
        pumpTtsQueue();
    }

    // Nick 2026-10-23 v1.46: sentence buffering — a 0.5s "collecting my
    // thought" pause is NOT a sentence end. Chunks arriving within
    // SENTENCE_GAP_MS of the previous chunk are mid-thought pauses:
    // appended to the in-progress sentence, not sent. A longer gap (or
    // the flush timer below) means the sentence ended — POST it as one
    // clean batch. 1.0s is a starting point; tune if it feels wrong.
    private final StringBuilder sentenceBuffer = new StringBuilder();
    private long lastChunkMs = 0;
    private static final long SENTENCE_GAP_MS = 1000;
    private Runnable sentenceFlushTask = null;

    /**
     * Nick 2026-10-23 v1.46: called per ACCEPTED chunk (post-filter).
     * Buffers mid-thought pauses into the in-progress sentence; the
     * sentence is sent by flushSentence() once a real pause lands.
     * Runs on the main thread (recognizer callback).
     */
    public void onVoiceInput(String text) {
        // Nick 2026-10-18 v1.9: Nick's words reach the UI instantly, even
        // if the POST later fails (offline, timeout). His inputs are never
        // spoken aloud — playback only plays Selena's reply MP3s.
        long now = android.os.SystemClock.uptimeMillis();
        long gap = now - lastChunkMs;
        // A long gap means the buffered chunks were a complete sentence —
        // flush them before starting the new one. (The flush timer
        // normally beats us here; this covers the deferred-while-speaking
        // case where the buffer went stale.)
        if (sentenceBuffer.length() > 0 && gap >= SENTENCE_GAP_MS) {
            flushSentence();
        }
        if (sentenceBuffer.length() > 0) sentenceBuffer.append(' ');
        sentenceBuffer.append(text);
        lastChunkMs = now;
        // Popup shows the in-progress sentence.
        broadcastInProgressSentence();
        // (Re)arm the flush: a full SENTENCE_GAP_MS with no new chunk ends
        // the sentence. Firing is deferred while he's still speaking.
        if (sentenceFlushTask != null) handler.removeCallbacks(sentenceFlushTask);
        sentenceFlushTask = () -> flushSentence();
        handler.postDelayed(sentenceFlushTask, SENTENCE_GAP_MS);
    }

    /**
     * Nick 2026-10-23 v1.46: send the buffered sentence to Selena as one
     * clean batch — POST to /api/selena-voice/input plus a from-Nick
     * history entry. Deferred while Nick is still speaking; never cut
     * him off mid-thought. After sending, the polite hold releases so
     * Selena may speak into the silence.
     */
    private void flushSentence() {
        sentenceFlushTask = null;
        if (sentenceBuffer.length() == 0) return;
        if (nickSpeaking) {
            // Still mid-utterance — wait for a real pause. The next chunk
            // re-arms; re-arm here as a backstop.
            sentenceFlushTask = () -> flushSentence();
            handler.postDelayed(sentenceFlushTask, SENTENCE_GAP_MS);
            return;
        }
        String sentence = sentenceBuffer.toString();
        sentenceBuffer.setLength(0);
        // Nick 2026-10-23 v1.46: his dictations live in the message
        // history as from-Nick entries, in the same list as her replies.
        MessageHistory.addNick(sentence);
        Intent sent = new Intent(ACTION_INPUT_SENT);
        sent.putExtra(EXTRA_TEXT, sentence);
        sendAppBroadcast(sent);
        final String toPost = sentence;
        new Thread(new InputPostTask(this, toPost)).start();
        // Nick 2026-10-23 v1.46: polite interruption — the sentence is
        // complete and he's silent; play anything Selena had held.
        releasePoliteHold();
    }

    /** Nick 2026-10-23 v1.46: popup shows the in-progress sentence. */
    private void broadcastInProgressSentence() {
        Intent i = new Intent(ACTION_PARTIAL_INPUT);
        i.putExtra(EXTRA_TEXT, sentenceBuffer.toString());
        sendAppBroadcast(i);
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

    private String lastPollStatus = "Poll: not started";

    private void broadcastPollStatus(String status) {
        lastPollStatus = status;
        Intent i = new Intent(ACTION_POLL_STATUS);
        i.putExtra(EXTRA_POLL_STATUS, status);
        sendAppBroadcast(i);
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
        sendAppBroadcast(i);
    }

    private void broadcastListeningState() {
        Intent i = new Intent(ACTION_LISTENING_STATE);
        i.putExtra(EXTRA_STATE, recognizerActive);
        sendAppBroadcast(i);
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
