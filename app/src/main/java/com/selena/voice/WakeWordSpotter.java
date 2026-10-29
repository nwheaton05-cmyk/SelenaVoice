package com.selena.voice;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.util.Arrays;

/**
 * Nick 2026-10-29 v1.60 (true wake word spike, approved 12:08 EDT):
 * a dedicated on-device keyword spotter for "Insight" / "Hey Insight"
 * (sherpa-onnx KWS, GigaSpeech zipformer 3.3M int8 — offline,
 * self-owned, no subscription), replacing "the full SpeechRecognizer
 * transcribes everything and something downstream checks the first
 * word" as the PRIMARY wake path. VoiceService owns the conversation
 * logic; this class owns the model + the mic while it is spotting.
 *
 * Top-level class (not an inner class) on purpose — see the d8 note
 * in VoiceService.armDictationDuckWatchdog.
 *
 * Mic discipline (the codebase's standing rule, v1.52): NEVER a
 * second live mic consumer alongside the SpeechRecognizer. This
 * spotter is the SOLE mic consumer while it runs — VoiceService
 * stops it before the recognizer starts, and the capture loop PARKS
 * (AudioRecord released, nothing fed to the model, nothing recorded)
 * whenever VoiceService.wakeSpotterParked() says the recognizer,
 * playback, or the noise gate owns the moment.
 *
 * Nothing is stored: audio exists only as the chunk being fed to the
 * model, plus a ~1.5s ring of level measurements (dBFS) used for the
 * drone mirror in VoiceService — measurements only, same rule as the
 * voiceprint.
 */
public class WakeWordSpotter {
    private static final String TAG = "SelenaVoice";

    // Model bundle in assets/kws/: the int8 GigaSpeech KWS zipformer
    // (encoder 4.8MB + decoder 0.3MB + joiner 0.2MB) + tokens.txt +
    // keywords.txt (our file: INSIGHT and HEY_INSIGHT token sequences
    // from the model's own bpe.model, with per-keyword boost/threshold
    // — see assets/kws/keywords.txt).
    private static final String ASSET_DIR = "kws";
    private static final String ENCODER =
            ASSET_DIR + "/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx";
    private static final String DECODER =
            ASSET_DIR + "/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx";
    private static final String JOINER =
            ASSET_DIR + "/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx";
    private static final String TOKENS = ASSET_DIR + "/tokens.txt";
    private static final String KEYWORDS = ASSET_DIR + "/keywords.txt";

    private static final int SAMPLE_RATE = 16000;
    private static final int FEATURE_DIM = 80;
    private static final int CHUNK_SAMPLES = 1600; // 100 ms per feed
    private static final long REFRACTORY_MS = 2500;
    private static final int STATS_WINDOW = 15;    // ~1.5s of chunks

    private final VoiceService service;
    private KeywordSpotter spotter;
    private OnlineStream stream;

    private volatile boolean running = false;
    private Thread thread;
    private AudioRecord record;
    private long lastSpotMs = 0;

    // Rolling level stats (dBFS per chunk) for the drone mirror.
    private final float[] statDb = new float[STATS_WINDOW];
    private int statHead = 0;
    private int statCount = 0;

    public WakeWordSpotter(VoiceService service) {
        this.service = service;
    }

    /**
     * Native init — call on a WORKER thread (model load takes a
     * moment). Returns false on any failure; the caller then fails
     * open to the pre-v1.60 always-recognizer behavior.
     */
    public boolean init(Context context) {
        try {
            FeatureConfig feat = new FeatureConfig(SAMPLE_RATE, FEATURE_DIM, 0.0f);
            OnlineTransducerModelConfig transducer =
                    new OnlineTransducerModelConfig(ENCODER, DECODER, JOINER);
            OnlineModelConfig model = new OnlineModelConfig(
                    transducer,
                    new OnlineParaformerModelConfig(),
                    new OnlineZipformer2CtcModelConfig(),
                    new OnlineNeMoCtcModelConfig(),
                    new OnlineToneCtcModelConfig(),
                    TOKENS, 2, false, "cpu", "", "", "");
            // Global defaults; both shipped keywords carry their own
            // :boost #threshold in keywords.txt.
            KeywordSpotterConfig config = new KeywordSpotterConfig(
                    feat, model, 4, KEYWORDS, 1.0f, 0.25f, 1);
            spotter = new KeywordSpotter(context.getAssets(), config);
            stream = spotter.createStream("");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "wakeword: spotter init failed", t);
            spotter = null;
            stream = null;
            return false;
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Start (or keep) the capture loop. Idempotent. */
    public synchronized void startCapture() {
        if (spotter == null) return;
        running = true;
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(this::captureLoop, "SelenaWakeWord");
        thread.start();
    }

    /** Stop the capture loop; unblocks a pending read. Idempotent. */
    public synchronized void stopCapture() {
        running = false;
        AudioRecord r = record;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
        }
    }

