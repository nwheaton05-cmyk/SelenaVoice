package com.selena.voice;

import android.Manifest;
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
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
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
    // Nick 2026-10-28 v1.52: noise-gate state riding the listening-state
    // broadcast ("ambient" / "killed" / "noise_hold") so the UI can show
    // the subtle "Paused — loud noise" state (spec §4.5).
    public static final String EXTRA_GATE_STATE = "gate_state";
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

    // v1.50 (2026-10-26, OVH batch): owned-domain addresses replace
    // the DuckDNS bridge names (same OVH server, permanent names).
    private static final String BASE_URL = "https://siteviewoperations.com";
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
    // Nick 2026-10-18 v1.3: was 500ms — the Samsung A17 plays a system
    // beep on every recognizer start, so 500ms restarts = constant beeping.
    // 5s backoff = far fewer beeps. NO_MATCH/TIMEOUT use quiet restart.
    private static final long RESTART_DELAY_MS = 5000;

    // Nick 2026-10-18 v1.2: ADJUST_MUTE did NOT stop the beep on the
    // Samsung A17 — the recognizer beep apparently plays on a stream that
    // ignores the mute flag. New approach: save each stream's volume and
    // set it to 0 (not mute), then restore. STREAM_VOICE_CALL added too.
    // Nick 2026-10-29 v1.63: STREAM_MUSIC is OUT of this set, permanently.
    // His standing rule (14:32 EDT field report): playback must NEVER
    // change the system media volume, in either direction — the media
    // stream is simply never written by this app again. The remaining
    // streams carry no media and none of her voice; they are zeroed
    // only around recognizer restarts to kill the Samsung beep (his
    // v1.2–v1.48 demand) and restored immediately after.
    private static final int[] BEEP_STREAMS = {
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_VOICE_CALL,
    };
    private final int[] savedVolumes = new int[BEEP_STREAMS.length];
    private boolean beepMuted = false;

    // Nick 2026-10-29 v1.63: the v1.17 STREAM_MUSIC boost state
    // (savedMusicVolume) is GONE — the boost was disabled in v1.48 and
    // its restore machinery is now removed entirely. Nothing in this
    // app raises or lowers STREAM_MUSIC; her loudness is her player's
    // own output at the level Nick set, and music ducking is the
    // MAY_DUCK audio-focus layer only (the music app lowers ITSELF).

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
    // Nick 2026-10-29 v1.59 (ducking rule): a SECOND, independent focus
    // request held only while a dictation is actively being captured,
    // so other audio (Spotify/Pandora) fades DOWN while Nick talks and
    // fades back when he stops — it never hard-pauses. Independent of
    // the playback request above: either can be held without the
    // other, and the music only un-ducks once BOTH are gone. Release
    // is driven by the dictation lifecycle (sentence flush,
    // recognizer-done, held-sentence send) plus a staleness watchdog —
    // a stuck hold that leaves music ducked forever is the failure
    // this design refuses.
    private AudioFocusRequest dictationFocusRequest;
    private volatile boolean dictationDuckHeld = false;
    private volatile long lastDictationActivityMs = 0;
    private Runnable dictationDuckWatchdog = null;
    private static final long DICTATION_DUCK_GRACE_MS = 3000;
    private static final long DICTATION_DUCK_STALE_MS = 20000;
    private static final long DICTATION_DUCK_WATCHDOG_MS = 5000;
    // Nick 2026-10-29 v1.63: the v1.61 DEEP DUCK fields lived here
    // (DEEP_DUCK_FRACTION, holder flags, saved/target volume, ramp
    // generation, watchdog). ALL REMOVED under Nick's standing rule
    // (14:32 EDT): this app never writes the system media volume.
    // With no music playing, the ramp + capped compensation was
    // netting out negative on Selena's OWN voice — his exact field
    // symptom. Ducking is the MAY_DUCK focus layer above, and only
    // that: the music app lowers its own playback; the knob never
    // moves, and her voice always plays at the level he set.
    // Nick 2026-10-29 v1.60 (true wake word spike): sherpa-onnx
    // keyword spotter (WakeWordSpotter) + the app-side conversation
    // window it opens. While the spotter is healthy and no
    // conversation is open, the spotter is the SOLE mic consumer and
    // the SpeechRecognizer rests; a spot (or the transcript fallback)
    // opens the window for WAKE_CONVO_WINDOW_MS — the same 2 minutes
    // as the web rule — during which dictations post exactly as they
    // did pre-v1.60. Outside the window, dictations without the wake
    // word are held (never posted). If the spotter fails to init,
    // wakeSpotterFailed fails the whole mode OPEN: recognizer always
    // on, everything posts, exactly v1.59 behavior.
    private WakeWordSpotter wakeSpotter = null;
    private volatile boolean wakeSpotterReady = false;
    private volatile boolean wakeSpotterFailed = false;
    private volatile boolean wakeConvoOpen = false;
    private volatile long wakeConvoUntilMs = 0;
    private Runnable wakeExpiryTask = null;
    private static final long WAKE_CONVO_WINDOW_MS = 120000;
    // Drone mirror for spot suppression, on the spotter's PCM scale
    // (dBFS — NOT the recognizer's RMS scale the gate constants use):
    // a mower at earbud distance is very loud and very flat.
    private static final float WAKE_DRONE_MEAN_DBFS = -25.0f;
    private static final float WAKE_DRONE_STD_DB = 2.0f;
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

    // ------------------------------------------------------------ v1.52 noise gate
    // Nick 2026-10-28 (spec §3/§4): a cheap sampler IN FRONT OF the
    // recognizer. The old app let SpeechRecognizer's own VAD decide
    // "someone is talking" — a mower is loud, so it opened dictation
    // sessions on engine noise and held them open. The gate watches
    // level + variation and kills the recognizer under a loud, steady
    // drone. Sampling NEVER opens a second mic consumer while the
    // recognizer is live (Android gives the mic to one client
    // reliably; two consumers starve/degrade each other, worst on
    // Samsung's engine):
    //   - recognizer live  -> samples come from its onRmsChanged
    //     stream (ServiceRecognitionListener.samplerStats).
    //   - recognizer held off (KILLED) -> short AudioRecord probes
    //     (GateProbeTask) are the only mic consumer and watch for the
    //     two re-arms: drone stops, or speech bursts over the drone.
    // Failure behavior (§4): any sampler error disables the gate and
    // the app falls back to plain recognizer behavior — fail open for
    // the user, closed for noise. A manual mic tap (or an earbud
    // dictation request) forces a session open, gate bypassed for
    // that session (§4.1).
    private static final int GATE_AMBIENT = 0;
    private static final int GATE_KILLED = 1;
    // Nick 2026-10-29 v1.61: DRONE — the extraction session
    // (DroneSession) owns the mic and dictation stays alive over a
    // running machine. Entered instead of KILLED whenever the
    // extraction pipeline is ready (Nick's order: extraction active
    // + session alive over kill); any failure inside it falls back
    // to KILLED, i.e. exact v1.60 behavior.
    private static final int GATE_DRONE = 2;
    private volatile int gateState = GATE_AMBIENT;
    private boolean gateDisabled = false;
    private boolean gateBypassSession = false;
    private long gateDroneSinceMs = 0;
    private long gateGraceUntilMs = 0;
    private long gateDroneAbsentSinceMs = 0;
    // Probe-side (dBFS) drone baseline, learned from the first probes
    // after a kill, then drift-tracked only by near-baseline samples
    // so a level drop can't drag the baseline down and hide itself.
    private float gateDroneBaselineDbfs = Float.NaN;
    private int gateProbeCount = 0;
    private int gateProbeDropCount = 0;
    private boolean gateProbeInFlight = false;
    private Runnable gateTickTask = null;
    private Runnable gateProbeScheduleTask = null;
    // Pause-and-remember (§3): a held dictation lives in sentenceBuffer
    // with these flags; the 60s noise clock (NOT the 4s sentence gap)
    // sends it flagged possiblyCutOff if no further dictation comes.
    private boolean noiseHoldActive = false;
    private boolean bufferCutOffFlag = false;
    private Runnable noiseHoldSendTask = null;
    private static final long GATE_TICK_MS = 250;
    // TUNABLE (spec §13.4 — kill timing left open by Nick; ~1s was the
    // proposal): how long loud-and-steady must persist before KILLED.
    private static final long GATE_KILL_SUSTAIN_MS = 1000;
    private static final float GATE_LOUD_DB = -9.0f;       // recognizer-scale mean level
    private static final float GATE_STEADY_STD_DB = 1.4f;  // recognizer-scale variation ceiling
    private static final long GATE_PROBE_INTERVAL_MS = 1200;
    private static final float GATE_PROBE_DROP_DB = 8.0f;      // drone-gone test (below baseline)
    private static final float GATE_PROBE_BURST_STD_DB = 2.5f; // speech-shaped variation (dBFS frames)
    private static final float GATE_PROBE_BURST_PEAK_DB = 5.0f; // burst peak above drone baseline
    private static final long NOISE_HOLD_SEND_MS = 60000;      // spec §3: 60s noise clock

    // Nick 2026-10-28 v1.53: learned voiceprint (Phase 4, spec §5).
    // The store/profiles live in Voiceprint; the service owns the one
    // live instance and feeds it from the acceptance path only. The
    // voiceprint's verdict is a booster at gate decision points —
    // it can open the gate wider, never narrower (§4.3).
    private Voiceprint voiceprint;
    private long vpDroneSeenMs = 0;              // last sampler drone sighting
    private long vpLastNoiseFeedMs = 0;          // noise-teaching throttle
    private float[] vpPendingBurstFeats = null;  // probe burst awaiting confirmation
    private long vpBurstConfirmUntilMs = 0;

    // Nick 2026-10-29 v1.61: Drone Dictation (extraction over a
    // running machine) + the per-machine profile library + the
    // naming prompts (13:21/13:25/13:27/13:28/13:31 rulings).
    // droneSession is built on a worker thread once the ASR model
    // download verifies; droneReady is the arm condition. The
    // naming capture: after a run of an UNNAMED machine ends, the
    // app asks "Hey, {user}, what was that noise?" and the next
    // short dictation inside the capture window becomes the name.
    // During the run it first says the playful line, verbatim with
    // its emphasis styling: "What IS that HORRIBLE noise?!"
    // Both prompts fire only while the profile is unnamed; a named
    // machine is never prompted again, ever.
    private MachineProfiles machineProfiles;
    private DroneSession droneSession;
    private volatile boolean droneReady = false;
    private volatile long droneSelfTalkUntilMs = 0;
    private int pendingNameProfileId = -1;
    private long namingCaptureUntilMs = 0;
    private static final long NAMING_CAPTURE_MS = 90000;
    public static final String DURING_DRONE_LINE = "What IS that HORRIBLE noise?!";

    // Nick 2026-10-29 v1.62: NEW-VOICE FLOW (standalone group mode,
    // 13:52/13:55 rulings). An accepted dictation whose recognizer
    // envelope matches NEITHER Nick's voiceprint NOR any enrolled
    // guest is a new-voice candidate: detection happens in
    // vpLearnAccepted (before anything learns — a guest must never
    // train Nick's print), the sentence flush HOLDS the post, she
    // asks "Who's talking?" / "Who's asking?" / "Who wants to
    // know?" (form by statement-vs-question, question forms
    // alternate), the reply enrolls the guest, and only then does
    // the held dictation post — tagged with the guest's name so the
    // answer addresses them by name. Unanswered (90 s) → the held
    // dictation posts untagged; no phantom guest is created.
    private GuestProfiles guestProfiles;
    private String bufferGuestName = null;
    private boolean bufferNewVoiceCandidate = false;
    private float[] pendingGuestFeats = null;
    private String pendingGuestSentence = null;
    private long guestNameCaptureUntilMs = 0;
    private long guestAskCooldownUntilMs = 0;
    private Runnable guestHoldReleaseTask = null;
    private static final long GUEST_NAME_CAPTURE_MS = 90000;
    private static final long GUEST_ASK_COOLDOWN_MS = 600000;
    /** Clearly-far line for "not Nick": MATCH_DIST is 2.4; a cold
     *  shifts an envelope, but not this far. Nudge-grade by design. */
    private static final double NEW_VOICE_MIN_DIST = 3.2;

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
    // can hold the ruled 3s gap before a same-group successor starts
    // (see advanceAfterSegmentGap).
    private String currentPlaySegmentGroup = null;
    // Nick 2026-10-29: 3-second spacing between segments of one
    // message — long enough to hear the seam, short enough to keep
    // the thought. NOT the dictation SENTENCE_GAP_MS further down.
    private static final long SEGMENT_GAP_MS = 3000;
    // A same-group advance waiting out its gap. Preemptible: pause /
    // stop / skip / priority cancel it (cancelPendingSegmentAdvance),
    // and a stale generation or already-started playback drops it.
    private Runnable pendingSegmentAdvance = null;
    private int segmentAdvanceGen = 0;
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
        WaitingPriority(File audioFile, String text, String serverId) {
            this(audioFile, text, serverId, null);
        }
        WaitingPriority(File audioFile, String text, String serverId,
                String segmentGroup) {
            this.audioFile = audioFile;
            this.text = text;
            this.serverId = serverId;
            this.segmentGroup = segmentGroup;
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
        // Nick 2026-10-28 v1.53: learned voiceprint store (Phase 4,
        // spec §5) + an opportunistic archive sync attempt (upload of
        // week-old measurements / re-seed from the server archive).
        voiceprint = new Voiceprint(this);
        new Thread(new VoiceprintSyncTask(
                this, VoiceprintSyncTask.MODE_SYNC, voiceprint)).start();
        // Nick 2026-10-28 v1.51: register the media session so Bluetooth
        // earbud/headset button presses are delivered to this app.
        initMediaSession();
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
        // Nick 2026-10-28 v1.52: noise-gate watchdog (250ms tick,
        // near-free when nothing is being listened to).
        startGateWatchdog();
        // Nick 2026-10-29 v1.60: wake word spotter loads async; when
        // it's ready it takes over the idle mic (see onWakeSpotterReady
        // for the handover). Until then the recognizer path above is
        // untouched — and stays untouched if init fails (fail open).
        initWakeSpotterAsync();
        // Nick 2026-10-29 v1.61: Drone Dictation prep — machine
        // profile store up front; the ASR model downloads/verifies
        // in the background and the extraction session builds when
        // it's ready. Until droneReady, the gate is exactly v1.60.
        machineProfiles = new MachineProfiles();
        guestProfiles = new GuestProfiles();
        initDroneAsync();
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
                    // Nick 2026-10-29 v1.60: with a healthy spotter,
                    // switch-on now means spotter mode (the recognizer
                    // rests until a wake opens a conversation); without
                    // one, exactly the pre-v1.60 behavior.
                    refreshWakeMode();
                    if (wakeSpotter == null || !wakeSpotter.isRunning()) {
                        startContinuousListening();
                    }
                } else {
                    manualListening = false;
                    // v1.60: switching off also closes any wake-opened
                    // conversation and stands the spotter down.
                    wakeConvoOpen = false;
                    if (wakeExpiryTask != null) {
                        handler.removeCallbacks(wakeExpiryTask);
                        wakeExpiryTask = null;
                    }
                    if (wakeSpotter != null) wakeSpotter.stopCapture();
                    stopContinuousListening();
                }
                broadcastListeningState();
            } else if ("EARBUD_TAP".equals(intent.getAction())) {
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
            } else if ("RESET_VOICEPRINT".equals(intent.getAction())) {
                // Nick 2026-10-28 v1.53 (§5): per-profile reset. The
                // service owns the live voiceprint, so the reset lands
                // here (local profile + records); the sync task drops
                // the server archive under the user's profile.
                if (voiceprint != null) voiceprint.resetCurrentUser();
                new Thread(new VoiceprintSyncTask(
                        this, VoiceprintSyncTask.MODE_RESET)).start();
                log("voiceprint: reset by user (local + server archive)");
            } else if ("MANUAL_LISTEN".equals(intent.getAction())) {
                // Nick 2026-10-28 v1.52 (§4.1): the mic tap is the
                // manual override — while the noise gate has the
                // recognizer killed, a tap forces a session open,
                // gate bypassed for that session. (recognizerActive
                // stays true while killed, so check the gate first.)
                if (gateState == GATE_DRONE) {
                    // Same override over drone mode: exitDroneMode
                    // sets the bypass session itself for this reason
                    // (forceGateOverride only acts from KILLED).
                    exitDroneMode("manual override");
                } else if (gateState == GATE_KILLED) {
                    forceGateOverride();
                }
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
        releaseDictationDuck("service destroyed"); // v1.59 safety net
        // Nick 2026-10-29 v1.60: stop + release the wake word spotter.
        if (wakeExpiryTask != null) {
            handler.removeCallbacks(wakeExpiryTask);
            wakeExpiryTask = null;
        }
        if (wakeSpotter != null) {
            wakeSpotter.release();
            wakeSpotter = null;
        }
        if (droneSession != null) {
            droneSession.release();
            droneSession = null;
        }
        unmuteBeep();
        recognizerActive = false;
        destroyRecognizer();
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
                // v1.63: STREAM_MUSIC is not in BEEP_STREAMS anymore,
                // so this loop can never touch her playback stream —
                // the v1.18 mid-playback skip that used to live here
                // is unnecessary by construction.
                savedVolumes[i] = audioManager.getStreamVolume(BEEP_STREAMS[i]);
                audioManager.setStreamVolume(BEEP_STREAMS[i], 0, 0);
                // Nick 2026-10-23 v1.48: also hard-mute — Samsung's beep
                // ignores volume-zero on some streams. setStreamMute is
                // stronger. Nick wants the beep GONE.
                try { audioManager.setStreamMute(BEEP_STREAMS[i], true); } catch (Exception ignored) {}
            }
            beepMuted = true;
            Log.d(TAG, "beep streams zeroed+muted");
        } catch (Exception e) {
            Log.w(TAG, "muteBeep failed", e);
        }
    }

    private void unmuteBeep() {
        if (!beepMuted) return;
        try {
            for (int i = 0; i < BEEP_STREAMS.length; i++) {
                // Nick 2026-10-23 v1.48: undo the hard-mute from muteBeep().
                try { audioManager.setStreamMute(BEEP_STREAMS[i], false); } catch (Exception ignored) {}
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
            if (!"low".equals(priority)) {
                markSpokenLocked(text);
                if (isPhoneVoiceMode()) {
                    final String speakText = text;
                    handler.post(() -> onPriorityArrivalTts(speakText, sid, sgroup));
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
                        handler.post(() -> onPriorityArrivalMp3(pf, ptext, psid, sgroup));
                    } catch (Exception e) {
                        log("priority: MP3 download failed: "
                                + e.getMessage() + " — TTS fallback");
                        final String speakText = text;
                        handler.post(() -> onPriorityArrivalTts(speakText, sid, sgroup));
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
                            handler.post(() -> onPriorityArrivalMp3(sf, stext, ssid, sgroup));
                        } catch (Exception e) {
                            log("priority: synthesis failed, on-device TTS "
                                    + "fallback: " + e.getMessage());
                            handler.post(() -> onPriorityArrivalTts(stext, ssid, sgroup));
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
            // Undo the mic-beep zeroing first so playback uses
            // his REAL volume. (The beep set no longer contains
            // STREAM_MUSIC at all — v1.63.)
            // Nick 2026-10-19 v1.17's boost-to-max once lived here;
            // Nick 2026-10-29 v1.63 removed it entirely: her voice
            // plays at exactly the level he set, and other audio is
            // ducked only by the MAY_DUCK focus request below.
            unmuteBeep();
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
            // Nick 2026-10-23 v1.47: stop the mic BEFORE her voice
            // comes out, so it can't be transcribed as Nick's input.
            muteMicForPlayback();
            player.start();
            isPlayingAudio = true;
            playbackPaused = false;
            log("playback started (echo guard on)");
            currentAudioFile = f;
            // v1.55: adopt the row id that rode with this file, when
            // one did (the priority path sets it explicitly beforehand
            // and its files never enter the map).
            String mappedSid = audioFileServerIds.remove(f.getAbsolutePath());
            if (mappedSid != null) currentPlayServerId = mappedSid;
            // v1.58: adopt the segment group the same way (the
            // priority path sets it explicitly beforehand, mirroring
            // the row id).
            String mappedGroup = audioFileSegmentGroups.remove(
                    f.getAbsolutePath());
            if (mappedGroup != null) currentPlaySegmentGroup = mappedGroup;
            broadcastPlaybackState();
        } catch (Exception e) {
            log("play failed: " + e);
            onPlaybackDone();
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
        reportPlaybackProgress(); // v1.55: before any unwinding below
        abandonAudioFocus();
        // v1.63: nothing to restore — no volume was written on the
        // way in (the v1.17 boost restore and the v1.61 deep-duck
        // release that lived here are both deleted).
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
            }
            // Nick 2026-10-23 v1.47: if the pumps didn't start new
            // playback, she's fully done — restart the mic if we muted it.
            if (!isPlayingAudio) unmuteMicAfterPlayback();
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
     * polite gate parks it like any other start, and if anything has
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
        Runnable delayed = () -> {
            if (segmentAdvanceGen != gen) return; // cancelled/superseded
            pendingSegmentAdvance = null;
            if (politeHoldActive()) {
                // Nick is talking — park exactly like an arrival
                // during speech; the polite-hold release pumps the
                // queues, so nothing is lost by not advancing here.
                log("segments: gap elapsed under polite hold — parked");
                return;
            }
            advance.run();
        };
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
        if (priorityMode && !waitingPriority.isEmpty()) {
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
        if (priorityMode && !waitingPriority.isEmpty()) {
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
            // v1.55: adopt the queued row id for this utterance (the
            // priority path sets currentPlayServerId itself and leaves
            // pending null, so it is never clobbered here).
            if (pendingTtsServerId != null) {
                currentPlayServerId = pendingTtsServerId;
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
        // Same routing as MP3 playback: loudspeaker, and undo the
        // mic-beep zeroing first so we use Nick's real device
        // volume (v1.10: no override, respect his setting — made
        // absolute in v1.63: nothing here writes the media volume;
        // the v1.17 boost call that lived in this spot is deleted).
        unmuteBeep();
        // Nick 2026-10-18 v1.11: Bluetooth-aware — earbuds win over the
        // speakerphone when connected, same as MP3 playback.
        routeAudioForPlayback();
        log("tts: stream=MUSIC");
        requestAudioFocus();
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
            // Nick 2026-10-23 v1.48: ensureMaxMusic DISABLED (volume raiser).
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
        // v1.58: capture the finished item's segment group BEFORE the
        // progress report clears the tracker.
        final String finishedGroup = currentPlaySegmentGroup;
        reportPlaybackProgress(); // v1.55: before any unwinding below
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
            }
            // Nick 2026-10-23 v1.47: if the pumps didn't start new
            // playback, she's fully done — restart the mic if we muted it.
            if (!isPlayingAudio) unmuteMicAfterPlayback();
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

    private void pausePlayback() {
        // v1.58: pausing during a segment gap cancels the pending
        // start — the successor stays queued and the pumps (or the
        // polite-hold release) start it later with no gap owed.
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
            final String serverId, final String segmentGroup) {
        log("priority: playing high-priority MP3"
                + (serverId != null ? " id=" + serverId : ""));
        priorityMode = true;
        interruptCurrentPlayback();
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
        playFileNow(f);
    }

    /**
     * Nick 2026-10-23 v1.40: speak a high-priority text immediately via
     * on-device TTS, interrupting whatever is playing. Runs on main thread.
     */
    private void playPriorityTts(final String text, final String serverId,
            final String segmentGroup) {
        log("priority: speaking high-priority text via on-device TTS");
        priorityMode = true;
        interruptCurrentPlayback();
        markSpokenLocked(text);
        broadcastMessage(text);
        MessageHistory.add(text, null, serverId);
        toast("Priority message");
        ttsSpeaking = true;
        // v1.55: after the interrupt snapshot (see playPriorityMp3).
        currentPlayServerId = serverId;
        // v1.58: same for the segment group.
        currentPlaySegmentGroup = segmentGroup;
        speakOnDevice(text, false);
    }

    /**
     * Nick 2026-10-23 v1.42: priority FIFO decision point for MP3 items.
     * Runs on the main thread. If a priority item is already playing, the
     * newcomer does NOT interrupt it — it queues behind it and plays
     * right after it finishes.
     */
    private void onPriorityArrivalMp3(final File f, final String text,
            final String serverId, final String segmentGroup) {
        // v1.58: a priority arrival preempts any pending post-gap
        // start — whether it plays now or parks, it outranks the gap.
        cancelPendingSegmentAdvance();
        if (priorityMode) {
            waitingPriority.offer(new WaitingPriority(f, text, serverId,
                    segmentGroup));
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
            waitingPriority.offer(new WaitingPriority(f, text, serverId,
                    segmentGroup));
            log("priority: held for Nick's pause ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        playPriorityMp3(f, text, serverId, segmentGroup);
    }

    /**
     * Nick 2026-10-23 v1.42: priority FIFO decision point for on-device
     * TTS items. Runs on the main thread. Same rule as
     * {@link #onPriorityArrivalMp3} — queue behind a playing priority
     * item, never interrupt it.
     */
    private void onPriorityArrivalTts(final String text, final String serverId,
            final String segmentGroup) {
        // v1.58: priority preempts a pending post-gap start (see
        // onPriorityArrivalMp3).
        cancelPendingSegmentAdvance();
        if (priorityMode) {
            waitingPriority.offer(new WaitingPriority(null, text, serverId,
                    segmentGroup));
            log("priority: queued behind playing priority ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        // Nick 2026-10-23 v1.46: polite interruption — park behind the
        // gate instead of breaking in over Nick's voice. Released on his
        // pause via releasePoliteHold().
        if (politeHoldActive()) {
            waitingPriority.offer(new WaitingPriority(null, text, serverId,
                    segmentGroup));
            log("priority: held for Nick's pause ("
                    + waitingPriority.size() + " waiting)");
            toast("Priority message queued");
            return;
        }
        playPriorityTts(text, serverId, segmentGroup);
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
            playPriorityMp3(wp.audioFile, wp.text, wp.serverId,
                    wp.segmentGroup);
        } else {
            playPriorityTts(wp.text, wp.serverId, wp.segmentGroup);
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
     * has content (playing or paused) or is listening — the states
     * where a press is plausibly FOR her. Fully idle → inactive, and
     * the music apps get their presses back. Tradeoff, deliberately
     * kept working: with always-listening on (Nick's normal mode) the
     * session stays active, so earbud pause/dictation behave exactly
     * as v1.51; the deactivation only bites when listening is off AND
     * nothing is playing — and even then the manifest
     * EarbudButtonReceiver can still deliver a press to start
     * dictation on devices that fall back to the broadcast path.
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
     * playing, paused-holding, speaking, or listening (including
     * gate-killed: recognizerActive stays true while killed). Only
     * touches the session on transitions.
     */
    private void refreshMediaSessionActive() {
        if (mediaSession == null) return;
        boolean want = safeIsPlaying() || ttsSpeaking
                || playbackPaused || ttsPaused
                || recognizerActive;
        if (want == mediaSessionActiveWanted) return;
        mediaSessionActiveWanted = want;
        try {
            mediaSession.setActive(want);
            log("earbud: media session " + (want
                    ? "activated (Selena has content or is listening)"
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
     * Semantics (Nick's ask: "pause the readout / start dictation"):
     *   toggle — playing -> pause; paused -> resume; idle -> dictation.
     *   play   — paused -> resume; idle -> dictation.
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
                + " paused=" + (playbackPaused || ttsPaused)
                + " recognizerActive=" + recognizerActive);
        switch (action) {
            case "toggle":
                if (playbackPaused || ttsPaused) resumePlayback();
                else if (safeIsPlaying() || ttsSpeaking) pausePlayback();
                else startDictationFromEarbud();
                break;
            case "play":
                if (playbackPaused || ttsPaused) resumePlayback();
                else if (!safeIsPlaying() && !ttsSpeaking) {
                    startDictationFromEarbud();
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
     * Nick 2026-10-28 v1.51: earbud tap while idle = start dictating.
     * If always-listening already has the recognizer running there is
     * nothing to start — say so instead of silently doing nothing.
     *
     * Nick 2026-10-28 v1.52: while the noise gate has the recognizer
     * killed, recognizerActive is still true but no session is live —
     * an earbud dictation request is a manual override (§4.1), same
     * as the on-screen mic tap.
     */
    private void startDictationFromEarbud() {
        if (gateState == GATE_DRONE) {
            exitDroneMode("manual override");
            toast("Listening");
            return;
        }
        if (gateState == GATE_KILLED) {
            forceGateOverride();
            toast("Listening");
            return;
        }
        if (recognizerActive) {
            log("earbud: dictation requested, already listening");
            toast("Already listening");
            return;
        }
        manualListening = true;
        startContinuousListening();
        toast("Listening");
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
        try {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------ v1.59 dictation duck

    /**
     * Nick 2026-10-29 v1.59 (ducking rule): Nick started dictating —
     * fade other audio down for the capture. Called from the
     * recognizer's real speech onset (onSpeechDetected), NOT for the
     * whole always-listening idle wait (music would sit ducked
     * forever). Re-entry while held only refreshes activity and the
     * watchdog. The batching window stays ducked because chunks and
     * partials keep refreshing activity and the release requires an
     * empty sentence buffer — mid-thought pauses between sentences
     * of one dictation never pop the music back up.
     */
    private void acquireDictationDuck() {
        lastDictationActivityMs = android.os.SystemClock.uptimeMillis();
        armDictationDuckWatchdog();
        if (dictationDuckHeld) return;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            dictationFocusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attrs)
                    .build();
            int rc = audioManager.requestAudioFocus(dictationFocusRequest);
            dictationDuckHeld = rc == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            log("duck: dictation focus " + (dictationDuckHeld
                    ? "held — other audio fades down" : "NOT granted rc=" + rc));
        } catch (Exception e) {
            log("duck: dictation focus request failed: " + e);
        }
        // v1.63: the MAY_DUCK focus request above is the WHOLE duck
        // now — the v1.61 manual ramp that rode this lifecycle is
        // deleted (standing rule: never write the media volume).
    }

    /** Partials/chunks/speech-end while the duck is held = still capturing. */
    private void noteDictationActivity() {
        if (dictationDuckHeld) {
            lastDictationActivityMs = android.os.SystemClock.uptimeMillis();
        }
    }

    /**
     * Release the dictation duck when the dictation is truly over:
     * he's not speaking, nothing is buffered awaiting its flush, and
     * the tail grace has passed (covers the ~1s between speech-end
     * and the final chunk landing). Called from the sentence flush,
     * the held-sentence send, recognizer-done, and the watchdog; the
     * watchdog additionally force-releases on staleness, so no path
     * can leave Spotify ducked forever.
     */
    private void maybeReleaseDictationDuck() {
        if (!dictationDuckHeld) return;
        if (nickSpeaking) return;
        if (sentenceBuffer.length() > 0) return;
        long idle = android.os.SystemClock.uptimeMillis()
                - lastDictationActivityMs;
        if (idle < DICTATION_DUCK_GRACE_MS) return;
        releaseDictationDuck("dictation ended");
    }

    private void releaseDictationDuck(String why) {
        if (!dictationDuckHeld && dictationFocusRequest == null) return;
        try {
            if (dictationFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(dictationFocusRequest);
            }
        } catch (Exception ignored) {}
        dictationFocusRequest = null;
        dictationDuckHeld = false;
        if (dictationDuckWatchdog != null) {
            handler.removeCallbacks(dictationDuckWatchdog);
            dictationDuckWatchdog = null;
        }
        log("duck: released (" + why + ") — music fades back");
    }

    private void armDictationDuckWatchdog() {
        if (dictationDuckWatchdog != null) return;
        // A lambda, not an anonymous Runnable class, on purpose: this
        // codebase keeps service callbacks in lambdas / named task
        // classes — the one anonymous inner class tried here made the
        // build-tools 34 d8 fail with an internal NPE (nestmate
        // desugaring), 2026-10-29.
        dictationDuckWatchdog = () -> {
            dictationDuckWatchdog = null;
            if (!dictationDuckHeld) return;
            long idle = android.os.SystemClock.uptimeMillis()
                    - lastDictationActivityMs;
            if (idle > DICTATION_DUCK_STALE_MS) {
                // No speech/chunk/partial activity for 20s: whatever
                // the bookkeeping says, the capture is not live.
                releaseDictationDuck("watchdog: stale " + idle + "ms");
                return;
            }
            if (!nickSpeaking && sentenceBuffer.length() == 0
                    && idle >= DICTATION_DUCK_GRACE_MS) {
                releaseDictationDuck("watchdog: dictation over");
                return;
            }
            armDictationDuckWatchdog();
        };
        handler.postDelayed(dictationDuckWatchdog, DICTATION_DUCK_WATCHDOG_MS);
    }

    // ------------------------------------------------------------ deep duck: REMOVED (v1.63)
    // The v1.61 manual deep duck (acquireDeepDuck / releaseDeepDuck /
    // rampMusicVolume / its watchdog) lived here. DELETED under
    // Nick's standing rule (2026-10-29 14:32 EDT): this app never
    // writes the system media volume — no ramp down, no restore, no
    // compensation. The field symptom it caused: with NO music
    // playing, the ramp + capped LoudnessEnhancer offset netted out
    // negative on Selena's own MP3 voice. Ducking is now exactly the
    // v1.59 layer: MAY_DUCK audio-focus requests (playback +
    // dictation), under which the music app lowers its own playback
    // and the system knob never moves.

    // ------------------------------------------------------------ v1.60 wake word (sherpa-onnx KWS)

    /**
     * Load the spotter off the main thread. Until it is ready, the
     * recognizer path runs exactly as before; if it never becomes
     * ready, it stays that way (fail open).
     */
    private void initWakeSpotterAsync() {
        new Thread(() -> {
            WakeWordSpotter candidate = new WakeWordSpotter(this);
            if (candidate.init(this)) {
                wakeSpotter = candidate;
                wakeSpotterReady = true;
                handler.post(this::onWakeSpotterReady);
            } else {
                wakeSpotterFailed = true;
                log("wakeword: spotter unavailable (init failed) — "
                        + "transcript wake word remains the only path");
            }
        }, "SelenaWakeInit").start();
    }

    /**
     * Main thread, spotter ready: if the assistant is in the plain
     * idle state (always-listening on, no conversation, nobody
     * mid-dictation, nothing playing), hand the mic over — recognizer
     * rests, spotter listens. Any busier state is left alone; the
     * spotter joins at the next natural transition (window expiry,
     * toggle) via refreshWakeMode().
     */
    private void onWakeSpotterReady() {
        log("wakeword: spotter ready — 'Insight' / 'Hey Insight' (sherpa-onnx KWS, on-device)");
        if (destroyed || !isAlwaysListening() || wakeConvoOpen
                || manualListening) {
            return;
        }
        if (recognizerActive && !nickSpeaking && sentenceBuffer.length() == 0
                && !isPlayingAudio() && gateState != GATE_KILLED) {
            stopContinuousListening();
        }
        refreshWakeMode();
    }

    /**
     * The mode arbiter: start the spotter capture iff it should be
     * the idle listener right now; stop it when a conversation is
     * open, a manual session owns the mic, always-listening is off,
     * or the service is going away. The recognizer-active and gate
     * conditions are ALSO re-checked per-chunk inside the capture
     * loop (wakeSpotterParked), so a transition this method misses
     * can never produce two live mic consumers.
     */
    private void refreshWakeMode() {
        if (wakeSpotter == null) return;
        boolean want = wakeSpotterReady && !wakeSpotterFailed
                && isAlwaysListening() && !wakeConvoOpen && !manualListening
                && !destroyed && gateState != GATE_KILLED
                && gateState != GATE_DRONE && !recognizerActive;
        if (want) {
            boolean wasRunning = wakeSpotter.isRunning();
            wakeSpotter.startCapture();
            if (!wasRunning && wakeSpotter.isRunning()) {
                updateNotification("👂 Waiting for 'Insight'…");
                log("wakeword: spotter listening — recognizer resting");
            }
        } else if (wakeConvoOpen || manualListening || !isAlwaysListening()
                || destroyed) {
            wakeSpotter.stopCapture();
        }
    }

    /**
     * Called from the spotter's capture thread per chunk: park (mic
     * released, nothing fed, nothing captured) whenever anything else
     * owns the moment — the recognizer, Selena's own playback (echo),
     * the playback mic-mute, or a gate-killed drone session.
     */
    public boolean wakeSpotterParked() {
        return destroyed || !isAlwaysListening() || wakeConvoOpen
                || manualListening || recognizerActive || isPlayingAudio()
                || micMutedForPlayback || gateState == GATE_KILLED
                || gateState == GATE_DRONE;
    }

    /** Spotter thread → main thread shim. */
    public void onWakeWordSpotted(String keyword, float meanDbfs, float stdDbfs) {
        handler.post(() -> handleWakeSpot(keyword, meanDbfs, stdDbfs));
    }

    private void handleWakeSpot(String keyword, float meanDbfs, float stdDbfs) {
        if (destroyed || wakeConvoOpen) return;
        String levels = "mean " + String.format(Locale.US, "%.1f", meanDbfs)
                + " dBFS, std " + String.format(Locale.US, "%.1f", stdDbfs);
        if (gateState == GATE_KILLED || gateState == GATE_DRONE) {
            log("wakeword: spot '" + keyword + "' suppressed — gate killed (drone)");
            return;
        }
        // The gate's principle, mirrored on the spotter's own levels:
        // loud AND steady = machine, not a wake word. (The capture
        // loop parks while the gate is KILLED; this guards the window
        // where the drone is present but the gate — which samples the
        // recognizer, now resting — has not killed.)
        if (meanDbfs >= WAKE_DRONE_MEAN_DBFS && stdDbfs <= WAKE_DRONE_STD_DB) {
            log("wakeword: spot '" + keyword + "' suppressed — loud steady drone ("
                    + levels + ")");
            return;
        }
        log("wakeword: spotted '" + keyword + "' (" + levels
                + ") — opening conversation, recognizer taking over");
        openWakeConversation("spotter");
        new Thread(new WakeDirectiveTask(this, keyword)).start();
    }

    /**
     * Open (or re-open) the app-side conversation window and make
     * sure the recognizer is the live mic consumer. The window lasts
     * WAKE_CONVO_WINDOW_MS from the last posted dictation — the same
     * 2 minutes as the web rule.
     */
    private void openWakeConversation(String source) {
        wakeConvoOpen = true;
        wakeConvoUntilMs = gateNow() + WAKE_CONVO_WINDOW_MS;
        armWakeExpiry();
        if (wakeSpotter != null) wakeSpotter.stopCapture();
        // No-op when the recognizer is already running (transcript /
        // noise-hold sources); the entry point for the spotter source.
        startContinuousListening();
        log("wakeword: conversation open (" + source + ") — 2m window");
    }

    /** A posted dictation inside the window extends it. */
    private void noteWakeConversationActivity() {
        if (!wakeConvoOpen) return;
        wakeConvoUntilMs = gateNow() + WAKE_CONVO_WINDOW_MS;
        armWakeExpiry();
    }

    private void armWakeExpiry() {
        if (wakeExpiryTask != null) handler.removeCallbacks(wakeExpiryTask);
        wakeExpiryTask = () -> onWakeConvoExpiry();
        long delay = Math.max(1000L, wakeConvoUntilMs - gateNow());
        handler.postDelayed(wakeExpiryTask, delay);
    }

    private void onWakeConvoExpiry() {
        wakeExpiryTask = null;
        if (!wakeConvoOpen) return;
        // Mid-flow never closes: he's dictating, composing, in a
        // manual session, or she's mid-answer. Re-check shortly.
        if (nickSpeaking || sentenceBuffer.length() > 0 || manualListening
                || isPlayingAudio()) {
            wakeExpiryTask = () -> onWakeConvoExpiry();
            handler.postDelayed(wakeExpiryTask, 30000);
            return;
        }
        wakeConvoOpen = false;
        log("wakeword: conversation window closed (2m idle) — spotter resuming");
        if (isAlwaysListening() && !destroyed && gateState != GATE_KILLED
                && recognizerActive) {
            // stopContinuousListening first sends any noise-held
            // dictation — and that send RE-OPENS this window (the
            // exchange is still live). Honor the re-open: bring the
            // recognizer back instead of handing over to the spotter.
            stopContinuousListening();
            if (wakeConvoOpen) {
                startContinuousListening();
                return;
            }
        }
        refreshWakeMode();
    }

    /**
     * True when the v1.60 wake gate should hold a flushed dictation:
     * spotter engaged, no conversation open, not a manual/deliberate
     * session. Spotter-failed mode never blocks (fail open).
     */
    private boolean wakeGateBlocksPost() {
        if (wakeSpotter == null || !wakeSpotterReady || wakeSpotterFailed) {
            return false;
        }
        if (wakeConvoOpen) return false;
        if (manualListening || gateBypassSession) return false;
        return true;
    }

    /**
     * The transcript fallback, mirroring the web layer's detectWakeWord
     * placement rule: the wake word counts as the first word(s) of the
     * sentence — "insight" or "hey insight", case-insensitive, light
     * punctuation tolerated.
     */
    private boolean sentenceStartsWithWakeWord(String sentence) {
        if (sentence == null) return false;
        String t = sentence.trim().toLowerCase(Locale.US);
        t = t.replaceAll("^[^a-z]+", "");
        return t.equals("insight") || t.startsWith("insight ")
                || t.startsWith("insight,") || t.startsWith("insight.")
                || t.equals("hey insight") || t.startsWith("hey insight ")
                || t.startsWith("hey insight,") || t.startsWith("hey insight.");
    }

    /**
     * Spotter failure (init or runtime): fail OPEN — the mode
     * disables itself and the recognizer path resumes, exactly the
     * pre-v1.60 behavior. Called from any thread.
     */
    public void onWakeSpotterFailed(String reason) {
        if (wakeSpotterFailed) return;
        wakeSpotterFailed = true;
        log("wakeword: spotter failed (" + reason + ") — falling back to "
                + "always-recognizer; transcript wake word only");
        handler.post(() -> {
            if (wakeSpotter != null) wakeSpotter.stopCapture();
            if (!destroyed && isAlwaysListening() && !recognizerActive
                    && !manualListening) {
                startContinuousListening();
            }
        });
    }

    /**
     * POST the wake directive: {action:'wake_word_spotted', keyword}.
     * The input route records any string action into
     * selena_voice_directives (user_id null for app posts, same as the
     * app's dictation posts today) — the durable server-side record of
     * the spot; the brain treats the unknown kind gracefully.
     */
    public void doPostWakeDirective(String keyword) {
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
            body.put("action", "wake_word_spotted");
            body.put("keyword", keyword);
            body.put("timestamp", System.currentTimeMillis());
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignored) {}
            }
            int code = conn.getResponseCode();
            log("wakeword: directive posted (" + code + "): '" + keyword + "'");
        } catch (Exception e) {
            Log.w(TAG, "wakeword: directive post failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
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
        // Nick 2026-10-29 v1.60: the recognizer taking the mic always
        // stands the wake spotter down first — never two live mic
        // consumers (the spotter's own capture loop also parks itself
        // whenever the recognizer is active, as a backstop).
        if (wakeSpotter != null) wakeSpotter.stopCapture();
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
        // Nick 2026-10-28 v1.52: ending listening ends any noise hold —
        // send the held dictation (flagged) rather than losing the
        // user's words, and reset the gate for the next session.
        if (noiseHoldActive) sendHeldSentence();
        if (gateState == GATE_KILLED) {
            gateState = GATE_AMBIENT;
            if (gateProbeScheduleTask != null) {
                handler.removeCallbacks(gateProbeScheduleTask);
                gateProbeScheduleTask = null;
            }
        }
        if (gateState == GATE_DRONE) {
            // Listening is ending mid-drone: the extraction session
            // stands down with everything else.
            gateState = GATE_AMBIENT;
            if (droneSession != null) droneSession.endCapture();
        }
        gateBypassSession = false;
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
        // Nick 2026-10-28 v1.52: noise gate — while KILLED the
        // recognizer stays OFF (nothing is recorded or sent); the
        // probe loop owns re-arming. Every recognizer start funnels
        // through here, so this one check suppresses them all. The
        // manual override clears the killed state before starting.
        if ((gateState == GATE_KILLED || gateState == GATE_DRONE)
                && !gateBypassSession) return;
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

    // Nick 2026-10-23 v1.48: Bluetooth-aware interrupt mode. Nick marks
    // which BT device is his earbuds (one-time, in SharedPreferences).
    // During Selena's playback:
    // - Earbuds connected → VOICE INTERRUPT: recognizer stays ON,
    //   onBeginningOfSpeech pauses her, results discarded (VAD ignores
    //   mower/engine noise, only human speech triggers).
    // - Anything else (truck radio, phone speaker, no BT) → TAP INTERRUPT:
    //   recognizer OFF (v1.47 mic-mute), tap screen to pause.
    private static final String PREFS_NAME = "selena_voice_prefs";
    private static final String PREF_EARBUDS_NAME = "earbuds_device_name";

    /** Nick 2026-10-23 v1.48: true while in voice-interrupt mode. */
    private volatile boolean voiceInterruptMode = false;

    public boolean isVoiceInterruptMode() {
        return voiceInterruptMode;
    }

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
     */
    private boolean isEarbudsConnected() {
        String saved = getEarbudsDeviceName();
        if (saved == null || saved.isEmpty()) return false;
        String connected = getConnectedBtAudioDeviceName();
        if (connected == null) return false;
        // Fuzzy match: either contains the other (handles "Nick's
        // AirPods" vs "AirPods" etc.)
        return connected.toLowerCase().contains(saved.toLowerCase())
                || saved.toLowerCase().contains(connected.toLowerCase());
    }

    /**
     * Nick 2026-10-23 v1.47: stop the recognizer entirely when Selena
     * starts speaking, so her voice can't be transcribed as Nick's
     * input. Uses cancel() (not stopListening()) so no partial result
     * fires. The onError(ERROR_CLIENT) this triggers is swallowed by
     * the micMutedForPlayback guards — no restart is scheduled.
     * Idempotent: safe to call when already muted or not listening.
     *
     * Nick 2026-10-23 v1.48: Bluetooth-aware. If Nick's earbuds are
     * connected → VOICE INTERRUPT mode: recognizer stays ON, but
     * onBeginningOfSpeech pauses her playback (results discarded —
     * the VAD ignores mower/engine noise). Otherwise → TAP INTERRUPT:
     * full mute as in v1.47, tap the screen to pause her.
     */
    private void muteMicForPlayback() {
        if (micMutedForPlayback || voiceInterruptMode) return;
        if (!recognizerActive || recognizer == null) return;
        // Nick 2026-10-23 v1.48: check BT before muting.
        if (isEarbudsConnected()) {
            voiceInterruptMode = true;
            log("mic: voice-interrupt mode (earbuds) — recognizer stays on, "
                    + "speech pauses playback, results discarded");
            broadcastListeningState();
            return;
        }
        micMutedForPlayback = true;
        log("mic: muted for Selena playback (tap-interrupt mode)");
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
        // Nick 2026-10-23 v1.48: clear voice-interrupt mode too.
        if (voiceInterruptMode) {
            voiceInterruptMode = false;
            log("mic: voice-interrupt mode cleared");
            broadcastListeningState();
        }
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
        // Nick 2026-10-23 v1.48: voice-interrupt mode — Nick started
        // talking while Selena was speaking. Pause her immediately.
        // Results are discarded (ServiceRecognitionListener checks
        // isPlayingAudio), so this never becomes an input.
        if (voiceInterruptMode && isPlayingAudio()) {
            log("interrupt: Nick speaking during playback — pausing her");
            pausePlayback();
            return;
        }
        // Nick 2026-10-23 v1.46: polite interruption — Nick started
        // talking; Selena holds any pending playback until his pause.
        nickSpeaking = true;
        // Nick 2026-10-29 v1.59 (ducking rule): real capture started —
        // fade other audio down for the dictation.
        acquireDictationDuck();
        sendBroadcast(new Intent(ACTION_SPEECH_DETECTED));
    }

    public void onSpeechEnd() {
        nickSpeaking = false;
        // v1.59: speech-end anchors the duck's release grace — the
        // dictation is in its tail (final chunk + flush), not over.
        noteDictationActivity();
        unmuteBeep();
        // Nick 2026-10-23 v1.46: polite interruption — Nick just paused.
        // onResults (with his chunk) usually lands within ~1s; delay the
        // release so the chunk is POSTed first — otherwise the echo guard
        // would discard it as "during playback". Also the fallback for
        // chunks the filters discard. The gate is re-checked at play
        // time, so a re-started utterance re-holds.
        // Nick 2026-10-23 v1.48: 3s to match SENTENCE_GAP_MS.
        handler.postDelayed(() -> releasePoliteHold(), 4000);
    }

    public void onRecognizerDone() {
        // Nick 2026-10-23 v1.46: the recognizer session ended (results or
        // error) — the utterance is over, so the speech flag must not
        // stick. Safety-net release for error paths where onEndOfSpeech
        // never fired; harmless otherwise (pumps are idempotent).
        nickSpeaking = false;
        // Nick 2026-10-29 v1.59: session over — if nothing is buffered
        // (e.g. the gate rejected the utterance, so no chunk ever
        // landed and no flush will ever run), the duck releases here.
        maybeReleaseDictationDuck();
        unmuteBeep();
        // Nick 2026-10-23 v1.48: FAST restart (500ms) after normal utterance
        // end — the 5s delay is for error cases only. Nick was waiting
        // ~10s (3s gap + 5s restart + init) for dictation to resume.
        handler.postDelayed(new RecognizerRestartTask(this), 500);
        // Nick 2026-10-23 v1.48: 3s to match SENTENCE_GAP_MS.
        handler.postDelayed(() -> releasePoliteHold(), 4000);
    }

    /**
     * Nick 2026-10-23 v1.46: live dictation popup. Called by
     * ServiceRecognitionListener.onPartialResults on the main thread.
     * The popup shows the in-progress sentence = buffered chunks plus
     * the live partial. Unfiltered by design — the final accept/reject
     * still happens in onResults.
     */
    public void onPartialVoiceInput(String text) {
        noteDictationActivity(); // v1.59: partials = capture still live
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
    // Nick 2026-10-23 v1.48: 3-second gap — Nick speaks, 3s of quiet =
    // batch end. (Was 1.0s in v1.46; Nick found 1s cut him off mid-thought.)
    private static final long SENTENCE_GAP_MS = 4000; // Nick 2026-10-23: 4s silence before batch (was 3s)
    private Runnable sentenceFlushTask = null;

    /**
     * Nick 2026-10-23 v1.46: called per ACCEPTED chunk (post-filter).
     * Buffers mid-thought pauses into the in-progress sentence; the
     * sentence is sent by flushSentence() once a real pause lands.
     * Runs on the main thread (recognizer callback).
     */
    public void onVoiceInput(String text) {
        // Nick 2026-10-29 v1.61 naming capture: if the naming
        // question is armed (asked right after an unnamed machine's
        // run ended), a SHORT reply is the machine's name — consume
        // it here, never buffer or POST it. A longer reply means the
        // user just went on dictating; the question was asked once
        // for this run, so disarm and fall through normally.
        if (text != null && pendingNameProfileId >= 0
                && System.currentTimeMillis() <= namingCaptureUntilMs) {
            String t = text.trim();
            if (!t.isEmpty() && t.split("\\s+").length <= 6) {
                applyMachineName(t);
                return;
            }
            disarmNamingCapture();
        }
        // Nick 2026-10-29 v1.62 guest-name capture: she just asked
        // who's talking/asking — a SHORT reply is the new person's
        // name (enroll + release the held dictation tagged with it).
        // A longer reply means the ask went unanswered: release the
        // held dictation untagged and let this chunk flow normally.
        if (text != null && pendingGuestSentence != null
                && System.currentTimeMillis() <= guestNameCaptureUntilMs) {
            String t = text.trim();
            if (!t.isEmpty() && t.split("\\s+").length <= 6) {
                handleGuestNameReply(t);
                return;
            }
            releaseHeldGuestSentence(null);
        }
        // Nick 2026-10-18 v1.9: Nick's words reach the UI instantly, even
        // if the POST later fails (offline, timeout). His inputs are never
        // spoken aloud — playback only plays Selena's reply MP3s.
        // Nick 2026-10-28 v1.53: capture the gate context BEFORE the
        // bookkeeping below clears it — the voiceprint sample class
        // depends on how this chunk was accepted.
        final boolean vpWasNoiseHold = noiseHoldActive;
        final boolean vpWasBypass = gateBypassSession;
        long now = android.os.SystemClock.uptimeMillis();
        noteDictationActivity(); // v1.59: an accepted chunk = capture live
        // Nick 2026-10-28 v1.52: a real accepted chunk after a noise
        // pause means dictation RESUMED on top of the held text — the
        // message is continuing, so it is no longer a cut-off send:
        // clear the noise-hold bookkeeping and the 60s noise clock.
        if (noiseHoldActive) {
            noiseHoldActive = false;
            bufferCutOffFlag = false;
            if (noiseHoldSendTask != null) {
                handler.removeCallbacks(noiseHoldSendTask);
                noiseHoldSendTask = null;
            }
            // The gap check below must not flush the held text as its
            // own sentence — the resumed chunk continues THIS message.
            lastChunkMs = now;
            log("gate: dictation resumed on top of held text");
        }
        // Actively dictating (possibly over a still-running drone after
        // a burst re-arm) — keep the gate from re-killing mid-flow.
        // Each accepted chunk extends the grace; once he stops, the
        // normal 4s flush lands well before grace + kill-sustain do.
        gateGraceUntilMs = now + 6000;
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
        // Nick 2026-10-28 v1.53: this chunk was ACCEPTED — it may
        // teach the voiceprint (§5: only accepted dictations teach).
        vpLearnAccepted(text, vpWasNoiseHold, vpWasBypass);
    }

    /**
     * Nick 2026-10-28 v1.53 (Phase 4, spec §5): feed the learned
     * voiceprint from one accepted dictation chunk. Sample class:
     * manual-override sessions are the strongest evidence (§4.1);
     * chunks accepted out of a noise hold (or with the drone recently
     * in the sampler) are over-noise — light voice weight, and the
     * surrounding drone teaches the noise profile too; quiet-room
     * chunks are clean; everything else is normal. A chunk that says
     * "ignore that / that wasn't me" instead redirects the PREVIOUS
     * sample to the noise profile (§4.2) and teaches nothing itself.
     */
    private void vpLearnAccepted(String text, boolean wasNoiseHold,
                                 boolean wasBypass) {
        if (voiceprint == null || recognitionListener == null) return;
        try {
            float[] feats = recognitionListener.takeUtteranceFeatures();
            float conf = recognitionListener.takeUtteranceConfidence();
            if (feats == null) return;
            String t = (text == null ? "" : text.trim().toLowerCase(Locale.US));
            if (t.startsWith("ignore that") || t.startsWith("that wasn't me")
                    || t.startsWith("that wasnt me") || t.startsWith("not me")
                    || t.startsWith("disregard that")) {
                voiceprint.moveLastVoiceSampleToNoise();
                log("voiceprint: 'wasn't me' — last sample redirected to the noise profile");
                return;
            }
            String cls;
            if (wasBypass) {
                cls = Voiceprint.CLS_OVERRIDE;
            } else if (wasNoiseHold || gateNow() - vpDroneSeenMs < 15000) {
                cls = Voiceprint.CLS_OVER_NOISE;
            } else {
                float[] s = recognitionListener.samplerStats();
                cls = (s[3] >= 1f && s[0] < GATE_LOUD_DB - 4f)
                        ? Voiceprint.CLS_CLEAN : Voiceprint.CLS_NORMAL;
            }
            // Nick 2026-10-29 v1.62 new-voice attribution — BEFORE
            // Nick's profile learns anything from this chunk. A chunk
            // matching an enrolled guest teaches the GUEST only; a
            // chunk matching nobody (Nick established, distance
            // clearly beyond the cold-and-off-day grace zone) marks
            // the buffer as a new-voice candidate and teaches NO
            // ONE — the flush holds that sentence for the ask, and
            // the enrollment (if any) seeds from these measurements.
            if (guestProfiles != null && voiceprint.voiceHasVote()
                    && voiceprint.verdictRecog(feats) == Voiceprint.NO_MATCH) {
                GuestProfiles.Guest g = guestProfiles.matchRecog(this, feats);
                if (g != null) {
                    guestProfiles.learnRecog(this, g.id, feats,
                            Voiceprint.weightFor(cls));
                    if (bufferGuestName == null) bufferGuestName = g.name;
                    log("guests: chunk attributed to '" + g.name
                            + "' — Nick's print untouched");
                    return;
                }
                double distNick = voiceprint.distanceRecog(feats);
                if (distNick >= NEW_VOICE_MIN_DIST
                        && pendingGuestSentence == null
                        && System.currentTimeMillis()
                                >= guestAskCooldownUntilMs) {
                    float[] lastUn = guestProfiles.lastUnmatchedFeats(this);
                    if (lastUn == null
                            || GuestProfiles.distanceBetween(feats, lastUn)
                                    > 1.2) {
                        pendingGuestFeats = feats;
                        bufferNewVoiceCandidate = true;
                        log("guests: NEW VOICE candidate (distance to Nick "
                                + String.format(Locale.US, "%.2f", distNick)
                                + ") — print not trained; the flush will "
                                + "hold the sentence for the ask");
                        return;
                    }
                    log("guests: unrecognized voice = the signature already "
                            + "asked about in the last 24h — no re-ask, "
                            + "print not trained");
                    return;
                }
                // NO_MATCH but inside the grace distance: benefit of
                // the doubt (Nick with a cold reads exactly like
                // this) — fall through and learn as his, as before.
            }
            voiceprint.learnVoice(feats, cls, conf);
            if (Voiceprint.CLS_OVER_NOISE.equals(cls)) {
                float[] nf = recognitionListener.samplerTraceFeatures();
                if (nf != null) voiceprint.learnNoiseRecog(nf, 0.7);
            }
            // A dictation landing right after a probe-burst re-arm
            // CONFIRMS the burst was the user — teach the probe-domain
            // voice profile so the next burst is recognized sooner.
            if (vpPendingBurstFeats != null && gateNow() <= vpBurstConfirmUntilMs) {
                voiceprint.learnVoiceProbe(vpPendingBurstFeats,
                        Voiceprint.weightFor(cls) * 0.8);
                vpPendingBurstFeats = null;
                log("voiceprint: probe burst confirmed by dictation — probe voice profile taught");
            }
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: learn failed", e);
        }
    }

    /**
     * Nick 2026-10-28 v1.53 (§5/§4.3): the drone guard's one exception.
     * Returns true only when the learned voiceprint is confident this
     * envelope is the signed-in user (and clearly unlike the known
     * noise) and the recognizer's own confidence isn't low. A false
     * here changes nothing — the guard discards exactly as it did
     * before the voiceprint existed. The voiceprint can open the
     * gate; it can never be the reason anything is refused.
     */
    public boolean voiceprintRescueVerdict(float[] feats, float confidence) {
        if (voiceprint == null || feats == null) return false;
        try {
            if (confidence >= 0 && confidence < 0.5f) return false;
            boolean match = voiceprint.verdictRecog(feats) == Voiceprint.MATCH;
            if (match) {
                log("voiceprint: envelope MATCHES the signed-in user — rescuing result from the drone guard");
            }
            return match;
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: rescue verdict failed", e);
            return false;
        }
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
        // Nick 2026-10-28 v1.52: a noise-held dictation is owned by
        // the 60s noise clock (or by resumed dictation) — the normal
        // 4s sentence gap must never flush it early or unflagged.
        if (noiseHoldActive) return;
        if (nickSpeaking) {
            // Still mid-utterance — wait for a real pause. The next chunk
            // re-arms; re-arm here as a backstop.
            sentenceFlushTask = () -> flushSentence();
            handler.postDelayed(sentenceFlushTask, SENTENCE_GAP_MS);
            return;
        }
        // Nick 2026-10-29 v1.62: a new-voice candidate sits in this
        // buffer and the dictation has now STOPPED — hold the post
        // and run the ask. (Pre-wake chatter is exempt: it was never
        // going to post, so there is no question to preserve.)
        if (bufferNewVoiceCandidate && pendingGuestSentence == null) {
            bufferNewVoiceCandidate = false;
            if (!(wakeGateBlocksPost()
                    && !sentenceStartsWithWakeWord(
                            sentenceBuffer.toString()))) {
                String held = sentenceBuffer.toString();
                sentenceBuffer.setLength(0);
                startNewVoiceAsk(held);
                maybeReleaseDictationDuck();
                releasePoliteHold();
                return;
            }
        }
        String sentence = sentenceBuffer.toString();
        sentenceBuffer.setLength(0);
        // Nick 2026-10-23 v1.46: his dictations live in the message
        // history as from-Nick entries, in the same list as her replies.
        MessageHistory.addNick(sentence);
        // Nick 2026-10-29 v1.60 (true wake word): while the spotter is
        // engaged and no conversation is open, a dictation is SENT
        // only when it carries the wake word itself (the transcript
        // fallback — the spotter is the primary path). Anything else
        // is pre-wake chatter: it stays in his local history above and
        // is never posted. An open conversation, a manual session, and
        // spotter-failed mode all post exactly as before.
        boolean heldByWakeGate = false;
        if (wakeGateBlocksPost()) {
            if (sentenceStartsWithWakeWord(sentence)) {
                log("wakeword: transcript wake word — conversation opened (fallback path)");
                openWakeConversation("transcript");
            } else {
                heldByWakeGate = true;
                String preview = sentence.length() > 30
                        ? sentence.substring(0, 30) + "…" : sentence;
                log("wakeword: dictation held — conversation closed, no wake word: '"
                        + preview + "'");
            }
        }
        if (!heldByWakeGate) {
            Intent sent = new Intent(ACTION_INPUT_SENT);
            sent.putExtra(EXTRA_TEXT, sentence);
            sendAppBroadcast(sent);
            final String toPost = sentence;
            // v1.62: a guest spoke this sentence — bind the name
            // explicitly (directive in order, then the tagged post).
            final String spk = bufferGuestName;
            bufferGuestName = null;
            if (spk != null) {
                postGuestDirective("guest_speaking", spk, null, toPost);
            }
            new Thread(new InputPostTask(this, toPost, false, spk)).start();
            noteWakeConversationActivity();
        }
        if (heldByWakeGate) bufferGuestName = null; // v1.62: never leak a tag
        // Nick 2026-10-29 v1.59: the batch is sent and the buffer is
        // empty — the dictation is over; release the duck BEFORE any
        // held playback starts (playback takes its own MAY_DUCK focus,
        // so the music never pops up in between).
        maybeReleaseDictationDuck();
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
        doPostInput(text, false);
    }

    /**
     * Nick 2026-10-28 v1.52: possiblyCutOff carries the noise gate's
     * verdict — true only when the dictation was held by loud noise
     * and sent on the 60s no-further-dictation clock. The Siteview
     * brain (which consumes this later) judges complete-vs-cut-off
     * from it. Normal dictations post it as false.
     */
    public void doPostInput(String text, boolean possiblyCutOff) {
        doPostInput(text, possiblyCutOff, null);
    }

    /**
     * Nick 2026-10-29 v1.62: speakerName binds a dictation to an
     * enrolled guest (standalone group mode) — the responder
     * addresses them by name. Null = the signed-in user, as always.
     * The binding is ALSO posted as a guest_speaking directive
     * immediately before the dictation (ordering preserved), so it
     * survives even if a consumer ignores these fields.
     */
    public void doPostInput(String text, boolean possiblyCutOff,
                            String speakerName) {
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
            body.put("possiblyCutOff", possiblyCutOff);
            if (speakerName != null) {
                body.put("speakerName", speakerName);
                body.put("speakerKind", "guest");
            }
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignored) {}
            }
            int code = conn.getResponseCode();
            log("input posted (" + code + "): " + text
                    + (speakerName != null
                            ? " [speaker: guest '" + speakerName + "']" : ""));
            // Nick 2026-10-18 v1.9: the INPUT_SENT broadcast now fires in
            // onVoiceInput() before the POST, so the log is instant even
            // when the network is down. Nothing to broadcast here anymore.
        } catch (Exception e) {
            Log.w(TAG, "postInput failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------ v1.52 gate machinery

    private long gateNow() {
        return android.os.SystemClock.uptimeMillis();
    }

    /** Starts the 250ms gate evaluation loop (idempotent). */
    private void startGateWatchdog() {
        if (gateTickTask != null) return;
        gateTickTask = () -> {
            gateTick();
            if (!destroyed) handler.postDelayed(gateTickTask, GATE_TICK_MS);
        };
        handler.postDelayed(gateTickTask, GATE_TICK_MS);
    }

    /**
     * True when the recognizer's live RMS window reads as machine
     * drone: loud (mean at/above GATE_LOUD_DB) AND steady (variation
     * at/below GATE_STEADY_STD_DB) over a fresh, sufficiently long
     * window. Speech fails the steadiness test by nature.
     */
    public boolean samplerSeesDrone() {
        if (recognitionListener == null) return false;
        float[] s = recognitionListener.samplerStats();
        if (s[3] < 1f) return false;      // stale window — between sessions
        if (s[2] < 700f) return false;    // not enough signal to judge
        return s[0] >= GATE_LOUD_DB && s[1] <= GATE_STEADY_STD_DB;
    }

    /**
     * Nick 2026-10-28 v1.52: consulted by ServiceRecognitionListener
     * before accepting a result — while the gate is killed, or while
     * the live window reads as drone at this moment, recognizer
     * "results" are the machine talking, not the user.
     */
    public boolean isGateDroneActiveNow() {
        if (gateState == GATE_KILLED || gateState == GATE_DRONE) return true;
        if (gateDisabled || gateBypassSession) return false;
        return samplerSeesDrone();
    }

    /**
     * Gate evaluation, every 250ms. Only the AMBIENT side lives here
     * (KILLED exits are driven by probes). Any exception disables the
     * gate — spec §4.4: fall back to plain recognizer behavior.
     */
    private void gateTick() {
        if (destroyed || gateDisabled) return;
        try {
            if (gateState == GATE_KILLED) return; // probes drive exits
            if (gateState == GATE_DRONE) return; // the drone session drives exits
            if (!recognizerActive || recognizer == null || micMutedForPlayback) {
                gateDroneSinceMs = 0;
                return;
            }
            if (gateBypassSession) {
                // Override session (§4.1): the gate stays out of the
                // way until the drone has been gone a while, then it
                // quietly resumes duty for the next noise event.
                if (!samplerSeesDrone()) {
                    if (gateDroneAbsentSinceMs == 0) {
                        gateDroneAbsentSinceMs = gateNow();
                    } else if (gateNow() - gateDroneAbsentSinceMs > 5000) {
                        gateBypassSession = false;
                        gateDroneAbsentSinceMs = 0;
                        log("gate: override session over — gate back on duty");
                    }
                } else {
                    gateDroneAbsentSinceMs = 0;
                }
                return;
            }
            if (gateNow() < gateGraceUntilMs) {
                gateDroneSinceMs = 0;
                return;
            }
            boolean droneNow = samplerSeesDrone();
            if (droneNow) {
                // Nick 2026-10-28 v1.53: the drone is a free NOISE
                // profile sample (§5: the system learns two profiles).
                // Throttled — a mower offers the same lesson for hours.
                vpDroneSeenMs = gateNow();
                if (voiceprint != null && gateNow() - vpLastNoiseFeedMs > 5000) {
                    vpLastNoiseFeedMs = gateNow();
                    float[] nf = recognitionListener.samplerTraceFeatures();
                    if (nf != null) voiceprint.learnNoiseRecog(nf, 1.0);
                }
            }
            if (droneNow) {
                if (gateDroneSinceMs == 0) {
                    gateDroneSinceMs = gateNow();
                } else if (gateNow() - gateDroneSinceMs >= GATE_KILL_SUSTAIN_MS) {
                    // Nick 2026-10-29 v1.61: extraction first — if
                    // the drone pipeline is ready, the session stays
                    // alive over the machine; the kill is the
                    // fallback, not the default.
                    if (!tryEnterDroneMode()) enterGateKill();
                }
            } else {
                gateDroneSinceMs = 0;
            }
        } catch (Exception e) {
            disableGate("tick failed: " + e);
        }
    }

    /**
     * Loud + steady sustained past the kill timing → KILLED. The
     * recognizer session is cancelled and launchRecognizer() refuses
     * to relaunch while killed: nothing is recorded or sent. If real
     * accepted dictation is buffered, this becomes a noise HOLD
     * instead — pause-and-remember, never discard (§3).
     */
    private void enterGateKill() {
        gateState = GATE_KILLED;
        gateDroneSinceMs = 0;
        gateDroneBaselineDbfs = Float.NaN;
        gateProbeCount = 0;
        gateProbeDropCount = 0;
        boolean holding = sentenceBuffer.length() > 0;
        if (holding) {
            noiseHoldActive = true;
            bufferCutOffFlag = true;
            if (sentenceFlushTask != null) {
                handler.removeCallbacks(sentenceFlushTask);
                sentenceFlushTask = null;
            }
            armNoiseHoldTimer();
            log("gate: loud steady noise — dictation PAUSED and held ("
                    + sentenceBuffer.length() + " chars kept)");
        } else {
            log("gate: loud steady noise — recognizer killed, nothing recorded");
        }
        // Cancel the in-flight session: the "utterance" in progress is
        // the drone itself. Its error callback routes to
        // onRecognizerDone, whose restarts launchRecognizer() then
        // suppresses — the recognizer stays off until a probe re-arms
        // or the user overrides.
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        nickSpeaking = false;
        updateNotification(holding
                ? "🚜 Paused — loud noise (dictation held)"
                : "🚜 Paused — loud noise");
        broadcastListeningState();
        scheduleGateProbe(300);
    }

    /** (Re)arm the 60s noise clock for a held dictation (§3). */
    private void armNoiseHoldTimer() {
        if (noiseHoldSendTask != null) handler.removeCallbacks(noiseHoldSendTask);
        noiseHoldSendTask = () -> sendHeldSentence();
        handler.postDelayed(noiseHoldSendTask, NOISE_HOLD_SEND_MS);
    }

    /**
     * The 60s noise clock fired with no further dictation: send the
     * held dictation now, flagged possiblyCutOff for the brain.
     */
    private void sendHeldSentence() {
        noiseHoldSendTask = null;
        if (!noiseHoldActive) return;
        noiseHoldActive = false;
        bufferCutOffFlag = false;
        if (sentenceBuffer.length() == 0) return;
        String sentence = sentenceBuffer.toString();
        sentenceBuffer.setLength(0);
        // v1.62: a new-voice candidate rode the noise hold — the ask
        // owns this sentence now, not the 60s send.
        if (bufferNewVoiceCandidate && pendingGuestSentence == null) {
            bufferNewVoiceCandidate = false;
            startNewVoiceAsk(sentence);
            return;
        }
        log("gate: 60s with no further dictation — sending held dictation"
                + " flagged possiblyCutOff");
        MessageHistory.addNick(sentence);
        Intent sent = new Intent(ACTION_INPUT_SENT);
        sent.putExtra(EXTRA_TEXT, sentence);
        sendAppBroadcast(sent);
        // Nick 2026-10-29 v1.60: a noise-held dictation BYPASSES the
        // wake gate (the §3 60-second send is a promise — the words
        // were dictated inside a live exchange the drone interrupted).
        // It also re-opens/extends the wake conversation: the exchange
        // it belongs to is still live.
        if (wakeSpotter != null && wakeSpotterReady && !wakeSpotterFailed
                && !wakeConvoOpen && isAlwaysListening()) {
            openWakeConversation("noise-hold");
        } else {
            noteWakeConversationActivity();
        }
        final String heldSpk = bufferGuestName;
        bufferGuestName = null;
        if (heldSpk != null) {
            postGuestDirective("guest_speaking", heldSpk, null, sentence);
        }
        new Thread(new InputPostTask(this, sentence, true, heldSpk)).start();
        // Nick 2026-10-29 v1.59: held dictation sent, buffer empty —
        // release the duck (the watchdog would stale-release anyway;
        // this is the prompt path).
        maybeReleaseDictationDuck();
        releasePoliteHold();
    }

    // ------------------------------------------------------------ gate probes (KILLED side)

    private void scheduleGateProbe(long delayMs) {
        if (gateState != GATE_KILLED || destroyed) return;
        if (gateProbeScheduleTask != null) {
            handler.removeCallbacks(gateProbeScheduleTask);
        }
        gateProbeScheduleTask = () -> runGateProbe();
        handler.postDelayed(gateProbeScheduleTask, delayMs);
    }

    private void runGateProbe() {
        gateProbeScheduleTask = null;
        if (gateState != GATE_KILLED || destroyed || gateDisabled) return;
        // Selena's own voice over the speaker would read as a speech
        // burst — never probe while she is audible.
        if (isPlayingAudio() || micMutedForPlayback) {
            scheduleGateProbe(800);
            return;
        }
        if (gateProbeInFlight) {
            scheduleGateProbe(400);
            return;
        }
        gateProbeInFlight = true;
        new Thread(new GateProbeTask(this)).start();
    }

    /** Called by GateProbeTask on its worker thread. */
    public void onGateProbeResult(float meanDbfs, float stdDb,
                                  float peakDbfs, float zcr, float bright,
                                  boolean ok, float[] spectrum) {
        handler.post(() -> handleGateProbeResult(
                meanDbfs, stdDb, peakDbfs, zcr, bright, ok, spectrum));
    }

    private void handleGateProbeResult(float meanDbfs, float stdDb,
                                       float peakDbfs, float zcr, float bright,
                                       boolean ok, float[] spectrum) {
        gateProbeInFlight = false;
        if (gateState != GATE_KILLED || destroyed) return;
        if (!ok) {
            // Spec §4.4: sampler failure → plain recognizer behavior.
            disableGate("mic probe failed");
            return;
        }
        try {
            float[] probeFeats = new float[]{meanDbfs, stdDb, peakDbfs, zcr, bright};
            // Nick 2026-10-28 v1.53: the voiceprint booster at the
            // probe gate (§5/§4.3). When confirmed bursts have taught
            // the probe-domain voice profile what the user sounds like
            // over a drone, a burst that matches it (and doesn't match
            // the drone) earns RELAXED thresholds. A non-match changes
            // nothing — the standard thresholds judge it, exactly as
            // before the voiceprint existed.
            boolean vpBoost = voiceprint != null
                    && voiceprint.probeBoostApplies(probeFeats);
            float burstStd = vpBoost ? 2.0f : GATE_PROBE_BURST_STD_DB;
            float burstPeakMargin = vpBoost ? 3.5f : GATE_PROBE_BURST_PEAK_DB;
            // Re-arm B (§3): a speech-shaped burst riding clearly above
            // the learned drone baseline. Judged BEFORE this sample can
            // move the baseline. Before a baseline exists, only an
            // unmistakably loud + varied probe counts.
            boolean burst;
            if (gateProbeCount >= 2 && !Float.isNaN(gateDroneBaselineDbfs)) {
                burst = stdDb >= burstStd
                        && peakDbfs >= gateDroneBaselineDbfs + burstPeakMargin;
            } else {
                burst = stdDb >= burstStd && meanDbfs >= -20f;
            }
            if (burst) {
                if (vpBoost) {
                    log("voiceprint: probe boost — voice-like burst over the drone, thresholds relaxed");
                }
                // Remember this burst: if a real dictation lands
                // within the confirm window, it was the user, and the
                // probe-domain voice profile learns from it (§5).
                vpPendingBurstFeats = probeFeats;
                vpBurstConfirmUntilMs = gateNow() + 12000;
                exitGateKill("speech burst over the drone");
                return;
            }
            // Learn / drift-track the drone baseline. The first probes
            // set it; afterwards only near-baseline samples move it, so
            // a level drop cannot drag it down and mask itself.
            if (gateProbeCount < 3 || Float.isNaN(gateDroneBaselineDbfs)) {
                gateDroneBaselineDbfs = Float.isNaN(gateDroneBaselineDbfs)
                        ? meanDbfs
                        : (gateDroneBaselineDbfs * gateProbeCount + meanDbfs)
                                / (gateProbeCount + 1);
            } else if (Math.abs(meanDbfs - gateDroneBaselineDbfs) < 3f) {
                gateDroneBaselineDbfs =
                        gateDroneBaselineDbfs * 0.9f + meanDbfs * 0.1f;
            }
            // Nick 2026-10-28 v1.53: near-baseline probes are the
            // drone itself — teach the probe-domain NOISE profile
            // (§5's second profile, device/environment, outside the
            // per-user wall).
            if (voiceprint != null && !Float.isNaN(gateDroneBaselineDbfs)
                    && Math.abs(meanDbfs - gateDroneBaselineDbfs) < 3f) {
                voiceprint.learnNoiseProbe(probeFeats,
                        gateProbeCount < 3 ? 0.7 : 1.0);
            }
            // Nick 2026-10-29 v1.61 (13:17): near-baseline probes are
            // the machine itself — teach the MACHINE-PROFILE library
            // from their spectra (distinct profile per machine), and
            // once the drone is confirmed + a baseline exists, hand
            // the session UP to Drone Dictation if the extraction
            // pipeline is ready: capture stays alive instead of the
            // recognizer sitting dead until a re-arm.
            if (machineProfiles != null && spectrum != null
                    && !Float.isNaN(gateDroneBaselineDbfs)
                    && Math.abs(meanDbfs - gateDroneBaselineDbfs) < 3f
                    && (gateProbeCount < 3 || gateProbeCount % 3 == 0)) {
                machineProfiles.matchOrCreate(this, machineUserKey(),
                        spectrum, null);
                if (droneReady && gateProbeCount >= 3
                        && tryEnterDroneMode()) {
                    return; // drone session owns the mic now
                }
            }
            gateProbeCount++;
            // Re-arm A (§3): the level fell well below the drone
            // baseline and stayed there — the machine stopped.
            if (gateProbeCount >= 3
                    && meanDbfs <= gateDroneBaselineDbfs - GATE_PROBE_DROP_DB) {
                gateProbeDropCount++;
                if (gateProbeDropCount >= 2) {
                    exitGateKill("noise stopped");
                    return;
                }
            } else {
                gateProbeDropCount = 0;
            }
            scheduleGateProbe(GATE_PROBE_INTERVAL_MS);
        } catch (Exception e) {
            disableGate("probe handling failed: " + e);
        }
    }

    /**
     * Leave KILLED: recognizer restarts (unless muted for playback),
     * the gate gets a short grace period so a dictation in flow is
     * never instantly re-killed, and a held dictation gets a fresh
     * 60s noise clock for its continuation.
     */
    private void exitGateKill(String reason) {
        if (gateState != GATE_KILLED) return;
        gateState = GATE_AMBIENT;
        gateDroneSinceMs = 0;
        gateGraceUntilMs = gateNow() + 6000;
        if (gateProbeScheduleTask != null) {
            handler.removeCallbacks(gateProbeScheduleTask);
            gateProbeScheduleTask = null;
        }
        log("gate: re-armed (" + reason + ")"
                + (noiseHoldActive ? " — held dictation resumes" : ""));
        // Nick 2026-10-29 v1.61: the level just dropped near ambient
        // after a machine run — if that machine is still unnamed,
        // this is the ruled moment to ask its name (13:21/13:25).
        if ("noise stopped".equals(reason)) maybeAskMachineName();
        if (noiseHoldActive) armNoiseHoldTimer();
        updateNotification("🎤 Listening…");
        broadcastListeningState();
        if (recognizerActive && !micMutedForPlayback && !destroyed) {
            restartRecognizerNow();
        }
    }

    // ------------------------------------------------------------ v1.61 drone dictation (extraction over a running machine)

    /** Profile the playful during-drone line fired for this run (-1 = not fired). */
    private int duringLineProfileId = -1;

    private String machineUserKey() {
        try {
            return SupabaseAuth.userKey(this);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Background prep: fetch the ASR model (43.6 MB checksummed
     * download into app-private storage — never baked into the APK)
     * and build the extraction session when it's verified. Until
     * droneReady flips, the gate behaves exactly as v1.60.
     */
    private void initDroneAsync() {
        fetchUserDisplayNameAsync();
        final android.content.Context app = getApplicationContext();
        DroneModelStore.ensureAsync(app, () -> {
            if (destroyed) return;
            try {
                if (!DroneModelStore.isReady(app)) {
                    log("drone: ASR model unavailable — extraction stays off (gate unchanged)");
                    return;
                }
                DroneSession s = new DroneSession(VoiceService.this,
                        machineProfiles);
                if (s.init(app)) {
                    droneSession = s;
                    droneReady = true;
                    log("drone: extraction pipeline READY (own capture + spectral suppression + streaming zipformer)");
                } else {
                    log("drone: ASR session init failed — extraction stays off (gate unchanged)");
                }
            } catch (Exception e) {
                log("drone: init failed: " + e);
            }
        });
    }

    /**
     * Nick's order (2026-10-29): extraction active + session alive
     * OVER the kill. Called from the gate tick (instead of
     * enterGateKill) and from the KILLED-side probes (upgrade once a
     * drone is confirmed). Returns false — caller falls back to the
     * kill — whenever the pipeline isn't ready.
     */
    private boolean tryEnterDroneMode() {
        if (!droneReady || droneSession == null || destroyed) return false;
        if (gateState == GATE_DRONE) return true;
        if (gateDisabled || gateBypassSession) return false;
        if (gateState == GATE_KILLED && gateProbeScheduleTask != null) {
            handler.removeCallbacks(gateProbeScheduleTask);
            gateProbeScheduleTask = null;
        }
        enterDroneMode();
        return gateState == GATE_DRONE;
    }

    /** Mirrors enterGateKill's holding bookkeeping exactly. */
    private void enterDroneMode() {
        gateState = GATE_DRONE;
        gateDroneSinceMs = 0;
        duringLineProfileId = -1;
        boolean holding = sentenceBuffer.length() > 0;
        if (holding) {
            noiseHoldActive = true;
            bufferCutOffFlag = true;
            if (sentenceFlushTask != null) {
                handler.removeCallbacks(sentenceFlushTask);
                sentenceFlushTask = null;
            }
            armNoiseHoldTimer();
            log("drone: loud steady noise — extraction takes the mic ("
                    + sentenceBuffer.length() + " chars held)");
        } else {
            log("drone: loud steady noise — extraction takes the mic");
        }
        if (recognizer != null) {
            try { recognizer.cancel(); } catch (Exception ignored) {}
        }
        nickSpeaking = false;
        gateProbeInFlight = false;
        updateNotification("🚜 Listening over the noise…");
        broadcastListeningState();
        droneSession.beginCapture(machineUserKey());
    }

    /** Mirrors exitGateKill: grace window, recognizer back, naming ask. */
    private void exitDroneMode(String reason) {
        if (gateState != GATE_DRONE) return;
        if (droneSession != null) droneSession.endCapture();
        gateState = GATE_AMBIENT;
        gateDroneSinceMs = 0;
        gateGraceUntilMs = gateNow() + 6000;
        if ("manual override".equals(reason)) {
            gateBypassSession = true;
            gateDroneAbsentSinceMs = 0;
        }
        log("drone: extraction ended (" + reason + ")"
                + (noiseHoldActive ? " — held dictation resumes" : ""));
        if ("noise stopped".equals(reason)) maybeAskMachineName();
        if (noiseHoldActive) armNoiseHoldTimer();
        updateNotification("🎤 Listening…");
        broadcastListeningState();
        if (recognizerActive && !micMutedForPlayback && !destroyed) {
            restartRecognizerNow();
        }
    }

    /** DroneSession polls this: park capture while she's talking or we're gone. */
    public boolean droneSessionParked() {
        return destroyed || isPlayingAudio() || micMutedForPlayback
                || System.currentTimeMillis() < droneSelfTalkUntilMs;
    }

    /**
     * The extraction pipeline failed mid-run: fall back to the exact
     * v1.60 kill bookkeeping (held text keeps its 60 s promise; the
     * probe cadence takes over from here).
     */
    public void onDroneFailed(String why) {
        handler.post(() -> {
            if (gateState != GATE_DRONE) return;
            log("drone: pipeline failed (" + why
                    + ") — falling back to the gate kill");
            if (droneSession != null) droneSession.endCapture();
            gateState = GATE_KILLED;
            gateProbeCount = 0;
            gateProbeDropCount = 0;
            gateDroneBaselineDbfs = Float.NaN;
            updateNotification(noiseHoldActive
                    ? "🚜 Paused — loud noise (dictation held)"
                    : "🚜 Paused — loud noise");
            broadcastListeningState();
            scheduleGateProbe(300);
        });
    }

    /** DroneSession saw the level drop near ambient: the run is over. */
    public void onDroneGone(int profileId) {
        handler.post(() -> {
            if (gateState != GATE_DRONE) return;
            exitDroneMode("noise stopped");
        });
    }

    public void onDronePartial(String text) {
        handler.post(() -> {
            if (gateState != GATE_DRONE || text == null) return;
            onPartialVoiceInput(text);
        });
    }

    /**
     * A finished utterance from the drone ASR. Feeds the EXISTING
     * acceptance path (sentence buffer, wake gating, POST) unchanged
     * — plus the two drone-only extras: a during-drone naming answer
     * is consumed as a name, and the raw-branch probe features teach
     * the SEPARATE drone-domain voice profile (never the platform
     * voiceprint — different measurement domain).
     */
    public void onDroneDictation(String text, float[] feats) {
        handler.post(() -> {
            if (text == null || text.trim().isEmpty()) return;
            acquireDictationDuck(); // capture is live — music fades (v1.59 rule)
            String trimmed = text.trim();
            // 13:28: an answer to the during-drone line names the
            // machine on the spot — short, prefix-shaped ("it's the
            // mower", "the blower") while that line fired this run.
            if (gateState == GATE_DRONE && machineProfiles != null
                    && duringLineProfileId >= 0 && droneSession != null
                    && droneSession.activeProfileId() == duringLineProfileId) {
                MachineProfiles.Profile p = machineProfiles.get(
                        this, machineUserKey(), duringLineProfileId);
                String low = trimmed.toLowerCase(Locale.US);
                if (p != null && !p.named
                        && trimmed.split("\\s+").length <= 4
                        && (low.startsWith("it's ") || low.startsWith("it is ")
                                || low.startsWith("that's ")
                                || low.startsWith("that is ")
                                || low.startsWith("the "))) {
                    String name = cleanMachineName(trimmed);
                    if (!name.isEmpty()) {
                        machineProfiles.rename(this, machineUserKey(),
                                p.id, name);
                        log("drone: machine " + p.id + " named '" + name
                                + "' (during-drone answer)");
                        updateNotification("🚜 Got it — that was the " + name);
                        if (pendingNameProfileId == p.id) {
                            disarmNamingCapture();
                        }
                        duringLineProfileId = -1;
                        return;
                    }
                }
            }
            onVoiceInput(text);
            if (machineProfiles != null && feats != null) {
                String low = trimmed.toLowerCase(Locale.US);
                if (low.startsWith("ignore that")
                        || low.startsWith("that wasn't me")
                        || low.startsWith("that wasnt me")
                        || low.startsWith("not me")
                        || low.startsWith("disregard that")) {
                    machineProfiles.undoLastVoiceSample(
                            this, machineUserKey());
                } else {
                    machineProfiles.learnVoiceSample(
                            this, machineUserKey(), feats);
                }
            }
        });
    }

    /**
     * DroneSession: an UNNAMED machine has been running a while —
     * say the playful line (Nick 13:28/13:31, verbatim with its
     * emphasis styling). Non-blocking; nothing waits on an answer.
     */
    public void onDroneSustainedUnnamed(int profileId, String profileName) {
        handler.post(() -> {
            if (gateState != GATE_DRONE) return;
            duringLineProfileId = profileId;
            speakDronePrompt(DURING_DRONE_LINE, "machine_during_drone",
                    profileId, profileName);
        });
    }

    // ------------------------------------------------------------ machine naming (13:21/13:25/13:27)

    /**
     * After a run of a still-unnamed machine, ask its name — exactly
     * "Hey, {user}, what was that noise?" — once per run, on every
     * run, until it's named; never again after. The reply is
     * captured in onVoiceInput (short) / onDroneDictation.
     */
    private void maybeAskMachineName() {
        if (machineProfiles == null) return;
        MachineProfiles.Profile p = machineProfiles.lastActiveUnnamed(
                this, machineUserKey());
        if (p == null) return;
        pendingNameProfileId = p.id;
        namingCaptureUntilMs = System.currentTimeMillis() + NAMING_CAPTURE_MS;
        String name = userDisplayName();
        String q = (name != null && !name.isEmpty())
                ? "Hey, " + name + ", what was that noise?"
                : "Hey, what was that noise?";
        log("drone: naming ask for profile " + p.id + " (" + p.name
                + "): " + q);
        speakDronePrompt(q, "machine_naming_ask", p.id, p.name);
        fetchUserDisplayNameAsync(); // keep the name fresh for next time
    }

    /**
     * Deliver a drone prompt: a durable directive POST through the
     * normal input path (the brain sees the question and the naming
     * context), the notification line, and the on-device voice when
     * she's not already speaking — never over a queued reply.
     */
    private void speakDronePrompt(String text, String action,
                                  int profileId, String profileName) {
        new Thread(new MachineNameDirectiveTask(
                this, action, profileId, profileName, text)).start();
        updateNotification("🚜 " + text);
        if (tts != null && ttsReady && !isPlayingAudio() && !playing
                && !ttsSpeaking) {
            // Park her own ears while she talks: the drone capture
            // stands down for the estimated utterance so she can't
            // transcribe herself (the platform side is echo-guarded
            // inside speakOnDevice).
            int words = Math.max(1, text.split("\\s+").length);
            droneSelfTalkUntilMs = System.currentTimeMillis()
                    + words * 650L + 1200L;
            speakOnDevice(text, false);
        } else {
            log("drone: prompt not spoken (tts busy/unavailable) — "
                    + "directive + notification only: " + text);
        }
    }

    private void applyMachineName(String spoken) {
        int pid = pendingNameProfileId;
        disarmNamingCapture();
        if (machineProfiles == null || pid < 0) return;
        String name = cleanMachineName(spoken);
        if (name.isEmpty()) return;
        machineProfiles.rename(this, machineUserKey(), pid, name);
        log("drone: machine " + pid + " named '" + name + "' (voice reply)");
        updateNotification("🚜 Got it — that was the " + name);
    }

    private void disarmNamingCapture() {
        pendingNameProfileId = -1;
        namingCaptureUntilMs = 0;
    }

    /** Light cleanup of a spoken machine name ("that was the mower." → "mower"). */
    static String cleanMachineName(String spoken) {
        String s = spoken.trim().replaceAll("[.!?]+$", "").trim();
        String low = s.toLowerCase(Locale.US);
        String[] prefixes = {
                "that was the ", "that was a ", "that was ",
                "it was the ", "it was a ", "it was ",
                "it's the ", "it's a ", "it's ",
                "it is the ", "it is a ", "it is ",
                "that's the ", "that's a ", "that's ", "the "};
        for (String pre : prefixes) {
            if (low.startsWith(pre)) {
                s = s.substring(pre.length());
                break;
            }
        }
        return s.trim();
    }

    /** First name of the signed-in user, cached from their Siteview profile. */
    private String userDisplayName() {
        String full = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("user_display_name", "");
        if (full == null || full.isEmpty()) return null;
        int sp = full.indexOf(' ');
        return sp > 0 ? full.substring(0, sp) : full;
    }

    /**
     * One-shot profiles fetch for the naming prompt's {user}. Quiet
     * failure by design: the prompt falls back to the nameless form
     * and the next run tries again.
     */
    private void fetchUserDisplayNameAsync() {
        new Thread(() -> {
            try {
                String have = getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("user_display_name", "");
                if (have != null && !have.isEmpty()) return;
                String userKey = SupabaseAuth.userKey(this);
                if (userKey == null || userKey.startsWith("em_")) return;
                String token = SupabaseAuth.validAccessToken(this);
                if (token == null) return;
                URL url = new URL("https://api.siteviewoperations.com"
                        + "/rest/v1/profiles?id=eq." + userKey
                        + "&select=display_name");
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setRequestProperty("apikey", SupabaseAuth.apiKey());
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) return;
                java.io.InputStream in = c.getInputStream();
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                }
                org.json.JSONArray arr = new org.json.JSONArray(sb.toString());
                if (arr.length() > 0) {
                    String dn = arr.getJSONObject(0)
                            .optString("display_name", "");
                    if (!dn.isEmpty()) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString("user_display_name", dn).apply();
                        log("drone: user display name cached for prompts");
                    }
                }
            } catch (Exception e) {
                log("drone: display-name fetch failed: " + e);
            }
        }).start();
    }

    /** MachineNameDirectiveTask's POST — mirrors doPostWakeDirective. */
    public void doPostMachineDirective(String action, int profileId,
                                       String profileName, String line) {
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
            body.put("action", action);
            body.put("profileId", profileId);
            body.put("profileName", profileName);
            body.put("line", line);
            body.put("timestamp", System.currentTimeMillis());
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignored) {}
            }
            int code = conn.getResponseCode();
            log("drone: directive posted (" + code + "): " + action);
        } catch (Exception e) {
            Log.w(TAG, "drone: directive post failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ------------------------------------------------------------ v1.62 new-voice flow (group mode)

    /**
     * The dictation has stopped and its speaker matched nobody: ask
     * who that was. Form by content (13:54 disambiguation — the
     * slash form is never spoken): a statement gets "Who's
     * talking?"; a question gets "Who's asking?", alternating with
     * "Who wants to know?". The held dictation is NOT in the ask's
     * directive — the brain answers the tagged dictation post that
     * follows enrollment, never the ask itself.
     */
    private void startNewVoiceAsk(String sentence) {
        pendingGuestSentence = sentence;
        guestAskCooldownUntilMs = System.currentTimeMillis()
                + GUEST_ASK_COOLDOWN_MS;
        if (guestProfiles != null && pendingGuestFeats != null) {
            guestProfiles.noteUnmatched(this, pendingGuestFeats);
        }
        String line;
        if (!isQuestion(sentence)) {
            line = "Who's talking?";
        } else {
            boolean alt = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("guest_ask_alt", false);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("guest_ask_alt", !alt).apply();
            line = alt ? "Who wants to know?" : "Who's asking?";
        }
        log("guests: new voice — asking '" + line + "' (held dictation: "
                + sentence.length() + " chars)");
        postGuestDirective("new_voice_ask", null, line, null);
        updateNotification("🗣️ " + line);
        if (tts != null && ttsReady && !isPlayingAudio() && !playing
                && !ttsSpeaking) {
            int words = Math.max(1, line.split("\\s+").length);
            droneSelfTalkUntilMs = System.currentTimeMillis()
                    + words * 650L + 1200L;
            speakOnDevice(line, false);
        } else {
            log("guests: ask not spoken (tts busy/unavailable) — "
                    + "directive + notification only");
        }
        guestNameCaptureUntilMs = System.currentTimeMillis()
                + GUEST_NAME_CAPTURE_MS;
        if (guestHoldReleaseTask != null) {
            handler.removeCallbacks(guestHoldReleaseTask);
        }
        guestHoldReleaseTask = () -> releaseHeldGuestSentence(null);
        handler.postDelayed(guestHoldReleaseTask, GUEST_NAME_CAPTURE_MS);
    }

    /**
     * Post the held dictation — tagged with the guest's name when
     * the ask was answered, untagged when it wasn't (the question
     * still gets answered either way; only the name is missing).
     * Mirrors flushSentence's post section, wake gate included.
     */
    private void releaseHeldGuestSentence(String speakerName) {
        if (guestHoldReleaseTask != null) {
            handler.removeCallbacks(guestHoldReleaseTask);
            guestHoldReleaseTask = null;
        }
        guestNameCaptureUntilMs = 0;
        if (pendingGuestSentence == null) return;
        String sentence = pendingGuestSentence;
        pendingGuestSentence = null;
        pendingGuestFeats = null;
        MessageHistory.addNick(sentence);
        boolean heldByWakeGate = false;
        if (wakeGateBlocksPost()) {
            if (sentenceStartsWithWakeWord(sentence)) {
                openWakeConversation("transcript");
            } else {
                heldByWakeGate = true;
                log("guests: held dictation had no wake word — stays local");
            }
        }
        if (!heldByWakeGate) {
            Intent sent = new Intent(ACTION_INPUT_SENT);
            sent.putExtra(EXTRA_TEXT, sentence);
            sendAppBroadcast(sent);
            if (speakerName != null) {
                postGuestDirective("guest_speaking", speakerName, null,
                        sentence);
            }
            new Thread(new InputPostTask(this, sentence, false,
                    speakerName)).start();
            noteWakeConversationActivity();
            log("guests: held dictation released"
                    + (speakerName != null
                            ? " as guest '" + speakerName + "'" : " untagged"));
        }
        maybeReleaseDictationDuck();
        releasePoliteHold();
    }

    /**
     * The name reply landed. "It's me" (or Nick's own name) means the
     * new-voice flag was a false alarm — Nick with a cold — and the
     * held dictation simply posts as his, no guest created.
     * Anything else enrolls a guest seeded from the held speech's
     * measurements, and the held dictation posts under their name.
     */
    private void handleGuestNameReply(String spoken) {
        String low = spoken.trim().toLowerCase(Locale.US)
                .replaceAll("[.!?]+$", "").trim();
        if (low.equals("me") || low.equals("it's me")
                || low.equals("it is me") || low.equals("that's me")
                || low.equals("this is me") || low.equals("just me")
                || low.equals("nick") || low.equals("this is nick")) {
            log("guests: name reply says it's Nick himself — false "
                    + "alarm, no guest created");
            releaseHeldGuestSentence(null);
            return;
        }
        String name = cleanGuestName(spoken);
        if (name.isEmpty() || guestProfiles == null) {
            releaseHeldGuestSentence(null);
            return;
        }
        GuestProfiles.Guest g = guestProfiles.enroll(
                this, name, pendingGuestFeats);
        postGuestDirective("guest_enrolled", g.name, null, null);
        updateNotification("🗣️ Got it — hi, " + g.name);
        log("guests: enrolled '" + g.name + "' from the name reply");
        releaseHeldGuestSentence(g.name);
    }

    /** "I'm Amber" / "this is Amber" / "my name is Amber" → "Amber". */
    static String cleanGuestName(String spoken) {
        String s = spoken.trim().replaceAll("[.!?]+$", "").trim();
        String low = s.toLowerCase(Locale.US);
        String[] prefixes = {
                "my name is ", "the name is ", "this is ", "i'm ",
                "im ", "it's ", "it is ", "call me "};
        for (String pre : prefixes) {
            if (low.startsWith(pre)) {
                s = s.substring(pre.length());
                break;
            }
        }
        s = s.trim();
        if (!s.isEmpty()) {
            s = s.substring(0, 1).toUpperCase(Locale.US) + s.substring(1);
        }
        return s;
    }

    /** Statement vs question, for the ask's form (13:54 ruling). */
    static boolean isQuestion(String sentence) {
        String s = sentence.trim().toLowerCase(Locale.US);
        if (s.endsWith("?")) return true;
        String first = s.split("\\s+")[0].replaceAll("[^a-z']", "");
        switch (first) {
            case "who": case "what": case "when": case "where":
            case "why": case "how": case "can": case "could":
            case "would": case "will": case "do": case "does":
            case "did": case "is": case "are": case "was":
            case "were": case "should": case "may":
                return true;
            default:
                return false;
        }
    }

    private void postGuestDirective(String action, String speakerName,
                                    String line, String text) {
        new Thread(new GuestDirectiveTask(
                this, action, speakerName, line, text)).start();
    }

    /** GuestDirectiveTask's POST — mirrors doPostMachineDirective. */
    public void doPostGuestDirective(String action, String speakerName,
                                     String line, String text) {
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
            body.put("action", action);
            if (speakerName != null) body.put("speakerName", speakerName);
            if (line != null) body.put("line", line);
            if (text != null) body.put("text", text);
            body.put("timestamp", System.currentTimeMillis());
            OutputStream os = null;
            try {
                os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
            } finally {
                if (os != null) try { os.close(); } catch (Exception ignored) {}
            }
            int code = conn.getResponseCode();
            log("guests: directive posted (" + code + "): " + action);
        } catch (Exception e) {
            Log.w(TAG, "guests: directive post failed", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Spec §4.1: manual override — the user tapped the mic (or asked
     * for dictation from the earbud) while the gate had the recognizer
     * killed. Force the session open; the gate is bypassed for this
     * session and resumes duty once the drone has been gone a while.
     */
    public void forceGateOverride() {
        if (gateState != GATE_KILLED) return;
        gateBypassSession = true;
        gateDroneAbsentSinceMs = 0;
        log("gate: MANUAL OVERRIDE — session forced open, gate bypassed");
        exitGateKill("manual override");
    }

    /**
     * Spec §4.4: sampler/analyzer failure — disable the gate and fall
     * back to today's plain SpeechRecognizer behavior. Failing open
     * for the user beats a gate that can't hear.
     */
    private void disableGate(String why) {
        if (gateDisabled) return;
        gateDisabled = true;
        log("gate: DISABLED (" + why + ") — plain recognizer fallback (fail open)");
        if (gateState == GATE_KILLED) {
            exitGateKill("sampler failure fallback");
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
        // Nick 2026-10-28 v1.52: gate state for the UI's subtle
        // "Paused — loud noise" indicator (§4.5). recognizerActive
        // stays true while killed (listening mode is still on — the
        // sessions are what's suppressed), so this extra is what
        // distinguishes "listening" from "held off by noise".
        i.putExtra(EXTRA_GATE_STATE,
                gateState == GATE_KILLED
                        ? (noiseHoldActive ? "noise_hold" : "killed")
                        : "ambient");
        sendAppBroadcast(i);
        refreshMediaSessionActive();
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
