package com.selena.voice;

/**
 * Nick 2026-10-29 v1.61: stationary-noise spectral suppression — the
 * extraction half of Drone Dictation mode. The machine profiles
 * (MachineProfiles) learn each machine's power spectrum (PSD) while
 * it runs; this class attenuates exactly that stationary spectrum
 * out of the live capture so the user's voice — non-stationary by
 * nature — survives for the on-device ASR.
 *
 * Method: STFT (FFT 512, Hann, hop 256, overlap-add) with power
 * spectral subtraction per bin:
 *     gain² = max(1 − ALPHA · noisePsd / |X|², BETA), gain smoothed
 * Constants were chosen by an offline sweep against sherpa-onnx
 * decoding of speech mixed under a synthetic mower drone at −6 dB
 * SNR (raw mix decoded to garbage; these constants recovered roughly
 * half the words on the 20M model — the best of five variants):
 * ALPHA 2.0, BETA 0.02 (≈ −17 dB floor), gain smoothing 0.65.
 * They are TUNABLES for the field pass, named so they can be found.
 *
 * The noise PSD is ADAPTED only by the caller (DroneSession) during
 * non-speech and FROZEN during speech; this class never adapts on
 * its own. Audio exists only as the frames being processed — nothing
 * is stored here or anywhere (measurements-only rule).
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class SpectralSuppressor {
    public static final int FFT_SIZE = 512;
    public static final int HOP = 256;
    public static final int BINS = FFT_SIZE / 2 + 1;

    // Tunables (offline sweep 2026-10-29, see class header).
    public static final float ALPHA = 2.0f;        // oversubtraction
    public static final float BETA = 0.02f;        // gain² floor
    public static final float GAIN_SMOOTH = 0.65f; // gain temporal smoothing

    private final float[] window = new float[FFT_SIZE];
    private final float[] inBuf = new float[FFT_SIZE];   // last 512 raw samples
    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private final float[] noisePsd = new float[BINS];    // power per bin
    private final float[] gainSmooth = new float[BINS];
    private final float[] outAcc = new float[FFT_SIZE];  // overlap-add
    private final float[] normAcc = new float[FFT_SIZE];
    private boolean hasNoise = false;

    public SpectralSuppressor() {
        for (int i = 0; i < FFT_SIZE; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(
                    2.0 * Math.PI * i / (FFT_SIZE - 1)));
        }
        for (int k = 0; k < BINS; k++) gainSmooth[k] = 1f;
    }

    /** Hand the suppressor the active machine's power spectrum. */
    public synchronized void setNoisePsd(float[] psdPower) {
        if (psdPower == null || psdPower.length != BINS) {
            hasNoise = false;
            return;
        }
        System.arraycopy(psdPower, 0, noisePsd, 0, BINS);
        hasNoise = true;
    }

    public boolean hasNoise() {
        return hasNoise;
    }

    /**
     * Process one hop (256 new raw samples). Writes 256 enhanced
     * float samples (range ≈ ±1) into out. Fills powerOut[257] with
     * the RAW frame's power spectrum for the caller's PSD adaptation
     * and profile matching. Returns the raw frame's mean level dBFS.
     */
    public synchronized float processHop(short[] hop, float[] out,
                                         float[] powerOut) {
        // Shift the analysis buffer and append the new hop.
        System.arraycopy(inBuf, HOP, inBuf, 0, FFT_SIZE - HOP);
        double sumSq = 0;
        for (int i = 0; i < HOP; i++) {
            float v = hop[i] / 32768f;
            inBuf[FFT_SIZE - HOP + i] = v;
            sumSq += (double) hop[i] * hop[i];
        }
        double rms = Math.sqrt(sumSq / HOP);
        float hopDbfs = (float) (20.0 * Math.log10(Math.max(rms, 1.0) / 32768.0));

        for (int i = 0; i < FFT_SIZE; i++) {
            re[i] = inBuf[i] * window[i];
            im[i] = 0f;
        }
        Fft.forward(re, im);
        for (int k = 0; k < BINS; k++) {
            powerOut[k] = re[k] * re[k] + im[k] * im[k];
        }

        // Spectral-subtraction gains against the frozen machine PSD.
        for (int k = 0; k < BINS; k++) {
            float g = 1f;
            if (hasNoise) {
                double px = Math.max(powerOut[k], 1e-12f);
                double g2 = 1.0 - ALPHA * noisePsd[k] / px;
                if (g2 < BETA) g2 = BETA;
                g = (float) Math.sqrt(g2);
            }
            g = GAIN_SMOOTH * gainSmooth[k] + (1f - GAIN_SMOOTH) * g;
            gainSmooth[k] = g;
            re[k] *= g;
            im[k] *= g;
        }
        // Mirror the upper half for the inverse transform.
        for (int k = BINS; k < FFT_SIZE; k++) {
            re[k] = re[FFT_SIZE - k];
            im[k] = -im[FFT_SIZE - k];
        }
        Fft.inverse(re, im);

        for (int i = 0; i < FFT_SIZE; i++) {
            outAcc[i] += re[i] * window[i];
            normAcc[i] += window[i] * window[i];
        }
        for (int i = 0; i < HOP; i++) {
            float norm = normAcc[i];
            out[i] = norm > 1e-6f ? outAcc[i] / norm : 0f;
        }
        System.arraycopy(outAcc, HOP, outAcc, 0, FFT_SIZE - HOP);
        System.arraycopy(normAcc, HOP, normAcc, 0, FFT_SIZE - HOP);
        for (int i = FFT_SIZE - HOP; i < FFT_SIZE; i++) {
            outAcc[i] = 0f;
            normAcc[i] = 0f;
        }
        return hopDbfs;
    }

    /** Reset streaming state (new capture session). Noise PSD kept. */
    public synchronized void resetStream() {
        java.util.Arrays.fill(inBuf, 0f);
        java.util.Arrays.fill(outAcc, 0f);
        java.util.Arrays.fill(normAcc, 0f);
        for (int k = 0; k < BINS; k++) gainSmooth[k] = 1f;
    }
}
