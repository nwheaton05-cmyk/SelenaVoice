package com.selena.voice;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.MediaPlayer;
import android.media.audiofx.LoudnessEnhancer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
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
import java.util.List;
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
 *  2. v1.82 (Nick, 2026-11-02): PLAYBACK-ONLY. The microphone is
 *     GONE — no RECORD_AUDIO permission, no recognizer, no dictation,
 *     no wake word, no voiceprint/guest capture, no polite hold.
 *     Nick replies in the Muse app; this app only speaks. Nothing
 *     in here may ever consult a listening state again: playback
 *     pumps run purely on queue/lane state.
 *  3. v1.83 (Nick, 2026-11-02): MIC-ACTIVITY AUTO-PAUSE. The app
 *     itself still never records — but it now WATCHES whether any
 *     OTHER app is using the phone's microphone (his case: dictation
 *     in the Muse app, where her voice was being picked up into
 *     his dictation). While the mic is busy elsewhere, playback
 *     pauses/holds; ~2 s after it frees, a pause the hold caused
 *     resumes. This is an external-activity hold, a sibling of the
 *     earbuds-only lane hold — not a listening state of this app.
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
    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_POLL_STATUS = "poll_status";
    public static final String EXTRA_TEXT = "text";

    static final String PREFS = "selena_voice";
    private static final String KEY_LAST_TS = "last_ts";
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
    // Nick 2026-10-30 v1.73: earbuds-only playback lane. Default ON
    // (his explicit order) — but it only takes effect once an earbuds
    // name is saved (see the lane section near isEarbudsConnected).
    public static final String KEY_EARBUDS_ONLY = "earbuds_only_playback";
    // Nick 2026-10-30 v1.77: headset fallback for the earbuds lane.
    // Default ON (his stated preference): when the clean media pin
    // is refused or unprovable in a split case (Android Auto does
    // exactly this), the lane falls back to the earbuds' headset
    // channel instead of parking in silence.
    public static final String KEY_HEADSET_FALLBACK = "headset_fallback";

    // v1.50 (2026-10-26, OVH batch): owned-domain addresses replace
    // the DuckDNS bridge names (same OVH server, permanent names).
    static final String BASE_URL = "https://siteviewoperations.com";
    // Nick 2026-10-18 v1.14: Supabase Realtime push (LEG 1).
    // v1.50: the anon key is now ONLY the gateway/apikey value — the
    // queue's anon SELECT policy was removed 2026-10-26, so Realtime
    // joins also carry the signed-in user's access token (see
    // SupabaseAuth + VoiceAuthTokenProvider). Authenticated SELECT on
    // the queue is what authorizes row delivery.
    private static final String SUPABASE_HOST = "api.siteviewoperations.com";
    private static final String SUPABASE_ANON_KEY = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzdXBhYmFzZSIsImlhdCI6MTc5Mjg1Njk0MCwiZXhwIjo0OTQ4NTMwNTQwLCJyb2xlIjoiYW5vbiJ9.tHuj64aAawXs_4dl-TVdgfxOUcmyKloh204BfEc60mk";
    private static final long POLL_MS = 3000;
    private static final long HEARTBEAT_MS = 30000;
    // Nick 2026-10-29 v1.63: the v1.17 STREAM_MUSIC boost state
    // (savedMusicVolume) is GONE — the boost was disabled in v1.48 and
    // its restore machinery is now removed entirely. Nothing in this
    // app raises or lowers STREAM_MUSIC; her loudness is her player's
    // own output at the level Nick set, and music ducking is the
    // MAY_DUCK audio-focus layer only (the music app lowers ITSELF).

    // True while Selena's audio is playing (playback state shared
    // with the TTS listener; v1.82: no recognizer remains for this
    // to guard — it is plain playback state now).
    private static volatile boolean isPlayingAudio = false;

    public boolean isPlayingAudio() {
        return isPlayingAudio;
    }

    /** Called while Selena's voice messages play. */
    public static void setPlayingAudio(boolean playing) {
        isPlayingAudio = playing;
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
    private boolean destroyed = false;
    private MediaPlayer player;
    // Nick 2026-10-28 v1.51: media session for earbud/headset buttons.
    // Before v1.51 the app had NO MediaSession and NO media-button
    // receiver, so earbud taps were never delivered to it at all (Nick:
    // "tap earbud has never worked"). The session is the modern delivery
    // path; EarbudButtonReceiver (manifest) is the legacy second path;
    // onEarbudAction debounces a press arriving via both.
    private MediaSession mediaSession;
    private String lastEarbudAction = null;
    private long lastEarbudActionMs = 0;
    // Nick 2026-10-28 v1.52 (§6 press rules): which delivery path the
    // last press came from ("session" / "broadcast"). A physical press
    // can arrive via BOTH paths — cross-path repeats inside the
    // debounce window are the same press; same-path repeats are
    // genuinely new presses. The double-tap detection while paused
    // depends on telling those apart.
    private String lastEarbudSource = null;
    // Nick 2026-10-28 v1.52: MediaSession politeness — the session is
    // only ACTIVE while Selena has content (playing/paused) or is
    // listening; while fully idle it deactivates so Spotify/Pandora
    // receive their own earbud presses. Tracked to only call
    // setActive on transitions.
    private boolean mediaSessionActiveWanted = false;
    // Nick 2026-10-28 v1.52: a press while Selena is paused holding
    // the audio path does not resume instantly — it waits out the
    // double-tap window. A second distinct press inside the window
    // means STOP (and music resumes), not resume.
    private Runnable pendingResumeTask = null;
    private String pendingResumeSource = null;
    private long pendingResumeMs = 0;
    private static final long EARBUD_DOUBLE_TAP_MS = 400;
    // Nick 2026-10-22 v1.34: path of the MP3 currently loaded in the
    // player (null when idle). Lets CLEAR_UNLOCKED stop playback only
    // when the playing message is unlocked.
    private String currentAudioPath;
    // Nick 2026-10-29 v1.63 (14:38 "turn Selena voice up a bit",
    // quantified 14:39): "I want Selena voice and Siteview to be 25%
    // louder than Spotify." The SPEC is the relationship: her voice
    // sits ≈1.25× the music's playing level. Android exposes no
    // per-app output meter for Spotify, so the constant below is the
    // honest approximation: a FIXED session gain sized against the
    // music-stream level under the system MAY_DUCK (Spotify ducks to
    // ~0.79 amplitude under the standard −6 dB system duck; her
    // player at the full stream level × 1.25/0.79 ≈ ×1.58 amplitude
    // ≈ +4 dB restores the 1.25× relationship over the ducked music).
    // HER_VOICE_GAIN_MB = round(2000 · log10(VOICE_OVER_MUSIC_TARGET
    // / 0.794)) with the target at 1.25. It is constant for every MP3
    // play in every state — it does NOT vary with music or duck state
    // (the revoked v1.61 shape coupled an enhancer to the STREAM_MUSIC
    // ramp as compensation; that coupling netted her voice DOWN and
    // stays deleted). The system stream volume is never written
    // (standing rule). Field-tunable: change the target, recompute.
    static final double VOICE_OVER_MUSIC_TARGET = 1.25;
    static final int HER_VOICE_GAIN_MB = 394; // ≈ +3.94 dB, from the target above
    private LoudnessEnhancer loudnessEnhancer = null;
    private File currentAudioFile;
    private final Queue<File> audioQueue = new LinkedList<>();
    private boolean playing = false;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    // Nick 2026-10-29 v1.63: the v1.61 DEEP DUCK fields lived here
    // (DEEP_DUCK_FRACTION, holder flags, saved/target volume, ramp
    // generation, watchdog). ALL REMOVED under Nick's standing rule
    // (14:32 EDT): this app never writes the system media volume.
    // With no music playing, the ramp + capped compensation was
    // netting out negative on Selena's OWN voice — his exact field
    // symptom. Ducking is the MAY_DUCK focus layer above, and only
    // that: the music app lowers its own playback; the knob never
    // moves, and her voice always plays at the level he set.
    private NotificationManager notificationManager;
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
    // Nick 2026-10-28 v1.55 (Phase 5, spec §8): playback-progress wire.
    // The brain's fraction accounting needs to know WHICH queue row
    // finished; currentPlayServerId tracks the selena_voice_queue row
    // of whatever is playing right now (either path) and the completion
    // handlers report it (playback_row_played) before unwinding.
    private String currentPlayServerId = null;
    // Nick 2026-10-29 v1.58: segment spacing. Rows that are segments of
    // ONE message share segment_group (null = standalone message).
    // currentPlaySegmentGroup rides alongside currentPlayServerId
    // through every tracking path below, so the completion handlers
    // can hold the ruled segment gap before a same-group successor
    // starts (see advanceAfterSegmentGap).
    private String currentPlaySegmentGroup = null;
    // Nick 2026-11-02 v1.83: true while the play in flight is a
    // serious-critical row (priority "critical") — the class that
    // breaks through every hold, the mic hold included. Set beside
    // currentPlayServerId on the priority paths; cleared when a
    // queue play adopts its row id, and on stop. A witness for the
    // orphan guard (a parked critical stays critical) and for the
    // mic hold's critical exemption (a playing critical is never
    // mic-paused).
    private boolean currentPlayCritical = false;
    // Nick 2026-11-02 v1.80: the gap between segments of one
    // message must land between 1.5 s and 3 s end-to-start, as
    // close to real-time as possible — a 2000 ms hold plus normal
    // start latency sits mid-band. (Was 3000, Nick 2026-10-29.)
    // NOT the dictation SENTENCE_GAP_MS further down.
    private static final long SEGMENT_GAP_MS = 2000;
    // A same-group advance waiting out its gap. Preemptible: pause /
    // stop / skip / priority cancel it (cancelPendingSegmentAdvance),
    // and a stale generation or already-started playback drops it.
    private Runnable pendingSegmentAdvance = null;
    private int segmentAdvanceGen = 0;
    // Nick 2026-11-02 v1.79: almost-done wire. Selena segments long
    // messages herself and feeds them just-in-time: the next segment
    // must be SENT 10-15 s before the current one ends, so the
    // completion report (playback_row_played) arrives too late. The
    // first time the playing row's remaining time drops to
    // ALMOST_DONE_LEAD_MS, fire PlaybackProgressTask with action
    // "playback_almost_done" — once per row. MediaPlayer path: a 1 s
    // position watch against the real duration. On-device TTS path:
    // utterances have no real duration, so a one-shot is scheduled at
    // (estimated duration - lead), estimated at ~14 chars/sec (the
    // 0.95 speaking rate).
    private static final long ALMOST_DONE_LEAD_MS = 12000;
    private static final long ALMOST_DONE_TICK_MS = 1000;
    // Rows the almost-done report already fired for — never twice
    // for one row. Bounded FIFO, same discipline as spokenTexts.
    private final LinkedList<String> almostDoneFiredRows =
            new LinkedList<>();
    // MediaPlayer almost-done watch (self-rescheduling 1 s tick).
    private Runnable almostDoneWatchTask = null;
    // On-device TTS almost-done one-shot (estimate-based).
    private Runnable almostDoneTtsTask = null;
    // MP3 path: the row id rides with the queued File (path -> row id),
    // consumed when playFileNow starts the file.
    private final java.util.Map<String, String> audioFileServerIds =
            new java.util.HashMap<>();
    // MP3 path segment groups, same map discipline as the row ids.
    private final java.util.Map<String, String> audioFileSegmentGroups =
            new java.util.HashMap<>();
    // TTS path: row ids ride a parallel queue alongside ttsQueue (same
    // lock); pumpTtsQueue polls both together.
    private final Queue<String> ttsServerIdQueue = new LinkedList<>();
    // TTS path segment groups, same parallel-queue discipline.
    private final Queue<String> ttsSegmentGroupQueue = new LinkedList<>();
    // Row id of the TTS utterance pumpTtsQueue is about to speak;
    // speakOnDevice adopts it for fresh utterances.
    private String pendingTtsServerId = null;
    // Segment group of that same utterance; adopted the same way.
    private String pendingTtsSegmentGroup = null;
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
        // v1.58: the interrupted item's segment group, restored with
        // its row id so a later completion gaps correctly.
        String segmentGroup;
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
        // v1.58: segment group (null = standalone), for the 3s gap
        // when this item succeeds a same-group segment.
        final String segmentGroup;
        // v1.81: true when this entry is a priority play that was
        // superseded mid-play by a newer start and parked for replay
        // (see the orphan guard in playFileNow).
        boolean wasOrphaned;
        // v1.83: serious-critical rows (priority "critical") — the
        // class that breaks through the mic hold even from the FIFO.
        final boolean critical;
        WaitingPriority(File audioFile, String text, String serverId) {
            this(audioFile, text, serverId, null);
        }
        WaitingPriority(File audioFile, String text, String serverId,
                String segmentGroup) {
            this(audioFile, text, serverId, segmentGroup, false);
        }
        WaitingPriority(File audioFile, String text, String serverId,
                String segmentGroup, boolean critical) {
            this.audioFile = audioFile;
            this.text = text;
            this.serverId = serverId;
            this.segmentGroup = segmentGroup;
            this.critical = critical;
        }
    }
    private final java.util.ArrayDeque<WaitingPriority> waitingPriority =
            new java.util.ArrayDeque<>();
    // v1.81: number of priority plays started in the current priority
    // chain (playPriorityMp3 increments it on a successful start; the
    // completion unwinds that end a priority flow reset it to 0 — the
    // same points that clear priorityMode). playFileNow reads it to
    // tell a live PRIORITY play from a live queue play when a new
    // start is about to supersede the current player: a superseded
    // priority play never completes (its completion listener dies
    // with the released player), so without this witness its row is
    // silently lost — the 2026-11-02 field swallow (see playFileNow).
    private int priorityInterruptDepth = 0;
    // v1.81: text of the priority play currently in flight, captured
    // by playPriorityMp3 alongside currentPlayServerId so the orphan
    // guard in playFileNow can park the row complete (text included).
    private String currentPriorityPlayText = null;
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
        // Nick 2026-10-23 v1.41: start as MEDIA_PLAYBACK only (explicit
        // type). v1.82: this is now the ONLY type, permanently — the
        // app holds no microphone permission and never claims one.
        startForegroundAs("Selena Voice ready",
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        // Nick 2026-10-23 v1.36: global crash reporter (service process).
        CrashReporter.install(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // Nick 2026-10-28 v1.51: register the media session so Bluetooth
        // earbud/headset button presses are delivered to this app.
        initMediaSession();
        // Nick 2026-10-19 v1.16: hold a partial wake lock for the service's
        // lifetime so the poll loop, Realtime socket, and TTS keep
        // running with the screen locked. (MediaPlayer additionally
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
        registerLaneDeviceWatch(); // v1.73: earbuds-only lane watch
        startMicHoldWatch(); // v1.83: mic-activity auto-pause watch
        log("lane: earbuds-only state at create: " + laneModeName()
                + (getEarbudsDeviceName() != null
                        ? " (earbuds '" + getEarbudsDeviceName() + "')"
                        : ""));
        log("service created (v1.82 playback-only)");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            if ("EARBUD_TAP".equals(intent.getAction())) {
                // Nick 2026-10-28 v1.51: forwarded by EarbudButtonReceiver
                // (manifest MEDIA_BUTTON path). The MediaSession callback
                // lands in onEarbudAction directly; both paths debounce
                // against each other there.
                String earbudAction = intent.getStringExtra(
                        EarbudButtonReceiver.EXTRA_EARBUD_ACTION);
                String earbudSource = intent.getStringExtra(
                        EarbudButtonReceiver.EXTRA_EARBUD_SOURCE);
                log("intent: EARBUD_TAP received (" + earbudAction + ")");
                if (earbudAction != null) {
                    onEarbudAction(earbudAction,
                            earbudSource != null ? earbudSource : "broadcast");
                }
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
            } else if ("RESTART_REALTIME".equals(intent.getAction())) {
                // v1.50 (2026-10-26): after an in-app sign-in, rejoin
                // Realtime so the channel picks up the user access
                // token immediately instead of on the next reconnect.
                log("intent: RESTART_REALTIME received");
                stopRealtime();
                startRealtime();
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
        // v1.63: no volume safety nets remain to run — nothing in
        // this app writes the system media volume anymore (v1.17
        // boost restore and the v1.61 deep-duck release are gone).
        unregisterLaneDeviceWatch(); // v1.73
        stopMicHoldWatch(); // v1.83
        laneEnd(); // v1.73: never leave the headset channel pinned
        releasePlayer();
        // Nick 2026-10-28 v1.51: release the media session.
        try {
            if (mediaSession != null) {
                mediaSession.setActive(false);
                mediaSession.release();
                mediaSession = null;
            }
        } catch (Exception ignored) {}
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

    // ------------------------------------------------------------ foreground service type
    // The service lives as MEDIA_PLAYBACK only (v1.41 shape, made
    // permanent in v1.82): mediaPlayback needs no runtime permission,
    // and the app no longer holds — or ever claims — a microphone
    // foreground-service type.

    private void startForegroundAs(String text, int type) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, type);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    // Nick 2026-10-29 v1.63: boostMusicVolume() / restoreMusicVolume()
    // / ensureMaxMusic() — the v1.17 STREAM_MUSIC boost machinery —
    // are DELETED, not merely disabled. The boost had been a no-op
    // since v1.48, but its restore/re-assert halves still wrote the
    // media stream. Under the standing rule (14:32 EDT) no code path
    // in this app writes STREAM_MUSIC in either direction; MAY_DUCK
    // audio focus is the only ducking mechanism that remains.

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
            // Nick 2026-10-29 v1.58: carry the segment grouping for
            // the 3s between-segment gap (snake_case in the record;
            // camelCase from here on, matching the poll API shape).
            if (!record.isNull("segment_group")) {
                m.put("segmentGroup",
                        record.optString("segment_group", ""));
            }
            if (!record.isNull("segment_index")) {
                m.put("segmentIndex", record.optInt("segment_index", -1));
            }
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
                this, new VoiceAuthTokenProvider(this));
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
            ttsServerIdQueue.clear(); // v1.55: parallel queue stays in sync
            ttsSegmentGroupQueue.clear(); // v1.58: group queue likewise
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
        // v1.83: fallback mic-config check on every poll cycle —
        // catches anything the recording callback missed.
        refreshMicHold("poll");
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
            // v1.50: present the signed-in user's token when we have
            // one. The queue route is service-role server-side today;
            // the header keeps the read path honest if it ever moves
            // behind the authenticated policy like Realtime did.
            try {
                String tok = SupabaseAuth.validAccessToken(this);
                if (tok != null) {
                    conn.setRequestProperty("Authorization", "Bearer " + tok);
                }
            } catch (Exception ignored) {}
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
            // v1.81: poll-cycle backstop drain — if anything sits in
            // the waiting-priority FIFO while the service is idle
            // (e.g. a row parked by the orphan guard in playFileNow
            // whose chain never produced a completion advance), the
            // next poll cycle starts it. Guarantees every accepted
            // row a playback attempt even if a completion trigger
            // was lost. No-op while busy or when nothing waits;
            // playNextWaitingPriority re-checks polite/lane gates.
            handler.post(() -> {
                if (!playing && !ttsSpeaking
                        && !waitingPriority.isEmpty()) {
                    log("priority: poll backstop draining "
                            + waitingPriority.size()
                            + " waiting item(s)");
                    playNextWaitingPriority();
                }
            });
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
        // Nick 2026-10-29 v1.58: this row's segment group when it is
        // one segment of a multi-row message (null = standalone). It
        // rides every enqueue/play call below alongside the row id.
        String segmentGroup = m.optString("segmentGroup", "");
        if (segmentGroup.isEmpty()) segmentGroup = null;
        final String sgroup = segmentGroup;
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
            // v1.83: serious-critical rows (priority "critical")
            // are the break-through class — they route through the
            // priority path like any non-low row, and the mic hold
            // exempts them at every gate.
            final boolean critical = "critical".equalsIgnoreCase(priority);
            // v1.81 (Nick, 2026-11-02): intake disposition — EVERY
            // accepted row logs its id and intended disposition here,
            // before any dispatch, so a row that later vanishes can
            // be traced from the field logs alone (the 2026-11-02
            // swallow was undiagnosable at intake level: nothing
            // named the row between the poll and its disappearance).
            final String disposition;
            if (!"low".equals(priority)) {
                disposition = isPhoneVoiceMode() ? "priority-tts"
                        : (!audioUrl.isEmpty()
                                && audioUrl.startsWith("http"))
                                ? "priority-mp3-download"
                                : "priority-mp3-synth";
            } else if (isNoAutoReadout()) {
                disposition = "held-no-auto-readout";
            } else if (isPhoneVoiceMode()) {
                disposition = "queue-tts";
            } else if (!audioUrl.isEmpty()) {
                disposition = "queue-mp3";
            } else {
                disposition = "queue-mp3-synth";
            }
            log("intake: row id=" + (sid != null ? sid : "(none)")
                    + " priority=" + priority
                    + " group=" + (sgroup != null ? sgroup : "(none)")
                    + " disposition=" + disposition);
            if (!"low".equals(priority)) {
                markSpokenLocked(text);
                if (isPhoneVoiceMode()) {
                    final String speakText = text;
                    handler.post(() -> onPriorityArrivalTts(speakText, sid, sgroup, critical));
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
                        handler.post(() -> onPriorityArrivalMp3(pf, ptext, psid, sgroup, critical));
                    } catch (Exception e) {
                        log("priority: MP3 download failed: "
                                + e.getMessage() + " — TTS fallback");
                        final String speakText = text;
                        handler.post(() -> onPriorityArrivalTts(speakText, sid, sgroup, critical));
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
                            handler.post(() -> onPriorityArrivalMp3(sf, stext, ssid, sgroup, critical));
                        } catch (Exception e) {
                            log("priority: synthesis failed, on-device TTS "
                                    + "fallback: " + e.getMessage());
                            handler.post(() -> onPriorityArrivalTts(stext, ssid, sgroup, critical));
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
                handler.post(() -> enqueueTts(speakText, sid, sgroup));
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
                    // ever enqueued and played.
                    markSpokenLocked(text);
                    broadcastMessage(text);
                    try {
                        File f = downloadToCache(audioUrl);
                        final File df = f;
                        // Nick 2026-10-21 v1.24: cache for replay.
                        MessageHistory.add(text, f, sid);
                        handler.post(() -> enqueueAudio(df, sid, sgroup));
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
                        handler.post(() -> enqueueAudio(sf, sid, sgroup));
                    } catch (Exception e) {
                        log("synth: low-priority synthesis failed, "
                                + "on-device fallback: " + e.getMessage());
                        handler.post(() -> enqueueTts(stext, sid, sgroup));
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
        enqueueAudio(f, null);
    }

    /** v1.55: queue a file WITH its queue row id, so the completion
     *  report can name the row that finished (see playFileNow). */
    private void enqueueAudio(File f, String serverId) {
        enqueueAudio(f, serverId, null);
    }

    /** v1.58: queue a file WITH its row id AND segment group, so the
     *  completion handlers can hold the 3s gap before a same-group
     *  successor (see playFileNow / advanceAfterSegmentGap). */
    private void enqueueAudio(File f, String serverId,
            String segmentGroup) {
        if (serverId != null) {
            audioFileServerIds.put(f.getAbsolutePath(), serverId);
        }
        if (segmentGroup != null) {
            audioFileSegmentGroups.put(f.getAbsolutePath(), segmentGroup);
        }
        synchronized (audioQueue) {
            audioQueue.add(f);
        }
        pumpAudioQueue();
    }

    /** Runs on main thread: start the next queued file if idle. */
    private void pumpAudioQueue() {
        refreshMicHold("pump"); // v1.83: fresh config count at start time
        if (playing) return;
        // Nick 2026-10-30 v1.73: earbuds-only lane armed but no
        // earbuds — the file stays queued, unplayed and unreported;
        // the device watch pumps again when the earbuds connect.
        if (laneBlocked()) {
            logLaneHeld("MP3 playback");
            return;
        }
        // Nick 2026-11-02 v1.83: mic hold — another app is using
        // the microphone; the file stays queued until it frees
        // (the mic-free flow pumps again).
        if (micHold) {
            logMicHeld("MP3 playback");
            return;
        }
        File f;
        synchronized (audioQueue) {
            f = audioQueue.poll();
        }
        if (f == null) return;
        playing = true;
        if (!playFileNow(f)) {
            // v1.75: the play parked at the lane gate (e.g. comm
            // refusal / AA pin failure) — the file returns to the
            // head of the queue, unplayed and unreported.
            synchronized (audioQueue) {
                ((LinkedList<File>) audioQueue).addFirst(f);
            }
            playing = false;
        }
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
        // Nick 2026-10-30 v1.73: a laned play never touched the
        // speakerphone or media focus.
        // v1.76: the lane is a SESSION — an item ending schedules
        // the session's idle release (scheduleLaneSessionRelease)
        // instead of tearing the route down; per-segment teardown
        // is what made Android Auto announce "call ended" at
        // every segment seam in the 2026-10-30 18:25 field test.
        if (laneSessionHeld()) {
            scheduleLaneSessionRelease();
            return;
        }
        if (lanePlaybackActive) {
            laneEnd();
            return;
        }
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

    /** Runs on main thread: starts MediaPlayer for the downloaded file.
     *
     * Nick 2026-11-02: Selena segments; the app plays each queue row
     * whole — never split row text here. (Long messages arrive as
     * multiple selena_voice_queue rows, each ~20-30 s of speech,
     * pre-segmented by the sender. Every playback path — in-app
     * synthesis, MediaPlayer, on-device TTS — treats one row as one
     * indivisible unit.) */
    // setSpeakerphoneOn is deprecated (API 31+) but still functional through
    // API 34; its replacement (setCommunicationDevice) is for voice-call
    // routing — the wrong semantic for media playback.
    @SuppressWarnings("deprecation")
    private boolean playFileNow(File f) {
        // Nick 2026-10-30 v1.73: backstop — the pumps and priority
        // gates already hold items while the earbuds-only lane is
        // armed without earbuds; a direct caller (e.g. a replay tap)
        // must still never sound out loud. The item is NOT reported
        // played; queue-owned items were never polled (gate is
        // upstream), so nothing is lost by returning here.
        if (laneBlocked()) {
            log("lane: armed-held — direct play attempt held off: "
                    + f.getName());
            playing = false;
            return false;
        }
        // v1.83: mic-hold backstop (the lane twin above) — while
        // another app holds the microphone, a direct play attempt
        // must not sound: her voice would land in his dictation.
        // Serious-critical plays carry the bypass, set by
        // playPriorityMp3 for the duration of the start call.
        if (micHold && !micCriticalBypass) {
            log("michold: held — direct play attempt held off: "
                    + f.getName());
            playing = false;
            return false;
        }
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
            // Nick 2026-10-19 v1.17's boost-to-max once lived here;
            // Nick 2026-10-29 v1.63 removed it entirely: her voice
            // plays at exactly the level he set, and other audio is
            // ducked only by the MAY_DUCK focus request below.
            log("audio: using device volume (no override)");
            // Nick 2026-10-30 v1.73: earbuds-only lane — when engaged,
            // she plays ONLY into the named earbuds' headset channel
            // (laneBegin set the communication device) with
            // voice-communication attributes and NO media focus
            // request, so Spotify on the truck radio is untouched.
            // Otherwise the v1.11 routing + MAY_DUCK focus, unchanged.
            // v1.75: laneBegin is four-way now. PARK (e.g. the comm
            // device was refused) returns false — the caller keeps
            // its item unplayed; she never falls through to the
            // legacy path and lands on the car. AA plays as MEDIA
            // pinned to the earbuds with NO focus and NO routing
            // calls (Android Auto owns the car's lanes; the BT media
            // lane to the earbuds is free).
            int laneBegin = laneBegin();
            if (laneBegin == LANE_BEGIN_PARK) {
                playing = false;
                isPlayingAudio = false;
                setPlayingAudio(false);
                return false;
            }
            // v1.77: not final — a refused pin flips these to the
            // headset fallback mid-method (laneNow on, lanePin off).
            boolean laneNow = laneBegin == LANE_BEGIN_HEADSET
                    || laneBegin == LANE_BEGIN_HEADSET_FALLBACK;
            boolean laneAa = laneBegin == LANE_BEGIN_AA;
            // v1.76: both pin flavors (AA-present, and the
            // foreign-comm-incumbent media pin) share the pin
            // discipline below: per-player pin, pre-play route
            // proof, NO focus, NO routing calls.
            boolean lanePin = laneAa
                    || laneBegin == LANE_BEGIN_PIN;
            final String pinCaseName =
                    laneAa ? "AA-present" : "media-pin";
            final String pinFail = laneAa
                    ? "AA preferred-device FAILED" : "media-pin FAILED";
            if (laneBegin == LANE_BEGIN_LEGACY) {
                // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win
                // over the speakerphone when connected.
                routeAudioForPlayback();
                log("audio: stream=MUSIC (USAGE_MEDIA)");
                requestAudioFocus();
            } else if (laneAa) {
                log("audio: stream=MUSIC pinned to earbuds "
                        + "(AA-present pin — no focus, no routing "
                        + "change)");
            } else if (lanePin) {
                log("audio: stream=MUSIC pinned to earbuds "
                        + "(media pin — no focus, no routing change)");
            }
            // v1.81 ORPHAN GUARD (field, 2026-11-02): this start is
            // about to release the current player below. If that
            // player is a live PRIORITY play (priorityInterruptDepth
            // > 0), releasing it kills the play's completion listener
            // with it: the row never reports played, no completion
            // pump ever runs for it, and priorityMode stays up with
            // nothing playing — the row is silently swallowed. (That
            // is exactly how row msg-1793641641223-uo9po8 died on
            // v1.79: a segment start superseded it 3 s in — there
            // the start was wrongful because the `playing` flag was
            // down on the priority path, fixed in v1.80 — but a
            // replay tap or any other direct start can still
            // supersede a live priority play, so the guard stands on
            // its own.) Park the superseded row at the HEAD of the
            // waiting-priority FIFO before the state moves: the
            // superseding play's completion advance drains it like
            // any queued priority item and it plays out in full.
            // A queue play being superseded is NOT parked here —
            // priorityInterruptDepth is 0 outside priority chains,
            // and interrupted queue output belongs to the interrupt
            // stack, not this guard.
            if (player != null && priorityInterruptDepth > 0
                    && currentPlayServerId != null
                    && !"priority".equals(currentPlayServerId)
                    && currentAudioFile != null
                    && !f.getAbsolutePath().equals(
                            currentAudioFile.getAbsolutePath())) {
                WaitingPriority orphan = new WaitingPriority(
                        currentAudioFile, currentPriorityPlayText,
                        currentPlayServerId, currentPlaySegmentGroup,
                        currentPlayCritical);
                orphan.wasOrphaned = true;
                waitingPriority.addFirst(orphan);
                log("priority: in-flight priority play superseded "
                        + "by a new start — row parked for replay id="
                        + currentPlayServerId + " ("
                        + waitingPriority.size() + " waiting)");
            }
            releasePlayer();
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(laneNow
                            ? AudioAttributes.USAGE_VOICE_COMMUNICATION
                            : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            if ((laneNow || lanePin) && laneDevice != null
                    && android.os.Build.VERSION.SDK_INT >= 28) {
                // Headset: belt and braces alongside the comm device.
                // Pins: this pin IS the lane — if the system refuses
                // it, she falls back to the headset channel when the
                // v1.77 user setting allows, else parks rather than
                // risk the car speakers.
                try {
                    boolean pinned =
                            player.setPreferredDevice(laneDevice);
                    if (lanePin && !pinned) {
                        log("lane: " + pinFail + " — "
                                + "setPreferredDevice refused for "
                                + laneDevice.getProductName());
                        if (isHeadsetFallbackOn()
                                && laneFallbackToHeadset(
                                        "lane: pin refused — "
                                        + "headset fallback (user "
                                        + "setting on)")) {
                            lanePin = false;
                            laneAa = false;
                            laneNow = true;
                            laneBegin =
                                    LANE_BEGIN_HEADSET_FALLBACK;
                            player.setAudioAttributes(
                                    new AudioAttributes.Builder()
                                            .setUsage(AudioAttributes
                                                    .USAGE_VOICE_COMMUNICATION)
                                            .setContentType(AudioAttributes
                                                    .CONTENT_TYPE_SPEECH)
                                            .build());
                            try {
                                player.setPreferredDevice(laneDevice);
                            } catch (Exception ignored) {}
                        } else {
                            log("lane: " + pinFail + " — parked");
                            releasePlayer();
                            laneEnd();
                            playing = false;
                            isPlayingAudio = false;
                            setPlayingAudio(false);
                            return false;
                        }
                    }
                } catch (Exception e) {
                    log("lane: setPreferredDevice failed: " + e);
                    if (lanePin) {
                        if (isHeadsetFallbackOn()
                                && laneFallbackToHeadset(
                                        "lane: pin refused — "
                                        + "headset fallback (user "
                                        + "setting on)")) {
                            lanePin = false;
                            laneAa = false;
                            laneNow = true;
                            laneBegin =
                                    LANE_BEGIN_HEADSET_FALLBACK;
                            try {
                                player.setAudioAttributes(
                                        new AudioAttributes.Builder()
                                                .setUsage(AudioAttributes
                                                        .USAGE_VOICE_COMMUNICATION)
                                                .setContentType(AudioAttributes
                                                        .CONTENT_TYPE_SPEECH)
                                                .build());
                            } catch (Exception ignored) {}
                            try {
                                player.setPreferredDevice(laneDevice);
                            } catch (Exception ignored) {}
                        } else {
                            releasePlayer();
                            laneEnd();
                            playing = false;
                            isPlayingAudio = false;
                            setPlayingAudio(false);
                            return false;
                        }
                    }
                }
            }
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
            // v1.63 (14:38): her own +gain — FIXED at HER_VOICE_GAIN_MB
            // on her player's session, applied identically on every
            // play regardless of music/duck state. This is NOT the
            // v1.61 compensation (which varied with the ramp and
            // netted her voice down — Nick's 14:33 symptom); the ramp
            // is gone, the gain is constant, and no stream volume is
            // ever written. Failure is soft: she still plays, ungained.
            try {
                loudnessEnhancer = new LoudnessEnhancer(
                        player.getAudioSessionId());
                loudnessEnhancer.setTargetGain(HER_VOICE_GAIN_MB);
                loudnessEnhancer.setEnabled(true);
                log("voice: her gain +" + HER_VOICE_GAIN_MB
                        + " mB on her player session (target "
                        + VOICE_OVER_MUSIC_TARGET + "x over ducked music)");
            } catch (Exception e) {
                log("voice: her gain failed (plays ungained): " + e);
                loudnessEnhancer = null;
            }
            if (lanePin) {
                // Nick 2026-10-30 16:40 — HARD RULE: she must NEVER
                // output through Android Auto / the truck radio /
                // car speakers, not as a fallback and not for a
                // split second. So in the pin cases the route
                // is PROVEN before start(): a silent probe track
                // pinned to the same earbuds must report the
                // earbuds as its routed device. The car route, any
                // other device, or UNKNOWN → park before any
                // audible output. (v1.74 checked the route only
                // after starting and logged "routed to unknown"
                // while playing; that is impossible here.)
                AudioDeviceInfo proven =
                        probePinnedRoutedDevice(laneDevice);
                if (proven == null
                        || !nameMatchesEarbuds(
                                proven.getProductName())) {
                    log("lane: " + pinCaseName
                            + " route NOT earbuds ("
                            + (proven != null
                                    ? proven.getProductName()
                                    : "unknown")
                            + ")");
                    // v1.77: an unprovable pin is a refused pin —
                    // with the fallback setting on, the headset
                    // channel serves instead. The player was built
                    // for the pin (media attributes, prepared), so
                    // release it and re-enter once: the sticky
                    // fallback session makes the re-entry begin as
                    // HEADSET_FALLBACK and play cleanly.
                    if (isHeadsetFallbackOn()
                            && laneFallbackToHeadset(
                                    "lane: pin unprovable — "
                                    + "headset fallback (user "
                                    + "setting on)")) {
                        releasePlayer();
                        playing = false;
                        isPlayingAudio = false;
                        setPlayingAudio(false);
                        return playFileNow(f);
                    }
                    log("lane: " + pinCaseName
                            + " route NOT earbuds — parked, "
                            + "no output");
                    releasePlayer();
                    laneEnd();
                    playing = false;
                    isPlayingAudio = false;
                    setPlayingAudio(false);
                    return false;
                }
                log("lane: " + pinCaseName + " route verified — "
                        + proven.getProductName()
                        + " proven before start");
            }
            player.start();
            isPlayingAudio = true;
            playbackPaused = false;
            log("playback started (echo guard on)");
            // v1.73: in the field test, this line is the ground
            // truth for where she actually landed.
            if (isEarbudsOnlyOn()
                    && android.os.Build.VERSION.SDK_INT >= 28) {
                try {
                    AudioDeviceInfo routed = player.getRoutedDevice();
                    log("lane: player routed to "
                            + (routed != null
                                    ? routed.getProductName()
                                            + " (type " + routed.getType()
                                            + ")"
                                    : "unknown")
                            + " — mode " + laneModeName());
                    if (lanePin && routed != null
                            && !nameMatchesEarbuds(
                                    routed.getProductName())) {
                        // v1.75: the pin did not hold — she is
                        // headed somewhere that is NOT the earbuds
                        // (the car). Stop and park; the caller
                        // keeps the item unplayed.
                        log("lane: " + pinFail + " — "
                                + "player routed to "
                                + routed.getProductName()
                                + ", not the earbuds; parked");
                        releasePlayer();
                        laneEnd();
                        playing = false;
                        isPlayingAudio = false;
                        setPlayingAudio(false);
                        return false;
                    }
                    if (lanePin && routed == null) {
                        // Route not assigned yet at start; verify
                        // shortly — a late non-earbuds route aborts
                        // to park via the normal mid-play path.
                        final File aaFile = f;
                        handler.postDelayed(() -> {
                            if (!lanePinActive || !playing
                                    || currentAudioFile != aaFile
                                    || player == null) return;
                            try {
                                AudioDeviceInfo r2 =
                                        player.getRoutedDevice();
                                if (r2 != null
                                        && !nameMatchesEarbuds(
                                                r2.getProductName())) {
                                    log("lane: " + pinFail + " "
                                            + "— late route to "
                                            + r2.getProductName()
                                            + "; aborting to park");
                                    laneAbortPlayback();
                                }
                            } catch (Exception ignored) {}
                        }, 700);
                    }
                } catch (Exception ignored) {}
            }
            currentAudioFile = f;
            // v1.55: adopt the row id that rode with this file, when
            // one did (the priority path sets it explicitly beforehand
            // and its files never enter the map).
            String mappedSid = audioFileServerIds.remove(f.getAbsolutePath());
            if (mappedSid != null) {
                currentPlayServerId = mappedSid;
                // v1.83: a queue play is never the critical class —
                // criticals take the priority path and set the
                // witness themselves before this call.
                currentPlayCritical = false;
            }
            // v1.58: adopt the segment group the same way (the
            // priority path sets it explicitly beforehand, mirroring
            // the row id).
            String mappedGroup = audioFileSegmentGroups.remove(
                    f.getAbsolutePath());
            if (mappedGroup != null) currentPlaySegmentGroup = mappedGroup;
            // v1.79: a queue row just started on the MediaPlayer —
            // arm the almost-done watch (self-skips for id-less files).
            armAlmostDoneWatch();
            broadcastPlaybackState();
            return true;
        } catch (Exception e) {
            log("play failed: " + e);
            onPlaybackDone();
            return true;
        }
    }

    /**
     * v1.55 (Phase 5, §8): report the just-finished queue row to the
     * brain so its per-sentence fraction accounting advances. Called
     * FIRST in both completion handlers, before any interrupt/resume
     * unwinding can overwrite currentPlayServerId. Fire-and-forget on
     * a worker thread; a lost report only leaves the fraction
     * conservative (the brain never moves a count backwards).
     */
    private void reportPlaybackProgress() {
        String rowId = currentPlayServerId;
        currentPlayServerId = null;
        // v1.58: the finished item's group is captured by the caller
        // BEFORE this runs; clear it here so a stale group can never
        // leak into an unrelated item that sets no group of its own.
        currentPlaySegmentGroup = null;
        if (rowId == null || rowId.isEmpty()) return;
        log("progress: reporting row played: " + rowId);
        new Thread(new PlaybackProgressTask(rowId)).start();
    }

    /**
     * v1.82 (Nick, 2026-11-02): delete-after-full-play. Called from
     * the two completion handlers at the exact points where the
     * played report fires — a message that played through in full
     * loses its local footprint: the MessageHistory entry and its
     * cached audio file(s), including the replay cache. Matched by
     * queue row id; history replays complete with no live row id, so
     * they match by the played file's path instead. Partial plays,
     * pauses, and stops never call this. Locked entries are exempt
     * (MessageHistory enforces it — a lock is Nick's explicit keep).
     */
    private void cleanupFullyPlayedMessage(String rowId, String audioPath) {
        if ((rowId == null || rowId.isEmpty() || "priority".equals(rowId))
                && audioPath == null) {
            return;
        }
        String removed = MessageHistory.deleteIfFullyPlayed(rowId, audioPath);
        if (removed != null) {
            log("cleanup: deleted fully-played message row id=" + removed);
        }
    }

    /**
     * v1.56 (2026-10-29): report a user STOP to the brain
     * (delete-once-heard, Nick 2026-10-29). A stop is terminal — the
     * stopped message never resumes — so the brain deletes the owning
     * item's queue rows + stored audio. Captures the playing/paused
     * row AND any stacked interrupted rows (a stop discards the stack
     * too) BEFORE stopCurrentPlayback() clears them. A PAUSE never
     * calls this: a paused message keeps its rows — it either resumes
     * (and deletes when it plays out) or its remainder rolls into the
     * next dictation's rework (and deletes when that merge lands).
     * Priority interjections are not server rows — never reported.
     */
    private void reportPlaybackStopped() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (currentPlayServerId != null && !currentPlayServerId.isEmpty()
                && !"priority".equals(currentPlayServerId)) {
            ids.add(currentPlayServerId);
        }
        for (InterruptedState st : interruptStack) {
            if (st.serverId != null && !st.serverId.isEmpty()
                    && !"priority".equals(st.serverId)) {
                ids.add(st.serverId);
            }
        }
        for (String rowId : ids) {
            log("stop: reporting row stopped: " + rowId);
            new Thread(new PlaybackProgressTask(rowId, "playback_stopped")).start();
        }
    }

    /**
     * v1.79 (Nick, 2026-11-02): fire the almost-done report for a
     * row, once per row ever. Both playback paths funnel here. The
     * callers check at fire time that the row is still the playing
     * one and still unreported: the played report consumes
     * currentPlayServerId (so a finished row reads null hereafter)
     * and a stop releases the player / halts TTS (so the watches
     * below die before they could fire) — a stale watch can never
     * report almost-done for a row that already played or stopped.
     * Priority interjections are not server rows — never reported.
     */
    private void reportAlmostDone(String rowId) {
        if (rowId == null || rowId.isEmpty()
                || "priority".equals(rowId)) return;
        synchronized (almostDoneFiredRows) {
            if (almostDoneFiredRows.contains(rowId)) return;
            almostDoneFiredRows.add(rowId);
            while (almostDoneFiredRows.size() > 20) {
                almostDoneFiredRows.removeFirst();
            }
        }
        log("progress: reporting row almost done: " + rowId);
        new Thread(new PlaybackProgressTask(rowId,
                "playback_almost_done")).start();
    }

    /** v1.79: arm the MediaPlayer almost-done watch. Called when a
     *  queue row starts playing (playFileNow success, MP3 resume).
     *  The tick re-reads the CURRENT row id every second, so a
     *  priority interrupt / resume retargets it naturally; it ends
     *  by itself when playback stops or completes (player released),
     *  when the tracker goes null (row reported played), or when
     *  the row already fired. While paused it idles without firing —
     *  position isn't moving, so "almost done" isn't either. */
    private void armAlmostDoneWatch() {
        cancelAlmostDoneWatch();
        almostDoneWatchTask = () -> almostDoneWatchTick();
        handler.postDelayed(almostDoneWatchTask,
                ALMOST_DONE_TICK_MS / 2);
        // v1.80: prove the arming in the field log, with the row
        // and the path that started it (the priority path silently
        // never fired in v1.79 — see playPriorityMp3). Id-less
        // plays and the "priority" marker are not server rows and
        // are never reported, so they get no line.
        String armRowId = currentPlayServerId;
        if (armRowId != null && !armRowId.isEmpty()
                && !"priority".equals(armRowId)) {
            log("progress: almost-done watch armed for row "
                    + armRowId
                    + (priorityMode ? " (priority path)"
                            : " (queue path)"));
        }
    }

    private void cancelAlmostDoneWatch() {
        if (almostDoneWatchTask != null) {
            handler.removeCallbacks(almostDoneWatchTask);
            almostDoneWatchTask = null;
        }
    }

    private void almostDoneWatchTick() {
        almostDoneWatchTask = null;
        String rowId = currentPlayServerId;
        if (player == null || !playing || rowId == null
                || rowId.isEmpty() || "priority".equals(rowId)) {
            return;
        }
        synchronized (almostDoneFiredRows) {
            if (almostDoneFiredRows.contains(rowId)) return;
        }
        if (!playbackPaused && safeIsPlaying()) {
            try {
                int dur = player.getDuration();
                if (dur > 0
                        && dur - player.getCurrentPosition()
                                <= ALMOST_DONE_LEAD_MS) {
                    reportAlmostDone(rowId);
                    return;
                }
            } catch (Exception ignored) {}
        }
        almostDoneWatchTask = () -> almostDoneWatchTick();
        handler.postDelayed(almostDoneWatchTask, ALMOST_DONE_TICK_MS);
    }

    /** v1.79: arm the on-device TTS almost-done one-shot for the
     *  utterance that just started. No real duration exists, so the
     *  fire time is estimated from the text being spoken (on a
     *  resume, the remainder — the right thing to estimate) at
     *  ~14 chars/sec. A row shorter than the lead fires immediately,
     *  which is correct: the next segment should come at once. At
     *  fire time the utterance must still be speaking THIS row. */
    private void armAlmostDoneTts(String spokenText) {
        cancelAlmostDoneTts();
        final String rowId = currentPlayServerId;
        if (rowId == null || rowId.isEmpty()
                || "priority".equals(rowId)) return;
        synchronized (almostDoneFiredRows) {
            if (almostDoneFiredRows.contains(rowId)) return;
        }
        long estMs = spokenText != null
                ? (long) (spokenText.length() * 1000.0 / 14.0) : 0;
        long delay = Math.max(0, estMs - ALMOST_DONE_LEAD_MS);
        almostDoneTtsTask = () -> {
            almostDoneTtsTask = null;
            if (ttsSpeaking && rowId.equals(currentPlayServerId)) {
                reportAlmostDone(rowId);
            }
        };
        handler.postDelayed(almostDoneTtsTask, delay);
        log("progress: almost-done armed for row " + rowId
                + " in ~" + delay + "ms (est " + estMs + "ms)");
    }

    private void cancelAlmostDoneTts() {
        if (almostDoneTtsTask != null) {
            handler.removeCallbacks(almostDoneTtsTask);
            almostDoneTtsTask = null;
        }
    }

    // NOTE (v1.58, 2026-10-29): there is deliberately NO pause wire.
    // Nick's revised rule (08:41 EDT, superseding the 08:40 draft that
    // briefly shipped in v1.57): a paused message is HELD — no report,
    // no deletion at pause or at dictation. When the next dictation
    // arrives, the brain's rework rolls the paused item's unplayed
    // remainder into the successor message, and the QUEUING of that
    // successor is what deletes the paused original's rows + audio
    // (server-side, in the merge path). Resume before any dictation =
    // normal path (plays out, deletes on heard). Nothing for the app
    // to report in either case.

    /** Called by PlayerCompletionListener / PlayerErrorListener. */
    public void onPlaybackDone() {
        handler.post(new PlaybackDoneTask(this));
    }

    /** Called by PlaybackDoneTask on main thread after playback finishes or fails. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    public void onPlaybackDoneNow() {
        // v1.58: capture the finished item's segment group BEFORE the
        // progress report clears the tracker.
        final String finishedGroup = currentPlaySegmentGroup;
        // v1.82: same for the finished row id + file — the delete-
        // after-full-play cleanup below keys on them.
        final String finishedRowId = currentPlayServerId;
        final File finishedFile = currentAudioFile;
        reportPlaybackProgress(); // v1.55: before any unwinding below
        abandonAudioFocus();
        // v1.63: nothing to restore — no volume was written on the
        // way in (the v1.17 boost restore and the v1.61 deep-duck
        // release that lived here are both deleted).
        releasePlayer();
        // v1.82 (Nick, 2026-11-02): delete-after-full-play — this row
        // played through in full, so its local footprint goes now
        // (history entry + cached/replay audio). Partial plays,
        // pauses and stops never reach this point.
        cleanupFullyPlayedMessage(finishedRowId,
                finishedFile != null ? finishedFile.getAbsolutePath() : null);
        playing = false;
        isPlayingAudio = false;
        // Nick 2026-10-18 v1.7: put the speakerphone back the way we found
        // it, so Nick's next real phone call doesn't start on speaker.
        // Nick 2026-10-18 v1.11: no-op when Bluetooth handled the routing.
        restoreAudioRouting();
        log("playback done (echo guard off)");
        if (currentAudioFile != null) {
            // v1.82: the cleanup above already removed the finished
            // message's history entry (and its cached file with it) —
            // a file still referenced here belongs to a LOCKED entry
            // (the lock exempts it from delete-after-full-play) and is
            // kept for replay. Unreferenced one-shot files are cleaned
            // up; expired entries' MP3s go via purgeExpired.
            String path = currentAudioFile.getAbsolutePath();
            if (MessageHistory.isAudioReferenced(path)) {
                log("playback: keeping replay-cached file");
            } else {
                //noinspection ResultOfMethodCallIgnored
                currentAudioFile.delete();
            }
            currentAudioFile = null;
        }
        // Nick 2026-10-29 v1.58: the advance below is the pre-v1.58
        // advance verbatim, routed through the segment-gap gate — a
        // same-group successor starts after SEGMENT_GAP_MS, anything
        // else starts immediately. The report above already fired, so
        // delete-on-heard never waits on the gap.
        advanceAfterSegmentGap(finishedGroup, nextGroupAudioFirst(), () -> {
            if (!playing && !ttsSpeaking) {
                // Nick 2026-10-23 v1.42: priority FIFO — a priority item that just
                // finished yields to waiting priority items first. Priority items
                // queue behind each other and never interrupt each other;
                // interrupted output only resumes once no priority items remain.
                // v1.81: the drain is NOT gated on priorityMode anymore.
                // A row parked by the orphan guard (see playFileNow) can
                // be waiting while the flag is already down — the
                // superseding play may be an ordinary queue item whose
                // completion clears priorityMode in the unwind below —
                // and a flag-gated drain would leave the parked row
                // stranded with no future trigger. playNextWaitingPriority
                // re-checks the polite/lane gates itself, so draining
                // whenever anything waits is safe.
                if (!waitingPriority.isEmpty()) {
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
                    priorityInterruptDepth = 0;
                    resumeNextInterrupted();
                    return;
                }
                if (priorityMode) {
                    priorityMode = false;
                    priorityInterruptDepth = 0;
                    log("priority: flow done, resuming normal queues");
                }
                pumpAudioQueue();
                pumpTtsQueue();
            }
        });
    }

    /**
     * Nick 2026-10-29 v1.58: the ruled 3-second gap between segments
     * of ONE message. When the item that just finished and the next
     * item up share a non-null segment_group, the advance runs after
     * SEGMENT_GAP_MS; standalone or different-group successors advance
     * immediately, exactly as before. The gap never delays a chain
     * start — it only ever sits BETWEEN two segments.
     *
     * Preemption: the delayed advance is a handler runnable guarded by
     * a generation counter. Pause / stop / skip-to-newest / a priority
     * arrival cancel it outright (cancelPendingSegmentAdvance), the
     * and if anything has
     * already started playing by the time it fires the advance drops
     * itself — the pumps re-check playing/ttsSpeaking, so a late fire
     * can never double-start. While the gap runs, playback state reads
     * idle (nothing is playing), so the existing interrupt / pause /
     * stop semantics all win over the pending start.
     */
    private void advanceAfterSegmentGap(String finishedGroup,
            String nextGroup, Runnable advance) {
        if (finishedGroup == null || nextGroup == null
                || !finishedGroup.equals(nextGroup)) {
            advance.run();
            return;
        }
        final int gen = ++segmentAdvanceGen;
        log("segments: holding " + SEGMENT_GAP_MS
                + "ms gap before next segment of " + finishedGroup);
        final Runnable[] self = new Runnable[1];
        Runnable delayed = () -> {
            if (segmentAdvanceGen != gen) return; // cancelled/superseded
            // v1.83: the mic hold defers the start itself — the
            // advance re-checks every 500 ms and fires the moment
            // the mic frees, so the exact advance logic (not a
            // reconstruction) runs then. Generation discipline is
            // untouched: pause / stop / priority still cancel it
            // outright via cancelPendingSegmentAdvance.
            if (micHold) {
                logMicHeld("segment advance");
                handler.postDelayed(self[0], 500);
                return;
            }
            pendingSegmentAdvance = null;
            advance.run();
        };
        self[0] = delayed;
        pendingSegmentAdvance = delayed;
        handler.postDelayed(delayed, SEGMENT_GAP_MS);
    }

    /** Drop a pending post-gap advance (pause/stop/skip/priority win). */
    private void cancelPendingSegmentAdvance() {
        if (pendingSegmentAdvance != null) {
            handler.removeCallbacks(pendingSegmentAdvance);
            pendingSegmentAdvance = null;
        }
        segmentAdvanceGen++;
    }

    /** Group of the next item up, in onPlaybackDoneNow's branch order:
     *  priority FIFO (when the priority branch would run) -> interrupt
     *  stack -> audio queue -> TTS queue. Null covers both "nothing
     *  queued" and "next item is a standalone message" — either way no
     *  gap applies, which is all the caller needs. */
    private String nextGroupAudioFirst() {
        if (!waitingPriority.isEmpty()) {
            // v1.81: not gated on priorityMode — the advance drains
            // the waiting FIFO unconditionally now (see
            // onPlaybackDoneNow), so the gap decision peeks it the
            // same way.
            return waitingPriority.peek().segmentGroup;
        }
        if (!interruptStack.isEmpty()) {
            return interruptStack.peek().segmentGroup;
        }
        synchronized (audioQueue) {
            File head = audioQueue.peek();
            if (head != null) {
                return audioFileSegmentGroups.get(head.getAbsolutePath());
            }
        }
        synchronized (ttsQueue) {
            if (!ttsQueue.isEmpty()) return ttsSegmentGroupQueue.peek();
        }
        return null;
    }

    /** Same peek in onTtsDone's branch order (TTS before audio). */
    private String nextGroupTtsFirst() {
        if (!waitingPriority.isEmpty()) {
            // v1.81: not gated on priorityMode — the advance drains
            // the waiting FIFO unconditionally now (see
            // onPlaybackDoneNow), so the gap decision peeks it the
            // same way.
            return waitingPriority.peek().segmentGroup;
        }
        if (!interruptStack.isEmpty()) {
            return interruptStack.peek().segmentGroup;
        }
        synchronized (ttsQueue) {
            if (!ttsQueue.isEmpty()) return ttsSegmentGroupQueue.peek();
        }
        synchronized (audioQueue) {
            File head = audioQueue.peek();
            if (head != null) {
                return audioFileSegmentGroups.get(head.getAbsolutePath());
            }
        }
        return null;
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
     *
     * v1.79 (Nick, 2026-11-02): the text here is ONE queue row and is
     * synthesized as ONE request (see synthesizeOnce) — never chunk
     * row text here; Selena segments on her side.
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
        enqueueTts(text, null);
    }

    /** v1.55: queue a text WITH its queue row id on the parallel queue
     *  (same lock as ttsQueue; pumpTtsQueue polls both together). */
    private void enqueueTts(String text, String serverId) {
        enqueueTts(text, serverId, null);
    }

    /** v1.58: queue a text WITH its row id AND segment group on the
     *  parallel queues (same lock; pumpTtsQueue polls all together). */
    private void enqueueTts(String text, String serverId,
            String segmentGroup) {
        synchronized (ttsQueue) {
            ttsQueue.add(text);
            ttsServerIdQueue.add(serverId);
            ttsSegmentGroupQueue.add(segmentGroup);
        }
        pumpTtsQueue();
    }

    /** Runs on main thread: speak the next queued text if nothing is playing. */
    private void pumpTtsQueue() {
        refreshMicHold("pump"); // v1.83: fresh config count at start time
        // Nick 2026-10-22 v1.27: ttsPaused blocks the pump — a paused
        // message holds its place until resume.
        if (ttsSpeaking || playing || ttsPaused) return;
        // Nick 2026-10-30 v1.73: earbuds-only lane armed but no
        // earbuds — the text stays queued, unspoken and unreported.
        // v1.75: TTS also holds under Android Auto (laneTtsBlocked)
        // — device TTS cannot be pinned to the earbuds.
        if (laneTtsBlocked()) {
            logLaneHeld("TTS playback");
            return;
        }
        // Nick 2026-11-02 v1.83: mic hold — the text stays queued,
        // unspoken, until the microphone frees.
        if (micHold) {
            logMicHeld("TTS playback");
            return;
        }
        String text;
        String rowId;
        String segGroup;
        synchronized (ttsQueue) {
            text = ttsQueue.poll();
            rowId = ttsServerIdQueue.poll();
            segGroup = ttsSegmentGroupQueue.poll();
        }
        if (text == null) return;
        ttsSpeaking = true;
        pendingTtsServerId = rowId;
        pendingTtsSegmentGroup = segGroup;
        speakOnDevice(text, false);
    }

    /** Runs on main thread: speak text with Android's built-in TTS.
     *  v1.79 (Nick, 2026-11-02): the text is one queue row, spoken as
     *  one utterance — never split it here; Selena segments. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    private void speakOnDevice(String text, boolean isResume) {
        // Nick 2026-10-30 v1.73: backstop (see playFileNow) — never
        // speak out loud while the lane is armed without earbuds.
        // Unwind WITHOUT a progress report: nothing was heard.
        // v1.75: the TTS gate also holds under Android Auto — TTS
        // cannot be pinned to the earbuds, so the spoken form waits
        // and the text stays written in the window.
        if (laneTtsBlocked()) {
            int ttsVerdict = evaluateLane("tts-speak");
            if (ttsVerdict == LANE_CASE_B_AA
                    || ttsVerdict == LANE_CASE_B_PIN) {
                log("lane: "
                        + (ttsVerdict == LANE_CASE_B_AA
                                ? "AA split" : "media-pin split")
                        + " — TTS cannot be pinned to the "
                        + "earbuds; speech held (text stays written)");
            } else {
                log("lane: armed-held — direct TTS attempt held off");
            }
            ttsSpeaking = false;
            isPlayingAudio = false;
            setPlayingAudio(false);
            pendingTtsServerId = null;
            pendingTtsSegmentGroup = null;
            currentPlayServerId = null;
            currentPlaySegmentGroup = null;
            broadcastPlaybackState();
            return;
        }
        // v1.83: mic-hold backstop (the lane twin above) — unwind
        // exactly like it: nothing was heard, no report.
        if (micHold && !micCriticalBypass) {
            log("michold: held — direct TTS attempt held off");
            ttsSpeaking = false;
            isPlayingAudio = false;
            setPlayingAudio(false);
            pendingTtsServerId = null;
            pendingTtsSegmentGroup = null;
            currentPlayServerId = null;
            currentPlaySegmentGroup = null;
            broadcastPlaybackState();
            return;
        }
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
            // v1.55: adopt the queued row id for this utterance (the
            // priority path sets currentPlayServerId itself and leaves
            // pending null, so it is never clobbered here).
            if (pendingTtsServerId != null) {
                currentPlayServerId = pendingTtsServerId;
                currentPlayCritical = false; // v1.83: queue play, see playFileNow
                pendingTtsServerId = null;
            }
            // v1.58: adopt the queued segment group the same way.
            if (pendingTtsSegmentGroup != null) {
                currentPlaySegmentGroup = pendingTtsSegmentGroup;
                pendingTtsSegmentGroup = null;
            }
        }
        String preview = text.length() > 30 ? text.substring(0, 30) + "…" : text;
        log("tts: on-device speak '" + preview + "'");
        // Same routing as MP3 playback: loudspeaker at Nick's real
        // device volume (v1.10: no override, respect his setting —
        // made absolute in v1.63: nothing here writes the media
        // volume; the v1.17 boost call that lived in this spot is
        // deleted).
        // Nick 2026-10-30 v1.73: earbuds-only lane — when engaged,
        // the TTS speaks over the earbuds' headset channel with
        // voice-communication attributes and NO media focus request.
        // Otherwise the v1.11 routing + focus, unchanged.
        // v1.75: a begin-time PARK (state flipped after the gate —
        // e.g. the comm device was refused) unwinds exactly like
        // the backstop: nothing was heard, no report. AA cannot be
        // pinned for TTS and is treated the same.
        final int laneBegin = laneBeginInternal(true);
        if (laneBegin == LANE_BEGIN_PARK
                || laneBegin == LANE_BEGIN_AA
                || laneBegin == LANE_BEGIN_PIN) {
            log("lane: TTS held at begin — not spoken");
            ttsSpeaking = false;
            isPlayingAudio = false;
            setPlayingAudio(false);
            ttsCurrentText = null;
            ttsResumeAtChar = 0;
            pendingTtsServerId = null;
            pendingTtsSegmentGroup = null;
            currentPlayServerId = null;
            currentPlaySegmentGroup = null;
            broadcastPlaybackState();
            return;
        }
        if (laneBegin == LANE_BEGIN_HEADSET
                || laneBegin == LANE_BEGIN_HEADSET_FALLBACK) {
            try {
                tts.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                laneTtsAttrsSet = true;
            } catch (Exception e) {
                log("lane: tts attributes failed: " + e);
            }
        } else {
            // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win
            // over the speakerphone when connected, same as MP3.
            routeAudioForPlayback();
            log("tts: stream=MUSIC");
            requestAudioFocus();
        }
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
            // Nick 2026-10-23 v1.48: ensureMaxMusic DISABLED (volume raiser).
            int rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params,
                    utteranceId);
            if (rc != TextToSpeech.SUCCESS) {
                log("tts: speak() returned " + rc);
                onTtsDone();
            } else {
                // v1.79: arm the estimate-based almost-done report
                // for this utterance's queue row.
                armAlmostDoneTts(text);
            }
        } catch (Exception e) {
            log("tts: speak failed: " + e);
            onTtsDone();
        }
    }

    /** Runs on main thread after on-device TTS finishes or fails. */
    @SuppressWarnings("deprecation") // setSpeakerphoneOn, see playFileNow
    private void onTtsDone() {
        // v1.58: capture the finished item's segment group BEFORE the
        // progress report clears the tracker.
        final String finishedGroup = currentPlaySegmentGroup;
        // v1.82: same for the finished row id (delete-after-full-play).
        final String finishedRowId = currentPlayServerId;
        reportPlaybackProgress(); // v1.55: before any unwinding below
        // v1.82 (Nick, 2026-11-02): delete-after-full-play — see
        // onPlaybackDoneNow. TTS plays carry no file; the entry is
        // matched by row id.
        cleanupFullyPlayedMessage(finishedRowId, null);
        abandonAudioFocus();
        // v1.63: no boost to undo — nothing wrote the media volume.
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
        // Nick 2026-10-29 v1.58: same segment-gap gate as
        // onPlaybackDoneNow; the advance below is the pre-v1.58
        // advance verbatim (TTS pump first on this path).
        advanceAfterSegmentGap(finishedGroup, nextGroupTtsFirst(), () -> {
            if (!playing && !ttsSpeaking) {
                // Nick 2026-10-23 v1.42: priority FIFO — a priority item that just
                // finished yields to waiting priority items first (same rule as
                // onPlaybackDoneNow).
                // v1.81: drain not gated on priorityMode (see
                // onPlaybackDoneNow) — an orphan-parked row must drain
                // here too.
                if (!waitingPriority.isEmpty()) {
                    playNextWaitingPriority();
                    return;
                }
                // Nick 2026-10-23 v1.40: priority interrupt unwinding (same as
                // onPlaybackDoneNow).
                if (!interruptStack.isEmpty()) {
                    log("priority: TTS completion, unwinding interrupt stack ("
                            + interruptStack.size() + " left)");
                    priorityMode = false;
                    priorityInterruptDepth = 0;
                    resumeNextInterrupted();
                    return;
                }
                if (priorityMode) {
                    priorityMode = false;
                    priorityInterruptDepth = 0;
                    log("priority: flow done, resuming normal queues");
                }
                pumpTtsQueue();
                pumpAudioQueue();
            }
        });
    }

    private void releasePlayer() {
        // v1.63: release her gain enhancer with the player so it
        // never lingers on a dead audio session.
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

    // ------------------------------------------------------------ v1.83 mic hold
    // Nick 2026-11-02 v1.83: MIC-ACTIVITY AUTO-PAUSE (his design).
    // When ANY other app uses the phone's microphone — his case:
    // dictating in the Muse app — her voice would be picked up
    // into that app's dictation, so playback pauses and new starts
    // hold until the mic frees; ~2 s of continuous mic-free later,
    // a pause THIS hold caused resumes on its own. Detection is
    // the count of active recording configurations (post-v1.82
    // this app never records, so every configuration is external;
    // AOSP anonymizes the entries for a regular app — the count is
    // device-wide, which is all this needs): an AudioRecordingCallback
    // (API 29+, instant) is primary, and a getActiveRecordingConfigurations()
    // re-read rides every poll cycle and every start gateway as the
    // fallback. No permission is required for either. The hold is a
    // sibling of the earbuds-only lane hold and composes with it:
    // the two flags are independent, and a start or resume needs
    // BOTH clear. EXCEPTION, standing rule: serious-critical rows
    // (priority "critical") break through — they are never paused
    // by the hold and never held by it.
    private boolean micHold = false;
    // True only when the CURRENT pause was caused by this hold.
    // Mic-free resumes only its own pauses; a user's pause is never
    // auto-resumed, and a manual pause during a hold clears this
    // (the pause becomes his — see pausePlayback()).
    private boolean micPausedByUs = false;
    // Set only for the duration of a serious-critical start call,
    // so the playFileNow / speakOnDevice backstops let it through.
    private boolean micCriticalBypass = false;
    // Last seen recording-config count (-1 = never read). Logged
    // on every change — the field log proves which detection
    // mechanism actually fires on the A17.
    private int micConfigCount = -1;
    private long micHeldLogAtMs = 0;
    private Runnable micResumeTask = null;
    private MicRecordingCallback micRecordingCallback = null;
    private static final long MIC_RESUME_DELAY_MS = 2000;

    /** Register the recording-config callback (API 29+). The
     *  config-count fallback in refreshMicHold covers older levels
     *  and any event the callback somehow misses. */
    private void startMicHoldWatch() {
        if (audioManager != null
                && Build.VERSION.SDK_INT >= 29
                && micRecordingCallback == null) {
            micRecordingCallback = new MicRecordingCallback(this);
            try {
                audioManager.registerAudioRecordingCallback(
                        micRecordingCallback, handler);
                log("michold: recording callback registered");
            } catch (Exception e) {
                log("michold: callback register failed: "
                        + e.getMessage());
                micRecordingCallback = null;
            }
        }
        refreshMicHold("startup");
    }

    private void stopMicHoldWatch() {
        if (audioManager != null && micRecordingCallback != null) {
            try {
                audioManager.unregisterAudioRecordingCallback(
                        micRecordingCallback);
            } catch (Exception ignored) {}
            micRecordingCallback = null;
        }
        cancelMicResume();
    }

    /** Main-thread entry from MicRecordingCallback. */
    public void onMicRecordingConfigsChanged(int count) {
        applyMicConfigCount(count, "callback");
    }

    /** Active recording configurations right now (see the section
     *  comment: every one of them is another app's). */
    private int currentRecordingConfigCount() {
        if (audioManager == null) return 0;
        try {
            List<AudioRecordingConfiguration> configs =
                    audioManager.getActiveRecordingConfigurations();
            return configs == null ? 0 : configs.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Re-read the config count and drive the hold state. Called
     *  on every poll cycle, at every start gateway, and (through
     *  the callback) the instant the set changes. Main thread. */
    private void refreshMicHold(String source) {
        applyMicConfigCount(currentRecordingConfigCount(), source);
    }

    private void applyMicConfigCount(int count, String source) {
        if (count != micConfigCount) {
            micConfigCount = count;
            log("michold: recording configs=" + count
                    + " (" + source + ")");
        }
        if (count > 0) {
            onMicActive();
        } else {
            onMicFree();
        }
    }

    private void onMicActive() {
        cancelMicResume();
        if (!micHold) {
            micHold = true;
            log("michold: mic active — pausing/holding"
                    + ((safeIsPlaying() || ttsSpeaking)
                            ? "" : " (nothing playing — starts held)"));
        }
        // Pause live playback with the existing machinery (the
        // position is kept). Never claim a pause that is already
        // the user's, and never pause a serious-critical play.
        if ((safeIsPlaying() || ttsSpeaking)
                && !playbackPaused && !ttsPaused
                && !currentPlayCritical) {
            micPausedByUs = true;
            doPausePlayback();
        }
    }

    private void onMicFree() {
        if (!micHold || micResumeTask != null) return;
        // Anti-flap: the resume lands only after ~2 s of CONTINUOUS
        // mic-free — onMicActive cancels this task if the mic is
        // grabbed again inside the window.
        micResumeTask = () -> {
            micResumeTask = null;
            if (micHold && micConfigCount == 0) finishMicHold();
        };
        handler.postDelayed(micResumeTask, MIC_RESUME_DELAY_MS);
    }

    private void cancelMicResume() {
        if (micResumeTask != null) {
            handler.removeCallbacks(micResumeTask);
            micResumeTask = null;
        }
    }

    private void finishMicHold() {
        micHold = false;
        boolean wasMicPaused = micPausedByUs;
        micPausedByUs = false;
        if (playbackPaused || ttsPaused) {
            if (!wasMicPaused) {
                // His pause, not ours — mic-free never resumes it.
                log("michold: mic free — staying paused (user pause)");
                return;
            }
            log("michold: mic free — resuming");
            // resumePlayback re-checks the lane gate itself: if the
            // earbuds lane also blocks, the item stays paused under
            // the lane's authority — the two holds compose.
            resumePlayback();
        } else {
            log("michold: mic free — resuming");
        }
        // Restart whatever the hold kept from starting. Every
        // starter re-checks its own gates, and a deferred segment
        // advance fires on its own re-check too — double-starts are
        // impossible because each path re-tests live state.
        if (!playing && !ttsSpeaking && !playbackPaused && !ttsPaused) {
            if (!waitingPriority.isEmpty()) {
                playNextWaitingPriority();
            } else if (!interruptStack.isEmpty()) {
                resumeNextInterrupted();
            } else {
                pumpAudioQueue();
                pumpTtsQueue();
            }
        }
    }

    /** Throttled held-state log so the gates can call it freely
     *  (the logLaneHeld pattern). */
    private void logMicHeld(String what) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - micHeldLogAtMs < 60_000) return;
        micHeldLogAtMs = now;
        log("michold: held — " + what
                + " waiting for the microphone to free up");
    }

    private void pausePlayback() {
        // v1.83: a manual pause during a mic hold converts the
        // hold's pause into the USER's pause — mic-free must not
        // auto-resume a pause he claimed for himself.
        if (micPausedByUs) {
            micPausedByUs = false;
            log("michold: user pause during mic hold — "
                    + "auto-resume cancelled");
        }
        doPausePlayback();
    }

    /** The pause machinery itself (the v1.28 body). Callers: the
     *  user's pause controls via pausePlayback(), and the mic hold
     *  — which must not trip the user-pause conversion above. */
    private void doPausePlayback() {
        // v1.58: pausing during a segment gap cancels the pending
        // start — the successor stays queued and the pumps start it
        // later with no gap owed.
        cancelPendingSegmentAdvance();
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
        } else {
            log("pause: nothing was playing");
            toast("Nothing playing to pause");
        }
        broadcastPlaybackState();
    }

    private void resumePlayback() {
        log("resume: requested ttsPaused=" + ttsPaused
                + " mp3Paused=" + playbackPaused);
        // Nick 2026-10-30 v1.73: a paused item must not resume out
        // loud while the earbuds-only lane is armed without earbuds
        // (the paused player would re-route to the speaker).
        if (laneBlocked() && (playbackPaused || ttsPaused)) {
            log("lane: armed-held — resume held off (no earbuds)");
            toast("Earbuds-only is on — connect your earbuds to listen");
            return;
        }
        // Nick 2026-11-02 v1.83: same for the mic hold — a paused
        // item must not resume into an active dictation. The
        // mic-free timer performs the resume when the mic frees
        // (finishMicHold clears the hold before calling in here).
        if (micHold && (playbackPaused || ttsPaused)) {
            log("michold: resume held — mic still active");
            toast("Microphone in use — resumes when it's free");
            return;
        }
        boolean didResume = false;
        // MP3 path (Aria/quality mode, replays).
        if (player != null && playbackPaused) {
            try {
                player.start();
                playbackPaused = false;
                didResume = true;
                // v1.80: a plain pause never cleared the playing
                // flag, but a priority interrupt of the paused item
                // did (interruptCurrentPlayback) — restore it so
                // the re-armed watch below can actually tick.
                playing = true;
                // v1.79: re-arm the almost-done watch on resume
                // (insurance — the tick normally idles through pause).
                armAlmostDoneWatch();
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
        // Nick 2026-10-29 v1.58: a stop also cancels a pending
        // post-gap segment start and drops the group tracker.
        cancelPendingSegmentAdvance();
        currentPlaySegmentGroup = null;
        // Nick 2026-10-28 v1.52: a stop from anywhere (screen button,
        // STOP_ALL intent) also cancels an earbud press's pending
        // double-tap-window resume.
        cancelPendingResume();
        // Nick 2026-10-23 v1.40: stop cancels any priority flow too.
        priorityMode = false;
        // v1.83: a stop also retires any mic-hold pause claim —
        // nothing remains for mic-free to resume.
        micPausedByUs = false;
        currentPlayCritical = false;
        priorityInterruptDepth = 0;
        currentPriorityPlayText = null;
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
        // v1.76: a user stop ends the lane session immediately —
        // the idle hold-over is for queue flow, not for a stop.
        laneEnd();
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
        // v1.58: an interruption preempts any pending post-gap start.
        cancelPendingSegmentAdvance();
        InterruptedState st = new InterruptedState();
        // v1.55: the interrupted item keeps its row id so its resume
        // restores it (its completion report belongs to this row).
        st.serverId = currentPlayServerId;
        // v1.58: same for its segment group.
        st.segmentGroup = currentPlaySegmentGroup;
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
            final String serverId, final String segmentGroup,
            final boolean critical) {
        log("priority: playing high-priority MP3"
                + (serverId != null ? " id=" + serverId : ""));
        priorityMode = true;
        interruptCurrentPlayback();
        // v1.83: the interrupt stack now owns any paused state —
        // retire the mic hold's resume claim on it.
        micPausedByUs = false;
        markSpokenLocked(text);
        // MessageHistory entry so the card exists for replay.
        MessageHistory.add(text, f, serverId);
        broadcastMessage(text);
        toast("Priority message");
        // v1.55: set AFTER the interrupt snapshot so the interrupted
        // item's saved state keeps ITS row id, not this one's.
        currentPlayServerId = serverId;
        // v1.58: same for the segment group.
        currentPlaySegmentGroup = segmentGroup;
        // v1.83: critical witness for the orphan guard + mic hold.
        currentPlayCritical = critical;
        // v1.80: raise the playing flag exactly as the queue pump
        // (playNext) does before playFileNow. interruptCurrentPlayback
        // cleared it and playFileNow never sets it, so on this path
        // the v1.79 almost-done watch died at its first tick
        // (!playing) and high-priority rows never reported
        // almost-done — their next segment rode the completion
        // backstop instead (field, 2026-11-02).
        playing = true;
        // v1.81: the row's text rides along so the orphan guard in
        // playFileNow can park this play complete if a newer start
        // supersedes it mid-play.
        currentPriorityPlayText = text;
        // v1.83: a serious-critical row plays through the mic
        // hold — the bypass covers the playFileNow start gate only.
        micCriticalBypass = critical;
        boolean started = playFileNow(f);
        micCriticalBypass = false;
        if (!started) {
            // v1.75: parked at the lane gate — back to the head of
            // the waiting queue; the priority chain releases it when
            // the lane can actually play.
            playing = false;
            currentPlayServerId = null;
            currentPlaySegmentGroup = null;
            currentPlayCritical = false;
            currentPriorityPlayText = null;
            priorityMode = false;
            // v1.83: the row keeps its class across the park — a
            // critical parked by the lane is still a critical when
            // the FIFO drains it.
            waitingPriority.addFirst(new WaitingPriority(f, text,
                    serverId, segmentGroup, critical));
            log("lane: priority play parked — returned to the "
                    + "waiting queue (" + waitingPriority.size()
                    + " waiting)");
        } else {
            // v1.81: this play is now live — count it so playFileNow
            // can recognize a live priority play if a newer start
            // supersedes it (see the orphan guard there).
            priorityInterruptDepth++;
        }
    }

    /**
     * Nick 2026-10-23 v1.40: speak a high-priority text immediately via
     * on-device TTS, interrupting whatever is playing. Runs on main thread.
     */
    private void playPriorityTts(final String text, final String serverId,
            final String segmentGroup, final boolean critical) {
        log("priority: speaking high-priority text via on-device TTS");
        priorityMode = true;
        interruptCurrentPlayback();
        // v1.83: see playPriorityMp3 — the stack owns the pause now.
        micPausedByUs = false;
        markSpokenLocked(text);
        broadcastMessage(text);
        MessageHistory.add(text, null, serverId);
        toast("Priority message");
        ttsSpeaking = true;
        // v1.55: after the interrupt snapshot (see playPriorityMp3).
        currentPlayServerId = serverId;
        // v1.58: same for the segment group.
        currentPlaySegmentGroup = segmentGroup;
        // v1.83: critical witness (see playPriorityMp3).
        currentPlayCritical = critical;
        // v1.83: a serious-critical row plays through the mic hold —
        // the bypass covers the speakOnDevice start gate only.
        micCriticalBypass = critical;
        speakOnDevice(text, false);
        micCriticalBypass = false;
    }

    /**
     * Nick 2026-10-23 v1.42: priority FIFO decision point for MP3 items.
     * Runs on the main thread. If a priority item is already playing, the
     * newcomer does NOT interrupt it — it queues behind it and plays
     * right after it finishes.
     */
    private void onPriorityArrivalMp3(final File f, final String text,
            final String serverId, final String segmentGroup,
            final boolean critical) {
        refreshMicHold("arrival"); // v1.83: fresh count before a start
        // v1.58: a priority arrival preempts any pending post-gap
        // start — whether it plays now or parks, it outranks the gap.
        cancelPendingSegmentAdvance();
        if (priorityMode) {
            waitingPriority.offer(new WaitingPriority(f, text, serverId,
                    segmentGroup, critical));
            log("priority: queued behind playing priority ("
                    + waitingPriority.size() + " waiting)"
                    + (serverId != null ? " id=" + serverId : ""));
            toast("Priority message queued");
            return;
        }
        // Nick 2026-11-02 v1.83: mic hold — a priority item parks
        // while another app uses the microphone (her voice would
        // land in his dictation). EXCEPTION: serious-critical rows
        // play anyway — critical alerts break through, always.
        if (micHold && !critical) {
            waitingPriority.offer(new WaitingPriority(f, text, serverId,
                    segmentGroup, critical));
            log("michold: priority MP3 parked — microphone in use ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        // Nick 2026-10-30 v1.73: earbuds-only lane armed but no
        // earbuds — even a priority item parks; it must not burst
        // out of the speaker or the truck radio.
        if (laneBlocked()) {
            waitingPriority.offer(new WaitingPriority(f, text, serverId,
                    segmentGroup));
            log("lane: priority MP3 parked — earbuds-only, no earbuds ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        playPriorityMp3(f, text, serverId, segmentGroup, critical);
    }

    /**
     * Nick 2026-10-23 v1.42: priority FIFO decision point for on-device
     * TTS items. Runs on the main thread. Same rule as
     * {@link #onPriorityArrivalMp3} — queue behind a playing priority
     * item, never interrupt it.
     */
    private void onPriorityArrivalTts(final String text, final String serverId,
            final String segmentGroup, final boolean critical) {
        refreshMicHold("arrival"); // v1.83: fresh count before a start
        // v1.58: priority preempts a pending post-gap start (see
        // onPriorityArrivalMp3).
        cancelPendingSegmentAdvance();
        if (priorityMode) {
            waitingPriority.offer(new WaitingPriority(null, text, serverId,
                    segmentGroup, critical));
            log("priority: queued behind playing priority ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        // Nick 2026-11-02 v1.83: mic hold — park instead of
        // speaking into his dictation (see the MP3 twin).
        // Serious-critical rows are exempt: they play anyway.
        if (micHold && !critical) {
            waitingPriority.offer(new WaitingPriority(null, text, serverId,
                    segmentGroup, critical));
            log("michold: priority TTS parked — microphone in use ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        // Nick 2026-10-30 v1.73: earbuds-only lane armed but no
        // earbuds — park instead of speaking out loud (see MP3 twin).
        // v1.75: also parks under Android Auto (TTS cannot be pinned).
        if (laneTtsBlocked()) {
            waitingPriority.offer(new WaitingPriority(null, text, serverId,
                    segmentGroup));
            log("lane: priority TTS parked — earbuds-only, no earbuds ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        playPriorityTts(text, serverId, segmentGroup, critical);
    }

    /**
     * Nick 2026-10-23 v1.42: play the head of the waiting-priority FIFO.
     * Called from the completion handlers when a priority item finishes
     * and more are waiting. Runs on the main thread; the previous
     * priority item has already released the player, so there is nothing
     * to interrupt — the flow simply continues with priorityMode held.
     */
    private void playNextWaitingPriority() {
        refreshMicHold("drain"); // v1.83: fresh count before a start
        // Nick 2026-10-30 v1.73: earbuds-only lane armed but no
        // earbuds — the queued priority item waits for the earbuds.
        if (laneBlocked()) {
            logLaneHeld("queued priority playback");
            return;
        }
        // Nick 2026-11-02 v1.83: mic hold — the FIFO waits for the
        // microphone, UNLESS its head is a serious-critical row:
        // critical alerts break through, always.
        if (micHold) {
            WaitingPriority head = waitingPriority.peek();
            if (head == null || !head.critical) {
                logMicHeld("queued priority playback");
                return;
            }
        }
        WaitingPriority wp = waitingPriority.poll();
        if (wp == null) return;
        log("priority: playing queued priority item ("
                + waitingPriority.size() + " still waiting)"
                + (wp.serverId != null ? " id=" + wp.serverId : ""));
        if (wp.wasOrphaned) {
            // v1.81: this row was superseded mid-play once already
            // (see the orphan guard in playFileNow) — name it, so
            // the field log proves the replay happened.
            log("priority: replaying superseded priority row id="
                    + wp.serverId);
        }
        if (wp.audioFile != null) {
            playPriorityMp3(wp.audioFile, wp.text, wp.serverId,
                    wp.segmentGroup, wp.critical);
        } else {
            playPriorityTts(wp.text, wp.serverId, wp.segmentGroup,
                    wp.critical);
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
        refreshMicHold("resume"); // v1.83: fresh count before a start
        // Nick 2026-10-30 v1.73: earbuds-only lane armed but no
        // earbuds — the interrupted item stays on the stack,
        // unresumed, until the earbuds return.
        if (laneBlocked()) {
            logLaneHeld("interrupted-item resume");
            return;
        }
        // Nick 2026-11-02 v1.83: mic hold — the interrupted item
        // likewise stays on the stack until the mic frees (the
        // mic-free flow re-enters here).
        if (micHold) {
            logMicHeld("interrupted-item resume");
            return;
        }
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
        // v1.80: same playing-flag fix as playPriorityMp3 — the
        // completion of the priority item cleared the flag
        // (onPlaybackDoneNow), so a watch armed here would die at
        // its first tick and the resumed row would never report
        // almost-done.
        playing = true;
        if (!playFileNow(f)) {
            // v1.75: parked at the lane gate — the item goes back
            // on the interrupt stack, unresumed, until the lane can
            // play again.
            playing = false;
            interruptStack.push(st);
            log("lane: resumed play parked — item returned to the "
                    + "interrupt stack");
            return;
        }
        // v1.55: the resumed item's completion belongs to ITS row.
        currentPlayServerId = st.serverId;
        // v1.58: same for its segment group.
        currentPlaySegmentGroup = st.segmentGroup;
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
        // v1.55: the resumed item's completion belongs to ITS row.
        currentPlayServerId = st.serverId;
        // v1.58: same for its segment group.
        currentPlaySegmentGroup = st.segmentGroup;
        speakOnDevice(rest, true);
    }

    /**
     * Restore the exact paused state after a priority that broke through
     * a manual pause. MP3: rebuild paused at position. TTS: restore
     * paused-at-char. No autoplay.
     */
    private void restorePausedState(InterruptedState st) {
        // v1.55: whichever path restores, its eventual completion
        // reports this item's row.
        currentPlayServerId = st.serverId;
        // v1.58: same for its segment group.
        currentPlaySegmentGroup = st.segmentGroup;
        if (st.audioFile != null && st.audioFile.exists()) {
            // Rebuild the player paused at the saved position.
            if (!playFileNow(st.audioFile)) {
                // v1.75: parked at the lane gate — do not leave the
                // item's row id adopted by a play that never ran.
                currentPlayServerId = null;
                currentPlaySegmentGroup = null;
                return;
            }
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
        // v1.56: report the stop to the brain FIRST — stopCurrentPlayback
        // clears the interrupt stack the report reads (delete-once-heard).
        reportPlaybackStopped();
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
            ttsServerIdQueue.clear(); // v1.55: parallel queue stays in sync
            ttsSegmentGroupQueue.clear(); // v1.58: group queue likewise
        }
        pendingTtsServerId = null;
        pendingTtsSegmentGroup = null; // v1.58
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
        // Nick 2026-10-29 v1.58: skip takes manual control — a pending
        // post-gap start dies here, and the group tracker resets: the
        // newest message never waits on a gap owed to the abandoned one.
        cancelPendingSegmentAdvance();
        currentPlaySegmentGroup = null;
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
            ttsServerIdQueue.clear(); // v1.55: parallel queue stays in sync
            ttsSegmentGroupQueue.clear(); // v1.58: group queue likewise
        }
        pendingTtsServerId = null;
        pendingTtsSegmentGroup = null; // v1.58
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
        // v1.58: the parallel row-id / segment-group queues are
        // rebuilt lock-step with the texts — polling a text without
        // its pair would misalign every following item's report.
        synchronized (ttsQueue) {
            java.util.Queue<String> kept = new java.util.LinkedList<>();
            java.util.Queue<String> keptIds = new java.util.LinkedList<>();
            java.util.Queue<String> keptGroups = new java.util.LinkedList<>();
            String t;
            while ((t = ttsQueue.poll()) != null) {
                String pairId = ttsServerIdQueue.poll();
                String pairGroup = ttsSegmentGroupQueue.poll();
                if (MessageHistory.isTextLocked(t)) {
                    kept.add(t);
                    keptIds.add(pairId);
                    keptGroups.add(pairGroup);
                } else {
                    droppedTts++;
                }
            }
            ttsQueue.addAll(kept);
            ttsServerIdQueue.addAll(keptIds);
            ttsSegmentGroupQueue.addAll(keptGroups);
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
        // Nick 2026-10-28 v1.51: keep the media session's state in sync —
        // Bluetooth devices decide which transport command (play vs pause)
        // to send from the state we publish.
        updateMediaSessionState();
        // Nick 2026-10-28 v1.52: playback transitions are also when the
        // session's active/inactive politeness state can change.
        refreshMediaSessionActive();
    }

    // ------------------------------------------------------------ v1.51 earbud buttons

    /**
     * Nick 2026-10-28 v1.51: create the media session. Framework
     * android.media.session (no androidx in this build). The session
     * must be ACTIVE for Android to route media-button presses to its
     * callback.
     *
     * Nick 2026-10-28 v1.52 (§6 politeness): v1.51 kept the session
     * active for the service's whole lifetime, which also meant an
     * idle Selena kept claiming earbud presses that belonged to
     * Spotify/Pandora. Now the session is active only while Selena
     * has content (playing or paused) — the states where a press is
     * plausibly FOR her. Fully idle → inactive, and the music apps
     * get their presses back; the manifest EarbudButtonReceiver can
     * still deliver a press on devices that fall back to the
     * broadcast path.
     */
    private void initMediaSession() {
        try {
            mediaSession = new MediaSession(this, TAG);
            mediaSession.setCallback(new ServiceMediaSessionCallback(this));
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSessionActiveWanted = false;
            refreshMediaSessionActive();
            updateMediaSessionState();
            log("earbud: media session created");
        } catch (Exception e) {
            // The manifest broadcast receiver still covers button presses
            // on the legacy path — a session failure must not kill the
            // service.
            log("earbud: media session init failed: " + e);
            mediaSession = null;
        }
    }

    /**
     * Nick 2026-10-28 v1.52: keep the media session's active flag in
     * step with whether Selena has anything a press could mean —
     * playing, paused-holding, or speaking. Only touches the session
     * on transitions. (v1.82: the listening term is gone with the mic.)
     */
    private void refreshMediaSessionActive() {
        if (mediaSession == null) return;
        boolean want = safeIsPlaying() || ttsSpeaking
                || playbackPaused || ttsPaused;
        if (want == mediaSessionActiveWanted) return;
        mediaSessionActiveWanted = want;
        try {
            mediaSession.setActive(want);
            log("earbud: media session " + (want
                    ? "activated (Selena has content)"
                    : "deactivated (idle — music apps keep their presses)"));
        } catch (Exception e) {
            log("earbud: session active toggle failed: " + e);
        }
    }

    /** Nick 2026-10-28 v1.51: publish current playback state to the session. */
    private void updateMediaSessionState() {
        if (mediaSession == null) return;
        try {
            boolean isPlaying = safeIsPlaying() || ttsSpeaking;
            boolean isPaused = playbackPaused || ttsPaused;
            int state = isPlaying ? PlaybackState.STATE_PLAYING
                    : isPaused ? PlaybackState.STATE_PAUSED
                    : PlaybackState.STATE_STOPPED;
            long actions = PlaybackState.ACTION_PLAY
                    | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_SKIP_TO_NEXT
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                    | PlaybackState.ACTION_STOP;
            long pos = (state == PlaybackState.STATE_PLAYING
                    || state == PlaybackState.STATE_PAUSED)
                    ? safePosition() : 0;
            mediaSession.setPlaybackState(new PlaybackState.Builder()
                    .setState(state, pos, isPlaying ? 1.0f : 0.0f)
                    .setActions(actions)
                    .build());
        } catch (Exception e) {
            log("earbud: session state update failed: " + e);
        }
    }

    /** v1.51 entry point, kept for any caller without a source. */
    public void onEarbudAction(String action) {
        onEarbudAction(action, "unknown");
    }

    /**
     * Nick 2026-10-28 v1.51: one earbud/headset button action, from the
     * MediaSession callback or the manifest broadcast receiver.
     * Semantics:
     *   toggle — playing -> pause; paused -> resume; idle -> play queued.
     *   play   — paused -> resume; idle -> play queued.
     *   pause  — playing -> pause.
     *   next   — skip to newest queued message.
     *   stop   — stop all playback.
     *   previous — not mapped yet (no replay-last transport in the app);
     *              logged so the press never vanishes silently.
     * The same physical press can arrive via BOTH delivery paths within
     * milliseconds — identical actions inside 1s are debounced, or a
     * pause would instantly resume itself.
     *
     * Nick 2026-10-28 v1.52 (§6): while Selena is PAUSED holding the
     * audio path, toggle/play presses get double-tap interpretation
     * (handlePausedPress) — a single press resumes her, a double tap
     * STOPS her outright and gives the path back to the music. That
     * path does its own duplicate detection using the delivery
     * source: a cross-path repeat is the same physical press, a
     * same-path repeat is a new one.
     */
    public void onEarbudAction(String action, String source) {
        if ((playbackPaused || ttsPaused)
                && ("toggle".equals(action) || "play".equals(action))) {
            handlePausedPress(action, source);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (action.equals(lastEarbudAction)
                && now - lastEarbudActionMs < 1000) {
            log("earbud: duplicate " + action + " debounced");
            return;
        }
        lastEarbudAction = action;
        lastEarbudActionMs = now;
        lastEarbudSource = source;
        log("earbud: action=" + action + " via " + source
                + " mp3Playing=" + safeIsPlaying()
                + " ttsSpeaking=" + ttsSpeaking
                + " paused=" + (playbackPaused || ttsPaused));
        switch (action) {
            case "toggle":
                if (playbackPaused || ttsPaused) resumePlayback();
                else if (safeIsPlaying() || ttsSpeaking) pausePlayback();
                else playQueuedFromEarbud();
                break;
            case "play":
                if (playbackPaused || ttsPaused) resumePlayback();
                else if (!safeIsPlaying() && !ttsSpeaking) {
                    playQueuedFromEarbud();
                }
                break;
            case "pause":
                if (safeIsPlaying() || ttsSpeaking) pausePlayback();
                break;
            case "next":
                skipToNewest();
                break;
            case "stop":
                stopAllPlayback();
                break;
            case "previous":
                log("earbud: previous not mapped yet");
                toast("Previous isn't set up yet");
                break;
            default:
                log("earbud: unknown action " + action);
                break;
        }
        updateMediaSessionState();
    }

    /**
     * True when this event is the OTHER delivery path's copy of the
     * press we just handled (session callback + manifest broadcast can
     * both deliver one physical press). Requires both sources known
     * and different, the same action, inside the debounce window.
     */
    private boolean isCrossPathDuplicate(String action, String source, long now) {
        return action.equals(lastEarbudAction)
                && source != null && lastEarbudSource != null
                && !source.equals(lastEarbudSource)
                && !"unknown".equals(source) && !"unknown".equals(lastEarbudSource)
                && now - lastEarbudActionMs < 1000;
    }

    /**
     * Nick 2026-10-28 v1.52 (§6): a toggle/play press while Selena is
     * paused holding the audio path. She does NOT resume instantly:
     * the press waits out EARBUD_DOUBLE_TAP_MS. A second DISTINCT
     * press inside the window is a double tap → stopAllPlayback (the
     * message never finishes) + abandon audio focus so the music
     * resumes. If the window lapses, the pending resume fires — the
     * only cost of the double-tap rule is ~400ms on resume.
     *
     * Duplicate safety: the press that CAUSED the pause can still
     * have its cross-path twin in flight when this runs — that twin
     * is ignored, or a single pause-press would "resume" itself. A
     * second press delivered ONLY via the other path than the first
     * is indistinguishable from such a twin and is treated as one
     * (resume proceeds) — the failure direction is deliberate: a
     * missed double-tap leaves Selena resumable, while a false one
     * would destroy a message the user wanted paused.
     */
    private void handlePausedPress(String action, String source) {
        long now = android.os.SystemClock.uptimeMillis();
        if (isCrossPathDuplicate(action, source, now)) {
            log("earbud: cross-path duplicate " + action + " debounced (paused)");
            return;
        }
        if (pendingResumeTask != null) {
            boolean duplicate = source != null && pendingResumeSource != null
                    && !source.equals(pendingResumeSource)
                    && !"unknown".equals(source)
                    && !"unknown".equals(pendingResumeSource)
                    && now - pendingResumeMs < 1000;
            if (duplicate) {
                log("earbud: cross-path duplicate debounced in double-tap window");
                return;
            }
            // Second distinct press inside the window = DOUBLE TAP.
            cancelPendingResume();
            lastEarbudAction = action;
            lastEarbudActionMs = now;
            lastEarbudSource = source;
            log("earbud: DOUBLE TAP while paused — stopping Selena,"
                    + " releasing the audio path to music");
            stopAllPlayback();
            abandonAudioFocus();
            updateMediaSessionState();
            return;
        }
        lastEarbudAction = action;
        lastEarbudActionMs = now;
        lastEarbudSource = source;
        pendingResumeSource = source;
        pendingResumeMs = now;
        log("earbud: press while paused — resume pending " + EARBUD_DOUBLE_TAP_MS
                + "ms (a second press in the window = double-tap stop)");
        pendingResumeTask = () -> {
            pendingResumeTask = null;
            pendingResumeSource = null;
            // State may have moved on (screen stop, new message) —
            // resume only if she's still the paused one.
            if (playbackPaused || ttsPaused) {
                log("earbud: single press confirmed — resuming Selena");
                resumePlayback();
            }
        };
        handler.postDelayed(pendingResumeTask, EARBUD_DOUBLE_TAP_MS);
    }

    /** Drop a pending double-tap-window resume, if any. */
    private void cancelPendingResume() {
        if (pendingResumeTask != null) {
            handler.removeCallbacks(pendingResumeTask);
            pendingResumeTask = null;
        }
        pendingResumeSource = null;
    }

    /**
     * v1.82: earbud tap while idle. Dictation is gone with the
     * microphone (Nick, 2026-11-02 — the app is playback-only), so
     * an idle press means "play what's waiting": pump both queues.
     * The pumps' own lane gates still hold playback while the
     * earbuds-only lane is armed without earbuds.
     */
    private void playQueuedFromEarbud() {
        log("earbud: idle press — pumping queues (playback-only)");
        pumpAudioQueue();
        pumpTtsQueue();
    }

    private void requestAudioFocus() {
        try {
            // Nick 2026-10-18 v1.7: match the playback usage (USAGE_MEDIA)
            // so focus is requested for the music stream, not voice-call.
            // Nick 2026-10-29 v1.59 (ducking rule): the type is now
            // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK — the old
            // GAIN_TRANSIENT made well-behaved media apps PAUSE
            // outright; MAY_DUCK fades them down and keeps them playing
            // while she speaks, and they fade back when she finishes.
            // (The v1.17-era comments always said "duck"; the type now
            // matches.) The STREAM_MUSIC boost stays disabled (v1.48) —
            // she plays at Nick's set volume over the ducked music.
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            focusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
            // Nick 2026-10-19 v1.18: capture the result and retry once on
            // failure — a failed focus means Spotify never ducks.
            int rc = audioManager.requestAudioFocus(focusRequest);
            log("audio: focus request rc=" + rc + " (1=granted)");
            if (rc == AudioManager.AUDIOFOCUS_REQUEST_FAILED) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                focusRequest = new AudioFocusRequest.Builder(
                        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
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
        // Nick 2026-10-30 v1.73: a laned play never requested focus —
        // there is nothing to abandon (its teardown is laneEnd via
        // restoreAudioRouting).
        if (lanePlaybackActive) return;
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

    // Nick's earbuds identity (v1.48, kept for the v1.73 earbuds-only
    // playback lane): he marks which BT device is his earbuds, one
    // time, in SharedPreferences.
    private static final String PREFS_NAME = "selena_voice_prefs";
    private static final String PREF_EARBUDS_NAME = "earbuds_device_name";

    /** Nick 2026-10-23 v1.48: save Nick's earbuds BT device name. */
    public void setEarbudsDeviceName(String name) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(PREF_EARBUDS_NAME, name).apply();
        log("bt: earbuds device set to '" + name + "'");
    }

    /** Nick 2026-10-23 v1.48: get the saved earbuds name (or null). */
    public String getEarbudsDeviceName() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_EARBUDS_NAME, null);
    }

    /**
     * Nick 2026-10-23 v1.48: get the name of the currently connected
     * Bluetooth A2DP (audio) device, or null if none. Uses
     * AudioManager.getDevices() — synchronous, no profile proxy needed.
     */
    private String getConnectedBtAudioDeviceName() {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) return null;
            android.media.AudioManager am =
                    (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return null;
            android.media.AudioDeviceInfo[] devices =
                    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS);
            for (android.media.AudioDeviceInfo d : devices) {
                int type = d.getType();
                if (type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    CharSequence name = d.getProductName();
                    if (name != null && name.length() > 0) {
                        return name.toString();
                    }
                }
            }
        } catch (Exception e) {
            log("bt: device query failed: " + e);
        }
        return null;
    }

    /**
     * Nick 2026-10-23 v1.48: true if the connected BT audio device
     * matches Nick's saved earbuds name. Null-safe: no device or no
     * saved name → false (tap interrupt mode).
     *
     * v1.75 (2026-10-30): PRESENCE IS AN ALL-DEVICE SCAN. The v1.48
     * implementation asked getConnectedBtAudioDeviceName(), which
     * returns the FIRST enumerated A2DP/SCO output and stops — in
     * the two-device truck case the first device is the truck radio,
     * so the earbuds (connected, second in the list) read as absent
     * and the v1.73 lane parked every message ("lane: priority MP3
     * parked — earbuds-only, no earbuds" with the earbuds in Nick's
     * ears, field failure 2026-10-30 16:06). Presence now means: ANY
     * connected output of type A2DP / SCO / BLE_HEADSET / BLE_SPEAKER
     * — or any available communication device — fuzzy-matches the
     * saved name. Presence is a different question from "which device
     * holds the media route" (the probe answers that); the two are
     * never conflated again.
     */
    private boolean isEarbudsConnected() {
        return earbudsPresent();
    }

    /** v1.75: Bluetooth/USB audio device types the lane reasons about. */
    private static boolean isBtAudioType(int t) {
        return t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                || t == AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }

    private static boolean isUsbAudioType(int t) {
        return t == AudioDeviceInfo.TYPE_USB_DEVICE
                || t == AudioDeviceInfo.TYPE_USB_HEADSET
                || t == AudioDeviceInfo.TYPE_USB_ACCESSORY;
    }

    /** Short type tag for lane decision logs (e.g. "A2DP", "SCO"). */
    private static String btTypeName(int t) {
        switch (t) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: return "A2DP";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "SCO";
            case AudioDeviceInfo.TYPE_BLE_HEADSET: return "BLE_HEADSET";
            case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "BLE_SPEAKER";
            case AudioDeviceInfo.TYPE_USB_DEVICE: return "USB";
            case AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET";
            case AudioDeviceInfo.TYPE_USB_ACCESSORY: return "USB_ACC";
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: return "SPEAKER";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "EARPIECE";
            case AudioDeviceInfo.TYPE_REMOTE_SUBMIX: return "REMOTE_SUBMIX";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED";
            default: return "T" + t;
        }
    }

    private AudioManager laneAudioManager() {
        if (audioManager != null) return audioManager;
        try {
            return (AudioManager) getSystemService(AUDIO_SERVICE);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * v1.75: the earbuds-presence scan — ANY output device (A2DP /
     * SCO / BLE headset / BLE speaker) or ANY available communication
     * device whose product name fuzzy-matches the saved earbuds name.
     * Independent of enumeration order and of the media route.
     */
    private boolean earbudsPresent() {
        String saved = getEarbudsDeviceName();
        if (saved == null || saved.isEmpty()) return false;
        AudioManager am = laneAudioManager();
        if (am == null) return false;
        try {
            for (AudioDeviceInfo d :
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (isBtAudioType(d.getType())
                        && nameMatchesEarbuds(d.getProductName())) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d :
                        am.getAvailableCommunicationDevices()) {
                    if (nameMatchesEarbuds(d.getProductName())) {
                        return true;
                    }
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /**
     * v1.75: decision evidence — every connected Bluetooth/USB audio
     * output as "Name(TYPE)", so a lane decision can be diagnosed
     * from one log pass (Nick's rule after the 16:06 field failure:
     * the log must list every connected BT device name + type).
     */
    private String describeBtOutputs() {
        AudioManager am = laneAudioManager();
        if (am == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            for (AudioDeviceInfo d :
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (!isBtAudioType(t) && !isUsbAudioType(t)
                        && t != AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                    continue;
                }
                if (!first) sb.append(", ");
                first = false;
                sb.append(d.getProductName())
                        .append("(").append(btTypeName(t)).append(")");
            }
        } catch (Exception e) {
            sb.append("query failed: ").append(e.getMessage());
        }
        return sb.append("]").toString();
    }

    /** v1.75: available communication devices as "Name(TYPE)" (API 31+). */
    private String describeCommDevices() {
        if (android.os.Build.VERSION.SDK_INT < 31) return "n/a";
        AudioManager am = laneAudioManager();
        if (am == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        try {
            for (AudioDeviceInfo d :
                    am.getAvailableCommunicationDevices()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(d.getProductName())
                        .append("(").append(btTypeName(d.getType()))
                        .append(")");
            }
        } catch (Exception e) {
            sb.append("query failed: ").append(e.getMessage());
        }
        return sb.append("]").toString();
    }

    // ------------------------------------------------------------ v1.73 earbuds-only lane

    /**
     * Nick 2026-10-30 v1.73: EARBUDS-ONLY PLAYBACK LANE (his order,
     * 14:33 EDT). His scenario, verbatim: earbuds AND the truck radio
     * both connected over Bluetooth, Spotify playing on the radio —
     * Selena plays ONLY through the earbuds and does not affect
     * Spotify. Plus his first ask: she only plays audio at all when
     * his earbuds are connected.
     *
     * Shape:
     *  - CONFIGURED = the earbuds-only toggle is on (default on) AND
     *    an earbuds name is saved. No name = the lane is inert and
     *    playback behaves exactly as pre-v1.73 (logged once).
     *  - MEDIA-DUCK mode (Nick's refinement, 2026-10-30 14:44): the
     *    earbuds themselves hold the media route (single-device
     *    case — Spotify in the same earbuds) → she plays exactly as
     *    she always has (media attributes + MAY_DUCK focus): the
     *    duck is WANTED there. The no-touch rule is only for the
     *    split case below.
     *  - HEADSET mode (split case: a DIFFERENT device — the truck
     *    radio — holds the media route, earbuds connected): every
     *    playback path routes ONLY to the named earbuds over their
     *    headset/call channel — MODE_IN_COMMUNICATION +
     *    setCommunicationDevice(matching device) for the duration
     *    of the play, USAGE_VOICE_COMMUNICATION player/TTS
     *    attributes — and NO media audio-focus is requested
     *    (playback or dictation duck), so Spotify on the truck is
     *    never ducked, paused, or re-routed by her. The device is
     *    chosen by the saved NAME among ALL connected outputs,
     *    never "any Bluetooth": the truck radio can never be
     *    selected (his A17 holds both connections at once — field
     *    confirmed 2026-10-30 14:40).
     *  - ARMED-HELD (configured + earbuds absent entirely): nothing
     *    plays out loud anywhere. Queued messages hold in their
     *    queues — unplayed, unreported, undeleted — and play when
     *    the earbuds connect (AudioDeviceCallback below). Transient
     *    bundled clips (holding notes, offline/saved/reconnected
     *    clips) are DROPPED instead: a stale "hold on" must never
     *    burst out late. Fast-lane answers stay written in the
     *    window. (Also held: earbuds present for media only with no
     *    headset channel while another device holds the route —
     *    she must not land on the truck, and there is no lane.)
     *  - Earbuds lost MID-PLAY in headset mode: playback stops at
     *    once and the item returns to the head of its queue
     *    unplayed; it replays from the start on reconnection.
     *  - Toggle OFF (or no name): pre-v1.73 behavior, untouched.
     *  While configured, the lane outranks the legacy v1.17 output
     *  toggles (force speaker / force Bluetooth) — it is the newer,
     *  explicit rule. The phone-call mic mute (v1.72) still outranks
     *  the lane: fireHoldingNote's call check runs first, and no
     *  playback path starts during a call under the existing rules.
     *  setCommunicationDevice needs API 31+; below that the lane
     *  cannot engage and stays inert (fleet is Android 14+).
     */
    private boolean lanePlaybackActive = false;
    private int lanePrevMode = AudioManager.MODE_NORMAL;
    private AudioDeviceInfo laneDevice = null;
    private boolean laneTtsAttrsSet = false;
    private boolean laneNoNameLogged = false;
    private long laneHeldLogAtMs = 0;
    private LaneDeviceCallback laneDeviceCallback = null;

    private boolean isEarbudsOnlyOn() {
        return getEarbudsOnly(this);
    }

    /** v1.77: the headset-fallback user setting (default on). */
    private boolean isHeadsetFallbackOn() {
        return getHeadsetFallback(this);
    }

    /** Toggle on + name saved + API 31+. No name → inert by design. */
    private boolean laneConfigured() {
        if (!isEarbudsOnlyOn()) return false;
        String name = getEarbudsDeviceName();
        if (name == null || name.isEmpty()) {
            if (!laneNoNameLogged) {
                laneNoNameLogged = true;
                log("lane: earbuds-only is ON but no earbuds name is "
                        + "saved yet — playback unchanged until a name "
                        + "is set (long-press the version text)");
            }
            return false;
        }
        return android.os.Build.VERSION.SDK_INT >= 31;
    }

    /** Fuzzy name match (same rule as isEarbudsConnected). */
    private boolean nameMatchesEarbuds(CharSequence productName) {
        String saved = getEarbudsDeviceName();
        if (saved == null || saved.isEmpty() || productName == null) {
            return false;
        }
        String n = productName.toString();
        return n.toLowerCase().contains(saved.toLowerCase())
                || saved.toLowerCase().contains(n.toLowerCase());
    }

    /**
     * The named earbuds as a COMMUNICATION device (BT SCO / BLE
     * headset output whose product name matches the saved name), or
     * null. A2DP-only presence does not count: Android cannot stream
     * two different media outputs at once, so her lane is the
     * headset channel — the same split a phone call uses while the
     * truck keeps the media channel.
     */
    private AudioDeviceInfo findEarbudsCommDevice() {
        AudioManager am = laneAudioManager();
        if (am == null) return null;
        // v1.75: the canonical list for setCommunicationDevice is
        // getAvailableCommunicationDevices() (API 31+) — a headset
        // connected for calls is listed there even when it does not
        // currently appear among GET_DEVICES_OUTPUTS (the outputs
        // list only surfaces the SCO device while a call route is
        // live, which is part of why v1.74 saw "no earbuds"). Search
        // it FIRST, then fall back to the outputs scan.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                for (AudioDeviceInfo d :
                        am.getAvailableCommunicationDevices()) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                        if (nameMatchesEarbuds(d.getProductName())) {
                            return d;
                        }
                    }
                }
            } catch (Exception e) {
                log("lane: comm device query failed: " + e);
            }
        }
        try {
            for (AudioDeviceInfo d :
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    if (nameMatchesEarbuds(d.getProductName())) return d;
                }
            }
        } catch (Exception e) {
            log("lane: comm device query failed: " + e);
        }
        return null;
    }

    /**
     * v1.75: the named earbuds as a MEDIA device (A2DP preferred) —
     * the pin target for the Android Auto lane, where her voice
     * travels as ordinary media pinned per-player to the earbuds
     * while Android Auto's own media reaches the car over USB/Wi-Fi.
     */
    private AudioDeviceInfo findEarbudsMediaDevice() {
        AudioManager am = laneAudioManager();
        if (am == null) return null;
        AudioDeviceInfo bleFallback = null;
        try {
            for (AudioDeviceInfo d :
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (!nameMatchesEarbuds(d.getProductName())) continue;
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) {
                    return d;
                }
                if (d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || d.getType() == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    bleFallback = d;
                }
            }
        } catch (Exception e) {
            log("lane: media device query failed: " + e);
        }
        return bleFallback;
    }

    /**
     * v1.76: the communication lane's incumbency check. Returns a
     * NON-earbuds Bluetooth call device (SCO / BLE headset) that
     * stands between her and the call lane, or null when the lane
     * is hers or free:
     *  - if a communication device is CURRENTLY selected and it is
     *    a non-earbuds BT call device, that device is the incumbent;
     *  - if the currently selected device IS the earbuds (her own
     *    live session), there is no foreign incumbent — other
     *    devices merely being available must not tear her session
     *    down;
     *  - otherwise, any non-earbuds BT call device in the available
     *    communication list counts: another HFP claimant (a car)
     *    will fight her for the phone's single call lane — the
     *    2026-10-30 18:25 field test, where truck SYNC and the
     *    earbuds tore each other's SCO link down in turn.
     */
    private AudioDeviceInfo foreignCommIncumbent() {
        if (android.os.Build.VERSION.SDK_INT < 31) return null;
        AudioManager am = laneAudioManager();
        if (am == null) return null;
        try {
            AudioDeviceInfo current = am.getCommunicationDevice();
            if (current != null) {
                if (nameMatchesEarbuds(current.getProductName())) {
                    return null; // her own session holds the lane
                }
                int ct = current.getType();
                if (ct == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || ct == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    return current;
                }
            }
            for (AudioDeviceInfo d :
                    am.getAvailableCommunicationDevices()) {
                int t = d.getType();
                if ((t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLE_HEADSET)
                        && !nameMatchesEarbuds(d.getProductName())) {
                    return d;
                }
            }
        } catch (Exception e) {
            log("lane: comm incumbency query failed: " + e);
        }
        return null;
    }

    /**
     * v1.76: the named earbuds as an A2DP media device ONLY (no BLE
     * fallback) — the pin target for CASE_MEDIA_PIN (v1.78:
     * pin-first in non-AA splits; see evaluateLane).
     */
    private AudioDeviceInfo findEarbudsA2dpDevice() {
        AudioManager am = laneAudioManager();
        if (am == null) return null;
        try {
            for (AudioDeviceInfo d :
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        && nameMatchesEarbuds(d.getProductName())) {
                    return d;
                }
            }
        } catch (Exception e) {
            log("lane: media device query failed: " + e);
        }
        return null;
    }

    /**
     * True when the named earbuds hold the MEDIA route right now
     * (Nick's 14:44 refinement: that is the single-device case and
     * she should play + duck exactly as always). Decider: a silent
     * PROBE track — a zero-filled AudioTrack with USAGE_MEDIA
     * attributes, played for a few ms; getRoutedDevice() then names
     * exactly where media policy would send her voice. (The direct
     * APIs are not public: BluetoothA2dp.getActiveDevice is
     * @SystemApi and AudioManager has no per-attributes device
     * query on API 34.) The probe is inaudible by construction and
     * its result is cached for a few seconds; device add/remove
     * events invalidate the cache. Probe failure (or API < 28)
     * falls back to the conservative presence rule: the earbuds
     * hold the route only if present with NO other Bluetooth audio
     * device around — with two candidates she never guesses her
     * way onto the truck.
     */
    private AudioDeviceInfo laneRouteCache = null;
    private long laneRouteCacheMs = 0;
    private static final long LANE_ROUTE_CACHE_MS = 4000;

    private void invalidateLaneRouteCache() {
        laneRouteCacheMs = 0;
        laneRouteCache = null;
    }

    private AudioDeviceInfo probeMediaRoutedDevice() {
        long now = android.os.SystemClock.uptimeMillis();
        if (laneRouteCacheMs > 0
                && now - laneRouteCacheMs < LANE_ROUTE_CACHE_MS) {
            return laneRouteCache;
        }
        if (android.os.Build.VERSION.SDK_INT < 28) return null;
        android.media.AudioTrack probe = null;
        try {
            int minBuf = android.media.AudioTrack.getMinBufferSize(
                    8000, android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            probe = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setSampleRate(8000)
                            .setEncoding(
                                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(
                                    android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, 1024))
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build();
            byte[] silence = new byte[1024];
            probe.write(silence, 0, silence.length,
                    android.media.AudioTrack.WRITE_BLOCKING);
            probe.play();
            AudioDeviceInfo routed = null;
            for (int i = 0; i < 10 && routed == null; i++) {
                routed = probe.getRoutedDevice();
                if (routed == null) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {}
                }
            }
            try {
                probe.stop();
            } catch (Exception ignored) {}
            laneRouteCache = routed;
            laneRouteCacheMs = android.os.SystemClock.uptimeMillis();
            return routed;
        } catch (Exception e) {
            log("lane: media-route probe failed: " + e);
            return null;
        } finally {
            if (probe != null) {
                try {
                    probe.release();
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * v1.75 (Nick 2026-10-30 16:40 — HARD RULE): pin a SILENT probe
     * track to the given device and report where it actually
     * routes. This is the pre-play PROOF for the AA-present earbuds
     * media pin: zero-filled samples, no focus requested, inaudible
     * whatever happens. Returns null when the route cannot be
     * proven — and unproven means do not play.
     */
    private AudioDeviceInfo probePinnedRoutedDevice(
            AudioDeviceInfo pin) {
        if (pin == null
                || android.os.Build.VERSION.SDK_INT < 28) return null;
        android.media.AudioTrack probe = null;
        try {
            int minBuf = android.media.AudioTrack.getMinBufferSize(
                    8000, android.media.AudioFormat.CHANNEL_OUT_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT);
            probe = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setSampleRate(8000)
                            .setEncoding(
                                    android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(
                                    android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(Math.max(minBuf, 1024))
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build();
            if (!probe.setPreferredDevice(pin)) return null;
            byte[] silence = new byte[1024];
            probe.write(silence, 0, silence.length,
                    android.media.AudioTrack.WRITE_BLOCKING);
            probe.play();
            AudioDeviceInfo routed = null;
            for (int i = 0; i < 15 && routed == null; i++) {
                routed = probe.getRoutedDevice();
                if (routed == null) {
                    try {
                        Thread.sleep(40);
                    } catch (InterruptedException ignored) {}
                }
            }
            try {
                probe.stop();
            } catch (Exception ignored) {}
            return routed;
        } catch (Exception e) {
            log("lane: pinned-route probe failed: " + e);
            return null;
        } finally {
            if (probe != null) {
                try {
                    probe.release();
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean anyOtherBtAudioPresent() {
        if (audioManager == null) return false;
        try {
            for (AudioDeviceInfo d :
                    audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int t = d.getType();
                if (t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        || t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || t == AudioDeviceInfo.TYPE_BLE_HEADSET
                        || t == AudioDeviceInfo.TYPE_BLE_SPEAKER) {
                    if (!nameMatchesEarbuds(d.getProductName())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean earbudsHoldMediaRoute() {
        AudioDeviceInfo routed = probeMediaRoutedDevice();
        if (routed != null) {
            // The probe names the route holder exactly: a name
            // match is the ONLY way the earbuds hold it — any
            // other device (truck BT, speaker, earpiece) is a no.
            return nameMatchesEarbuds(routed.getProductName());
        }
        return isEarbudsConnected() && !anyOtherBtAudioPresent();
    }

    // ------------------------------------------------ v1.75 decision core

    /**
     * The lane decision, evaluated fresh at every gate.
     * (v1.75 core; v1.76 additions marked.)
     *  OFF        — toggle off / no name saved: pre-lane behavior.
     *  CASE_A     — the earbuds hold the media route (single-device
     *               case): legacy media playback + MAY_DUCK.
     *  CASE_B_HEADSET — split case, no Android Auto, no foreign
     *               comm incumbent: headset/call channel to the
     *               earbuds, no focus, no duck.
     *  CASE_B_AA  — split case WITH Android Auto on stage: media
     *               pinned per-player to the earbuds, NO focus, NO
     *               mode changes, NEVER the communication channel —
     *               AA owns the HFP lane and surfaces any
     *               communication-mode stream as a phone call on the
     *               head unit (field failure 2026-10-30 16:18:
     *               setCommunicationDevice refused, playback treated
     *               as a call, Spotify paused on the radio).
     *  CASE_MEDIA_PIN — split case without AA: her media is
     *               pinned per-player to the earbuds' A2DP (same
     *               pin discipline as CASE_B_AA: no focus, no mode
     *               change, route proven before output). v1.76
     *               introduced it for the sub-case where a FOREIGN
     *               device stands in the communication lane (do not
     *               fight it for the call lane); v1.78 (Nick
     *               2026-10-30 19:30) makes it PIN-FIRST for EVERY
     *               non-AA split where the earbuds have an A2DP
     *               device among the outputs — the pin is the only
     *               channel that can leave the truck's own media
     *               (Spotify) untouched on the truck, so it is
     *               attempted before the headset channel. No A2DP
     *               target (earbuds present SCO-only) → no pin is
     *               possible: the v1.76/77 branches stand (foreign
     *               incumbent → PARK; otherwise the headset
     *               channel). A refused/unprovable pin converts
     *               per the v1.77 headset-fallback setting.
     *  PARK       — configured but cannot play (earbuds absent, or
     *               present media-only with no usable channel while
     *               another device holds the route): hold silently.
     */
    private static final int LANE_OFF = 0;
    private static final int LANE_CASE_A = 1;
    private static final int LANE_CASE_B_HEADSET = 2;
    private static final int LANE_CASE_B_AA = 3;
    private static final int LANE_PARK = 4;
    // Split case WITHOUT Android Auto — her media is pinned to
    // the earbuds' A2DP. v1.76: only when a foreign device stood
    // in the communication lane. v1.78: pin-first for any non-AA
    // split with an A2DP target among the outputs.
    private static final int LANE_CASE_B_PIN = 5;

    private boolean lanePinActive = false;
    private boolean laneHeadsetLive = false;
    private String laneAaSignal = null;
    // v1.76: the lane is a SESSION, not a per-item rental. One
    // acquisition serves a whole message (all its segments) and
    // any immediately-following queued items; it releases only
    // after the playback queues have been idle for
    // LANE_SESSION_HOLD_MS. (v1.75 set and cleared the
    // communication device per segment; under Android Auto every
    // set/clear looked like a phone call starting and ending —
    // the truck announced "call ended" at every segment seam,
    // field logs 2026-10-30 18:25–18:28.)
    private static final long LANE_SESSION_HOLD_MS = 5000;
    private int laneSessionKind = 0; // LANE_BEGIN_LEGACY (declared below)
    private String laneSessionDeviceName = null;
    private long laneSessionAcquiredMs = 0;
    private int laneSessionItems = 0;
    private Runnable laneSessionReleaseTask = null;
    private int laneSessionGen = 0;
    private boolean laneSessionHoldLogged = false;
    // v1.77: the verdict the current begin/play is serving, and
    // the verdict a live headset-fallback session was acquired
    // under — a fallback session is STICKY for its verdict, so the
    // pin is never re-attempted per item (no retry storms).
    private int laneCurrentVerdict = LANE_OFF;
    private int laneFallbackVerdict = LANE_OFF;
    // v1.78: why the current CASE_MEDIA_PIN verdict was chosen
    // ("pin-first (non-AA split)" or the v1.76 foreign-incumbent
    // reason) — set by evaluateLane, read by laneBegin's log.
    private String lanePinWhy = null;
    private long laneTransitionUntilMs = 0;
    private Runnable lanePendingAbort = null;
    private String laneLastDecisionSig = "";
    private long laneLastDecisionLogMs = 0;

    private static String laneVerdictName(int v) {
        switch (v) {
            case LANE_CASE_A: return "CASE_A";
            case LANE_CASE_B_HEADSET: return "CASE_B_HEADSET";
            case LANE_CASE_B_AA: return "CASE_AA_PIN";
            case LANE_CASE_B_PIN: return "CASE_MEDIA_PIN";
            case LANE_PARK: return "PARK";
            default: return "OFF";
        }
    }

    /**
     * Android Auto on stage? Signals (logged when one fires):
     *  "remote-submix" (v1.76) — the media route holder IS a
     *    remote-submix device (type 25): wireless AA holds its
     *    media there. The ONLY signal present in Nick's Ford
     *    F-250 (field logs 2026-10-30 18:25) — v1.75 missed it
     *    and every decision fell through to the headset case.
     *  "remote-submix-present+bt-vehicle" (v1.76) — a remote-
     *    submix output exists while a non-earbuds Bluetooth
     *    vehicle device is connected.
     *  "usb-route" — the media route holder IS a USB audio device
     *    (wired AA carries its audio over USB; the phone's Bluetooth
     *    media lane to the earbuds is genuinely free).
     *  "usb-present+bt-route" — a USB audio device is among the
     *    outputs while a non-earbuds Bluetooth device holds the
     *    route (AA head unit connected over USB for projection,
     *    its HFP/Bluetooth side holding the phone's route).
     * AA setups with no signal fall through to the plain-Bluetooth
     * rules — and the comm-incumbency pin + comm-refusal park keep
     * even that case from bursting onto the car.
     */
    private String androidAutoSignal(AudioDeviceInfo routed) {
        // v1.76 signal (a): the route holder IS a remote-submix
        // device (AudioDeviceInfo type 25). Wireless Android Auto
        // holds its media on a remote-submix output — Nick's Ford
        // F-250 / Ford SYNC setup, field logs 2026-10-30 18:25:
        // the probe reported route='remote-submix' on EVERY
        // decision while v1.75's USB-only detection said aa=none,
        // so the verdict never became CASE_AA_PIN and every
        // segment took the headset channel instead.
        if (routed != null
                && routed.getType()
                        == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
            return "remote-submix";
        }
        if (routed != null && isUsbAudioType(routed.getType())) {
            return "usb-route";
        }
        AudioManager am = laneAudioManager();
        if (am == null) return null;
        boolean submixPresent = false;
        boolean usbPresent = false;
        boolean vehicleBtPresent = false;
        try {
            for (AudioDeviceInfo d :
                    am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType()
                        == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                    submixPresent = true;
                } else if (isUsbAudioType(d.getType())) {
                    usbPresent = true;
                } else if (isBtAudioType(d.getType())
                        && !nameMatchesEarbuds(d.getProductName())) {
                    vehicleBtPresent = true;
                }
            }
        } catch (Exception ignored) {}
        // v1.76 signal (c): a remote-submix output exists while a
        // non-earbuds Bluetooth vehicle device is connected.
        if (submixPresent && vehicleBtPresent) {
            return "remote-submix-present+bt-vehicle";
        }
        if (usbPresent && routed != null
                && isBtAudioType(routed.getType())) {
            return "usb-present+bt-route";
        }
        return null;
    }

    /**
     * v1.75: evaluate the lane and LOG the evidence — saved name,
     * every connected BT/USB output (name + type), the available
     * communication devices, the route holder, presence, the AA
     * signal, and the verdict. Logged when the evidence signature
     * changes (or every 30 s), so one log pass diagnoses any state.
     */
    private int evaluateLane(String why) {
        if (!isEarbudsOnlyOn()) {
            logLaneDecision(why, LANE_OFF, false, null, null, null,
                    false);
            return LANE_OFF;
        }
        if (!laneConfigured()) {
            logLaneDecision(why, LANE_OFF, false, null, null, null,
                    false);
            return LANE_OFF;
        }
        boolean present = earbudsPresent();
        AudioDeviceInfo routed = probeMediaRoutedDevice();
        boolean routeEarbuds = earbudsHoldMediaRoute();
        String aa = (present && !routeEarbuds)
                ? androidAutoSignal(routed) : null;
        // v1.76: the comm-lane incumbency question is asked only
        // when AA is not already on stage (AA takes the pin path
        // regardless — the comm channel is never opened under AA).
        AudioDeviceInfo foreign = (present && !routeEarbuds
                && aa == null) ? foreignCommIncumbent() : null;
        // v1.78 PIN-FIRST (Nick 2026-10-30 19:30): in a NON-AA
        // split, the pin question is asked BEFORE the headset and
        // foreign-incumbent branches. The per-player pin is the
        // only channel that can put her in the earbuds while the
        // truck's own media (Spotify) stays on the truck
        // untouched: Case A drags Spotify along the shared A2DP
        // river, and opening the call channel makes head units
        // hand the whole river to the earbuds (Chevy field tests
        // 2026-10-30 19:21–19:29). Android Auto refused the pin
        // (v1.76) — whether a plain-Bluetooth head unit accepts
        // it is exactly what this ordering finds out. The pin
        // target is the same A2DP-only device laneBegin pins to.
        AudioDeviceInfo pinTarget = (present && !routeEarbuds
                && aa == null) ? findEarbudsA2dpDevice() : null;
        lanePinWhy = null;
        int verdict;
        if (routeEarbuds) {
            verdict = LANE_CASE_A;
        } else if (!present) {
            verdict = LANE_PARK;
        } else if (aa != null) {
            verdict = LANE_CASE_B_AA;
            laneAaSignal = aa;
        } else if (pinTarget != null) {
            verdict = LANE_CASE_B_PIN;
            // Distinguish the v1.78 plain pin-first from the
            // v1.76 sub-case (a foreign device in the call lane
            // is still a reason not to fight for that lane — the
            // two HFP claimants tore each other's SCO link down
            // in the 2026-10-30 18:25 field test).
            lanePinWhy = foreign != null
                    ? "a foreign device holds the communication "
                            + "lane"
                    : "pin-first (non-AA split)";
        } else if (foreign != null) {
            // No A2DP target and another device stands in the
            // communication lane — do NOT fight it for the call
            // lane; park (v1.76/77, unchanged).
            verdict = LANE_PARK;
        } else if (findEarbudsCommDevice() != null) {
            verdict = LANE_CASE_B_HEADSET;
        } else {
            // Present for media only, no headset channel, and the
            // route sits elsewhere: she must not land on the truck.
            verdict = LANE_PARK;
        }
        // v1.78: a non-AA split that reached the headset/park
        // branches did so because the pin had no target — the
        // earbuds are present SCO-only. The decision log says so,
        // so the field logs show the pin-attempted vs pin-skipped
        // ratio.
        boolean pinSkippedScoOnly = present && !routeEarbuds
                && aa == null && pinTarget == null
                && verdict != LANE_CASE_A;
        logLaneDecision(why, verdict, present, routed, aa, foreign,
                pinSkippedScoOnly);
        return verdict;
    }

    private void logLaneDecision(String why, int verdict,
            boolean present, AudioDeviceInfo routed, String aa,
            AudioDeviceInfo foreign, boolean pinSkippedScoOnly) {
        String saved = getEarbudsDeviceName();
        String routeDesc = routed != null
                ? "'" + routed.getProductName() + "'("
                        + btTypeName(routed.getType()) + ")"
                : "unknown";
        String sig = saved + "|" + present + "|" + routeDesc + "|"
                + aa + "|" + verdict + "|" + describeBtOutputs()
                + "|" + (foreign != null
                        ? foreign.getProductName() : "")
                + "|" + pinSkippedScoOnly;
        long now = android.os.SystemClock.uptimeMillis();
        if (sig.equals(laneLastDecisionSig)
                && now - laneLastDecisionLogMs < 30_000) {
            return;
        }
        laneLastDecisionSig = sig;
        laneLastDecisionLogMs = now;
        log("lane: decision[" + why + "] — saved='" + saved
                + "' present=" + present
                + " route=" + routeDesc
                + " aa=" + (aa != null ? aa : "none")
                + " verdict=" + laneVerdictName(verdict)
                + (foreign != null
                        ? " foreignComm='"
                                + foreign.getProductName() + "'"
                        : "")
                + (pinSkippedScoOnly
                        ? " pin-first skipped — no earbuds A2DP "
                                + "target (SCO only)"
                        : "")
                + " outputs=" + describeBtOutputs()
                + " comm=" + describeCommDevices());
    }

    /**
     * Configured AND a no-focus lane is live (headset split or AA
     * media-pin): the dictation duck must skip its focus request in
     * both — other audio stays untouched while he talks.
     */
    private boolean laneHeadsetActive() {
        int d = evaluateLane("duck-gate");
        return d == LANE_CASE_B_HEADSET || d == LANE_CASE_B_AA
                || d == LANE_CASE_B_PIN;
    }

    /**
     * Nothing may play out loud: the lane is configured and the
     * verdict is PARK (earbuds absent entirely, or present
     * media-only with no usable channel while another device holds
     * the route).
     */
    private boolean laneBlocked() {
        return evaluateLane("gate") == LANE_PARK;
    }

    /**
     * v1.75: TTS-specific block — device TTS cannot be pinned to a
     * device (no per-device API exists for TextToSpeech), so under
     * Android Auto the spoken form holds too; the text stays in the
     * window and is spoken when the case changes.
     */
    private boolean laneTtsBlocked() {
        int d = evaluateLane("tts-gate");
        if (d == LANE_PARK) return true;
        // v1.77: under a pin verdict, TTS holds only while the
        // headset fallback is OFF — with it on, the begin converts
        // to a headset-fallback session and the TTS speaks.
        if (d == LANE_CASE_B_AA || d == LANE_CASE_B_PIN) {
            return !isHeadsetFallbackOn();
        }
        return false;
    }

    /** Human-readable lane state for logs. */
    private String laneModeName() {
        if (!isEarbudsOnlyOn()) return "off";
        if (!laneConfigured()) return "inert (no earbuds name)";
        switch (evaluateLane("mode")) {
            case LANE_CASE_A: return "media-duck (earbuds)";
            case LANE_CASE_B_HEADSET: return "headset (split)";
            case LANE_CASE_B_AA: return "aa-present pin (split)";
            case LANE_CASE_B_PIN: return "media pin (split)";
            default: return "armed-held (no earbuds)";
        }
    }

    /** Throttled held-state log so the pumps can call it freely. */
    private void logLaneHeld(String what) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - laneHeldLogAtMs < 60_000) return;
        laneHeldLogAtMs = now;
        log("lane: armed-held — " + what + " waiting for earbuds ("
                + getEarbudsDeviceName() + "); nothing plays out loud");
    }

    // laneBegin() outcomes (v1.75; PIN added v1.76).
    private static final int LANE_BEGIN_LEGACY = 0;
    private static final int LANE_BEGIN_HEADSET = 1;
    private static final int LANE_BEGIN_AA = 2;
    private static final int LANE_BEGIN_PARK = 3;
    private static final int LANE_BEGIN_PIN = 4;
    // v1.77: a session that began as a pin verdict but fell back
    // to the headset channel (user setting) after the pin was
    // refused or unprovable. Plays exactly like HEADSET; the kind
    // exists so logs and session reuse can tell the two apart.
    private static final int LANE_BEGIN_HEADSET_FALLBACK = 5;

    private static String laneBeginName(int b) {
        switch (b) {
            case LANE_BEGIN_HEADSET: return "headset";
            case LANE_BEGIN_AA: return "aa-pin";
            case LANE_BEGIN_PIN: return "media-pin";
            case LANE_BEGIN_HEADSET_FALLBACK:
                return "headset-fallback";
            default: return "legacy";
        }
    }

    /** v1.76: a lane session is currently acquired (route held). */
    private boolean laneSessionHeld() {
        return laneSessionKind == LANE_BEGIN_HEADSET
                || laneSessionKind == LANE_BEGIN_AA
                || laneSessionKind == LANE_BEGIN_PIN
                || laneSessionKind == LANE_BEGIN_HEADSET_FALLBACK;
    }

    private void cancelLaneSessionRelease() {
        if (laneSessionReleaseTask != null) {
            handler.removeCallbacks(laneSessionReleaseTask);
            laneSessionReleaseTask = null;
        }
        laneSessionGen++;
    }

    /**
     * v1.76: is there still playback work the session should serve?
     * Anything playing, paused, mid segment-gap, or queued counts —
     * the session releases only when ALL of it has been idle.
     */
    private boolean laneWorkPending() {
        if (playing || ttsSpeaking || playbackPaused || ttsPaused
                || pendingSegmentAdvance != null || priorityMode) {
            return true;
        }
        if (!waitingPriority.isEmpty()
                || !interruptStack.isEmpty()) {
            return true;
        }
        synchronized (audioQueue) {
            if (!audioQueue.isEmpty()) return true;
        }
        synchronized (ttsQueue) {
            if (!ttsQueue.isEmpty()) return true;
        }
        return false;
    }

    /**
     * v1.76: an item finished under a held session — keep the lane
     * for LANE_SESSION_HOLD_MS (covers the 3 s segment gap and
     * back-to-back items), then release only if the queues have
     * gone idle; reschedule while work remains.
     */
    private void scheduleLaneSessionRelease() {
        if (!laneSessionHeld()) return;
        cancelLaneSessionRelease();
        final int gen = laneSessionGen;
        laneSessionReleaseTask = () -> {
            if (laneSessionGen != gen) return;
            laneSessionReleaseTask = null;
            if (laneWorkPending()) {
                scheduleLaneSessionRelease();
                return;
            }
            laneEnd();
        };
        handler.postDelayed(laneSessionReleaseTask,
                LANE_SESSION_HOLD_MS);
        if (!laneSessionHoldLogged) {
            laneSessionHoldLogged = true;
            log("lane: session hold — keeping the "
                    + laneBeginName(laneSessionKind)
                    + " lane for "
                    + (LANE_SESSION_HOLD_MS / 1000)
                    + "s for the next segment/item");
        }
    }

    /**
     * v1.76: a held headset session is reused only while the
     * earbuds still hold the communication device. If the system
     * cleared the selection between items, re-assert it ONCE for
     * the session; if another BT call device took the lane, do
     * not fight it — report false and the caller parks.
     */
    private boolean laneSessionCommIntact() {
        if (android.os.Build.VERSION.SDK_INT < 31
                || audioManager == null || laneDevice == null) {
            return laneHeadsetLive;
        }
        try {
            AudioDeviceInfo current =
                    audioManager.getCommunicationDevice();
            if (current != null
                    && nameMatchesEarbuds(current.getProductName())) {
                return true;
            }
            if (current != null) {
                int ct = current.getType();
                if (ct == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || ct == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    log("lane: session comm lane taken by "
                            + current.getProductName()
                            + " — not fighting it; parking");
                    return false;
                }
            }
            if (audioManager.setCommunicationDevice(laneDevice)) {
                log("lane: session comm device re-asserted for "
                        + laneDevice.getProductName());
                return true;
            }
            log("lane: session comm re-assert refused for "
                    + laneDevice.getProductName() + "; parking");
            return false;
        } catch (Exception e) {
            log("lane: session comm check failed: " + e);
            return false;
        }
    }

    /**
     * v1.77: acquire a headset-channel session of the given kind
     * (LANE_BEGIN_HEADSET or LANE_BEGIN_HEADSET_FALLBACK): mode +
     * communication device to the name-matched earbuds, session
     * fields set. Returns the device, or null when there is no
     * earbuds comm device or the system refused the selection —
     * the caller parks; there is never a legacy fallback and never
     * a retry burst.
     */
    private AudioDeviceInfo startHeadsetSession(int kind) {
        AudioDeviceInfo dev = findEarbudsCommDevice();
        if (dev == null) return null;
        try {
            lanePrevMode = audioManager.getMode();
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            if (!audioManager.setCommunicationDevice(dev)) {
                audioManager.setMode(lanePrevMode);
                log("lane: setCommunicationDevice refused for "
                        + dev.getProductName()
                        + " — parking (no legacy fallback, no retry)");
                return null;
            }
            laneDevice = dev;
            laneHeadsetLive = true;
            lanePlaybackActive = true;
            laneSessionKind = kind;
            laneSessionDeviceName =
                    String.valueOf(dev.getProductName());
            laneSessionAcquiredMs =
                    android.os.SystemClock.uptimeMillis();
            laneSessionItems = 1;
            laneSessionHoldLogged = false;
            laneTransitionUntilMs =
                    android.os.SystemClock.uptimeMillis() + 1500;
            return dev;
        } catch (Exception e) {
            log("lane: begin failed: " + e);
            lanePlaybackActive = false;
            laneHeadsetLive = false;
            laneDevice = null;
            return null;
        }
    }

    /**
     * v1.77: the pin path failed or cannot run — fall back to the
     * headset channel (Nick's 2026-10-30 19:09 decision: audible
     * in the earbuds beats parked silence; the fallback switch is
     * his, default ON). Releases any live pin session first, then
     * acquires a headset-fallback session under the current
     * verdict (sticky: later items for the same verdict reuse it
     * instead of re-attempting the pin). The audio still goes ONLY
     * to the earbuds; if the headset acquisition also fails, the
     * caller parks with zero output.
     */
    private boolean laneFallbackToHeadset(String reasonLog) {
        log(reasonLog);
        laneEnd();
        AudioDeviceInfo dev =
                startHeadsetSession(LANE_BEGIN_HEADSET_FALLBACK);
        if (dev == null) {
            log("lane: headset fallback unavailable — "
                    + "parking, no output");
            return false;
        }
        laneFallbackVerdict = laneCurrentVerdict;
        log("lane: session acquired — headset-fallback to "
                + laneSessionDeviceName);
        return true;
    }

    /**
     * Begin an engaged play. LEGACY = the caller takes the normal
     * routing + focus path (lane off, or Case A where the duck is
     * wanted). HEADSET = the communication device is live; play
     * with voice-communication attributes. AA = play as MEDIA with
     * the player pinned to laneDevice (the earbuds' media device),
     * NO focus request and NO mode changes. PARK = do not play;
     * the caller keeps/returns its item unplayed. A refused
     * setCommunicationDevice degrades to PARK with a clear log —
     * never to the legacy path (v1.74 fell through and played on
     * the truck) and never to a retry burst.
     * v1.77: with the headset-fallback setting ON, a pin verdict
     * whose pin is refused/unprovable — or a TTS item, which can
     * never be pinned — converts to a HEADSET_FALLBACK session
     * instead of parking.
     */
    private int laneBegin() {
        return laneBeginInternal(false);
    }

    private int laneBeginInternal(boolean forTts) {
        int d = evaluateLane("begin");
        laneCurrentVerdict = d;
        if (d == LANE_OFF || d == LANE_CASE_A) {
            // A session left over from an earlier split verdict
            // must never leak its route into a legacy play.
            if (laneSessionHeld()) laneEnd();
            if (d == LANE_CASE_A) {
                // Nick 2026-10-30 14:44: when the earbuds
                // themselves hold the media route, the duck is
                // wanted — decline the lane; the caller takes the
                // media + MAY_DUCK path.
                log("lane: case A same-device duck — earbuds hold "
                        + "the media route, normal playback with "
                        + "duck");
            }
            return LANE_BEGIN_LEGACY;
        }
        if (d == LANE_PARK) {
            if (laneSessionHeld()) laneEnd();
            return LANE_BEGIN_PARK;
        }
        // v1.77 STICKINESS: a live headset-fallback session serves
        // every later item for the verdict it was acquired under
        // (and a plain headset verdict, which it can serve as-is)
        // WITHOUT re-attempting the pin — one pin attempt per
        // verdict/presence change, never a per-item retry storm.
        if (laneSessionHeld()
                && laneSessionKind == LANE_BEGIN_HEADSET_FALLBACK
                && (d == laneFallbackVerdict
                        || d == LANE_CASE_B_HEADSET)) {
            if (!laneSessionCommIntact()) {
                laneEnd();
                return LANE_BEGIN_PARK;
            }
            cancelLaneSessionRelease();
            laneSessionItems++;
            log("lane: session continuing — item "
                    + laneSessionItems + " (headset-fallback to "
                    + laneSessionDeviceName + ")");
            return LANE_BEGIN_HEADSET_FALLBACK;
        }
        final int want = d == LANE_CASE_B_AA ? LANE_BEGIN_AA
                : d == LANE_CASE_B_PIN ? LANE_BEGIN_PIN
                : LANE_BEGIN_HEADSET;
        // v1.76 SESSION REUSE: a held session of the wanted kind
        // serves this item as-is — NO route set/clear per item or
        // per segment. (The v1.75 per-segment comm set/clear is
        // what made Android Auto announce "call ended" at every
        // segment seam in the 2026-10-30 18:25 field test.)
        if (laneSessionHeld() && laneSessionKind == want) {
            AudioDeviceInfo dev = want == LANE_BEGIN_HEADSET
                    ? laneDevice
                    : want == LANE_BEGIN_AA
                            ? findEarbudsMediaDevice()
                            : findEarbudsA2dpDevice();
            if (dev != null && laneSessionDeviceName != null
                    && laneSessionDeviceName.equals(
                            String.valueOf(dev.getProductName()))) {
                if (want == LANE_BEGIN_HEADSET
                        && !laneSessionCommIntact()) {
                    laneEnd();
                    return LANE_BEGIN_PARK;
                }
                cancelLaneSessionRelease();
                laneDevice = dev;
                laneSessionItems++;
                log("lane: session continuing — item "
                        + laneSessionItems + " ("
                        + laneBeginName(want) + " to "
                        + laneSessionDeviceName + ")");
                if (forTts && (want == LANE_BEGIN_AA
                        || want == LANE_BEGIN_PIN)) {
                    // v1.77: TTS can never be pinned — with the
                    // fallback setting on it converts to the
                    // headset channel; off, it holds as in v1.76
                    // (the caller treats AA/PIN begins as held).
                    if (isHeadsetFallbackOn()
                            && laneFallbackToHeadset(
                                    "lane: TTS cannot be pinned — "
                                    + "headset fallback (user "
                                    + "setting on)")) {
                        return LANE_BEGIN_HEADSET_FALLBACK;
                    }
                }
                return want;
            }
            laneEnd(); // earbuds device changed — reacquire below
        } else if (laneSessionHeld()) {
            laneEnd(); // verdict changed — reacquire below
        }
        laneTransitionUntilMs =
                android.os.SystemClock.uptimeMillis() + 1500;
        if (want == LANE_BEGIN_AA || want == LANE_BEGIN_PIN) {
            AudioDeviceInfo dev = want == LANE_BEGIN_AA
                    ? findEarbudsMediaDevice()
                    : findEarbudsA2dpDevice();
            if (dev == null) {
                // v1.77: no pin target at all — with the fallback
                // setting on, the headset channel serves instead
                // of parking (Nick 2026-10-30 19:09).
                if (isHeadsetFallbackOn()
                        && laneFallbackToHeadset(
                                "lane: pin unavailable — headset "
                                + "fallback (user setting on)")) {
                    return LANE_BEGIN_HEADSET_FALLBACK;
                }
                log("lane: "
                        + (want == LANE_BEGIN_AA
                                ? "AA preferred-device FAILED — no "
                                        + "earbuds media device "
                                        + "among outputs; parking"
                                : "media pin FAILED — no earbuds "
                                        + "A2DP device among "
                                        + "outputs; parking"));
                return LANE_BEGIN_PARK;
            }
            laneDevice = dev;
            lanePinActive = true;
            lanePlaybackActive = true;
            laneSessionKind = want;
            laneSessionDeviceName =
                    String.valueOf(dev.getProductName());
            laneSessionAcquiredMs =
                    android.os.SystemClock.uptimeMillis();
            laneSessionItems = 1;
            laneSessionHoldLogged = false;
            if (want == LANE_BEGIN_AA) {
                log("lane: AA-present earbuds media pin — playing "
                        + "to " + dev.getProductName()
                        + " as media (AA signal: " + laneAaSignal
                        + "), no focus, no mode change, other audio "
                        + "untouched");
            } else {
                log("lane: media pin — "
                        + (lanePinWhy != null ? lanePinWhy
                                : "pin-first (non-AA split)")
                        + "; playing to "
                        + dev.getProductName()
                        + " as media (A2DP pin), no focus, no mode "
                        + "change, other audio untouched");
            }
            log("lane: session acquired — " + laneBeginName(want)
                    + " to " + laneSessionDeviceName);
            if (forTts) {
                // v1.77: TTS can never be pinned — with the
                // fallback setting on it converts to the headset
                // channel; off, it holds as in v1.76.
                if (isHeadsetFallbackOn()
                        && laneFallbackToHeadset(
                                "lane: TTS cannot be pinned — "
                                + "headset fallback (user "
                                + "setting on)")) {
                    return LANE_BEGIN_HEADSET_FALLBACK;
                }
            }
            return want;
        }
        // CASE_B_HEADSET (plain Bluetooth split, no Android Auto,
        // no foreign comm incumbent). Defense in depth: the comm
        // channel must NEVER open under Android Auto, even if the
        // state flipped between the decision and here.
        if (androidAutoSignal(probeMediaRoutedDevice()) != null) {
            log("lane: Android Auto present at begin — the "
                    + "communication channel is never opened "
                    + "under AA; parking");
            return LANE_BEGIN_PARK;
        }
        AudioDeviceInfo dev =
                startHeadsetSession(LANE_BEGIN_HEADSET);
        if (dev == null) return LANE_BEGIN_PARK;
        // v1.78: under pin-first ordering, a fresh plain-headset
        // session in a non-AA split means the pin had no target
        // (it is attempted first whenever one exists) — say so
        // once per session, so the field logs show the
        // pin-attempted vs pin-skipped ratio. (Checked fresh: a
        // target that appeared since the decision is honored
        // silently — no skip line is logged then.)
        if (findEarbudsA2dpDevice() == null) {
            log("lane: pin-first skipped — no earbuds A2DP "
                    + "target (SCO only); headset channel");
        }
        log("lane: case B split no-focus — playing to "
                + dev.getProductName()
                + " via headset channel, other audio untouched");
        log("lane: session acquired — headset to "
                + laneSessionDeviceName);
        return LANE_BEGIN_HEADSET;
    }

    /** Release the lane: headset teardown only if the comm channel
     *  was actually taken (the media pins never touch mode/comm).
     *  v1.76: this is the SESSION release — it runs when the
     *  session's idle hold expires, when the verdict/device
     *  changes, on a user stop, or on service destroy — never at
     *  a segment boundary. */
    private void laneEnd() {
        cancelLaneSessionRelease();
        if (!lanePlaybackActive && !laneSessionHeld()) {
            lanePinActive = false;
            return;
        }
        boolean wasHeadset = laneHeadsetLive;
        if (laneHeadsetLive) {
            try {
                audioManager.clearCommunicationDevice();
            } catch (Exception ignored) {}
            try {
                audioManager.setMode(lanePrevMode);
            } catch (Exception ignored) {}
            laneHeadsetLive = false;
        }
        if (laneSessionHeld()) {
            long heldMs = laneSessionAcquiredMs > 0
                    ? android.os.SystemClock.uptimeMillis()
                            - laneSessionAcquiredMs
                    : 0;
            log("lane: session released — "
                    + laneBeginName(laneSessionKind) + " to "
                    + laneSessionDeviceName + ", "
                    + laneSessionItems + " item(s), held "
                    + heldMs + "ms");
        }
        lanePlaybackActive = false;
        lanePinActive = false;
        laneSessionKind = LANE_BEGIN_LEGACY;
        laneSessionDeviceName = null;
        laneSessionAcquiredMs = 0;
        laneSessionItems = 0;
        laneSessionHoldLogged = false;
        laneTransitionUntilMs =
                android.os.SystemClock.uptimeMillis() + 1500;
        laneDevice = null;
        // If the TTS engine's attributes were switched to the
        // voice-communication usage for a laned utterance, put them
        // back to the media default so later non-lane speech is
        // exactly as before.
        if (laneTtsAttrsSet) {
            laneTtsAttrsSet = false;
            try {
                if (tts != null) {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                }
            } catch (Exception ignored) {}
        }
        if (wasHeadset) log("lane: headset channel released");
    }

    /**
     * Earbuds vanished mid-play (AudioDeviceCallback): stop NOW and
     * return the item to the head of its queue UNPLAYED — no
     * progress report, so delete-on-heard never fires; it replays
     * from the start when the earbuds reconnect.
     */
    private void laneAbortPlayback() {
        if (!lanePlaybackActive && !laneSessionHeld()) return;
        if (!playing && !ttsSpeaking) {
            // The session was in its idle hold-over between items —
            // nothing is playing to return; just end the session.
            log("lane: session ended — earbuds lost during "
                    + "hold-over");
            laneEnd();
            return;
        }
        log("lane: earbuds lost mid-play — message returned to "
                + "queue unplayed");
        if (playing && currentAudioFile != null) {
            File f = currentAudioFile;
            String sid = currentPlayServerId;
            String grp = currentPlaySegmentGroup;
            releasePlayer(); // a synchronous release fires no completion
            playing = false;
            isPlayingAudio = false;
            setPlayingAudio(false);
            if (sid != null) {
                audioFileServerIds.put(f.getAbsolutePath(), sid);
            }
            if (grp != null) {
                audioFileSegmentGroups.put(f.getAbsolutePath(), grp);
            }
            synchronized (audioQueue) {
                ((LinkedList<File>) audioQueue).addFirst(f);
            }
            currentAudioFile = null;
            currentPlayServerId = null;
            currentPlaySegmentGroup = null;
        } else if (ttsSpeaking && ttsCurrentText != null) {
            String text = ttsCurrentText;
            String sid = currentPlayServerId;
            String grp = currentPlaySegmentGroup;
            suppressNextTtsDone = true;
            try {
                if (tts != null) tts.stop();
            } catch (Exception ignored) {}
            ttsSpeaking = false;
            isPlayingAudio = false;
            setPlayingAudio(false);
            synchronized (ttsQueue) {
                ((LinkedList<String>) ttsQueue).addFirst(text);
                ((LinkedList<String>) ttsServerIdQueue).addFirst(sid);
                ((LinkedList<String>) ttsSegmentGroupQueue).addFirst(grp);
            }
            ttsCurrentText = null;
            ttsResumeAtChar = 0;
            currentPlayServerId = null;
            currentPlaySegmentGroup = null;
        }
        laneEnd();
        broadcastPlaybackState();
    }

    /**
     * Watch for the named earbuds coming and going: arrival releases
     * held playback; departure mid-play aborts it (above). Registered
     * in onCreate, unregistered in onDestroy. The callback object is
     * LaneDeviceCallback (top-level, per the standing d8 rule).
     */
    private void registerLaneDeviceWatch() {
        if (audioManager == null || laneDeviceCallback != null) return;
        laneDeviceCallback = new LaneDeviceCallback(this);
        try {
            audioManager.registerAudioDeviceCallback(
                    laneDeviceCallback, handler);
            log("lane: device watch registered");
        } catch (Exception e) {
            log("lane: device watch failed: " + e);
            laneDeviceCallback = null;
        }
    }

    private void unregisterLaneDeviceWatch() {
        if (audioManager != null && laneDeviceCallback != null) {
            try {
                audioManager.unregisterAudioDeviceCallback(
                        laneDeviceCallback);
            } catch (Exception ignored) {}
        }
        laneDeviceCallback = null;
    }

    /** LaneDeviceCallback: an output device appeared. */
    public void onLaneDevicesAdded(AudioDeviceInfo[] added) {
        invalidateLaneRouteCache();
        if (!laneConfigured()) return;
        for (AudioDeviceInfo d : added) {
            if (nameMatchesEarbuds(d.getProductName())) {
                // v1.75: a return cancels any pending loss-abort.
                if (lanePendingAbort != null) {
                    handler.removeCallbacks(lanePendingAbort);
                    lanePendingAbort = null;
                    log("lane: earbuds back — pending loss-abort "
                            + "cancelled");
                }
                log("lane: earbuds connected (" + d.getProductName()
                        + ") — releasing held playback");
                handler.post(() -> {
                    // v1.75 ORDERLY DRAIN: parked priority items
                    // release ONE chain at a time, in order — the
                    // head starts and the rest follow through the
                    // normal completion chain. The queue pumps do
                    // NOT fire in the same tick: in the 16:18 field
                    // test the pump started a queued MP3 and the
                    // first parked priority item interrupted it at
                    // 0 ms, then the next interrupted that —
                    // confetti. New genuinely-urgent arrivals keep
                    // their normal rights (they queue behind an
                    // active priority chain by the standing rule).
                    if (!waitingPriority.isEmpty() && !priorityMode) {
                        if (!playing && !ttsSpeaking) {
                            log("lane: draining parked items in "
                                    + "order (" + waitingPriority.size()
                                    + " waiting)");
                            playNextWaitingPriority();
                        }
                        return;
                    }
                    pumpAudioQueue();
                    pumpTtsQueue();
                });
                return;
            }
        }
    }

    /** LaneDeviceCallback: an output device went away. */
    public void onLaneDevicesRemoved(AudioDeviceInfo[] removed) {
        invalidateLaneRouteCache();
        if (!lanePlaybackActive) return;
        for (AudioDeviceInfo d : removed) {
            if (nameMatchesEarbuds(d.getProductName())) {
                // v1.75 PRESENCE-LOSS DEBOUNCE: the 16:18 flap was
                // self-induced — SCO device churn while the lane's
                // own communication route was being set/torn down
                // reported the earbuds "lost" mid-play and aborted
                // a healthy play. A removal inside the lane's own
                // route-transition window is ignored outright, and
                // any other removal must survive a 3 s confirmation
                // (earbuds still absent when it fires) before it
                // aborts playback.
                long now = android.os.SystemClock.uptimeMillis();
                if (now < laneTransitionUntilMs) {
                    log("lane: earbuds removal during the lane's own "
                            + "route transition — ignored");
                    return;
                }
                if (lanePendingAbort != null) {
                    handler.removeCallbacks(lanePendingAbort);
                }
                lanePendingAbort = () -> {
                    lanePendingAbort = null;
                    if (lanePlaybackActive && !earbudsPresent()) {
                        laneAbortPlayback();
                    } else {
                        log("lane: presence flap ignored — earbuds "
                                + "present again within the debounce");
                    }
                };
                handler.postDelayed(lanePendingAbort, 3000);
                log("lane: earbuds removal seen — confirming for 3s "
                        + "before any abort");
                return;
            }
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

    // Nick 2026-10-30 v1.73: earbuds-only playback toggle. Default
    // TRUE — Nick ordered the behavior; the lane still stays inert
    // until an earbuds name is saved (laneConfigured()).
    public static boolean getEarbudsOnly(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_EARBUDS_ONLY, true);
    }

    public static void setEarbudsOnly(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_EARBUDS_ONLY, on).apply();
    }

    // Nick 2026-10-30 v1.77: headset fallback toggle. Default TRUE
    // — Nick's stated preference (v1.75's audible half-and-half over
    // v1.76's parked silence). OFF restores v1.76 park-on-refusal.
    public static boolean getHeadsetFallback(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_HEADSET_FALLBACK, true);
    }

    public static void setHeadsetFallback(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_HEADSET_FALLBACK, on).apply();
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
