package com.selena.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The ONE app-global voice store (Nick, 2026-11-05 — Phase 0).
 *
 * Every voice record in the app lives here, in a single
 * app-private SharedPreferences file ("selena_voiceprint") that
 * BOTH coach-session paths (solo and with-others — the same
 * CoachStreamer, the same app, the same process) read and extend.
 * There is deliberately no per-mode copy, no per-session copy, and
 * no server copy: prints never leave this phone.
 *
 * AUTOMATIC, CONTINUOUS LEARNING (Nick, 2026-11-05 13:23 EST —
 * supersedes any guided enrollment): there is no enrollment
 * ceremony, no guided samples, no enroll control. While the voice
 * test is on, every quality-accepted speech segment teaches:
 * - Voice 1 is Nick. In SOLO sessions every accepted segment is
 *   his by construction (the solo door means nobody else is in
 *   the conversation) and refines Voice 1. Solo NEVER creates
 *   guest records.
 * - Voice 2, 3, 4… are ANONYMOUS guests, auto-created in
 *   first-heard order in with-others sessions when a segment
 *   matches no stored voice. No name is ever asked, no prompt is
 *   ever spoken or shown — a coach session can never speak into
 *   the room. A re-heard voice matches its number and refines.
 *
 * LEARNING IS SLOW BY DESIGN (Nick's standing safeguard): a print
 * is a running centroid moved only TEACH_ALPHA toward each
 * teaching segment, plus up to ANCHOR_CAP verbatim anchor
 * embeddings kept from a voice's earliest confident segments.
 * Only accepted speech teaches — never segments that overlapped
 * Selena's own playback, never noise, never stubs too short to
 * carry a reliable embedding (CoachStreamer enforces the gates).
 * A voice's label is marked provisional in the log until
 * MIN_AUTHORITY taught segments have accrued.
 *
 * Matching score for a probe = max(centroid cosine, best anchor
 * cosine). Embeddings are 192-dim WeSpeaker vectors packed
 * Base64 exactly as the Siteview VoiceprintPlugin packs them.
 *
 * Top-level class per the standing d8 rule (no inner classes).
 */
public class VoiceprintStore {

    public static final String PREFS = "selena_voiceprint";
    public static final int EMBEDDING_DIM = 192; // WeSpeaker ResNet34-LM
    /** Verbatim anchors kept per voice (earliest confident segs). */
    public static final int ANCHOR_CAP = 4;
    /** EMA weight of one teaching segment on a voice's centroid. */
    public static final float TEACH_ALPHA = 0.12f;
    /** Taught segments before a label stops being "provisional". */
    public static final int MIN_AUTHORITY = 5;

    private static final String KEY_COUNT = "voice_count";

    private VoiceprintStore() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String kKind(int n)     { return "v" + n + "_kind"; }
    private static String kAnchors(int n)  { return "v" + n + "_anchors"; }
    private static String kCentroid(int n) { return "v" + n + "_centroid"; }
    private static String kCreated(int n)  { return "v" + n + "_created_ms"; }
    private static String kHeard(int n)    { return "v" + n + "_heard_ms"; }
    private static String kSegs(int n)     { return "v" + n + "_segs"; }
    private static String kTaught(int n)   { return "v" + n + "_taught"; }

    // ------------------------------------------------------------ reads

    public static int voiceCount(Context ctx) {
        return prefs(ctx).getInt(KEY_COUNT, 0);
    }

    /** "nick" for Voice 1, "guest" for the rest, null if absent. */
    public static String kind(Context ctx, int n) {
        return prefs(ctx).getString(kKind(n), null);
    }

    public static List<float[]> anchors(Context ctx, int n) {
        List<float[]> out = new ArrayList<>();
        String json = prefs(ctx).getString(kAnchors(n), null);
        if (json == null) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                float[] v = base64ToEmbedding(arr.getString(i));
                if (v != null && v.length == EMBEDDING_DIM) out.add(v);
            }
        } catch (Exception ignored) {}
        return out;
    }

    public static float[] centroid(Context ctx, int n) {
        String b64 = prefs(ctx).getString(kCentroid(n), null);
        if (b64 == null) return null;
        float[] v = base64ToEmbedding(b64);
        return (v != null && v.length == EMBEDDING_DIM) ? v : null;
    }

    /** Segments that have taught this voice so far. */
    public static int taughtCount(Context ctx, int n) {
        return prefs(ctx).getInt(kTaught(n), 0);
    }

    /** Segments attributed to this voice (any confidence). */
    public static int segmentCount(Context ctx, int n) {
        return prefs(ctx).getInt(kSegs(n), 0);
    }

    /** True when voice n has any print material at all. */
    public static boolean hasPrint(Context ctx, int n) {
        return centroid(ctx, n) != null || !anchors(ctx, n).isEmpty();
    }

    /** True once Voice 1 (Nick) has begun learning. */
    public static boolean nickLearning(Context ctx) {
        return voiceCount(ctx) >= 1 && hasPrint(ctx, 1);
    }

    /** Provisional = too few taught segments to trust the label. */
    public static boolean isProvisional(Context ctx, int n) {
        return taughtCount(ctx, n) < MIN_AUTHORITY;
    }

    /** Match score of a probe against voice n: max(centroid, anchors). */
    public static float scoreAgainst(Context ctx, int n, float[] probe) {
        float best = cosine(probe, centroid(ctx, n));
        for (float[] a : anchors(ctx, n)) {
            float c = cosine(probe, a);
            if (c > best) best = c;
        }
        return best;
    }

    // ------------------------------------------------------------ writes

    /**
     * Teach voice n one accepted segment: move its centroid
     * TEACH_ALPHA toward the (normalized) embedding, bank the
     * embedding as a verbatim anchor while anchor slots remain
     * (callers pass bankAnchor only for confident segments), and
     * bump the taught/heard counters. Creates the voice record
     * first if needed — kind "nick" for n == 1, "guest" otherwise.
     */
    public static synchronized void teach(Context ctx, int n,
                                          float[] embedding,
                                          boolean bankAnchor) {
        SharedPreferences p = prefs(ctx);
        SharedPreferences.Editor e = p.edit();
        long now = System.currentTimeMillis();
        if (p.getString(kKind(n), null) == null) {
            e.putString(kKind(n), n == 1 ? "nick" : "guest");
            e.putLong(kCreated(n), now);
            if (p.getInt(KEY_COUNT, 0) < n) e.putInt(KEY_COUNT, n);
        }
        float[] emb = normalize(embedding);
        float[] c = centroid(ctx, n);
        float[] next;
        if (c == null || emb == null) {
            next = emb;
        } else {
            double[] acc = new double[EMBEDDING_DIM];
            for (int i = 0; i < EMBEDDING_DIM; i++) {
                acc[i] = (1.0 - TEACH_ALPHA) * c[i]
                        + TEACH_ALPHA * emb[i];
            }
            next = normalize(acc);
        }
        if (next != null) {
            e.putString(kCentroid(n), embeddingToBase64(next));
        }
        if (bankAnchor) {
            List<float[]> set = anchors(ctx, n);
            if (set.size() < ANCHOR_CAP && emb != null) {
                set.add(emb);
                e.putString(kAnchors(n), anchorsToJson(set));
            }
        }
        e.putInt(kTaught(n), p.getInt(kTaught(n), 0) + 1);
        e.putLong(kHeard(n), now);
        e.apply();
    }

    /**
     * Auto-create the next guest voice, founded by one accepted
     * segment (its first teaching segment). Returns the new voice
     * number — always >= 2; Voice 1 is reserved for Nick even if
     * his print has not begun learning yet.
     */
    public static synchronized int createGuest(Context ctx,
                                               float[] embedding) {
        int n = Math.max(voiceCount(ctx), 1) + 1;
        teach(ctx, n, embedding, true);
        noteHeard(ctx, n);
        return n;
    }

    /** Record one more attributed segment for voice n. */
    public static synchronized void noteHeard(Context ctx, int n) {
        SharedPreferences p = prefs(ctx);
        p.edit()
                .putInt(kSegs(n), p.getInt(kSegs(n), 0) + 1)
                .putLong(kHeard(n), System.currentTimeMillis())
                .apply();
    }

    /** Delete EVERY voice record (Nick + all guests). Immediate. */
    public static synchronized void wipeAll(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }

    // ------------------------------------------------------------ UI line

    /** One-line status for the app's voiceprint panel. */
    public static String statusLine(Context ctx) {
        int count = voiceCount(ctx);
        if (count == 0) return "no voices stored yet";
        StringBuilder sb = new StringBuilder();
        for (int n = 1; n <= count; n++) {
            if (n > 1) sb.append(" · ");
            int t = taughtCount(ctx, n);
            if (n == 1) {
                sb.append("Voice 1 (you): ");
                sb.append(t == 0 ? "not learned yet"
                        : "learning from " + t + " segs");
            } else {
                sb.append("Voice ").append(n).append(": guest, ")
                  .append(t).append(" segs");
            }
            if (t > 0 && isProvisional(ctx, n)) sb.append(" (prov)");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ math + packing

    public static float[] normalize(float[] v) {
        if (v == null) return null;
        double norm = 0;
        for (float f : v) norm += (double) f * f;
        norm = Math.sqrt(norm);
        if (norm == 0) return null;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    private static float[] normalize(double[] v) {
        double norm = 0;
        for (double d : v) norm += d * d;
        norm = Math.sqrt(norm);
        if (norm == 0) return null;
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }

    /** Cosine similarity in [-1, 1]; -1 on degenerate input. */
    public static float cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return -1f;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return -1f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    public static String embeddingToBase64(float[] embedding) {
        ByteBuffer buf = ByteBuffer.allocate(embedding.length * 4);
        buf.order(ByteOrder.nativeOrder());
        for (float f : embedding) buf.putFloat(f);
        return Base64.encodeToString(buf.array(), Base64.NO_WRAP);
    }

    public static float[] base64ToEmbedding(String b64) {
        try {
            byte[] bytes = Base64.decode(b64, Base64.NO_WRAP);
            if (bytes.length % 4 != 0) return null;
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            buf.order(ByteOrder.nativeOrder());
            float[] v = new float[bytes.length / 4];
            for (int i = 0; i < v.length; i++) v[i] = buf.getFloat();
            return v;
        } catch (Exception e) {
            return null;
        }
    }

    private static String anchorsToJson(List<float[]> set) {
        JSONArray arr = new JSONArray();
        for (float[] v : set) arr.put(embeddingToBase64(v));
        return arr.toString();
    }
}
