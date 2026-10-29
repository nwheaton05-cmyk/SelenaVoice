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

/**
 * Nick 2026-10-29 v1.62: GUEST voice profiles for the standalone
 * app's group-conversation mode (13:00/13:02/13:52/13:55 rulings).
 *
 * The standalone app is Nick's only, but other people talk to it in
 * the room. A guest is a SEPARATE per-person voiceprint in the same
 * recognizer-envelope domain as Nick's Voiceprint (7-dim RMS
 * envelope features), stored in its own file under the app's
 * private voiceprint dir. Hard walls, by ruling:
 *  - a guest print NEVER merges into or trains Nick's Voiceprint;
 *  - Nick's speech NEVER trains a guest print;
 *  - guests are not Siteview users — no account, no server copy;
 *    the store lives and dies on this phone.
 * Measurements only — raw audio is never stored, as always.
 *
 * Matching is honestly nudge-grade: the envelope domain is coarse
 * (it was built as a gate booster, not an identifier). matchRecog
 * uses the same distance scale as the Voiceprint MATCH threshold,
 * distance-only (guests have no noise profile to margin against),
 * and callers treat a non-match as "unconfirmed", never as proof
 * of anything. False flags are expected occasionally; the flow
 * built on top stays graceful (an unanswered ask creates nothing).
 *
 * Also kept here: the last UNMATCHED signature + when it was asked
 * about, so the same unrecognized voice isn't re-asked on every
 * dictation for a day.
 *
 * Top-level class per the standing d8 rule.
 */
public class GuestProfiles {
    private static final String TAG = "SelenaVoice";
    private static final String FILE_NAME = "guests.json";
    private static final int MAX_GUESTS = 8;
    /**
     * Rider G1 (Nick, 2026-10-29 ~16:12): a guest unheard for this
     * long drops off the ACTIVE roster. Dropping is NOT deletion —
     * the stored print persists untouched (the 8-guest cap + LRU
     * above is the only removal), and the guest reactivates
     * instantly the next time their voice is recognized
     * (matchRecog / learnRecog refresh lastHeardMs).
     */
    public static final long GUEST_ACTIVE_TIMEOUT_MS = 600_000L;
    /** Same scale as Voiceprint's MATCH_DIST (2.4). */
    public static final double GUEST_MATCH_DIST = 2.4;
    // The recognizer-domain variance floors, mirrored from
    // Voiceprint (its copies are private).
    private static final float[] VAR_FLOOR = {
            1.5f, 0.5f, 1.5f, 1.5f, 1.0f, 0.4f, 0.08f};

    public static class Guest {
        public int id;
        public String name;
        public long createdMs;
        public long lastHeardMs;
        public VoiceprintProfile recog =
                new VoiceprintProfile(Voiceprint.DIMS, VAR_FLOOR);
    }

    private static class Store {
        List<Guest> guests = new ArrayList<>();
        float[] lastUnmatchedFeats;
        long lastUnmatchedMs;
        int nextId = 1;
    }

    private static final Object LOCK = new Object();