    /** Full native teardown (service destroy). */
    public void release() {
        stopCapture();
        try { if (stream != null) stream.release(); } catch (Exception ignored) {}
        try { if (spotter != null) spotter.release(); } catch (Exception ignored) {}
        stream = null;
        spotter = null;
    }

    private void captureLoop() {
        short[] buf = new short[CHUNK_SAMPLES];
        float[] samples = new float[CHUNK_SAMPLES];
        int readErrors = 0;
        while (running) {
            if (service.wakeSpotterParked()) {
                // The recognizer, playback, or the gate owns the mic
                // right now: release ours and wait. Nothing is fed,
                // nothing is captured.
                releaseRecord();
                sleep(200);
                continue;
            }
            if (record == null && !openRecord()) {
                service.onWakeSpotterFailed("AudioRecord init failed");
                return;
            }
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Exception e) {
                n = -1;
            }
            if (n <= 0) {
                if (!running) break;
                if (++readErrors > 20) {
                    service.onWakeSpotterFailed("AudioRecord read errors");
                    return;
                }
                sleep(50);
                continue;
            }
            readErrors = 0;
            pushStats(chunkDbfs(buf, n));
            for (int i = 0; i < n; i++) samples[i] = buf[i] / 32768.0f;
            float[] feed = (n == CHUNK_SAMPLES)
                    ? samples : Arrays.copyOf(samples, n);
            try {
                stream.acceptWaveform(feed, SAMPLE_RATE);
                while (spotter.isReady(stream)) {
                    spotter.decode(stream);
                    KeywordSpotterResult result = spotter.getResult(stream);
                    String keyword = result.getKeyword();
                    if (keyword != null && !keyword.isEmpty()) {
                        // Consume the detection either way so the same
                        // utterance can't re-fire from the stream tail.
                        spotter.reset(stream);
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastSpotMs >= REFRACTORY_MS) {
                            lastSpotMs = now;
                            float[] stats = statsSnapshot();
                            service.onWakeWordSpotted(
                                    keyword.replace('_', ' '), stats[0], stats[1]);
                        }
                        break;
                    }
                }
            } catch (Throwable t) {
                service.onWakeSpotterFailed("native decode failed: " + t);
                return;
            }
        }
        releaseRecord();
    }

    private boolean openRecord() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, CHUNK_SAMPLES * 2 * 4);
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
                    return true;
                }
                r.release();
            } catch (Exception e) {
                Log.w(TAG, "wakeword: AudioRecord source " + source
                        + " failed", e);
            }
        }
        return false;
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

    // ------------------------------------------------------------ level stats

    private static float chunkDbfs(short[] buf, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double v = buf[i] / 32768.0;
            sum += v * v;
        }
        double rms = Math.sqrt(sum / Math.max(1, n));
        return (float) (20.0 * Math.log10(rms + 1e-7));
    }

    private void pushStats(float db) {
        statDb[statHead] = db;
        statHead = (statHead + 1) % STATS_WINDOW;
        if (statCount < STATS_WINDOW) statCount++;
    }

    /** {meanDbfs, stdDbfs} over the recent window; {-60, 0} if none. */
    private float[] statsSnapshot() {
        if (statCount == 0) return new float[]{-60f, 0f};
        double mean = 0;
        for (int i = 0; i < statCount; i++) mean += statDb[i];
        mean /= statCount;
        double var = 0;
        for (int i = 0; i < statCount; i++) {
            double d = statDb[i] - mean;
            var += d * d;
        }
        return new float[]{(float) mean, (float) Math.sqrt(var / statCount)};
    }
}
