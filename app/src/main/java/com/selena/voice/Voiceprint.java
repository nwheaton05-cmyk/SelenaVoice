package com.selena.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Nick 2026-10-28 v1.53 (Phase 4, build spec §5): the learned
 * VOICEPRINT — passive, per-user, measurements only.
 *
 * WHAT IS MEASURED, AND WHY THIS SHAPE: while a dictation runs, the
 * SpeechRecognizer owns the microphone (Phase 1's hard rule: a second
 * mic consumer starves/degrades it, worst on Samsung). So no pitch
 * track is available without breaking dictation itself. What the
 * recognizer DOES stream is its RMS envelope (onRmsChanged), and an
 * utterance's envelope carries a consistent personal signature: the
 * level a user habitually speaks at into this mic, how much their
 * level swings syllable to syllable, their dynamics, their syllable
 * rhythm, and how much of their speech is pause. The feature vector is
 * those seven statistics. While the gate is KILLED, GateProbeTask's
 * short PCM probes (the only mic consumer then) yield a second,
 * probe-domain feature set — frame levels plus zero-crossing rate and
 * a brightness ratio — used for the noise profile and for confirming
 * speech bursts. The raw trace/probe audio exists only transiently in
 * memory to be measured and is then discarded: NOTHING playable is
 * ever written anywhere (Nick's measurements-only ruling, 22:14).
 *
 * TWO PROFILES (§5): the signed-in user's voice, walled per user and
 * keyed to the signed-in Siteview account; and the device/environment
 * noise profile, which is not identity and lives outside the wall.
 *
 * THE SCORE IS A BOOSTER, NEVER A LOCK (§4.3/§5): a MATCH can open
 * the gate (relax a probe burst, rescue a borderline discard); a
 * mismatch changes NOTHING — thresholds never rise, nothing is ever
 * refused because of the voiceprint. Cold start = NO_VOTE.
 *
 * STORAGE (§5 addenda): per user, dated measurement RECORDS live in a
 * private on-device file; the derived profile stays on the phone for
 * real-time scoring. Records older than a week are uploaded to the
 * server under the user's profile (VoiceprintSyncTask) and removed
 * locally once the server confirms; the server ages its archive with
 * the 1.5-month / locked-3-week-floor rule. If a fresh phone finds an
 * empty local profile, the server archive re-seeds it.
 */
public class Voiceprint {
    private static final String TAG = "SelenaVoice";

    // Recognizer-domain feature vector (from the RMS envelope):
    //   0 meanDb · 1 stdDb · 2 peakDb · 3 p10Db · 4 dynDb (p90-p10)
    //   5 burstsPerSec (syllable-rate proxy) · 6 pauseRatio
    public static final int DIMS = 7;
    private static final float[] VAR_FLOOR = {
            1.5f, 0.5f, 1.5f, 1.5f, 1.0f, 0.4f, 0.08f};

    // Probe-domain feature vector (GateProbeTask PCM frames):
    //   0 meanDbfs · 1 stdDb · 2 peakDbfs · 3 zcr · 4 brightRatio
    public static final int PROBE_DIMS = 5;
    private static final float[] PROBE_VAR_FLOOR = {
            1.5f, 0.4f, 1.5f, 0.01f, 0.05f};

    // Verdicts. NO_VOTE: not enough learned yet (cold start) — the
    // gate must behave exactly as if the voiceprint didn't exist.
    public static final int NO_VOTE = 0;
    public static final int MATCH = 1;
    public static final int NO_MATCH = 2;

    // A measurement is the user's when it sits close to the voice
    // profile AND clearly closer to it than to the known noise.
    private static final double MATCH_DIST = 2.4;
    private static final double MATCH_MARGIN = 0.7;
    private static final double PROBE_MATCH_DIST = 2.2;
    private static final double PROBE_MATCH_MARGIN = 0.5;

    // Sample classes and their voice weights (§5/§4.1): override
    // dictations are the strongest evidence (the user asserted "that
    // was me"); clean quiet samples weigh most of the ordinary ones;
    // a dictation that succeeded over noise proves less about the
    // voice but feeds the noise profile too.
    public static final String CLS_OVERRIDE = "override";
    public static final String CLS_CLEAN = "clean";
    public static final String CLS_NORMAL = "normal";
    public static final String CLS_OVER_NOISE = "over_noise";
    public static double weightFor(String cls) {
        switch (cls) {
            case CLS_OVERRIDE: return 1.5;
            case CLS_CLEAN: return 1.0;
            case CLS_OVER_NOISE: return 0.4;
            default: return 0.7;
        }
    }

    private static final int MAX_RECORDS = 500;
    public static final int UPLOAD_AGE_DAYS = 7;

    private final Context ctx;
    // Per-user state, loaded lazily for the signed-in key.
    private String loadedUserKey = null;
    private VoiceprintProfile voiceProfile = new VoiceprintProfile(DIMS, VAR_FLOOR);
    private List<JSONObject> voiceRecords = new ArrayList<>();
    private boolean archiveFolded = false;
    private JSONObject lastVoiceSample = null; // for "that wasn't me"
    // Device/environment state (outside the per-user wall).
    private VoiceprintProfile noiseRecog = new VoiceprintProfile(DIMS, VAR_FLOOR);
    private VoiceprintProfile noiseProbe = new VoiceprintProfile(PROBE_DIMS, PROBE_VAR_FLOOR);
    private VoiceprintProfile voiceProbe = new VoiceprintProfile(PROBE_DIMS, PROBE_VAR_FLOOR);
    private boolean noiseLoaded = false;

    public Voiceprint(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        loadNoise();
    }

    // ------------------------------------------------------------ features

    /**
     * Reduce an RMS envelope trace (recognizer dB values + their
     * uptime timestamps) to the 7-dim feature vector. Returns null
     * when the trace is too short to say anything honest.
     */
    public static float[] featuresFromTrace(float[] db, long[] ms, int n) {
        if (db == null || ms == null || n < 6) return null;
        double sum = 0;
        float peak = -100f;
        for (int i = 0; i < n; i++) {
            sum += db[i];
            if (db[i] > peak) peak = db[i];
        }
        double mean = sum / n;
        double var = 0;
        int pauses = 0;
        for (int i = 0; i < n; i++) {
            double d = db[i] - mean;
            var += d * d;
            if (db[i] < mean - 5.0) pauses++;
        }
        double std = Math.sqrt(var / n);
        float[] sorted = new float[n];
        System.arraycopy(db, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted);
        float p10 = sorted[Math.max(0, (int) (n * 0.10) - 1)];
        float p90 = sorted[Math.min(n - 1, (int) (n * 0.90))];
        double durSec = Math.max(0.2, (ms[n - 1] - ms[0]) / 1000.0);
        // Syllable-rate proxy: rising crossings of mean + 0.4*std with
        // at least 120ms between counted bursts.
        double thresh = mean + 0.4 * std;
        int bursts = 0;
        long lastBurstMs = -1000;
        boolean above = false;
        for (int i = 0; i < n; i++) {
            if (db[i] >= thresh) {
                if (!above && ms[i] - lastBurstMs >= 120) {
                    bursts++;
                    lastBurstMs = ms[i];
                }
                above = true;
            } else {
                above = false;
            }
        }
        float[] f = new float[DIMS];
        f[0] = (float) mean;
        f[1] = (float) std;
        f[2] = peak;
        f[3] = p10;
        f[4] = p90 - p10;
        f[5] = (float) (bursts / durSec);
        f[6] = (float) pauses / (float) n;
        return f;
    }

    // ------------------------------------------------------------ user key

    /** The signed-in account key, or null when nobody is signed in. */
    public String currentUserKey() {
        return SupabaseAuth.userKey(ctx);
    }

    private static String fileKey(String userKey) {
        return userKey.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private File userFile(String userKey) {
        File dir = new File(ctx.getFilesDir(), "voiceprint");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "u_" + fileKey(userKey) + ".json");
    }

    private File noiseFile() {
        File dir = new File(ctx.getFilesDir(), "voiceprint");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "noise.json");
    }

    // ------------------------------------------------------------ persistence

    private synchronized void ensureUserLoaded(String userKey) {
        if (userKey == null) return;
        if (userKey.equals(loadedUserKey)) return;
        loadedUserKey = userKey;
        voiceProfile = new VoiceprintProfile(DIMS, VAR_FLOOR);
        voiceRecords = new ArrayList<>();
        archiveFolded = false;
        lastVoiceSample = null;
        try {
            File f = userFile(userKey);
            if (!f.exists()) return;
            JSONObject root = new JSONObject(readFile(f));
            voiceProfile = VoiceprintProfile.fromJson(
                    root.optJSONObject("voice"), DIMS, VAR_FLOOR);
            archiveFolded = root.optBoolean("archiveFolded", false);
            JSONArray recs = root.optJSONArray("records");
            if (recs != null) {
                for (int i = 0; i < recs.length(); i++) {
                    voiceRecords.add(recs.getJSONObject(i));
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: user store load failed", e);
        }
    }

    private synchronized void loadNoise() {
        if (noiseLoaded) return;
        noiseLoaded = true;
        try {
            File f = noiseFile();
            if (!f.exists()) return;
            JSONObject root = new JSONObject(readFile(f));
            noiseRecog = VoiceprintProfile.fromJson(
                    root.optJSONObject("recog"), DIMS, VAR_FLOOR);
            noiseProbe = VoiceprintProfile.fromJson(
                    root.optJSONObject("probe"), PROBE_DIMS, PROBE_VAR_FLOOR);
            voiceProbe = VoiceprintProfile.fromJson(
                    root.optJSONObject("vprobe"), PROBE_DIMS, PROBE_VAR_FLOOR);
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: noise store load failed", e);
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

    /** Persist on a worker thread — file IO never touches the main thread. */
    private void persistAsync() {
        final Voiceprint self = this;
        new Thread(() -> self.persistNow()).start();
    }

    public synchronized void persistNow() {
        try {
            if (loadedUserKey != null) {
                JSONObject root = new JSONObject();
                root.put("v", 1);
                root.put("voice", voiceProfile.toJson());
                root.put("archiveFolded", archiveFolded);
                JSONArray recs = new JSONArray();
                for (JSONObject r : voiceRecords) recs.put(r);
                root.put("records", recs);
                writeFile(userFile(loadedUserKey), root.toString());
            }
            JSONObject n = new JSONObject();
            n.put("v", 1);
            n.put("recog", noiseRecog.toJson());
            n.put("probe", noiseProbe.toJson());
            n.put("vprobe", voiceProbe.toJson());
            writeFile(noiseFile(), n.toString());
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: persist failed", e);
        }
    }

    // ------------------------------------------------------------ learning

    /**
     * Teach the signed-in user's voice from one ACCEPTED dictation.
     * Called only on the acceptance path — discarded noise sessions,
     * low-confidence results, and Selena's own playback never reach
     * here (§5 training rules). No signed-in user → no learning.
     */
    public synchronized void learnVoice(float[] feats, String cls, float conf) {
        String key = currentUserKey();
        if (key == null || feats == null) return;
        ensureUserLoaded(key);
        double w = weightFor(cls);
        voiceProfile.update(feats, w);
        try {
            JSONObject rec = new JSONObject();
            rec.put("ts", System.currentTimeMillis());
            rec.put("day", dayString(System.currentTimeMillis()));
            rec.put("cls", cls);
            rec.put("w", w);
            rec.put("conf", conf);
            JSONArray fa = new JSONArray();
            for (float v : feats) fa.put(v);
            rec.put("f", fa);
            voiceRecords.add(rec);
            while (voiceRecords.size() > MAX_RECORDS) voiceRecords.remove(0);
            lastVoiceSample = rec;
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: record failed", e);
        }
        Log.i(TAG, "voiceprint: learned " + cls + " sample (profile n="
                + voiceProfile.count + ", w=" + String.format(Locale.US, "%.1f", voiceProfile.wsum) + ")");
        persistAsync();
    }

    /** Teach the environment's noise (recognizer domain). */
    public synchronized void learnNoiseRecog(float[] feats, double w) {
        if (feats == null) return;
        loadNoise();
        noiseRecog.update(feats, w);
        persistAsync();
    }

    /** Teach the environment's noise (probe domain, while KILLED). */
    public synchronized void learnNoiseProbe(float[] feats, double w) {
        if (feats == null) return;
        loadNoise();
        noiseProbe.update(feats, w);
        persistAsync();
    }

    /**
     * A probe burst that the user CONFIRMED by dictating right after
     * the re-arm: teach the probe-domain voice profile, so future
     * bursts from this user are recognized sooner.
     */
    public synchronized void learnVoiceProbe(float[] feats, double w) {
        if (feats == null) return;
        loadNoise();
        voiceProbe.update(feats, w);
        persistAsync();
    }

    /**
     * §4.2: the user said "ignore that — that wasn't me" about the
     * last accepted dictation. Pull its sample back out of the voice
     * profile's future (profiles are running blends, so the honest
     * move is: forget the sample record and teach its features to the
     * NOISE profile instead — the blend's small pull from one sample
     * is exactly what the slow learning rate bounds).
     */
    public synchronized void moveLastVoiceSampleToNoise() {
        if (lastVoiceSample == null) return;
        try {
            JSONArray fa = lastVoiceSample.optJSONArray("f");
            if (fa != null && fa.length() == DIMS) {
                float[] f = new float[DIMS];
                for (int i = 0; i < DIMS; i++) f[i] = (float) fa.optDouble(i, 0);
                loadNoise();
                noiseRecog.update(f, 1.0);
                voiceRecords.remove(lastVoiceSample);
                Log.i(TAG, "voiceprint: 'wasn't me' — last sample moved to noise profile");
            }
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: wasn't-me move failed", e);
        }
        lastVoiceSample = null;
        persistAsync();
    }

    // ------------------------------------------------------------ verdicts

    /**
     * Recognizer-domain verdict for a measurement. MATCH means: close
     * to the signed-in user's voice AND clearly closer to it than to
     * the known noise. Anything else is not a refusal — callers use
     * MATCH only to OPEN the gate wider (§4.3).
     */
    public synchronized int verdictRecog(float[] feats) {
        String key = currentUserKey();
        if (key == null || feats == null) return NO_VOTE;
        ensureUserLoaded(key);
        if (!voiceProfile.hasVote()) return NO_VOTE;
        double du = voiceProfile.distance(feats);
        if (du > MATCH_DIST) return NO_MATCH;
        if (noiseRecog.hasVote()) {
            double dn = noiseRecog.distance(feats);
            if (du + MATCH_MARGIN > dn) return NO_MATCH;
        }
        return MATCH;
    }

    /**
     * Probe-domain: does this burst look like the confirmed voice of
     * the signed-in user (and unlike the drone)? Used only to RELAX
     * the probe burst thresholds — never to tighten them.
     */
    public synchronized boolean probeBoostApplies(float[] probeFeats) {
        if (probeFeats == null) return false;
        loadNoise();
        if (!voiceProbe.hasVote()) return false;
        if (currentUserKey() == null) return false;
        double dv = voiceProbe.distance(probeFeats);
        if (dv > PROBE_MATCH_DIST) return false;
        if (noiseProbe.hasVote()) {
            double dn = noiseProbe.distance(probeFeats);
            if (dv + PROBE_MATCH_MARGIN > dn) return false;
        }
        return true;
    }

    public synchronized boolean voiceHasVote() {
        String key = currentUserKey();
        if (key == null) return false;
        ensureUserLoaded(key);
        return voiceProfile.hasVote();
    }

    /**
     * Nick 2026-10-29 v1.62 (new-voice flow): the raw recognizer-
     * domain distance of a measurement from the signed-in user's
     * voice profile — the guest attribution needs the number itself,
     * not just the verdict (a clearly-far NO_MATCH is a new-voice
     * candidate; a near one is just Nick sounding off today).
     * -1 when there's no profile to measure against.
     */
    public synchronized double distanceRecog(float[] feats) {
        String key = currentUserKey();
        if (key == null || feats == null) return -1;
        ensureUserLoaded(key);
        if (!voiceProfile.hasVote()) return -1;
        return voiceProfile.distance(feats);
    }

    public synchronized int voiceSampleCount() {
        String key = currentUserKey();
        if (key == null) return 0;
        ensureUserLoaded(key);
        return voiceProfile.count;
    }

    // ------------------------------------------------------------ archive pipeline

    /** Records older than the given age, for upload (copies). */
    public synchronized List<JSONObject> agedRecords(int ageDays) {
        List<JSONObject> out = new ArrayList<>();
        String key = currentUserKey();
        if (key == null) return out;
        ensureUserLoaded(key);
        long cutoff = System.currentTimeMillis() - ageDays * 86400000L;
        for (JSONObject r : voiceRecords) {
            if (r.optLong("ts", Long.MAX_VALUE) < cutoff) out.add(r);
        }
        return out;
    }

    /** Remove records the server has confirmed it holds. */
    public synchronized void removeRecords(List<JSONObject> done) {
        if (done == null || done.isEmpty()) return;
        voiceRecords.removeAll(done);
        persistAsync();
    }

    /**
     * Re-seed an empty local profile from the server archive (new
     * phone): fold the archived measurements into the derived profile
     * only — the records themselves stay server-side (they already
     * aged out once; re-aging them locally would duplicate them).
     */
    public synchronized void foldArchiveIntoProfile(List<JSONObject> records) {
        String key = currentUserKey();
        if (key == null || records == null) return;
        ensureUserLoaded(key);
        if (voiceProfile.hasVote() || archiveFolded) return;
        int folded = 0;
        try {
            for (JSONObject r : records) {
                JSONArray fa = r.optJSONArray("f");
                if (fa == null || fa.length() != DIMS) continue;
                float[] f = new float[DIMS];
                for (int i = 0; i < DIMS; i++) f[i] = (float) fa.optDouble(i, 0);
                voiceProfile.update(f, r.optDouble("w", 0.7));
                folded++;
            }
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: archive fold failed", e);
        }
        archiveFolded = true;
        Log.i(TAG, "voiceprint: re-seeded profile from server archive (" + folded + " measurements)");
        persistAsync();
    }

    public synchronized boolean archiveAlreadyFolded() {
        String key = currentUserKey();
        if (key == null) return true;
        ensureUserLoaded(key);
        return archiveFolded;
    }

    // ------------------------------------------------------------ reset

    /** Delete the signed-in user's local voiceprint (profile + records). */
    public synchronized void resetCurrentUser() {
        String key = currentUserKey();
        if (key == null) return;
        try {
            File f = userFile(key);
            //noinspection ResultOfMethodCallIgnored
            if (f.exists()) f.delete();
        } catch (Exception e) {
            Log.w(TAG, "voiceprint: local reset failed", e);
        }
        if (key.equals(loadedUserKey)) {
            voiceProfile = new VoiceprintProfile(DIMS, VAR_FLOOR);
            voiceRecords = new ArrayList<>();
            archiveFolded = false;
            lastVoiceSample = null;
        }
        Log.i(TAG, "voiceprint: profile reset for current user");
    }

    /** UI helper: one-line status for the given context's account. */
    public static String statusLine(Context c) {
        Voiceprint vp = new Voiceprint(c);
        String key = vp.currentUserKey();
        if (key == null) return "Not signed in — no voiceprint is learned.";
        int n = vp.voiceSampleCount();
        if (n == 0) return "No voiceprint learned yet for this account.";
        return "Voiceprint: " + n + " accepted dictations learned"
                + (vp.voiceHasVote() ? " (active at the gate)." : " (still warming up).");
    }

    // NOTE: reset is executed by VoiceService (the RESET_VOICEPRINT
    // intent), never from a throwaway UI-side instance — the service
    // owns the live in-memory profile, and only its reset sticks.

    private static String dayString(long epochMs) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(epochMs));
    }
}
