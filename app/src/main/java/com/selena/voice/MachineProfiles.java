package com.selena.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nick 2026-10-29 v1.61 (13:17 learning rule): the per-user MACHINE
 * PROFILE library for Drone Dictation mode. The system learns a
 * DISTINCT noise profile per machine — mower, weed whacker, blower
 * have genuinely different spectral signatures, so one generic
 * "drone" profile is not the design. Each profile is a compact
 * per-frequency power spectrum (PSD, 257 bins at FFT 512 / 16 kHz)
 * learned live whenever that machine's steady sound is present
 * (gate probes in KILLED, DroneSession frames in drone mode), plus
 * bookkeeping: runs, time heard, and a NAME.
 *
 * Naming (Nick 13:21 + 13:25 + 13:27 rulings): a new profile is
 * born as "Machine N" (first-heard order) in a needs-name state.
 * After a run of a still-unidentified machine ends (level drops
 * near ambient), VoiceService asks by voice — exactly "Hey, {user},
 * what was that noise?" — once per run, repeating on every hearing
 * until the user names it; once named, it is never asked again.
 * Names are also editable any time in MainActivity's machine-sounds
 * dialog (the fallback label path).
 *
 * Also held here, deliberately SEPARATE from the platform
 * Voiceprint: a drone-domain voice profile — probe-domain feature
 * vectors (the same 5 measurements GateProbeTask produces) learned
 * from utterances the drone ASR accepted. The design rule is that
 * own-capture/enhanced-domain samples never train the platform-RMS
 * voiceprint (mixing domains would silently corrupt it), so drone
 * voice learning lives in this store, in its own domain, and is
 * used only inside drone mode (burst classification assist in
 * later tuning; today it is learned and kept, honestly unused by
 * any gate decision).
 *
 * Storage: measurements/spectra ONLY — raw audio is never written
 * anywhere, ever (standing rule). Per-user file keyed to the
 * signed-in Siteview account (same key as the voiceprint); no
 * signed-in user → in-memory only, nothing persisted. All ops run
 * load→mutate→save under one static lock so the service and
 * MainActivity can both touch the store without clobbering.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class MachineProfiles {
    private static final String TAG = "SelenaVoice";
    public static final int BINS = SpectralSuppressor.BINS;
    public static final int MAX_PROFILES = 8;
    // TUNABLE: mean per-bin |log10 ratio| (in dB-ish units) under
    // which two spectra count as the same machine. Shape-only —
    // both spectra are total-power normalized before comparing, so
    // distance (mic gain, throttle) doesn't split one machine.
    public static final float MATCH_THRESHOLD = 3.0f;

    private static final Object LOCK = new Object();

    public static class Profile {
        public int id;
        public String name;
        public boolean named;
        public float[] psd;        // mean power spectrum (unnormalized)
        public double psdWeight;   // spectra folded in so far
        public double refPower;    // total power at learn conditions
        public int runs;
        public double totalSec;
        public long lastHeardMs;
    }

    /** Store state for one user key. */
    private static class Store {
        List<Profile> profiles = new ArrayList<>();
        int nextId = 1;
        int lastActiveId = -1;
        // Drone-domain voice profile (probe-domain 5-dim features).
        float[] voiceMean = null;
        double voiceWeight = 0;
        float[] voicePrevMean = null;
        double voicePrevWeight = 0;
    }

    // ------------------------------------------------------------ matching

    /** Shape distance between two PSDs (lower = same machine). */
    public static float distance(float[] a, float[] b) {
        if (a == null || b == null) return Float.MAX_VALUE;
        double ta = 0, tb = 0;
        for (int k = 2; k < BINS; k++) { ta += a[k]; tb += b[k]; }
        if (ta <= 0 || tb <= 0) return Float.MAX_VALUE;
        double sum = 0;
        int n = 0;
        for (int k = 2; k < BINS; k++) {
            double la = 10.0 * Math.log10(Math.max(a[k] / ta, 1e-9));
            double lb = 10.0 * Math.log10(Math.max(b[k] / tb, 1e-9));
            sum += Math.abs(la - lb);
            n++;
        }
        return (float) (sum / Math.max(1, n));
    }

    private static void foldPsd(Profile p, float[] psd, double w) {
        if (p.psd == null) {
            p.psd = new float[BINS];
            System.arraycopy(psd, 0, p.psd, 0, BINS);
            p.psdWeight = w;
        } else {
            double nw = p.psdWeight + w;
            for (int k = 0; k < BINS; k++) {
                p.psd[k] = (float) ((p.psd[k] * p.psdWeight + psd[k] * w) / nw);
            }
            p.psdWeight = nw;
        }
        double tot = 0;
        for (int k = 0; k < BINS; k++) tot += p.psd[k];
        p.refPower = tot;
        p.lastHeardMs = System.currentTimeMillis();
    }

    /**
     * The learning entry point: fold one drone spectrum into the
     * library. Returns the profile it landed in; sets created[0]
     * when a NEW profile was born. A signature that matches nothing
     * while the library is full folds into the nearest profile
     * instead of creating a ninth.
     */
    public Profile matchOrCreate(Context ctx, String userKey, float[] psd,
                                 boolean[] created) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            Profile best = null;
            float bestD = Float.MAX_VALUE;
            for (Profile p : s.profiles) {
                float d = distance(psd, p.psd);
                if (d < bestD) { bestD = d; best = p; }
            }
            boolean isNew = false;
            if (best == null || bestD > MATCH_THRESHOLD) {
                if (s.profiles.size() < MAX_PROFILES) {
                    best = new Profile();
                    best.id = s.nextId++;
                    best.name = "Machine " + best.id;
                    best.named = false;
                    s.profiles.add(best);
                    isNew = true;
                    Log.i(TAG, "drone: NEW machine profile learned — '"
                            + best.name + "' (needs a name)");
                } else if (best != null) {
                    Log.i(TAG, "drone: profile library full — folding new"
                            + " signature into '" + best.name + "'");
                }
            }
            if (best != null) {
                foldPsd(best, psd, 1.0);
                s.lastActiveId = best.id;
                save(ctx, userKey, s);
            }
            if (created != null) created[0] = isNew;
            return best;
        }
    }

    /** Drift-adapt an existing profile toward a fresh drone spectrum. */
    public void adapt(Context ctx, String userKey, int profileId, float[] psd,
                      double weight) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            for (Profile p : s.profiles) {
                if (p.id == profileId) {
                    foldPsd(p, psd, weight);
                    s.lastActiveId = p.id;
                    save(ctx, userKey, s);
                    return;
                }
            }
        }
    }

    /** Record that a run of this machine just happened. */
    public void noteRun(Context ctx, String userKey, int profileId, double sec) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            for (Profile p : s.profiles) {
                if (p.id == profileId) {
                    p.runs++;
                    p.totalSec += sec;
                    p.lastHeardMs = System.currentTimeMillis();
                    save(ctx, userKey, s);
                    return;
                }
            }
        }
    }

    /** The most recently active profile, if it still needs a name. */
    public Profile lastActiveUnnamed(Context ctx, String userKey) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            for (Profile p : s.profiles) {
                if (p.id == s.lastActiveId && !p.named) return p;
            }
            return null;
        }
    }

    public Profile get(Context ctx, String userKey, int profileId) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            for (Profile p : s.profiles) {
                if (p.id == profileId) return p;
            }
            return null;
        }
    }

    public List<Profile> list(Context ctx, String userKey) {
        synchronized (LOCK) {
            return new ArrayList<>(load(ctx, userKey).profiles);
        }
    }

    /** Name a profile (voice reply or dialog rename). Named = never asked again. */
    public void rename(Context ctx, String userKey, int profileId, String name) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            for (Profile p : s.profiles) {
                if (p.id == profileId) {
                    p.name = name;
                    p.named = true;
                    save(ctx, userKey, s);
                    Log.i(TAG, "drone: machine profile " + profileId
                            + " named '" + name + "'");
                    return;
                }
            }
        }
    }

    // ------------------------------------------------- drone-domain voice

    /**
     * Teach the DRONE-DOMAIN voice profile from one ASR-accepted
     * utterance's probe-domain features (raw branch, pre-suppression
     * measurements). Kept apart from the platform voiceprint by the
     * domain rule — this never touches Voiceprint's stores.
     */
    public void learnVoiceSample(Context ctx, String userKey, float[] feats) {
        if (feats == null || feats.length != Voiceprint.PROBE_DIMS) return;
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            s.voicePrevMean = s.voiceMean;
            s.voicePrevWeight = s.voiceWeight;
            if (s.voiceMean == null) {
                s.voiceMean = feats.clone();
                s.voiceWeight = 1.0;
            } else {
                double nw = s.voiceWeight + 1.0;
                for (int i = 0; i < feats.length; i++) {
                    s.voiceMean[i] = (float) (
                            (s.voiceMean[i] * s.voiceWeight + feats[i]) / nw);
                }
                s.voiceWeight = nw;
            }
            save(ctx, userKey, s);
            Log.i(TAG, "drone: voice sample learned in drone domain (n="
                    + (int) s.voiceWeight + ")");
        }
    }

    /** "That wasn't me" in drone mode: roll back the last voice sample. */
    public void undoLastVoiceSample(Context ctx, String userKey) {
        synchronized (LOCK) {
            Store s = load(ctx, userKey);
            if (s.voicePrevMean != null || s.voicePrevWeight > 0) {
                s.voiceMean = s.voicePrevMean;
                s.voiceWeight = s.voicePrevWeight;
                s.voicePrevMean = null;
                s.voicePrevWeight = 0;
                save(ctx, userKey, s);
                Log.i(TAG, "drone: last voice sample rolled back (wasn't me)");
            }
        }
    }

    // ------------------------------------------------------------ UI helpers

    /** Human lines for MainActivity's machine-sounds dialog. */
    public static List<Profile> listForUi(Context ctx) {
        String key = SupabaseAuth.userKey(ctx);
        return new MachineProfiles().list(ctx, key);
    }

    public static boolean renameForUi(Context ctx, int profileId, String name) {
        String key = SupabaseAuth.userKey(ctx);
        if (key == null || name == null || name.trim().isEmpty()) return false;
        new MachineProfiles().rename(ctx, key, profileId, name.trim());
        return true;
    }

    public static String describe(Profile p) {
        String heard = p.lastHeardMs > 0
                ? new java.text.SimpleDateFormat("MMM d, h:mm a", Locale.US)
                        .format(new java.util.Date(p.lastHeardMs))
                : "never";
        return p.name + (p.named ? "" : "  (unnamed — tap to name)")
                + "\n   " + p.runs + (p.runs == 1 ? " run" : " runs")
                + ", last heard " + heard;
    }

    // ------------------------------------------------------------ persistence

    private static String fileKey(String userKey) {
        return userKey.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static File storeFile(Context ctx, String userKey) {
        File dir = new File(ctx.getFilesDir(), "voiceprint");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "machines_" + fileKey(userKey) + ".json");
    }

    private Store load(Context ctx, String userKey) {
        Store s = new Store();
        if (userKey == null) return s; // signed out: in-memory only
        try {
            File f = storeFile(ctx, userKey);
            if (!f.exists()) return s;
            JSONObject root = new JSONObject(readFile(f));
            s.nextId = root.optInt("nextId", 1);
            s.lastActiveId = root.optInt("lastActiveId", -1);
            JSONArray arr = root.optJSONArray("profiles");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Profile p = new Profile();
                    p.id = o.optInt("id");
                    p.name = o.optString("name", "Machine " + p.id);
                    p.named = o.optBoolean("named", false);
                    p.psdWeight = o.optDouble("psdWeight", 0);
                    p.refPower = o.optDouble("refPower", 0);
                    p.runs = o.optInt("runs", 0);
                    p.totalSec = o.optDouble("totalSec", 0);
                    p.lastHeardMs = o.optLong("lastHeardMs", 0);
                    JSONArray psd = o.optJSONArray("psd");
                    if (psd != null && psd.length() == BINS) {
                        p.psd = new float[BINS];
                        for (int k = 0; k < BINS; k++) {
                            p.psd[k] = (float) psd.optDouble(k, 0);
                        }
                    }
                    s.profiles.add(p);
                }
            }
            JSONArray vm = root.optJSONArray("voiceMean");
            if (vm != null && vm.length() == Voiceprint.PROBE_DIMS) {
                s.voiceMean = new float[Voiceprint.PROBE_DIMS];
                for (int i = 0; i < Voiceprint.PROBE_DIMS; i++) {
                    s.voiceMean[i] = (float) vm.optDouble(i, 0);
                }
                s.voiceWeight = root.optDouble("voiceWeight", 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "drone: machine store load failed", e);
        }
        return s;
    }

    private void save(Context ctx, String userKey, Store s) {
        if (userKey == null) return; // signed out: nothing persisted
        try {
            JSONObject root = new JSONObject();
            root.put("v", 1);
            root.put("nextId", s.nextId);
            root.put("lastActiveId", s.lastActiveId);
            JSONArray arr = new JSONArray();
            for (Profile p : s.profiles) {
                JSONObject o = new JSONObject();
                o.put("id", p.id);
                o.put("name", p.name);
                o.put("named", p.named);
                o.put("psdWeight", p.psdWeight);
                o.put("refPower", p.refPower);
                o.put("runs", p.runs);
                o.put("totalSec", p.totalSec);
                o.put("lastHeardMs", p.lastHeardMs);
                if (p.psd != null) {
                    JSONArray psd = new JSONArray();
                    for (int k = 0; k < BINS; k++) {
                        psd.put(Math.round(p.psd[k] * 10000.0) / 10000.0);
                    }
                    o.put("psd", psd);
                }
                arr.put(o);
            }
            root.put("profiles", arr);
            if (s.voiceMean != null) {
                JSONArray vm = new JSONArray();
                for (float v : s.voiceMean) vm.put(v);
                root.put("voiceMean", vm);
                root.put("voiceWeight", s.voiceWeight);
            }
            writeFile(storeFile(ctx, userKey), root.toString());
        } catch (Exception e) {
            Log.w(TAG, "drone: machine store save failed", e);
        }
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0;
        int r;
        while ((r = in.read(buf, off, buf.length - off)) > 0) off += r;
        in.close();
        return new String(buf, 0, off, StandardCharsets.UTF_8);
    }

    private static void writeFile(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.close();
    }
}
