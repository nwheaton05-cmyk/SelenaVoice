package com.selena.voice;

/**
 * Nick 2026-10-29 v1.61: tiny iterative radix-2 FFT for the drone
 * extraction pipeline (SpectralSuppressor + GateProbeTask spectra).
 * Fixed purpose: real input, power-of-two sizes (512 in practice).
 * No allocation in the hot path — callers pass scratch arrays.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public final class Fft {
    private Fft() {}

    /**
     * In-place forward FFT. re/im length must be a power of two.
     * On return re/im hold the complex spectrum.
     */
    public static void forward(float[] re, float[] im) {
        int n = re.length;
        // Bit-reversal permutation.
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        // Danielson-Lanczos stages.
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            float wr = (float) Math.cos(ang);
            float wi = (float) Math.sin(ang);
            int half = len >> 1;
            for (int i = 0; i < n; i += len) {
                float cwr = 1f, cwi = 0f;
                for (int k = 0; k < half; k++) {
                    int u = i + k;
                    int v = u + half;
                    float vr = re[v] * cwr - im[v] * cwi;
                    float vi = re[v] * cwi + im[v] * cwr;
                    re[v] = re[u] - vr;
                    im[v] = im[u] - vi;
                    re[u] += vr;
                    im[u] += vi;
                    float nwr = cwr * wr - cwi * wi;
                    cwi = cwr * wi + cwi * wr;
                    cwr = nwr;
                }
            }
        }
    }

    /** In-place inverse FFT (conjugate trick, scaled by 1/n). */
    public static void inverse(float[] re, float[] im) {
        int n = re.length;
        for (int i = 0; i < n; i++) im[i] = -im[i];
        forward(re, im);
        for (int i = 0; i < n; i++) {
            re[i] /= n;
            im[i] = -im[i] / n;
        }
    }
}
