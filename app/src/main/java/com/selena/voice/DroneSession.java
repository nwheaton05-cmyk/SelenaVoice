package com.selena.voice;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nick 2026-10-29 v1.61: DRONE DICTATION session — the extraction
 * pipeline that keeps dictation ALIVE over a running machine
 * instead of letting the noise gate kill it.
 *
 * Why this shape (design-pass hard fact): Android's SpeechRecognizer
 * accepts no preprocessed audio — there is NO insertion point in
 * front of it on either surface. So drone mode is our own capture
 * feeding our own recognition:
 *   AudioRecord (sole mic owner; spotter/probes/recognizer parked)
 *     → RAW branch: level/spectrum metrics — machine-profile
 *       learning + matching, drone-gone detection, and the
 *       probe-domain features of each utterance.
 *     → ENHANCED branch: SpectralSuppressor keyed to the ACTIVE
 *       machine profile's PSD (adapted slowly during non-speech,
 *       frozen during speech) → sherpa-onnx streaming Zipformer
 *       (en-20M int8, downloaded + checksummed, see DroneModelStore)
 *       → partial/final text into VoiceService's EXISTING sentence
 *       buffer + POST path (onVoiceInput), so wake gating, noise
 *       hold resume, and the brain wire are unchanged.
 * Quiet conditions never touch this class — the platform path is
 * bit-for-bit v1.60. Any failure here fails open: VoiceService
 * falls back to the v1.60 gate (kill + probes) exactly.
 *
 * Voice learning domain rule: utterances accepted here teach ONLY
 * the drone-domain voice profile inside MachineProfiles (probe
 * domain, raw branch measurements). They never touch the platform
 * Voiceprint — mixing capture domains would silently corrupt it.
 *
 * Nothing is recorded: audio exists only as the hop being
 * processed; what persists is spectra + feature vectors.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class DroneSession {
    private static final String TAG = "SelenaVoice";
    private static final int SAMPLE_RATE = 16000;
    private static final int HOP = SpectralSuppressor.HOP; // 256

    // TUNABLES (field pass):
    private static final float DRONE_PRESENT_DBFS = -48f;  // above = machine-ish level
    private static final float GONE_DROP_DB = 10f;         // level fall = machine stopped
    private static final float GONE_FLOOR_DBFS = -52f;     // absolute quiet floor
    private static final int GONE_HOPS = 188;              // ~3 s of hops
    private static final int MATCH_EVERY_HOPS = 125;       // ~2 s between profile ops
    private static final float LEARN_MAX_STD_DB = 5f;      // steadiness for learning windows
    private static final float SPEECH_RISE_DB = 7f;        // enhanced level over floor = speech
    private static final float SPEECH_FALL_DB = 3f;
    private static final int SPEECH_START_HOPS = 2;
    private static final int SPEECH_END_HOPS = 31;         // ~0.5 s
    private static final long UNNAMED_LINE_AFTER_MS = 10000; // during-drone line timing

    private final VoiceService service;
    private final MachineProfiles profiles;
    private final SpectralSuppressor suppressor = new SpectralSuppressor();

    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private volatile boolean ready = false;

    private volatile boolean capturing = false;
    private Thread thread;
    private AudioRecord record;
    private String userKey;

    // Per-run state (capture thread).
    private int activeProfileId = -1;
    private String activeProfileName = null;
    private boolean activeProfileNamed = true;
    private long runStartMs = 0;
    private long unnamedActiveSinceMs = 0;
    private boolean duringLineFiredThisRun = false;
    private final float[] emaSpectrum = new float[SpectralSuppressor.BINS];
    private boolean emaValid = false;
    private int hopsSinceProfileOp = 0;
    private final List<Float> windowDb = new ArrayList<>();
    private float droneLevelEma = -60f;
    private int goneHops = 0;
    private float enhancedFloorDb = -60f;
    private boolean floorSeeded = false;
    private boolean inSpeech = false;
    private int speechHotHops = 0;
    private int speechColdHops = 0;
    private String lastPartial = "";
    // Utterance accumulators for probe-domain features (raw branch).
    private boolean inUtterance = false;
    private final List<Float> uttFrameDb = new ArrayList<>();
    private long uttSamples = 0;
    private long uttSignChanges = 0;
    private double uttEnergy = 0;
    private double uttDiffEnergy = 0;
    private int uttPrevSample = 0;

    public DroneSession(VoiceService service, MachineProfiles profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    // ------------------------------------------------------------ init

    /**
     * Build the recognizer from the downloaded model files. Call on
     * a worker thread (model load takes a moment). Returns false on
     * any failure — drone mode then never arms (fail open).
     */
    public boolean init(Context ctx) {
        try {
            if (!DroneModelStore.isReady(ctx)) return false;
            String dir = DroneModelStore.dir(ctx).getAbsolutePath();
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig(
                    dir + "/" + DroneModelStore.ENCODER,
                    dir + "/" + DroneModelStore.DECODER,
                    dir + "/" + DroneModelStore.JOINER);
            OnlineModelConfig model = new OnlineModelConfig(
                    transducer,
                    new OnlineParaformerModelConfig(),
                    new OnlineZipformer2CtcModelConfig(),
                    new OnlineNeMoCtcModelConfig(),
                    new OnlineToneCtcModelConfig(),
                    dir + "/" + DroneModelStore.TOKENS,
                    2, false, "cpu", "", "", "");
            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(SAMPLE_RATE, 80, 0.0f));
            config.setModelConfig(model);
            config.setDecodingMethod("greedy_search");
            config.setMaxActivePaths(4);
            config.setEnableEndpoint(true);
            config.setEndpointConfig(new EndpointConfig(
                    new EndpointRule(false, 2.4f, 0f),
                    new EndpointRule(true, 1.2f, 0f),
                    new EndpointRule(false, 0f, 20f)));
            OnlineRecognizer rec = null;
            try {
                // File-path models: no AssetManager (sherpa's
                // downloaded-model pattern).
                rec = new OnlineRecognizer(null, config);
            } catch (Throwable t1) {
                Log.w(TAG, "drone: recognizer init (null assets) failed, "
                        + "retrying with AssetManager", t1);
                rec = new OnlineRecognizer(ctx.getAssets(), config);
            }
            recognizer = rec;
            stream = recognizer.createStream("");
            ready = true;
            Log.i(TAG, "drone: extraction recognizer ready (streaming "
                    + "zipformer en-20M int8, on-device)");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "drone: recognizer init failed — drone mode off", t);
            recognizer = null;
            stream = null;
            ready = false;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    // ------------------------------------------------------------ capture

    /** Start (or keep) the drone capture loop. Idempotent. */
    public synchronized void beginCapture(String userKey) {
        if (!ready) return;
        this.userKey = userKey;
        capturing = true;
        if (thread != null && thread.isAlive()) return;
        runStartMs = System.currentTimeMillis();
        activeProfileId = -1;
        activeProfileName = null;
        activeProfileNamed = true;
        unnamedActiveSinceMs = 0;
        duringLineFiredThisRun = false;
        emaValid = false;
        hopsSinceProfileOp = 0;
        windowDb.clear();
        droneLevelEma = -60f;
        goneHops = 0;
        inSpeech = false;
        inUtterance = false;
        lastPartial = "";
        suppressor.resetStream();
        thread = new Thread(this::captureLoop, "SelenaDrone");
        thread.start();
    }

    /** Stop the capture loop; the recognizer stays built for reuse. */
    public synchronized void endCapture() {
        capturing = false;
        AudioRecord r = record;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
        noteRunDuration();
    }

    public boolean isCapturing() {
        return capturing;
    }

    public int activeProfileId() {
        return activeProfileId;
    }

    /** Full native teardown (service destroy). */
    public void release() {
        endCapture();
        try { if (stream != null) stream.release(); } catch (Exception ignored) {}
        try { if (recognizer != null) recognizer.release(); } catch (Exception ignored) {}
        stream = null;
        recognizer = null;
        ready = false;
    }

    private void noteRunDuration() {
        if (activeProfileId >= 0 && runStartMs > 0) {
            double sec = (System.currentTimeMillis() - runStartMs) / 1000.0;
            if (sec > 5) {
                profiles.noteRun(service, userKey, activeProfileId, sec);
            }
        }
        runStartMs = 0;
    }

    private void captureLoop() {
        short[] hop = new short[HOP];
        float[] enhanced = new float[HOP];
        float[] power = new float[SpectralSuppressor.BINS];
        int readErrors = 0;
        try {
            while (capturing) {
                if (service.droneSessionParked()) {
                    releaseRecord();
                    sleep(200);
                    continue;
                }
                if (record == null && !openRecord()) {
                    service.onDroneFailed("AudioRecord init failed");
                    return;
                }
                // Fill one hop (reads may return short counts).
                int filled = 0;
                while (filled < HOP) {
                    int n;
                    try {
                        n = record.read(hop, filled, HOP - filled);
                    } catch (Exception e) {
                        n = -1;
                    }
                    if (n <= 0) {
                        if (!capturing) break;
                        if (++readErrors > 30) {
                            service.onDroneFailed("AudioRecord read errors");
                            return;
                        }
                        sleep(20);
                        continue;
                    }
                    filled += n;
                }
                if (!capturing) break;
                readErrors = 0;
                processHop(hop, enhanced, power);
            }
        } catch (Throwable t) {
            Log.w(TAG, "drone: capture loop died", t);
            if (capturing) service.onDroneFailed("capture loop: " + t);
        } finally {
            releaseRecord();
        }
    }

    private void processHop(short[] hop, float[] enhanced, float[] power) {
        float hopDbfs = suppressor.processHop(hop, enhanced, power);
        long now = System.currentTimeMillis();

        // Raw level tracking (drone presence + gone detection).
        boolean droneLevel = hopDbfs > DRONE_PRESENT_DBFS;
        if (droneLevel) {
            droneLevelEma = droneLevelEma == -60f
                    ? hopDbfs : 0.98f * droneLevelEma + 0.02f * hopDbfs;
        }
        float goneLine = Math.min(droneLevelEma - GONE_DROP_DB, GONE_FLOOR_DBFS + 8f);
        if (hopDbfs < goneLine && !inSpeech) {
            if (++goneHops >= GONE_HOPS) {
                int id = activeProfileId;
                noteRunDuration();
                capturing = false;
                service.onDroneGone(id);
                return;
            }
        } else {
            goneHops = 0;
        }

        // Enhanced-domain speech detection (drives PSD freeze; the
        // ASR's own endpointing does the actual segmentation).
        double eSum = 0;
        for (float v : enhanced) eSum += (double) v * v;
        float eDb = (float) (10.0 * Math.log10(eSum / HOP + 1e-12));
        if (!floorSeeded) {
            enhancedFloorDb = eDb;
            floorSeeded = true;
        }
        boolean hot = eDb > enhancedFloorDb + SPEECH_RISE_DB;
        if (hot) {
            speechHotHops++;
            speechColdHops = 0;
            if (!inSpeech && speechHotHops >= SPEECH_START_HOPS) {
                inSpeech = true;
                if (!inUtterance) {
                    inUtterance = true;
                    resetUtteranceAccum();
                }
            }
        } else {
            speechHotHops = 0;
            if (eDb < enhancedFloorDb + SPEECH_FALL_DB) {
                if (++speechColdHops >= SPEECH_END_HOPS) inSpeech = false;
                if (!inSpeech) {
                    enhancedFloorDb = 0.98f * enhancedFloorDb + 0.02f * eDb;
                }
            } else {
                speechColdHops = 0;
            }
        }

        // Utterance raw-branch measurements (probe-domain features).
        if (inUtterance) {
            uttFrameDb.add(hopDbfs);
            for (int i = 0; i < HOP; i++) {
                int s = hop[i];
                if (uttSamples > 0) {
                    if ((s >= 0) != (uttPrevSample >= 0)) uttSignChanges++;
                    double diff = (double) s - uttPrevSample;
                    uttDiffEnergy += diff * diff;
                }
                uttEnergy += (double) s * s;
                uttPrevSample = s;
                uttSamples++;
            }
        }

        // ASR: feed the enhanced stream, surface partials + endpoints.
        try {
            stream.acceptWaveform(enhanced, SAMPLE_RATE);
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            String text = recognizer.getResult(stream).getText();
            if (text != null && !text.equals(lastPartial)) {
                lastPartial = text;
                if (!text.isEmpty()) service.onDronePartial(text);
            }
            if (recognizer.isEndpoint(stream)) {
                String finalText = text == null ? "" : text.trim();
                recognizer.reset(stream);
                lastPartial = "";
                if (!finalText.isEmpty()) {
                    float[] feats = utteranceFeats();
                    inUtterance = false;
                    service.onDroneDictation(finalText, feats);
                } else {
                    inUtterance = false;
                }
            }
        } catch (Throwable t) {
            service.onDroneFailed("ASR decode failed: " + t);
            capturing = false;
            return;
        }

        // Machine-profile learning + matching: non-speech drone only,
        // PSD frozen solid while the user is talking.
        if (!inSpeech && droneLevel) {
            if (!emaValid) {
                System.arraycopy(power, 0, emaSpectrum, 0, power.length);
                emaValid = true;
            } else {
                for (int k = 0; k < power.length; k++) {
                    emaSpectrum[k] = 0.94f * emaSpectrum[k] + 0.06f * power[k];
                }
            }
            windowDb.add(hopDbfs);
            if (++hopsSinceProfileOp >= MATCH_EVERY_HOPS) {
                hopsSinceProfileOp = 0;
                profileOp(now);
            }
        } else if (!droneLevel) {
            windowDb.clear();
        }

        // The during-drone line (Nick 13:28): once per run, after the
        // unnamed machine has been running a while. Non-blocking;
        // VoiceService owns speaking it and parking us while it does.
        if (!duringLineFiredThisRun && activeProfileId >= 0
                && !activeProfileNamed && unnamedActiveSinceMs > 0
                && now - unnamedActiveSinceMs >= UNNAMED_LINE_AFTER_MS) {
            duringLineFiredThisRun = true;
            service.onDroneSustainedUnnamed(activeProfileId, activeProfileName);
        }
    }

    /** Periodic profile match/adapt on the accumulated drone spectrum. */
    private void profileOp(long now) {
        if (!emaValid || windowDb.isEmpty()) return;
        // Steadiness of the learning window (speech gusts and wind
        // make poor profiles; genuinely steady machines pass easily).
        double mean = 0;
        for (float v : windowDb) mean += v;
        mean /= windowDb.size();
        double var = 0;
        for (float v : windowDb) { double d = v - mean; var += d * d; }
        float std = (float) Math.sqrt(var / windowDb.size());
        windowDb.clear();
        if (std > LEARN_MAX_STD_DB) return;

        try {
            if (activeProfileId >= 0) {
                MachineProfiles.Profile active =
                        profiles.get(service, userKey, activeProfileId);
                if (active != null && active.psd != null
                        && MachineProfiles.distance(emaSpectrum, active.psd)
                                <= MachineProfiles.MATCH_THRESHOLD) {
                    profiles.adapt(service, userKey, activeProfileId,
                            emaSpectrum, 0.3);
                    pushScaledPsd(active);
                    return;
                }
            }
            boolean[] created = new boolean[1];
            MachineProfiles.Profile p =
                    profiles.matchOrCreate(service, userKey, emaSpectrum, created);
            if (p == null) return;
            boolean changed = p.id != activeProfileId;
            activeProfileId = p.id;
            activeProfileName = p.name;
            activeProfileNamed = p.named;
            if (!p.named && (changed || unnamedActiveSinceMs == 0)) {
                unnamedActiveSinceMs = now;
            } else if (p.named) {
                unnamedActiveSinceMs = 0;
            }
            pushScaledPsd(p);
            Log.i(TAG, String.format(Locale.US,
                    "drone: active machine profile '%s' (%s)",
                    p.name, created[0] ? "new" : "matched"));
        } catch (Exception e) {
            Log.w(TAG, "drone: profile op failed", e);
        }
    }

    /** Scale the profile's stored PSD to the live level and hand it over. */
    private void pushScaledPsd(MachineProfiles.Profile p) {
        if (p.psd == null || p.refPower <= 0) return;
        double cur = 0;
        for (float v : emaSpectrum) cur += v;
        if (cur <= 0) return;
        double scale = cur / p.refPower;
        float[] scaled = new float[emaSpectrum.length];
        for (int k = 0; k < scaled.length; k++) {
            scaled[k] = (float) (p.psd[k] * scale);
        }
        suppressor.setNoisePsd(scaled);
    }

    // ------------------------------------------------------------ utterance features

    private void resetUtteranceAccum() {
        uttFrameDb.clear();
        uttSamples = 0;
        uttSignChanges = 0;
        uttEnergy = 0;
        uttDiffEnergy = 0;
        uttPrevSample = 0;
    }

    /**
     * Probe-domain features of the finished utterance — the same 5
     * measurements GateProbeTask/Voiceprint use (mean dBFS, std,
     * peak dBFS, zero-crossing rate, brightness ratio), computed on
     * the RAW branch. Null when the utterance was too short to say
     * anything honest.
     */
    private float[] utteranceFeats() {
        if (uttFrameDb.size() < 6 || uttSamples == 0) return null;
        double mean = 0;
        float peak = -120f;
        for (float v : uttFrameDb) {
            mean += v;
            if (v > peak) peak = v;
        }
        mean /= uttFrameDb.size();
        double var = 0;
        for (float v : uttFrameDb) { double d = v - mean; var += d * d; }
        float zcr = (float) ((double) uttSignChanges / (double) uttSamples);
        float bright = uttEnergy > 0
                ? (float) Math.min(1.0, uttDiffEnergy / (2.0 * uttEnergy))
                : 0f;
        return new float[]{(float) mean,
                (float) Math.sqrt(var / uttFrameDb.size()), peak, zcr, bright};
    }

    // ------------------------------------------------------------ audio record

    private boolean openRecord() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, HOP * 2 * 8);
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC,
        };
        for (int source : sources) {
            try {
                AudioRecord r = new AudioRecord(source, SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufBytes);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    record = r;
                    logRoutedDevice(r, source);
                    return true;
                }
                r.release();
            } catch (Exception e) {
                Log.w(TAG, "drone: AudioRecord source " + source + " failed", e);
            }
        }
        return false;
    }

    /**
     * Mic routing is a field variable (design pass): the standalone
     * AudioRecord doesn't select an input device, so LOG what
     * Android actually routed — phone mic vs earbud mic changes
     * every level and spectrum in this pipeline.
     */
    private void logRoutedDevice(AudioRecord r, int source) {
        String route = "unknown";
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                AudioDeviceInfo dev = r.getRoutedDevice();
                if (dev != null) {
                    route = dev.getProductName() + " (type " + dev.getType() + ")";
                }
            }
        } catch (Exception ignored) {}
        Log.i(TAG, "drone: capture open — source "
                + (source == MediaRecorder.AudioSource.VOICE_RECOGNITION
                        ? "VOICE_RECOGNITION" : "MIC")
                + ", routed input: " + route);
    }

    private void releaseRecord() {
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
