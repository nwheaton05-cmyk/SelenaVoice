package com.selena.voice;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Nick 2026-10-21 v1.24: persistent 3-day message history with lockable
 * entries. Messages (text + MP3 path + timestamp + locked flag) are stored
 * as JSON in the app's files dir. Entries older than 3 days are purged
 * (with their MP3 files deleted) unless locked. Static so MainActivity
 * can read without binding to the service. Must call init() once with
 * a Context before use.
 */
public class MessageHistory {
    private static final String TAG = "SelenaVoice";
    private static final String FILE_NAME = "message_history.json";
    private static final long RETENTION_MS = 3L * 24 * 60 * 60 * 1000; // 3 days
    private static final int MAX_ENTRIES = 50;

    public static class Entry {
        public final String id;
        public final String text;
        public String audioPath; // null until the MP3 is cached
        public final long timestamp;
        public boolean locked;
        /** Server-side selena_voice_queue row ID (for delete-unlocked). Null for local-only entries. */
        public final String serverId;
        /** Nick 2026-10-23 v1.46: true when this entry is Nick's own
         * dictation (not Selena's reply) — rendered distinctly. */
        public final boolean fromNick;

        Entry(String id, String text, String audioPath, long timestamp, boolean locked,
                String serverId, boolean fromNick) {
            this.id = id;
            this.text = text;
            this.audioPath = audioPath;
            this.timestamp = timestamp;
            this.locked = locked;
            this.serverId = serverId;
            this.fromNick = fromNick;
        }
    }

    private static Context appContext;
    private static final List<Entry> entries = new ArrayList<>();

    public static synchronized void init(Context ctx) {
        appContext = ctx.getApplicationContext();
        load();
        purgeExpired();
    }

    public static synchronized Entry add(String text, File audioFile) {
        return add(text, audioFile, null);
    }

    /** Nick 2026-10-22 v1.34: serverId links the local entry to the
     * selena_voice_queue row, so delete-unlocked can preserve locked
     * messages server-side. */
    public static synchronized Entry add(String text, File audioFile, String serverId) {
        return add(text, audioFile, serverId, false);
    }

    /** Nick 2026-10-23 v1.46: Nick's dictation — a from-Nick entry in the
     * same history list as Selena's replies. No audio, no server row. */
    public static synchronized Entry addNick(String text) {
        return add(text, null, null, true);
    }

    private static synchronized Entry add(String text, File audioFile, String serverId,
            boolean fromNick) {
        String id = "msg-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000);
        Entry e = new Entry(id, text,
            audioFile != null ? audioFile.getAbsolutePath() : null,
            System.currentTimeMillis(), false, serverId, fromNick);
        // Nick 2026-10-22 v1.26: chat order — newest at the bottom.
        entries.add(e);
        while (entries.size() > MAX_ENTRIES) {
            Entry removed = entries.remove(0);
            deleteAudioFile(removed);
        }
        save();
        return e;
    }

    /** Nick 2026-10-22 v1.25: attach a downloaded MP3 to an entry that was
     * recorded before its audio arrived (phone-voice mode). */
    public static synchronized void setAudioPath(String id, File audioFile) {
        for (Entry e : entries) {
            if (e.id.equals(id)) {
                e.audioPath = audioFile.getAbsolutePath();
                save();
                return;
            }
        }
    }

    public static synchronized List<Entry> getAll() {
        return new ArrayList<>(entries);
    }

    public static synchronized void setLocked(String id, boolean locked) {
        for (Entry e : entries) {
            if (e.id.equals(id)) {
                e.locked = locked;
                save();
                return;
            }
        }
    }

    /** Nick 2026-10-22 v1.34: server IDs of locked entries, for the
     * delete-unlocked keep_ids list. Entries without a serverId (local-only
     * or from before v1.34) are skipped — they have no server row to keep. */
    public static synchronized List<String> getLockedServerIds() {
        List<String> ids = new ArrayList<>();
        for (Entry e : entries) {
            if (e.locked && e.serverId != null) ids.add(e.serverId);
        }
        return ids;
    }

