package com.selena.voice;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Nick 2026-10-28 v1.52: one short microphone probe for the noise
 * gate, run on a worker thread while the gate holds the recognizer
 * OFF (KILLED / noise-hold). Because the recognizer is stopped in
 * that state, this AudioRecord is the only mic consumer — the two
 * never fight over Android's single microphone. While the recognizer
 * IS live, the gate samples from its RMS callbacks instead and this
 * task never runs.
 *
 * A probe captures ~240ms of 16kHz mono audio and reduces it to
 * per-20ms-frame levels in dBFS: mean level, frame-to-frame variation
 * (stddev), and the loudest frame. VoiceService compares these
 * against the drone baseline it learned from earlier probes: a big
 * level DROP means the machine stopped (re-arm), high variation with
 * a peak clearly above the baseline means speech is riding over the
 * drone (re-arm into dictation).
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class GateProbeTask implements Runnable {
    private static final String TAG = "SelenaVoice";
    private final VoiceService service;

    public GateProbeTask(VoiceService service) {
        this.service = service;
    }

    @Override
    public void run() {
        float meanDbfs = 0f;
        float stdDb = 0f;
        float peakDbfs = -120f;
        boolean ok = false;
        AudioRecord rec = null;
        try {
            int rate = 16000;
            int minBuf = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                throw new IllegalStateException("getMinBufferSize=" + minBuf);
            }
            int bufSize = Math.max(minBuf, rate / 2);
            try {
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
            } catch (Exception e) {
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IllegalStateException("AudioRecord not initialized");
            }
            rec.startRecording();
            short[] frame = new short[320]; // 20ms at 16kHz
            int framesWanted = 12;          // ~240ms total
            double[] frameDb = new double[framesWanted];
            int frames = 0;
            while (frames < framesWanted) {
                int read = rec.read(frame, 0, frame.length);
                if (read <= 0) break;
                double sumSq = 0;
                for (int i = 0; i < read; i++) {
                    sumSq += (double) frame[i] * (double) frame[i];
                }
                double rms = Math.sqrt(sumSq / read);
                double db = 20.0 * Math.log10(Math.max(rms, 1.0) / 32768.0);
                frameDb[frames++] = db;
                if (db > peakDbfs) peakDbfs = (float) db;
            }
            if (frames >= 6) {
                double sum = 0;
                for (int i = 0; i < frames; i++) sum += frameDb[i];
                double mean = sum / frames;
                double var = 0;
                for (int i = 0; i < frames; i++) {
                    double d = frameDb[i] - mean;
                    var += d * d;
                }
                meanDbfs = (float) mean;
                stdDb = (float) Math.sqrt(var / frames);
                ok = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "gate probe failed", e);
        } finally {
            if (rec != null) {
                try { rec.stop(); } catch (Exception ignored) {}
                try { rec.release(); } catch (Exception ignored) {}
            }
        }
        service.onGateProbeResult(meanDbfs, stdDb, peakDbfs, ok);
    }
}