    private static File file(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "voiceprint");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, FILE_NAME);
    }

    // ------------------------------------------------------------ matching

    /**
     * The enrolled guest this envelope belongs to, or null. Requires
     * the guest profile to have a vote (enrollment seeds it well
     * past that) and the distance to sit inside match range.
     */
    public Guest matchRecog(Context ctx, float[] feats) {
        if (feats == null || feats.length != Voiceprint.DIMS) return null;
        synchronized (LOCK) {
            Store s = load(ctx);
            Guest best = null;
            double bestD = Double.MAX_VALUE;
            for (Guest g : s.guests) {
                if (!g.recog.hasVote()) continue;
                double d = g.recog.distance(feats);
                if (d < bestD) {
                    bestD = d;
                    best = g;
                }
            }
            if (best != null && bestD <= GUEST_MATCH_DIST) {
                best.lastHeardMs = System.currentTimeMillis();
                save(ctx, s);
                return best;
            }
            return null;
        }
    }

    /** Distance from an arbitrary stored signature (unmatched memory). */
    public static double distanceBetween(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return Double.MAX_VALUE;
        }
        VoiceprintProfile tmp =
                new VoiceprintProfile(a.length, VAR_FLOOR);
        tmp.update(b, 1.0);
        return tmp.distance(a);
    }

    // ------------------------------------------------------------ enrollment

    /**
     * Create a guest from the name the person supplied and seed
     * their print with the measurements of the speech that triggered
     * the flow. Returns the guest (or the existing same-named one).
     */
    public Guest enroll(Context ctx, String name, float[] featsRecog) {
        synchronized (LOCK) {
            Store s = load(ctx);
            for (Guest g : s.guests) {
                if (g.name.equalsIgnoreCase(name)) {
                    if (featsRecog != null) {
                        g.recog.update(featsRecog, 1.5);
                    }
                    g.lastHeardMs = System.currentTimeMillis();
                    save(ctx, s);
                    return g;
                }
            }
            Guest g = new Guest();
            g.id = s.nextId++;
            g.name = name;
            g.createdMs = System.currentTimeMillis();
            g.lastHeardMs = g.createdMs;
            if (featsRecog != null) {
                // Seed firmly: the enrollment utterance is asserted
                // identity (the person just said their name) — three
                // folds put the fresh profile past its vote floor so
                // the next dictation can match.
                g.recog.update(featsRecog, 1.5);
                g.recog.update(featsRecog, 1.0);
                g.recog.update(featsRecog, 1.0);
            }
            s.guests.add(g);
            if (s.guests.size() > MAX_GUESTS) {
                // Full: drop the least-recently-heard guest. Nick's
                // own print is not in this store and is never touched.
                Guest oldest = s.guests.get(0);
                for (Guest x : s.guests) {
                    if (x.lastHeardMs < oldest.lastHeardMs) oldest = x;
                }
                s.guests.remove(oldest);
                Log.w(TAG, "guests: store full — dropped least-recent '"
                        + oldest.name + "'");
            }
            save(ctx, s);
            Log.i(TAG, "guests: enrolled '" + name + "' (id " + g.id + ")");
            return g;
        }
    }

    /** Teach an existing guest from a chunk attributed to them. */
    public void learnRecog(Context ctx, int guestId, float[] feats,
                           double weight) {
        if (feats == null || feats.length != Voiceprint.DIMS) return;
        synchronized (LOCK) {
            Store s = load(ctx);
            for (Guest g : s.guests) {
                if (g.id == guestId) {
                    g.recog.update(feats, weight);
                    g.lastHeardMs = System.currentTimeMillis();
                    save(ctx, s);
                    return;
                }
            }
        }
    }

    public List<Guest> list(Context ctx) {
        synchronized (LOCK) {
            return new ArrayList<>(load(ctx).guests);
        }
    }

    /**
     * The ACTIVE roster (rider G1): enrolled guests whose
     * last-heard stamp is inside GUEST_ACTIVE_TIMEOUT_MS, most
     * recently heard first. Purely a view over the store — nobody
     * is removed, nothing is announced.
     */
    public List<String> activeGuestNames(Context ctx) {
        synchronized (LOCK) {
            Store s = load(ctx);
            long now = System.currentTimeMillis();
            List<Guest> active = new ArrayList<>();
            for (Guest g : s.guests) {
                if (g.lastHeardMs > 0
                        && now - g.lastHeardMs <= GUEST_ACTIVE_TIMEOUT_MS) {
                    active.add(g);
                }
            }
            active.sort((a, b) -> Long.compare(b.lastHeardMs, a.lastHeardMs));
            List<String> out = new ArrayList<>();
            for (Guest g : active) out.add(g.name);
            return out;
        }
    }

    // ------------------------------------------------------------ unmatched memory

    /** The last unrecognized signature we already asked about (24 h window). */
    public float[] lastUnmatchedFeats(Context ctx) {
        synchronized (LOCK) {
            Store s = load(ctx);
            if (s.lastUnmatchedFeats == null) return null;
            if (System.currentTimeMillis() - s.lastUnmatchedMs
                    > 24L * 3600 * 1000) {
                return null;
            }
            return s.lastUnmatchedFeats.clone();
        }
    }

    public void noteUnmatched(Context ctx, float[] feats) {
        if (feats == null) return;
        synchronized (LOCK) {
            Store s = load(ctx);
            s.lastUnmatchedFeats = feats.clone();
            s.lastUnmatchedMs = System.currentTimeMillis();
            save(ctx, s);
        }
    }

    // ------------------------------------------------------------ persistence

    private Store load(Context ctx) {
        Store s = new Store();
        try {
            File f = file(ctx);
            if (!f.exists()) return s;
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            in.close();
            JSONObject root = new JSONObject(
                    new String(buf, 0, read, StandardCharsets.UTF_8));
            s.nextId = root.optInt("nextId", 1);
            JSONArray arr = root.optJSONArray("guests");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Guest g = new Guest();
                    g.id = o.optInt("id");
                    g.name = o.optString("name", "");
                    g.createdMs = o.optLong("createdMs", 0);
                    g.lastHeardMs = o.optLong("lastHeardMs", 0);
                    JSONObject rp = o.optJSONObject("recog");
                    if (rp != null) {
                        g.recog = VoiceprintProfile.fromJson(
                                rp, Voiceprint.DIMS, VAR_FLOOR);
                    }
                    if (!g.name.isEmpty()) s.guests.add(g);
                }
            }
            JSONArray lu = root.optJSONArray("lastUnmatchedFeats");
            if (lu != null && lu.length() == Voiceprint.DIMS) {
                s.lastUnmatchedFeats = new float[Voiceprint.DIMS];
                for (int i = 0; i < lu.length(); i++) {
                    s.lastUnmatchedFeats[i] = (float) lu.optDouble(i, 0);
                }
                s.lastUnmatchedMs = root.optLong("lastUnmatchedMs", 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "guests: load failed — starting empty", e);
            return new Store();
        }
        return s;
    }

    private void save(Context ctx, Store s) {
        try {
            JSONObject root = new JSONObject();
            root.put("nextId", s.nextId);
            JSONArray arr = new JSONArray();
            for (Guest g : s.guests) {
                JSONObject o = new JSONObject();
                o.put("id", g.id);
                o.put("name", g.name);
                o.put("createdMs", g.createdMs);
                o.put("lastHeardMs", g.lastHeardMs);
                o.put("recog", g.recog.toJson());
                arr.put(o);
            }
            root.put("guests", arr);
            if (s.lastUnmatchedFeats != null) {
                JSONArray lu = new JSONArray();
                for (float v : s.lastUnmatchedFeats) lu.put(v);
                root.put("lastUnmatchedFeats", lu);
                root.put("lastUnmatchedMs", s.lastUnmatchedMs);
            }
            File f = file(ctx);
            FileOutputStream out = new FileOutputStream(f);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "guests: save failed", e);
        }
    }
}
