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

        Entry(String id, String text, String audioPath, long timestamp, boolean locked) {
            this.id = id;
            this.text = text;
            this.audioPath = audioPath;
            this.timestamp = timestamp;
            this.locked = locked;
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
        String id = "msg-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 10000);
        Entry e = new Entry(id, text,
            audioFile != null ? audioFile.getAbsolutePath() : null,
            System.currentTimeMillis(), false);
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
                    o.optBoolean("locked", false)));
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
                arr.put(o);
            }
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            java.nio.file.Files.write(f.toPath(), arr.toString().getBytes());
        } catch (Exception e) {
            Log.w(TAG, "history: save failed", e);
        }
    }
}
