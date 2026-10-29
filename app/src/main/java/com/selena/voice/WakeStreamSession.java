package com.selena.voice;

import android.content.Context;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Nick 2026-10-29 v1.68 — ZERO-GAP WAKE HANDOFF (his 15:19 ruling:
 * dictation recognition starts IMMEDIATELY after the wake word; no
 * dead air, no clipped first words).
 *
 * The problem this replaces: at a KWS spot, the spotter stopped and
 * the platform SpeechRecognizer cold-started — its startup window
 * (hundreds of ms) captured nothing, so speech begun right after
 * "Insight" lost its first words. The platform recognizer cannot
 * accept pre-recorded audio, so the handoff runs on the app's OWN
 * streaming ASR (the same sherpa Zipformer en-20M the drone path
 * uses, model managed by DroneModelStore):
 *
 *   1. The spotter's capture NEVER stops. At the spot it switches
 *      to handoff mode (see WakeWordSpotter): chunks stop feeding
 *      the KWS model and are forwarded here instead. One mic, one
 *      AudioRecord, no transfer hole.
 *   2. This session first decodes the SEED — the ring slice from
 *      the wake word's end up to the spot — then the forwarded
 *      live chunks, in order. Every sample spoken from the wake
 *      word's end onward reaches the recognizer.
 *   3. On endpoint, the final goes to
 *      VoiceService.onWakeStreamDictation (the same sentence
 *      pipeline the platform recognizer feeds); the session ends
 *      and VoiceService hands the conversation to the platform
 *      recognizer exactly as the pre-v1.68 flow did after a spot.
 *
 * Finals only (no partials) — the wake dictation is the first
 * utterance of a conversation; the live popup is a later nicety.
 * Fails open: if the model isn't downloaded yet or anything here
 * breaks, VoiceService uses the pre-v1.68 cold-start handoff.
 *
 * Top-level class on purpose (the build chain's d8 note).
 */
public class WakeStreamSession implements WakeWordSpotter.ChunkSink {
    private static final String TAG = "SelenaVoice";
    private static final int SAMPLE_RATE = 16000;
    private static final int FEED_CHUNK = 1600; // 100 ms decode steps
    private static final long MAX_SESSION_MS = 25_000;
    private static final int MAX_QUEUED_CHUNKS = 100; // ~10 s backlog cap

    private final VoiceService service;
    private OnlineRecognizer recognizer;
    private volatile boolean ready = false;
    private volatile boolean active = false;
    private Thread thread;
    private final LinkedBlockingQueue<short[]> queue =
            new LinkedBlockingQueue<>();

    public WakeStreamSession(VoiceService service) {
        this.service = service;
    }

    /** Build the recognizer from the downloaded model files. Call
     *  on a worker thread. False = not available (fail open). */
    public boolean init(Context ctx) {
        try {
            if (!DroneModelStore.isReady(ctx)) return false;
            String dir = DroneModelStore.dir(ctx).getAbsolutePath();
            OnlineTransducerModelConfig transducer =
                    new OnlineTransducerModelConfig(
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
            // Same endpoint rules as the drone path (field-proven):
            // 2.4 s silence ends a no-speech stream, 1.2 s trailing
            // silence ends speech, 20 s hard cap per utterance.
            config.setEndpointConfig(new EndpointConfig(
                    new EndpointRule(false, 2.4f, 0f),
                    new EndpointRule(true, 1.2f, 0f),
                    new EndpointRule(false, 0f, 20f)));
            OnlineRecognizer rec;
            try {
                rec = new OnlineRecognizer(null, config);
            } catch (Throwable t1) {
                Log.w(TAG, "wakestream: init (null assets) failed, "
                        + "retrying with AssetManager", t1);
                rec = new OnlineRecognizer(ctx.getAssets(), config);
            }
            recognizer = rec;
            ready = true;
            Log.i(TAG, "wakestream: zero-gap recognizer ready");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "wakestream: recognizer init failed", t);
            recognizer = null;
            ready = false;
            return false;
        }
    }

    public boolean isReady() {
        return ready;
    }

    public boolean isActive() {
        return active;
    }

    /** WakeWordSpotter.ChunkSink: live chunks from the spotter's
     *  capture while a handoff is running. Drops beyond the backlog
     *  cap rather than growing without bound. */
    @Override
    public void onChunk(short[] chunk, int n) {
        if (!active) return;
        if (queue.size() < MAX_QUEUED_CHUNKS) {
            queue.offer(n == chunk.length ? chunk
                    : java.util.Arrays.copyOf(chunk, n));
        }
    }

    /**
     * Start a handoff session: decode the seed slice first, then
     * live chunks (arriving via onChunk) until endpoint. Returns
     * false when unavailable — the caller falls back to the
     * cold-start recognizer handoff.
     */
    public synchronized boolean begin(float[] seed) {
        if (!ready || active || recognizer == null) return false;
        active = true;
        queue.clear();
        final float[] seedCopy = seed != null ? seed : new float[0];
        thread = new Thread(() -> runSession(seedCopy), "SelenaWakeStream");
        thread.start();
        return true;
    }

    private void runSession(float[] seed) {
        OnlineStream stream = null;
        String finalText = null;
        try {
            stream = recognizer.createStream("");
            // 1) Seed: the audio from the wake word's end to the spot.
            feedAll(stream, seed);
            // 2) Live chunks until the recognizer endpoints. A 3 s
            // chunk drought (the spotter's capture was stopped out
            // from under the handoff) also ends the session — the
            // flush below still salvages any decoded text.
            long deadline = System.currentTimeMillis() + MAX_SESSION_MS;
            long lastChunkAt = System.currentTimeMillis();
            while (active && System.currentTimeMillis() < deadline) {
                if (recognizer.isEndpoint(stream)) break;
                short[] chunk = queue.poll(100, TimeUnit.MILLISECONDS);
                if (chunk == null) {
                    if (System.currentTimeMillis() - lastChunkAt > 3000) {
                        break;
                    }
                    continue;
                }
                lastChunkAt = System.currentTimeMillis();
                float[] f = new float[chunk.length];
                for (int i = 0; i < chunk.length; i++) {
                    f[i] = chunk[i] / 32768.0f;
                }
                feedAll(stream, f);
            }
            // Trailing-silence flush so a just-finished utterance
            // endpoints even if the live chunks stopped arriving.
            if (active) {
                float[] tail = new float[SAMPLE_RATE / 2];
                stream.acceptWaveform(tail, SAMPLE_RATE);
                stream.inputFinished();
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
            }
            OnlineRecognizerResult r = recognizer.getResult(stream);
            String text = r != null ? r.getText() : null;
            if (text != null && !text.trim().isEmpty()) {
                finalText = text.trim();
            }
        } catch (Throwable t) {
            Log.w(TAG, "wakestream: session failed", t);
            finalText = null;
        } finally {
            active = false;
            if (stream != null) {
                try { stream.release(); } catch (Exception ignored) {}
            }
        }
        if (finalText != null) {
            service.onWakeStreamDictation(finalText);
        }
        service.onWakeStreamEnded();
    }

    /** Feed samples in 100 ms steps, decoding as they go. */
    private void feedAll(OnlineStream stream, float[] samples) {
        int off = 0;
        while (off < samples.length) {
            int n = Math.min(FEED_CHUNK, samples.length - off);
            float[] part = new float[n];
            System.arraycopy(samples, off, part, 0, n);
            stream.acceptWaveform(part, SAMPLE_RATE);
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            off += n;
        }
    }

    /** Abort a running session (service teardown / superseded). */
    public synchronized void cancel() {
        active = false;
    }

    public void release() {
        cancel();
        try {
            if (recognizer != null) recognizer.release();
        } catch (Exception ignored) {}
        recognizer = null;
        ready = false;
    }
}