    /** Nick 2026-10-22 v1.34: remove all unlocked entries (and their MP3s).
     * Returns the count removed. Locked entries are preserved. */
    public static synchronized int deleteUnlocked() {
        int removed = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.locked) {
                deleteAudioFile(e);
                entries.remove(i);
                removed++;
            }
        }
        if (removed > 0) save();
        return removed;
    }

    /** Nick 2026-10-22 v1.34: delete selena-*.mp3 files in the given
     * directory that are not referenced by any remaining history entry.
     * Returns the count deleted. Catches orphans from failed downloads
     * or entries removed before v1.34 tracked server IDs. */
    public static synchronized int deleteOrphanedAudio(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        java.util.Set<String> referenced = new java.util.HashSet<>();
        for (Entry e : entries) {
            if (e.audioPath != null) referenced.add(e.audioPath);
        }
        int deleted = 0;
        java.io.File[] files = dir.listFiles();
        if (files == null) return 0;
        for (java.io.File f : files) {
            String name = f.getName();
            if (name.startsWith("selena-") && name.endsWith(".mp3")
                    && !referenced.contains(f.getAbsolutePath())) {
                try {
                    if (f.delete()) deleted++;
                } catch (Exception ignored) {}
            }
        }
        return deleted;
    }

    /** Delete entries older than 3 days unless locked; delete their MP3s. */
    public static synchronized void purgeExpired() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.locked && e.timestamp < cutoff) {
                deleteAudioFile(e);
                entries.remove(i);
                changed = true;
            }
        }
        if (changed) save();
    }

    private static void deleteAudioFile(Entry e) {
        if (e.audioPath != null) {
            try {
                File f = new File(e.audioPath);
                if (f.exists()) f.delete();
            } catch (Exception ex) {
                Log.w(TAG, "history: failed to delete " + e.audioPath, ex);
            }
        }
    }

    /**
     * Nick 2026-10-22 v1.28: true when a message card still points at this
     * MP3 for replay. Playback cleanup must not delete referenced files —
     * that was killing replay after the first play (v1.24 replay cache).
     */
    public static synchronized boolean isAudioReferenced(String absPath) {
        if (absPath == null) return false;
        for (Entry e : entries) {
            if (absPath.equals(e.audioPath)) return true;
        }
        return false;
    }

    /** Nick 2026-10-22 v1.34: true when the entry referencing this MP3
     * path is locked. Used by delete-unlocked to preserve locked audio
     * in the service playback queues. */
    public static synchronized boolean isAudioLocked(String absPath) {
        if (absPath == null) return false;
        for (Entry e : entries) {
            if (absPath.equals(e.audioPath)) return e.locked;
        }
        return false;
    }

    /** Nick 2026-10-22 v1.34: true when a locked entry has this exact
     * text. Used by delete-unlocked for the TTS (phone-voice) queue,
     * which holds raw strings. */
    public static synchronized boolean isTextLocked(String text) {
        if (text == null) return false;
        for (Entry e : entries) {
            if (text.equals(e.text) && e.locked) return true;
        }
        return false;
    }

    private static void load() {
        entries.clear();
        if (appContext == null) return;
        try {
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            if (!f.exists()) return;
            String json = new String(java.nio.file.Files.readAllBytes(f.toPath()));
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                entries.add(new Entry(
                    o.getString("id"),
                    o.getString("text"),
                    o.isNull("audioPath") ? null : o.getString("audioPath"),
                    o.getLong("timestamp"),
                    o.optBoolean("locked", false),
                    o.isNull("serverId") ? null : o.optString("serverId", null),
                    // Nick 2026-10-23 v1.46: pre-v1.46 entries have no
                    // fromNick key — they were all Selena's replies.
                    o.optBoolean("fromNick", false)));
            }
            // Nick 2026-10-22 v1.26: v1.24/v1.25 saved newest-first.
            // Detect by timestamp order; flip only when needed.
            if (entries.size() >= 2
                    && entries.get(0).timestamp > entries.get(entries.size() - 1).timestamp) {
                java.util.Collections.reverse(entries);
            }
        } catch (Exception e) {
            Log.w(TAG, "history: load failed", e);
        }
    }

    private static void save() {
        if (appContext == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("id", e.id);
                o.put("text", e.text);
                o.put("audioPath", e.audioPath != null ? e.audioPath : JSONObject.NULL);
                o.put("timestamp", e.timestamp);
                o.put("locked", e.locked);
                o.put("serverId", e.serverId != null ? e.serverId : JSONObject.NULL);
                // Nick 2026-10-23 v1.46: sender flag for Nick's dictations.
                o.put("fromNick", e.fromNick);
                arr.put(o);
            }
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            java.nio.file.Files.write(f.toPath(), arr.toString().getBytes());
        } catch (Exception e) {
            Log.w(TAG, "history: save failed", e);
        }
    }
}
