package com.selena.voice;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Nick 2026-10-28 v1.53 (Phase 4, spec §5): one learned measurement
 * profile — a weighted running mean/variance over a fixed-length
 * feature vector (measurements only; no audio is ever stored anywhere
 * in this system).
 *
 * Learning is deliberately SLOW: each update is blended with weight
 * alpha = w / (PRIOR_WEIGHT + totalWeight), so a sample's pull shrinks
 * as the profile matures and one odd sample (a crew member near the
 * phone, a TV) cannot move it. Only patterns repeated across many
 * dictations shape the profile.
 *
 * A profile has NO VOTE until it holds enough evidence (min weight
 * and count): cold start is honest — the gate runs on its Phase 1
 * rules alone and the voiceprint can never block an unlearned user.
 */
public class VoiceprintProfile {
    // Prior pseudo-weight: the profile starts life trusting "nothing
    // known" with this much weight, which is what makes early learning
    // gradual and late learning glacial.
    private static final double PRIOR_WEIGHT = 8.0;
    // Evidence floor before the profile may vote at all.
    private static final double MIN_VOTE_WEIGHT = 4.0;
    private static final int MIN_VOTE_COUNT = 5;

    public final int dims;
    public double[] mean;
    public double[] m2;      // weighted sum of squared deviations
    public double wsum;      // total accepted weight
    public int count;        // number of samples folded in
    // Per-dimension variance floors for distance scoring, so a
    // dimension the user happens to hold very steady doesn't turn
    // microscopic differences into huge z-scores.
    private final float[] varFloor;

    public VoiceprintProfile(int dims, float[] varFloor) {
        this.dims = dims;
        this.varFloor = varFloor;
        this.mean = new double[dims];
        this.m2 = new double[dims];
        this.wsum = 0;
        this.count = 0;
    }

    /** Fold one measurement in with the given class weight. */
    public synchronized void update(float[] feats, double w) {
        if (feats == null || feats.length != dims || w <= 0) return;
        double alpha = w / (PRIOR_WEIGHT + wsum);
        for (int i = 0; i < dims; i++) {
            double delta = feats[i] - mean[i];
            mean[i] += alpha * delta;
            // Weighted Welford: m2 tracks variance mass in the same
            // blended frame; scaled by effective weight below.
            m2[i] += alpha * (1.0 - alpha) * delta * delta * (PRIOR_WEIGHT + wsum);
        }
        wsum += w;
        count++;
    }

    public synchronized boolean hasVote() {
        return wsum >= MIN_VOTE_WEIGHT && count >= MIN_VOTE_COUNT;
    }

    /**
     * RMS z-distance between a measurement and this profile: 0 = dead
     * on the profile's center; ~1 = typical member; >2.5 = unlike
     * anything the profile has learned.
     */
    public synchronized double distance(float[] feats) {
        if (feats == null || feats.length != dims || count == 0) {
            return Double.MAX_VALUE;
        }
        double acc = 0;
        for (int i = 0; i < dims; i++) {
            double var = (wsum > 0) ? m2[i] / (PRIOR_WEIGHT + wsum) : 0;
            double sd = Math.sqrt(var + varFloor[i]);
            double z = (feats[i] - mean[i]) / sd;
            acc += z * z;
        }
        return Math.sqrt(acc / dims);
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        JSONArray m = new JSONArray();
        JSONArray v = new JSONArray();
        for (int i = 0; i < dims; i++) {
            m.put(mean[i]);
            v.put(m2[i]);
        }
        o.put("mean", m);
        o.put("m2", v);
        o.put("wsum", wsum);
        o.put("count", count);
        return o;
    }

    public static VoiceprintProfile fromJson(JSONObject o, int dims,
                                             float[] varFloor) {
        VoiceprintProfile p = new VoiceprintProfile(dims, varFloor);
        if (o == null) return p;
        try {
            JSONArray m = o.optJSONArray("mean");
            JSONArray v = o.optJSONArray("m2");
            if (m != null && v != null
                    && m.length() == dims && v.length() == dims) {
                for (int i = 0; i < dims; i++) {
                    p.mean[i] = m.optDouble(i, 0);
                    p.m2[i] = v.optDouble(i, 0);
                }
                p.wsum = o.optDouble("wsum", 0);
                p.count = o.optInt("count", 0);
            }
        } catch (Exception ignored) {}
        return p;
    }
}
