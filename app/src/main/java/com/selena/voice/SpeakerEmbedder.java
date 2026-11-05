package com.selena.voice;

import android.content.Context;
import android.content.res.AssetManager;

import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

import java.io.InputStream;

/**
 * On-device speaker embeddings (Phase 0, Nick 2026-11-05) via the
 * sherpa-onnx 1.12.40 SpeakerEmbeddingExtractor and the WeSpeaker
 * ResNet34-LM model bundled in APK assets — the exact approach the
 * Siteview VoiceprintPlugin field-proved, including its scars:
 *
 * - In this AAR the extractor's only public constructor is
 *   (AssetManager, config) and config.model MUST be the
 *   asset-relative path. Passing a filesystem path makes the
 *   native side exit the process with no catchable exception, so
 *   the model is NEVER loaded from files — assets only — and the
 *   asset's existence is pre-validated through the Java API
 *   before the native call.
 * - The extractor is created LAZILY on first use (never at app
 *   start — a native init failure must land as a clean error in
 *   the voiceprint flow, not a startup crash), on the caller's
 *   background thread, and compute() calls are serialized here
 *   because the native extractor is not documented thread-safe.
 *
 * One instance per coach session (owned by CoachStreamer),
 * released at session end. Embeddings are computed on-device and
 * never leave the phone.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class SpeakerEmbedder {

    public static final String MODEL_ASSET_PATH =
            "voiceprint/wespeaker_en_voxceleb_resnet34_LM.onnx";
    private static final int SAMPLE_RATE = 16000;

    private final Context appContext;
    private final Object lock = new Object();
    private SpeakerEmbeddingExtractor extractor = null;
    private volatile long lastComputeMs = -1;

    public SpeakerEmbedder(Context ctx) {
        this.appContext = ctx.getApplicationContext();
    }

    /** True when the bundled model asset is present in this build. */
    public static boolean modelAvailable(Context ctx) {
        try (InputStream in = ctx.getAssets().open(MODEL_ASSET_PATH)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Milliseconds the most recent embed() spent in native compute. */
    public long lastComputeMs() {
        return lastComputeMs;
    }

    /**
     * Extract a 192-dim embedding from 16 kHz mono float PCM in
     * [-1, 1]. Returns null on any failure (logged by the caller);
     * throws nothing — native Errors are contained here so the
     * voiceprint flow reports instead of dying.
     */
    public float[] embed(float[] pcm) {
        if (pcm == null || pcm.length < SAMPLE_RATE / 2) return null;
        synchronized (lock) {
            try {
                SpeakerEmbeddingExtractor ext = extractor();
                if (ext == null) return null;
                OnlineStream stream = ext.createStream();
                try {
                    long t0 = System.nanoTime();
                    stream.acceptWaveform(pcm, SAMPLE_RATE);
                    stream.inputFinished();
                    float[] vec = ext.compute(stream);
                    lastComputeMs = (System.nanoTime() - t0) / 1_000_000L;
                    if (vec == null || vec.length == 0) return null;
                    return vec;
                } finally {
                    try { stream.release(); } catch (Exception ignored) {}
                }
            } catch (Throwable t) {
                VoiceService.log("vp: embed failed: " + t);
                return null;
            }
        }
    }

    private SpeakerEmbeddingExtractor extractor() {
        if (extractor != null) return extractor;
        // Pre-validate via Java before the native boundary (see the
        // class doc: a missing asset must be a clean failure here,
        // never a native process kill).
        if (!modelAvailable(appContext)) {
            VoiceService.log("vp: model asset missing: " + MODEL_ASSET_PATH);
            return null;
        }
        try {
            AssetManager am = appContext.getAssets();
            SpeakerEmbeddingExtractorConfig config =
                    new SpeakerEmbeddingExtractorConfig(
                            MODEL_ASSET_PATH, 2, false, "cpu");
            extractor = new SpeakerEmbeddingExtractor(am, config);
            VoiceService.log("vp: speaker embedding extractor ready "
                    + "(WeSpeaker ResNet34-LM, on-device)");
            return extractor;
        } catch (Throwable t) {
            VoiceService.log("vp: extractor init failed: " + t);
            extractor = null;
            return null;
        }
    }

    public void release() {
        synchronized (lock) {
            if (extractor != null) {
                try { extractor.release(); } catch (Exception ignored) {}
                extractor = null;
            }
        }
    }
}
